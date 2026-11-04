package com.pcmdk369.superioa.orchestrator

import android.content.Context
import com.pcmdk369.superioa.action.ActionType
import com.pcmdk369.superioa.action.ActionValidator
import com.pcmdk369.superioa.action.AgentAction
import com.pcmdk369.superioa.action.PlanResult
import com.pcmdk369.superioa.exec.ActionEngine
import com.pcmdk369.superioa.exec.ExecResult
import com.pcmdk369.superioa.exec.SuperiAccessibilityService
import com.pcmdk369.superioa.framework.AgentProfileRegistry
import com.pcmdk369.superioa.framework.EngineAdapterRegistry
import com.pcmdk369.superioa.llm.AllEnginesFailedException
import com.pcmdk369.superioa.llm.EngineCaller
import com.pcmdk369.superioa.llm.FailoverManager
import com.pcmdk369.superioa.llm.SecurityDenied
import com.pcmdk369.superioa.llm.SettingsStore
import com.pcmdk369.superioa.memory.AppDb
import com.pcmdk369.superioa.memory.ReuseDecision
import com.pcmdk369.superioa.memory.TaskLogEntity
import com.pcmdk369.superioa.memory.WorkflowConfidence
import com.pcmdk369.superioa.memory.WorkflowEntity
import com.pcmdk369.superioa.security.Redactor
import com.pcmdk369.superioa.security.SecurityGate
import com.pcmdk369.superioa.skills.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray

enum class TaskStatus { IDLE, PLANNING, RUNNING, WAITING_USER, DONE, FAILED, CANCELLED }

data class TaskState(
    val status: TaskStatus = TaskStatus.IDLE,
    val log: List<String> = emptyList(),
    val approval: String? = null,
    val engine: Int? = null,
    val finalMessage: String? = null,
    val goal: String? = null,
)

/**
 * Master agent / task orchestrator.
 * The LLM only PLANS (structured JSON). Every action is schema-validated, security-gated, then executed locally.
 * LLM failover happens only on planning calls, which have no side effects, so a timeout never repeats an external action.
 */
