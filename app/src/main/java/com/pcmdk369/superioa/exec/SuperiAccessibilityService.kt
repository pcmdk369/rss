package com.pcmdk369.superioa.exec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class TypeResult { OK, NO_FIELD, PASSWORD_FIELD, FAILED }

/**
 * Local execution layer. It only acts when SuperiOA's orchestrator calls it for a task the user started.
 * It never reads password-field text and never performs actions on its own.
 */
class SuperiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SuperiAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    /** Compact text dump of the visible UI with tap coordinates. Password fields are masked. */
    fun readScreen(maxChars: Int = 4000): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        sb.append("package: ").append(root.packageName).append('\n')
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || count > 150 || sb.length > maxChars) return
            if (n.isVisibleToUser) {
                val label = if (n.isPassword) {
                    "[password field]"
                } else {
                    listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" / ")
                }
                if (label.isNotBlank() || n.isClickable || n.isEditable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    sb.append(label.take(80)).append(" @(").append(r.centerX()).append(',').append(r.centerY()).append(')')
                    if (n.isClickable) sb.append(" [click]")
                    if (n.isEditable) sb.append(" [input]")
                    sb.append('\n')
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    fun screenContains(needle: String): Boolean =
        readScreen(20_000).contains(needle, ignoreCase = true)

    private fun findNode(query: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val matches = if (query.contains(":id/")) root.findAccessibilityNodeInfosByViewId(query)
        else root.findAccessibilityNodeInfosByText(query)
        val node = matches.firstOrNull { it.isVisibleToUser } ?: return null
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        return cur ?: node
    }

    fun tapNode(query: String): Boolean =
        findNode(query)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    fun typeText(text: String): TypeResult {
        val root = rootInActiveWindow ?: return TypeResult.NO_FIELD
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return TypeResult.NO_FIELD
        if (node.isPassword) return TypeResult.PASSWORD_FIELD
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) TypeResult.OK else TypeResult.FAILED
    }

    fun clearText(): TypeResult {
        val root = rootInActiveWindow ?: return TypeResult.NO_FIELD
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: firstEditable(root) ?: return TypeResult.NO_FIELD
        if (node.isPassword) return TypeResult.PASSWORD_FIELD
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) TypeResult.OK else TypeResult.FAILED
    }

    fun longPressNode(query: String): Boolean {
        val node = findNode(query) ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return dispatchLongPress(bounds.centerX(), bounds.centerY())
    }

    suspend fun longPressAt(x: Int, y: Int): Boolean = gesture(x, y, x, y, 800)

    private fun dispatchLongPress(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 800))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun firstEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    fun scroll(direction: String): Boolean {
        val node = firstScrollable(rootInActiveWindow) ?: return false
        val forward = direction == "down" || direction == "right"
        return node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun copyFocused(): Boolean =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.ACTION_COPY) ?: false

    fun pasteFocused(): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (node.isPassword) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    suspend fun tapAt(x: Int, y: Int): Boolean = gesture(x, y, x, y, 60)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean = gesture(x1, y1, x2, y2, 300)

    private suspend fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
            }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durMs))
                .build()
            val started = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!started && cont.isActive) cont.resume(false)
        }
}
