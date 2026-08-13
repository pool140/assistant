package com.voicecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class VoiceAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "VoiceAccessibility"
        var instance: VoiceAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { Log.w(TAG, "Accessibility service interrupted") }

    fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try { startActivity(launchIntent); true } catch (_: Exception) { false }
    }

    fun tap(x: Float, y: Float, onDone: (Boolean) -> Unit = {}) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        dispatchGesture(gesture, resultCallback(onDone), null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, onDone: (Boolean) -> Unit = {}) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, resultCallback(onDone), null)
    }

    private fun resultCallback(onDone: (Boolean) -> Unit) = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
        override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
    }

    fun scrollFeed(direction: String = "up", onDone: (Boolean) -> Unit = {}) {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        if (direction == "down") swipe(x, m.heightPixels * .25f, x, m.heightPixels * .75f, 300, onDone)
        else swipe(x, m.heightPixels * .75f, x, m.heightPixels * .25f, 300, onDone)
    }

    /** Find a visible/accessibility-described control and click it. This avoids fixed screen coordinates. */
    fun clickBestMatchingControl(hints: List<String>, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val root = rootInActiveWindow
        if (root == null) { onDone(false, "no active window"); return }
        val normalizedHints = hints.map(CommandInterpreter::normalize)
        val node = findBestNode(root, normalizedHints)
        if (node == null) { onDone(false, "control not found"); return }
        val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
        if (ok) { onDone(true, "clicked node") ; return }
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        tap(r.centerX().toFloat(), r.centerY().toFloat()) { clicked -> onDone(clicked, "coordinate fallback") }
    }

    private fun findBestNode(node: AccessibilityNodeInfo, hints: List<String>): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        fun walk(n: AccessibilityNodeInfo) {
            val text = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName).joinToString(" ")
            val normalized = CommandInterpreter.normalize(text)
            if (normalized.isNotBlank()) {
                var score = 0
                hints.forEach { h -> if (normalized.contains(h)) score += 10 }
                if (n.isClickable) score += 3
                if (n.isEnabled) score += 1
                if (score > 0) candidates += score to n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(node)
        return candidates.maxByOrNull { it.first }?.second
    }
}
