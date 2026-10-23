package com.pcmdk369.superioa.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.pcmdk369.superioa.llm.EngineConfig
import com.pcmdk369.superioa.llm.SettingsStore
import com.pcmdk369.superioa.memory.AppDb
import com.pcmdk369.superioa.orchestrator.Orchestrator
import com.pcmdk369.superioa.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = SettingsStore(app)
    private val db = Room.databaseBuilder(app, AppDb::class.java, "superioa.db").build()
    private val speaker = Speaker(app)
    private val orchestrator = Orchestrator(app, settings, db) { if (settings.ttsEnabled) speaker.speak(it) }

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

    private var job: Job? = null

    fun start(goal: String) {
        if (goal.isBlank() || job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.Default) { orchestrator.run(goal.trim()) }
    }

    fun stop() {
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
        speaker.shutdown()
    }
}
