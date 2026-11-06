package com.pcmdk369.superioa.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.runtime.AgentRuntime
import com.pcmdk369.superioa.voice.RealtimeVoiceSession
import com.pcmdk369.superioa.voice.RealtimeVoiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val runtime = AgentRuntime.get(app)
    val settings = runtime.settings
    private val db = runtime.db
    private val orchestrator = runtime.orchestrator

    val state = orchestrator.state
    val history = db.logs().recent()
    val workflowCount = db.workflows().count()

    var engines by mutableStateOf(settings.loadEngines())
        private set
    var onboarded by mutableStateOf(settings.onboarded)
        private set
    @set:JvmName("setTtsState")
    var tts by mutableStateOf(settings.ttsEnabled)
        private set

    var realtimeState by mutableStateOf(RealtimeVoiceState.DISCONNECTED)
        private set
    var realtimeTranscript by mutableStateOf("")
        private set
    var realtimeError by mutableStateOf<String?>(null)
        private set

    private var realtimeSession: RealtimeVoiceSession? = null
    private var job: Job? = null

    val realtimeGatewayUrl: String get() = settings.realtimeGatewayUrl
    val realtimeClientToken: String get() = settings.realtimeClientToken

    fun saveRealtimeSettings(url: String, token: String) {
        settings.realtimeGatewayUrl = url
        settings.realtimeClientToken = token
    }

    fun startRealtimeVoice() {
        if (realtimeState != RealtimeVoiceState.DISCONNECTED && realtimeState != RealtimeVoiceState.ERROR) return
        realtimeError = null
        realtimeTranscript = ""
        val session = RealtimeVoiceSession(
            getApplication(),
            settings.realtimeGatewayUrl,
            settings.realtimeClientToken,
            onState = { realtimeState = it },
            onTranscript = { chunk, final ->
                realtimeTranscript = if (final) (realtimeTranscript + "\n" + chunk).takeLast(6000)
                    else (realtimeTranscript + chunk).takeLast(6000)
            },
            onError = { realtimeError = it },
            executeAndroidTask = { goal ->
                val task = runtime.launchTask(viewModelScope, goal, speakResult = false)
                if (task == null) "Another Android task is already running."
                else {
                    task.join()
                    runtime.orchestrator.state.value.finalMessage ?: "The Android task ended without a verified result."
                }
            }
        )
        realtimeSession = session
        session.connect()
    }

    fun stopRealtimeVoice() {
        val wasExecuting = realtimeState == RealtimeVoiceState.EXECUTING
        realtimeSession?.disconnect()
        realtimeSession = null
        if (wasExecuting) runtime.stopTask()
        realtimeState = RealtimeVoiceState.DISCONNECTED
    }

    fun start(goal: String) {
        if (goal.isBlank()) return
        job = runtime.launchTask(viewModelScope, goal)
    }

    fun stop() {
        runtime.stopTask()
        job?.cancel()
    }

    fun approve(allow: Boolean) = orchestrator.respondApproval(allow)

    fun saveEngine(c: EngineConfig) {
        settings.saveEngine(c)
        engines = settings.loadEngines()
    }

    fun finishOnboarding() {
        settings.onboarded = true
        onboarded = true
    }

    fun setTts(v: Boolean) {
        settings.ttsEnabled = v
        tts = v
    }

    fun clearWorkflows() {
        viewModelScope.launch { db.workflows().clear() }
    }

    fun clearHistory() {
        viewModelScope.launch { db.logs().clear() }
    }


    override fun onCleared() {
        realtimeSession?.disconnect()
        realtimeSession = null
        super.onCleared()
    }
}
