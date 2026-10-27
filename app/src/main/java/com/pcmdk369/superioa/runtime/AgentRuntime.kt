package com.pcmdk369.superioa.runtime

import android.content.Context
import androidx.room.Room
import com.pcmdk369.superioa.llm.SettingsStore
import com.pcmdk369.superioa.memory.AppDb
import com.pcmdk369.superioa.orchestrator.Orchestrator
import com.pcmdk369.superioa.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One process-wide task runtime shared by the Home screen and Admin Voice service.
 * Sharing the Orchestrator makes voice-triggered work visible in the same task state/log.
 */
class AgentRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)
    val db: AppDb = Room.databaseBuilder(appContext, AppDb::class.java, "superioa.db").build()
    private val speaker = Speaker(appContext)
    val orchestrator = Orchestrator(appContext, settings, db) {
        if (settings.ttsEnabled) speaker.speak(it)
    }

    @Volatile private var activeTask: Job? = null

    fun launchTask(scope: CoroutineScope, goal: String): Job? = synchronized(this) {
        val status = orchestrator.state.value.status
        if (activeTask?.isActive == true ||
            status == com.pcmdk369.superioa.orchestrator.TaskStatus.PLANNING ||
            status == com.pcmdk369.superioa.orchestrator.TaskStatus.RUNNING ||
            status == com.pcmdk369.superioa.orchestrator.TaskStatus.WAITING_USER
        ) return null

        val task = scope.launch(Dispatchers.Default) { orchestrator.run(goal.trim()) }
        activeTask = task
        task.invokeOnCompletion {
            synchronized(this) {
                if (activeTask === task) activeTask = null
            }
        }
        task
    }

    fun stopTask() {
        activeTask?.cancel()
    }

    companion object {
        @Volatile private var instance: AgentRuntime? = null

        fun get(context: Context): AgentRuntime =
            instance ?: synchronized(this) {
                instance ?: AgentRuntime(context).also { instance = it }
            }
    }
}