class Orchestrator(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val db: AppDb,
    private val caller: EngineCaller = EngineAdapterRegistry.create("openai-compatible"),
    private val onSpeak: (String) -> Unit = {},
) {
    companion object {
        const val MAX_ROUNDS = 100
        const val MAX_ACTIONS = 500
        const val MAX_RETRIES = 2
        const val MAX_TASK_DURATION_MS = 10 * 60 * 1000L
        const val ACTION_TIMEOUT_MS = 15_000L
        const val LLM_CALL_BUDGET_MS = 180_000L
        // Keep replayable Room workflows compact; large tasks execute but are not persisted as one JSON blob.
        const val MAX_SAVED_WORKFLOW_ACTIONS = 500
    }

    private class RunFlags {
        var sensitive = false
        var total = 0
        var retries = 0
        var lastFailedVerify: AgentAction? = null
        val startedAtMs = System.currentTimeMillis()
    }

    private class AbortTask(message: String) : Exception(message)

    private val _state = MutableStateFlow(TaskState())
    val state: StateFlow<TaskState> = _state

    @Volatile
    private var approvalWaiter: CompletableDeferred<Boolean>? = null

    fun respondApproval(allow: Boolean) {
        approvalWaiter?.complete(allow)
    }

    private fun log(line: String) {
        val safe = Redactor.redact(line)
        _state.update { it.copy(log = (it.log + safe).takeLast(200)) }
    }

    private fun setStatus(s: TaskStatus) = _state.update { it.copy(status = s) }

    private suspend fun askUser(reason: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        approvalWaiter = d
        _state.update { it.copy(status = TaskStatus.WAITING_USER, approval = reason) }
        try {
            return d.await()
        } finally {
            approvalWaiter = null
            _state.update { it.copy(status = TaskStatus.RUNNING, approval = null) }
        }
    }

    suspend fun run(goal: String) {
        val started = System.currentTimeMillis()
        _state.value = TaskState(status = TaskStatus.PLANNING, goal = goal.trim())
        var outcome = TaskStatus.FAILED
        var summary = ""
        var engineUsed: Int? = null
        val executed = mutableListOf<AgentAction>()
        val flags = RunFlags()
        val key = WorkflowConfidence.normalizeGoal(goal)
        val saved = try {
            db.workflows().find(key)
        } catch (e: Exception) {
            log("Workflow memory unavailable: ${e.javaClass.simpleName}")
            null
        }
        var replayFailed = false

        try {
            val svc = SuperiAccessibilityService.instance
            if (svc == null) {
                summary = "Accessibility service is off. Enable SuperiOA in Android Accessibility settings."
                return
            }
            val engine = ActionEngine(ctx)
            val (w, h) = engine.screenSize()
            val startPkg = withContext(Dispatchers.Main.immediate) { svc.currentPackage() }

            // 1) Reuse a saved workflow only if confidence and app context allow it.
            if (saved != null && saved.appContext == startPkg) {
                val decision = WorkflowConfidence.decide(saved.successCount, saved.failureCount)
                val go = when (decision) {
                    ReuseDecision.REUSE -> true
                    ReuseDecision.CONFIRM -> askUser("A saved workflow for this task is only moderately reliable. Run it?")
                    ReuseDecision.DISABLED -> false
                }
                if (go) {
                    log("Replaying saved workflow v${saved.version}")
                    setStatus(TaskStatus.RUNNING)
                    val plan = ActionValidator.parsePlan(saved.stepsJson, w, h)
                    val fail = if (plan is PlanResult.Valid) {
                        runActions(plan.plan.actions, engine, mutableListOf(), flags)
                    } else "stored workflow is invalid"
                    if (fail == null) {
                        recordOutcomeSafely(saved, key, true, null, startPkg)
                        outcome = TaskStatus.DONE
                        summary = "Done (saved workflow)."
                        return
                    }
                    log("Saved workflow failed: $fail. Falling back to the LLM planner.")
                    replayFailed = true
                    recordOutcomeSafely(saved, key, false, null, startPkg)
                    flags.retries = 0
                    flags.total = 0
                }
            }

            // 2) Plan -> validate -> gate -> execute -> verify, in bounded rounds.
            val configuredEngines = settings.loadEngines()
            // Preserve the user's configured primary-to-fallback order exactly.
            val failover = FailoverManager(configuredEngines, caller)
            val history = mutableListOf<String>()
            var finished = false
            var round = 0
            while (round < MAX_ROUNDS && !finished) {
                round++
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - flags.startedAtMs > MAX_TASK_DURATION_MS) {
                    throw AbortTask("task time limit reached")
                }
                var screen = withContext(Dispatchers.Main.immediate) { svc.readScreen() }
                SecurityGate.screenNeedsUser(screen)?.let { reason ->
                    flags.sensitive = true
                    if (!askUser("$reason Please complete it yourself, then tap Allow to continue.")) throw SecurityDenied(reason)
                    screen = withContext(Dispatchers.Main.immediate) { svc.readScreen() }
                }
                setStatus(TaskStatus.PLANNING)
                val remainingMs = MAX_TASK_DURATION_MS - (System.currentTimeMillis() - flags.startedAtMs)
                if (remainingMs <= 0) throw AbortTask("task time limit reached")
                val res = withTimeoutOrNull(minOf(LLM_CALL_BUDGET_MS, remainingMs)) {
                    failover.complete(
                        Prompts.system(SkillRegistry.hintsFor(goal) + "\n- [agent-profile:${AgentProfileRegistry.resolve(goal).id}] " + AgentProfileRegistry.resolve(goal).systemGuidance),
                        Prompts.user(goal, Redactor.redact(screen), history.takeLast(6))
                    )
                } ?: throw AbortTask("LLM planning time limit reached")
                engineUsed = res.engineIndex
                _state.update { it.copy(engine = res.engineIndex) }
                res.attempts.filter { !it.success }.forEach { log("Engine #${it.engine} failed: ${it.error}") }

                when (val pr = ActionValidator.parsePlan(res.text, w, h)) {
                    is PlanResult.Invalid -> {
                        history += "Your last reply was rejected: ${pr.reason}. Reply with valid JSON only."
                        log("Plan rejected: ${pr.reason}")
                    }
                    is PlanResult.Valid -> {
                        val plan = pr.plan
                        if (plan.message.isNotBlank()) log("Agent: ${plan.message}")
                        setStatus(TaskStatus.RUNNING)
                        val fail = runActions(plan.actions, engine, executed, flags)
                        if (fail != null) {
                            history += "Step failed: $fail"
                            log("Step failed: $fail")
                        } else {
                            history += "Executed: " + plan.actions.joinToString { it.describe() }
                            if (plan.done) finished = true
                        }
                    }
                }
            }
            if (finished) {
                outcome = TaskStatus.DONE
                summary = "Task finished."
                if (!flags.sensitive && !replayFailed && executed.isNotEmpty()) {
                    if (executed.size <= MAX_SAVED_WORKFLOW_ACTIONS) {
                        recordOutcomeSafely(saved, key, true, executed, startPkg)
                    } else {
                        log("Large workflow (" + executed.size + " actions) completed; replay snapshot skipped to keep local storage bounded.")
                    }
                }
            } else {
                summary = "Stopped after $MAX_ROUNDS rounds without a verified result."
            }
        } catch (e: CancellationException) {
            outcome = TaskStatus.CANCELLED
            summary = "Stopped by user."
            throw e
        } catch (e: SecurityDenied) {
            outcome = TaskStatus.CANCELLED
            summary = "Stopped: you declined a security checkpoint."
        } catch (e: AbortTask) {
            outcome = TaskStatus.FAILED
            summary = "Aborted: ${e.message}"
        } catch (e: AllEnginesFailedException) {
            outcome = TaskStatus.FAILED
            summary = e.message ?: "All LLM engines failed."
        } catch (e: Exception) {
            outcome = TaskStatus.FAILED
            summary = "Error: ${e.javaClass.simpleName}"
        } finally {
            withContext(NonCancellable) {
                val safeSummary = Redactor.redact(summary)
                _state.update { it.copy(status = outcome, approval = null, finalMessage = safeSummary) }
                log(safeSummary)
                try {
                    db.logs().insert(
                        TaskLogEntity(
                            goal = Redactor.redact(goal).take(200), status = outcome.name,
                            engine = engineUsed ?: 0, summary = safeSummary.take(300),
                            startedAt = started, endedAt = System.currentTimeMillis()
                        )
                    )
                } catch (ignored: Exception) {
                }
                if (outcome == TaskStatus.DONE || outcome == TaskStatus.FAILED) onSpeak(safeSummary)
            }
        }
    }

    /** Returns null on success, or a failure description so the planner can replan. */
    private suspend fun runActions(
        actions: List<AgentAction>,
        engine: ActionEngine,
        executed: MutableList<AgentAction>,
        f: RunFlags,
    ): String? {
        for (a in actions) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() - f.startedAtMs > MAX_TASK_DURATION_MS) return "Task time limit reached"
            f.total += 1
            if (f.total > MAX_ACTIONS) return "Action limit ($MAX_ACTIONS) reached"

            var act = a
            if (a.type == ActionType.RETRY) {
                val last = f.lastFailedVerify
                    ?: return "RETRY is only allowed for a previously failed VERIFY action; other actions are replanned to avoid duplicate side effects"
                f.retries += 1
                if (f.retries > MAX_RETRIES) return "Retry limit reached"
                act = last
                f.lastFailedVerify = null
            }

            val actionScreen = if (act.type in setOf(ActionType.TAP, ActionType.LONG_PRESS, ActionType.TOGGLE)) {
                withContext(Dispatchers.Main.immediate) { SuperiAccessibilityService.instance?.readScreen().orEmpty() }
            } else ""
            SecurityGate.actionNeedsApproval(act, actionScreen)?.let { reason ->
                f.sensitive = true
                if (!askUser(reason)) throw SecurityDenied(reason)
            }

            log("> ${act.describe()}")
            val executionResult = withTimeoutOrNull(ACTION_TIMEOUT_MS) { engine.execute(act) }
                ?: return "${act.describe()} timed out after ${ACTION_TIMEOUT_MS / 1000} seconds"
            when (val r = executionResult) {
                is ExecResult.Ok -> {
                    executed += act
                    f.lastFailedVerify = null
                }
                is ExecResult.Info -> {}
                is ExecResult.Failed -> {
                    f.lastFailedVerify = act.takeIf { it.type == ActionType.VERIFY }
                    return "${act.describe()} failed: ${r.reason}"
                }
                is ExecResult.NeedUser -> {
                    f.sensitive = true
                    if (!askUser(r.reason + " Tap Allow when done.")) throw SecurityDenied(r.reason)
                }
                is ExecResult.Aborted -> throw AbortTask(r.reason)
            }
            delay(350)
        }
        return null
    }

    private suspend fun recordOutcomeSafely(
        existing: WorkflowEntity?,
        key: String,
        success: Boolean,
        newSteps: List<AgentAction>?,
        pkg: String,
    ) {
        try {
            recordOutcome(existing, key, success, newSteps, pkg)
        } catch (e: Exception) {
            log("Workflow memory update failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun recordOutcome(
        existing: WorkflowEntity?,
        key: String,
        success: Boolean,
        newSteps: List<AgentAction>?,
        pkg: String,
    ) {
        val now = System.currentTimeMillis()
        val dao = db.workflows()
        val stepsJson = newSteps?.let { s -> JsonArray(s.map { it.toJson() }).toString() }
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    successCount = existing.successCount + if (success) 1 else 0,
                    failureCount = existing.failureCount + if (success) 0 else 1,
                    lastUsed = now,
                    stepsJson = stepsJson ?: existing.stepsJson,
                    version = if (stepsJson != null) existing.version + 1 else existing.version,
                )
            )
        } else if (success && stepsJson != null) {
            dao.upsert(
                WorkflowEntity(
                    goalKey = key, appContext = pkg, version = 1, stepsJson = stepsJson,
                    successCount = 1, failureCount = 0, lastUsed = now
                )
            )
        }
    }
}

