package com.voicecontrol.app

import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Launched on top of the target app (e.g. Claude, Facebook) so the user can
 * tap exactly where the "talk" button or feed is. For TAP actions we record
 * one point; for SWIPE we record a start and end point across two taps.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var actionId: String
    private lateinit var label: String
    private lateinit var appPackage: String
    private lateinit var type: String

    private var firstPoint: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        actionId = intent.getStringExtra("actionId") ?: return finish()
        label = intent.getStringExtra("label") ?: ""
        appPackage = intent.getStringExtra("appPackage") ?: ""
        type = intent.getStringExtra("type") ?: "TAP"

        val instructions = findViewById<TextView>(R.id.instructionText)
        instructions.text = if (type == "SWIPE")
            "دوس على نقطة البداية بتاعة الحركة: $label"
        else
            "دوس على مكان: $label"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.rawX
            val y = event.rawY

            if (type == "TAP") {
                saveAndFinish(x, y, x, y)
                return true
            }

            // SWIPE needs two points
            if (firstPoint == null) {
                firstPoint = x to y
                findViewById<TextView>(R.id.instructionText).text = "دلوقتي دوس على نقطة النهاية"
            } else {
                val (x1, y1) = firstPoint!!
                saveAndFinish(x1, y1, x, y)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun saveAndFinish(x1: Float, y1: Float, x2: Float, y2: Float) {
        val action = CalibratedAction(
            id = actionId,
            label = label,
            appPackage = appPackage,
            type = type,
            x = x1, y = y1, x2 = x2, y2 = y2
        )
        CommandStore.addOrUpdateAction(this, action)
        finish()
    }
}
