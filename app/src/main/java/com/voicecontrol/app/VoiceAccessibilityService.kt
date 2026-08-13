package com.voicecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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

    /** Default "scroll down feed" gesture: swipe up from lower-middle to upper-middle of screen. */
    fun scrollFeed(onDone: (Boolean) -> Unit = {}) {
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val fromY = metrics.heightPixels * 0.75f
        val toY = metrics.heightPixels * 0.25f
        swipe(centerX, fromY, centerX, toY, 250, onDone)
    }
}