object Prompts {
    fun system(skillHints: String): String = """
You are the planner for SuperiOA, an Android task-orchestration assistant. You never touch the phone yourself: you return JSON actions that the app validates and executes locally.
Reply with ONE JSON object only, no prose, no code fences:
{"message":"short status for the user","done":false,"actions":[{"type":"..."}]}
Allowed action types and fields:
OPEN_APP {target: app name or https URL}
BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, READ_UI, COPY, PASTE, CLEAR_TEXT, SELECT_ALL, RETRY
TAP {target: visible text or view id} or {x,y} taken from the screen dump
LONG_PRESS {target: visible text or view id} or {x,y} taken from the screen dump
TYPE {text}
SCROLL {direction: up|down|left|right}
SWIPE {x,y,x2,y2}
WAIT {millis: 1..10000}
VERIFY {target: text that must be visible on screen}
ABORT {reason}
REQUEST_USER {reason}
Rules:
- At most ${ActionValidator.MAX_ACTIONS_PER_PLAN} actions per reply. Unknown fields or types are rejected.
- Set done=true only after a VERIFY action in the same reply confirms the goal.
- Use REQUEST_USER for CAPTCHA, OTP, passwords, biometrics, payments and account-security confirmations. Never type secrets.
- Prefer visible-text targets. If the goal is impossible or unsafe, use ABORT with a reason.
Skill hints:
$skillHints
""".trimIndent()

    fun user(goal: String, screen: String, history: List<String>): String = buildString {
        append("GOAL: ").append(goal.take(500)).append("\n\nSCREEN (text only):\n").append(screen)
        if (history.isNotEmpty()) {
            append("\nHISTORY:\n")
            history.forEach { append("- ").append(it.take(300)).append('\n') }
        }
    }
}
