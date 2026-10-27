package com.pcmdk369.superioa.exec

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.pcmdk369.superioa.action.ActionType
import com.pcmdk369.superioa.action.AgentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

sealed class ExecResult {
    data object Ok : ExecResult()
    data class Info(val text: String) : ExecResult()
    data class Failed(val reason: String) : ExecResult()
    data class NeedUser(val reason: String) : ExecResult()
    data class Aborted(val reason: String) : ExecResult()
}

/** Executes already-validated actions locally. No network, no LLM calls. */
class ActionEngine(private val ctx: Context) {

    fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    suspend fun execute(a: AgentAction): ExecResult = withContext(Dispatchers.Main.immediate) {
        val svc = SuperiAccessibilityService.instance
            ?: return@withContext ExecResult.Failed("Accessibility service is not enabled")
        when (a.type) {
            ActionType.OPEN_APP -> openApp(svc, a.target ?: return@withContext ExecResult.Failed("no target"))
            ActionType.BACK -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> global(svc, AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.NOTIFICATIONS -> global(svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> global(svc, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.TAP -> {
                val t = a.target
                val ok = if (t != null && t.isNotEmpty()) svc.tapNode(t)
                else svc.tapAt(a.x ?: 0, a.y ?: 0)
                if (ok) ExecResult.Ok else ExecResult.Failed("could not tap ${t ?: "(${a.x},${a.y})"}")
            }
            ActionType.LONG_PRESS -> {
                val t = a.target
                val ok = if (!t.isNullOrEmpty()) svc.longPressNode(t)
                else svc.longPressAt(a.x ?: 0, a.y ?: 0)
                if (ok) ExecResult.Ok else ExecResult.Failed("could not long-press ${t ?: "(${a.x},${a.y})"}")
            }
            ActionType.TOGGLE -> if (svc.toggleNode(a.target ?: "")) ExecResult.Ok else ExecResult.Failed("could not toggle ${a.target}")
            ActionType.SELECT_ALL -> when (svc.selectAllFocused()) {
                TypeResult.OK -> ExecResult.Ok
                TypeResult.PASSWORD_FIELD -> ExecResult.NeedUser("A password field is focused. Please select text yourself.")
                TypeResult.NO_FIELD -> ExecResult.Failed("no input field available")
                TypeResult.FAILED -> ExecResult.Failed("select all failed")
            }
            ActionType.CLEAR_TEXT -> when (svc.clearText()) {
                TypeResult.OK -> ExecResult.Ok
                TypeResult.PASSWORD_FIELD -> ExecResult.NeedUser("A password field is focused. Please clear it yourself.")
                TypeResult.NO_FIELD -> ExecResult.Failed("no input field available")
                TypeResult.FAILED -> ExecResult.Failed("clearing text failed")
            }
            ActionType.TYPE -> when (svc.typeText(a.text ?: "")) {
                TypeResult.OK -> ExecResult.Ok
                TypeResult.PASSWORD_FIELD -> ExecResult.NeedUser("A password field is focused. Please type it yourself.")
                TypeResult.NO_FIELD -> ExecResult.Failed("no input field available")
                TypeResult.FAILED -> ExecResult.Failed("typing failed")
            }
            ActionType.SCROLL -> if (svc.scroll(a.direction ?: "down")) ExecResult.Ok else ExecResult.Failed("nothing scrollable")
            ActionType.SWIPE -> if (svc.swipe(a.x ?: 0, a.y ?: 0, a.x2 ?: 0, a.y2 ?: 0)) ExecResult.Ok else ExecResult.Failed("swipe cancelled")
            ActionType.COPY -> if (svc.copyFocused()) ExecResult.Ok else ExecResult.Failed("copy failed")
            ActionType.PASTE -> if (svc.pasteFocused()) ExecResult.Ok else ExecResult.Failed("paste failed")
            ActionType.READ_UI -> ExecResult.Info(svc.readScreen())
            ActionType.WAIT -> {
                delay(a.millis ?: 500)
                ExecResult.Ok
            }
            ActionType.VERIFY -> {
                val needle = a.target ?: a.text ?: ""
                delay(400)
                if (svc.screenContains(needle)) ExecResult.Ok else ExecResult.Failed("verification failed: \"$needle\" not on screen")
            }
            ActionType.RETRY -> ExecResult.Ok // handled by the orchestrator
            ActionType.ABORT -> ExecResult.Aborted(a.reason ?: "aborted by planner")
            ActionType.REQUEST_USER -> ExecResult.NeedUser(a.reason ?: "The agent needs you to continue.")
        }
    }

    private fun global(svc: AccessibilityService, action: Int): ExecResult =
        if (svc.performGlobalAction(action)) ExecResult.Ok else ExecResult.Failed("global action failed")

    private suspend fun openApp(svc: AccessibilityService, target: String): ExecResult {
        try {
            if (target.startsWith("https://", ignoreCase = true)) {
                val view = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    svc.startActivity(Intent(view).setPackage("com.android.chrome"))
                } catch (e: ActivityNotFoundException) {
                    svc.startActivity(view)
                }
            } else {
                val pkg = AppDiscovery.resolve(svc.packageManager, target)
                    ?: return ExecResult.Failed("no installed app matches \"$target\"")
                val launch = svc.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return ExecResult.Failed("app has no launcher activity")
                svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            return ExecResult.Failed("could not open app (${e.javaClass.simpleName}); Android may block background launches")
        }
        delay(1500)
        return ExecResult.Ok
    }
}

object AppDiscovery {
    data class AppInfo(val label: String, val pkg: String)

    fun listApps(pm: PackageManager): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }

    /** Exact package, then exact label, then label contains (case-insensitive). */
    fun resolve(pm: PackageManager, name: String): String? {
        val apps = listApps(pm)
        val n = name.trim()
        return apps.firstOrNull { it.pkg.equals(n, true) }?.pkg
            ?: apps.firstOrNull { it.label.equals(n, true) }?.pkg
            ?: apps.firstOrNull { it.label.contains(n, true) }?.pkg
    }
}
