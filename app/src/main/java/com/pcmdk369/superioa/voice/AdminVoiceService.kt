package com.pcmdk369.superioa.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.pcmdk369.superioa.R
import com.pcmdk369.superioa.llm.SettingsStore
import com.pcmdk369.superioa.memory.AppDb
import com.pcmdk369.superioa.orchestrator.Orchestrator
import com.pcmdk369.superioa.ui.AdminVoiceActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continuous microphone mode is active only after the user opens the app, passes
 * Android biometric/device-credential authentication, and starts this foreground service.
 * Every recognized utterance must begin with the enrolled admin passphrase.
 */
class AdminVoiceService : Service() {
    companion object {
        const val ACTION_START = "com.pcmdk369.superioa.voice.START_ADMIN_VOICE"
        const val ACTION_STOP = "com.pcmdk369.superioa.voice.STOP_ADMIN_VOICE"
        const val EXTRA_GRANT = "admin_voice_start_grant"
        private const val CHANNEL_ID = "superioa_admin_voice"
        private const val NOTIFICATION_ID = 3691
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var taskRunning = false
    @Volatile private var destroyed = false
    private lateinit var settings: SettingsStore
    private lateinit var db: AppDb
    private lateinit var speaker: Speaker
    private lateinit var orchestrator: Orchestrator

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(applicationContext)
        db = Room.databaseBuilder(applicationContext, AppDb::class.java, "superioa.db").build()
        speaker = Speaker(applicationContext)
        orchestrator = Orchestrator(applicationContext, settings, db) { text ->
            if (settings.ttsEnabled) speaker.speak(text)
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!AdminVoiceAuth.consumeStartGrant(intent.getStringExtra(EXTRA_GRANT)) ||
                    !AdminVoiceAuth.isConfigured(this) ||
                    !SpeechRecognizer.isRecognitionAvailable(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startListening()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startListening() {
        if (listening || taskRunning || destroyed) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            speaker.speak("Microphone permission is required. Open SuperiOA voice settings.")
            stopSelf()
            return
        }
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) {
                        listening = false
                        scheduleListenRetry()
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val utterance = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        handleUtterance(utterance)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            listening = true
            recognizer?.startListening(request)
        } catch (_: Exception) {
            listening = false
            scheduleListenRetry()
        }
    }

    private fun handleUtterance(utterance: String) {
        if (taskRunning) return
        val command = AdminVoiceAuth.authorizedCommand(this, utterance)
        if (command.isNullOrBlank()) {
            // Fail closed and stay quiet; arbitrary speech must not trigger actions.
            scheduleListenRetry()
            return
        }
        taskRunning = true
        scope.launch {
            try {
                stopRecognitionOnly()
                speaker.speak("Command accepted.")
                withContext(Dispatchers.Default) { orchestrator.run(command) }
                val result = orchestrator.state.value.finalMessage
                    ?: when (orchestrator.state.value.status.name) {
                        "DONE" -> "Task completed."
                        "CANCELLED" -> "Task stopped."
                        else -> "Task ended. Check SuperiOA for details."
                    }
                if (settings.ttsEnabled) speaker.speak(result)
                delay(900)
            } catch (_: CancellationException) {
                throw
            } catch (_: Exception) {
                if (settings.ttsEnabled) speaker.speak("Command failed. Check SuperiOA for details.")
            } finally {
                taskRunning = false
                scheduleListenRetry()
            }
        }
    }

    private fun scheduleListenRetry() {
        if (destroyed || taskRunning) return
        scope.launch {
            delay(700)
            startListening()
        }
    }

    private fun stopRecognitionOnly() {
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SuperiOA Admin Voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing notification while admin voice mode is listening"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, AdminVoiceActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 3692, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AdminVoiceService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 3693, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SuperiOA Admin Voice is active")
            .setContentText("Listening for an utterance beginning with your admin passphrase")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop listening", stopPending)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        stopListening()
        scope.cancel()
        speaker.shutdown()
        db.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
