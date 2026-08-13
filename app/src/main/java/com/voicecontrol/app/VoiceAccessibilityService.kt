package com.voicecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service used to programmatically tap / swipe on screen and
 * launch other apps. This is the "hands" of the assistant: it's what lets a
 * voice command like "دوس على زرار التحدث" actually touch the screen.
 */
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not currently used for reactive logic, but required override.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    /** Launches an app by package name. Returns false if the app isn't installed. */
    fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
        return true
    }

    /** Taps a specific point on screen. */
    fun tap(x: Float, y: Float, onDone: (Boolean) -> Unit = {}) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone(false)
            }
        }, null)
    }

    /** Swipes from one point to another, e.g. to scroll a feed. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, onDone: (Boolean) -> Unit = {}) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone(false)
            }
        }, null)
    }

    /** Scrolls the current screen in the requested direction. */
    fun scrollFeed(direction: String = "down", onDone: (Boolean) -> Unit = {}) {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val fromY = if (direction == "up") metrics.heightPixels * 0.25f else metrics.heightPixels * 0.75f
        val toY = if (direction == "up") metrics.heightPixels * 0.75f else metrics.heightPixels * 0.25f
        swipe(centerX, fromY, centerX, toY, 300, onDone)
    }

    /** Finds and clicks a likely voice-chat button from the current accessibility tree. */
    fun clickVoiceChatButton(timeoutMs: Long = 5000, onDone: (Boolean) -> Unit) {
        val started = System.currentTimeMillis()
        fun attempt() {
            if (rootInActiveWindow != null && findAndClickVoiceButton(rootInActiveWindow!!)) {
                onDone(true)
                return
            }
            if (System.currentTimeMillis() - started >= timeoutMs) {
                onDone(false)
                return
            }
            android.os.Handler(mainLooper).postDelayed({ attempt() }, 250)
        }
        attempt()
    }

    private fun findAndClickVoiceButton(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val keywords = listOf(
            "محادثه صوت", "محادثه صوتيه", "محادثة صوتية", "voice", "voice chat",
            "talk", "speak", "start voice", "بدء المحادثه الصوتيه", "التحدث", "صوت"
        )
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()).lowercase()
            if (node.isVisibleToUser && node.isClickable && keywords.any { text.contains(it) }) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return false
    }
}
