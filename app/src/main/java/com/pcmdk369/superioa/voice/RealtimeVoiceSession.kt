package com.pcmdk369.superioa.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.MediaType
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class RealtimeVoiceState { CONNECTING, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR, DISCONNECTED }

/**
 * Native WebRTC full-duplex audio session for Android.
 * The provider key remains on the gateway; the app receives only a short-lived client secret.
 */
class RealtimeVoiceSession(
    context: Context,
    private val gatewayUrl: String,
    private val clientToken: String,
    private val onState: (RealtimeVoiceState) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val executeAndroidTask: suspend (String) -> String
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val closed = AtomicBoolean(false)
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var audioModule: JavaAudioDeviceModule? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var localAudioTrack: AudioTrack? = null
    @Volatile private var peer: PeerConnection? = null
    @Volatile private var events: DataChannel? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerState = false

    fun connect() {
        if (closed.get()) return
        onState(RealtimeVoiceState.CONNECTING)
        scope.launch {
            try {
                require(gatewayUrl.startsWith("https://")) { "Realtime gateway URL must use HTTPS." }
                require(clientToken.isNotBlank()) { "Add your Realtime gateway access token in Settings." }
                require(ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Microphone permission is required."
                }
                val secret = fetchClientSecret()
                if (closed.get()) return@launch
                negotiateWebRtc(secret)
            } catch (e: Exception) {
                fail(e.message ?: "Could not start the realtime voice session.")
            }
        }
    }

    private suspend fun fetchClientSecret(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(gatewayUrl.trimEnd('/') + "/v1/realtime/client-secret")
            .header("Authorization", "Bearer $clientToken")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(MediaType.parse("application/json"), "{}"))
            .build()
        http.newCall(request).execute().use { response ->
            val json = JSONObject(response.body()?.string().orEmpty())
            if (!response.isSuccessful) throw IllegalStateException(json.optString("error", "Gateway request failed (${response.code()})."))
            json.optString("value").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Gateway did not return a temporary voice token.")
        }
    }

    private suspend fun negotiateWebRtc(secret: String) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioModule = adm
        val pcFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        factory = pcFactory

        previousAudioMode = audioManager.mode
        previousSpeakerState = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    if (!closed.get()) fail("Realtime audio network connection was interrupted.")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) = Unit
        }
        val rtc = pcFactory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), observer)
            ?: throw IllegalStateException("Could not create the WebRTC peer connection.")
        peer = rtc

        val source = pcFactory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = pcFactory.createAudioTrack("superioa-microphone", source)
        localAudioTrack = track
        track.setEnabled(true)
        rtc.addTrack(track, listOf("superioa-audio"))

        val channel = rtc.createDataChannel("oai-events", DataChannel.Init())
            ?: throw IllegalStateException("Could not create the Realtime event channel.")
        events = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) onState(RealtimeVoiceState.LISTENING)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleEvent(String(bytes, Charsets.UTF_8))
            }
        })

        val offer = rtc.createOfferAwait()
        rtc.setLocalDescriptionAwait(offer)
        val localSdp = rtc.localDescription?.description ?: throw IllegalStateException("WebRTC did not produce a local SDP offer.")
        val answerSdp = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/realtime/calls")
                .header("Authorization", "Bearer $secret")
                .header("Content-Type", "application/sdp")
                .post(RequestBody.create(MediaType.parse("application/sdp"), localSdp))
                .build()
            http.newCall(request).execute().use { response ->
                val answer = response.body()?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("Realtime WebRTC negotiation failed (${response.code()}): ${answer.take(300)}")
                answer
            }
        }
        if (closed.get()) return
        rtc.setRemoteDescriptionAwait(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) = Unit
            }, MediaConstraints())
        }

    private suspend fun PeerConnection.setLocalDescriptionAwait(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            }, description)
        }

    private suspend fun PeerConnection.setRemoteDescriptionAwait(description: SessionDescription) =
        suspendCancellableCoroutine<Unit> { continuation ->
            setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
            }, description)
        }

    private fun handleEvent(raw: String) {
        try {
            val event = JSONObject(raw)
            when (event.optString("type")) {
                "session.created", "session.updated" -> onState(RealtimeVoiceState.LISTENING)
                "input_audio_buffer.speech_started" -> onState(RealtimeVoiceState.LISTENING)
                "input_audio_buffer.speech_stopped" -> onState(RealtimeVoiceState.THINKING)
                "conversation.item.input_audio_transcription.completed" -> onTranscript(event.optString("transcript"), true)
                "response.output_audio_transcript.delta" -> onTranscript(event.optString("delta"), false)
                "response.output_audio.delta" -> onState(RealtimeVoiceState.SPEAKING)
                "response.function_call_arguments.done" -> handleToolCall(event)
                "response.done" -> onState(RealtimeVoiceState.LISTENING)
                "error" -> onError(event.optJSONObject("error")?.optString("message") ?: "Realtime session error.")
            }
        } catch (e: Exception) {
            onError("Could not process a voice event: ${e.message ?: "unknown error"}")
        }
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
        val channel = events ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(
            JSONObject().put("type", "conversation.item.create")
                .put("item", JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", result.take(4000)))
                .toString().toByteArray(Charsets.UTF_8)
        ), false))
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(
            JSONObject().put("type", "response.create").toString().toByteArray(Charsets.UTF_8)
        ), false))
    }

    fun disconnect() {
        if (!closed.compareAndSet(false, true)) return
        releaseResources()
        scope.cancel()
        onState(RealtimeVoiceState.DISCONNECTED)
    }

    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        onError(message)
        releaseResources()
        scope.cancel()
        onState(RealtimeVoiceState.ERROR)
    }

    private fun releaseResources() {
        runCatching { events?.unregisterObserver() }
        runCatching { events?.close() }
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        events = null
        peer = null
        localAudioTrack = null
        audioSource = null
        factory = null
        audioModule = null
        runCatching {
            audioManager.isSpeakerphoneOn = previousSpeakerState
            audioManager.mode = previousAudioMode
        }
    }
}
