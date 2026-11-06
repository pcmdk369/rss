package com.pcmdk369.superioa.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class RealtimeVoiceState { CONNECTING, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR, DISCONNECTED }

/** Full-duplex PCM16 voice. The provider key remains on the gateway; the app receives a short-lived token. */
class RealtimeVoiceSession(
    context: Context,
    private val gatewayUrl: String,
    private val clientToken: String,
    private val onState: (RealtimeVoiceState) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val executeAndroidTask: suspend (String) -> String
) {
    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val WS_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime"
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    private val micRunning = AtomicBoolean(false)
    private val playbackRunning = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>(128)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var micThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null

    fun connect() {
        if (closed.get()) return
        onState(RealtimeVoiceState.CONNECTING)
        scope.launch {
            try {
                val secret = fetchClientSecret()
                if (closed.get()) return@launch
                val request = Request.Builder().url(WS_URL)
                    .header("Authorization", "Bearer $secret")
                    .header("OpenAI-Beta", "realtime=v1")
                    .build()
                socket = http.newWebSocket(request, socketListener)
            } catch (e: Exception) {
                fail(e.message ?: "Could not connect to the realtime voice service.")
            }
        }
    }

    private suspend fun fetchClientSecret(): String = withContext(Dispatchers.IO) {
        require(gatewayUrl.startsWith("https://")) { "Realtime gateway URL must use HTTPS." }
        require(clientToken.isNotBlank()) { "Add your Realtime gateway access token in Settings." }
        val body = JSONObject().toString()
        val request = Request.Builder()
            .url(gatewayUrl.trimEnd('/') + "/v1/realtime/client-secret")
            .header("Authorization", "Bearer $clientToken")
            .header("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create(okhttp3.MediaType.parse("application/json"), body))
            .build()
        http.newCall(request).execute().use { response ->
            val json = JSONObject(response.body()?.string().orEmpty())
            if (!response.isSuccessful) throw IllegalStateException(json.optString("error", "Gateway request failed (${response.code()})."))
            json.optString("value").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Gateway did not return a temporary voice token.")
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val event = JSONObject(text)
                when (event.optString("type")) {
                    "session.created", "session.updated" -> startAudioPipelines()
                    "input_audio_buffer.speech_started" -> {
                        flushPlayback()
                        onState(RealtimeVoiceState.LISTENING)
                    }
                    "input_audio_buffer.speech_stopped" -> onState(RealtimeVoiceState.THINKING)
                    "conversation.item.input_audio_transcription.completed" -> onTranscript(event.optString("transcript"), true)
                    "response.output_audio_transcript.delta" -> onTranscript(event.optString("delta"), false)
                    "response.output_audio.delta" -> {
                        val encoded = event.optString("delta")
                        if (encoded.isNotBlank()) {
                            audioQueue.offer(Base64.decode(encoded, Base64.DEFAULT))
                            onState(RealtimeVoiceState.SPEAKING)
                        }
                    }
                    "output_audio_buffer.cleared" -> flushPlayback()
                    "response.function_call_arguments.done" -> handleToolCall(event)
                    "response.done" -> onState(RealtimeVoiceState.LISTENING)
                    "error" -> onError(event.optJSONObject("error")?.optString("message") ?: "Realtime session error.")
                }
            } catch (e: Exception) {
                onError("Could not process a voice event: ${e.message ?: "unknown error"}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed.get()) fail(t.message ?: "Realtime connection lost.")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) onState(RealtimeVoiceState.DISCONNECTED)
        }
    }

    private fun startAudioPipelines() {
        if (closed.get() || micRunning.get()) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required.")
            return
        }
        try {
            val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minIn > 0 && minOut > 0) { "This device does not support 24 kHz PCM audio." }
            val input = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minIn * 2, SAMPLE_RATE / 5 * 2)
            )
            require(input.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized." }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minOut * 2, SAMPLE_RATE / 3 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            recorder = input
            player = output
            input.startRecording()
            output.play()
            micRunning.set(true)
            playbackRunning.set(true)
            startMicThread(input)
            startPlaybackThread(output)
            onState(RealtimeVoiceState.LISTENING)
        } catch (e: Exception) {
            fail(e.message ?: "Could not start microphone or speaker.")
        }
    }

    private fun startMicThread(input: AudioRecord) {
        micThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(4800)
            while (micRunning.get() && !closed.get()) {
                val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0 && micRunning.get()) {
                    val aligned = count - (count % 2)
                    if (aligned > 0) {
                        val chunk = Base64.encodeToString(buffer.copyOf(aligned), Base64.NO_WRAP)
                        socket?.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", chunk).toString())
                    }
                } else if (count < 0) {
                    fail("Microphone capture failed ($count).")
                    break
                }
            }
        }, "SuperiOA-Realtime-Mic").apply { start() }
    }

    private fun startPlaybackThread(output: AudioTrack) {
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (playbackRunning.get() && !closed.get()) {
                val chunk = try { audioQueue.poll(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                if (chunk != null && playbackRunning.get()) {
                    var offset = 0
                    while (offset < chunk.size && playbackRunning.get()) {
                        val written = output.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                }
            }
        }, "SuperiOA-Realtime-Playback").apply { start() }
    }

    private fun handleToolCall(event: JSONObject) {
        val callId = event.optString("call_id")
        if (event.optString("name") != "execute_android_task" || callId.isBlank()) return
        val args = runCatching { JSONObject(event.optString("arguments")) }.getOrNull()
        val goal = args?.optString("goal")?.trim().orEmpty()
        if (goal.isBlank()) {
            sendToolResult(callId, "The Android task request was empty.")
            return
        }
        onState(RealtimeVoiceState.EXECUTING)
        scope.launch {
            val result = try { executeAndroidTask(goal) } catch (e: Exception) {
                "The task failed: ${e.message ?: "unknown error"}"
            }
            sendToolResult(callId, result)
        }
    }

    private fun sendToolResult(callId: String, result: String) {
        socket?.send(JSONObject().put("type", "conversation.item.create")
            .put("item", JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", result.take(4000))).toString())
        socket?.send(JSONObject().put("type", "response.create").toString())
    }

    private fun flushPlayback() {
        audioQueue.clear()
        runCatching { player?.pause(); player?.flush(); player?.play() }
    }

    fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        micRunning.set(false)
        playbackRunning.set(false)
        socket?.send(JSONObject().put("type", "session.close").toString())
        socket?.close(1000, "User ended voice session")
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { player?.pause(); player?.flush(); player?.release() }
        audioQueue.clear()
        micThread?.interrupt()
        playbackThread?.interrupt()
        recorder = null
        player = null
        scope.cancel()
        onState(RealtimeVoiceState.DISCONNECTED)
    }

    private fun fail(message: String) {
        if (closed.get()) return
        onError(message)
        onState(RealtimeVoiceState.ERROR)
        disconnect()
    }
}
