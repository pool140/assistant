package com.voicecontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("voice_control_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("start_on_boot", false)
            if (autoStart) {
                val serviceIntent = Intent(context, VoiceListenerService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
