package com.voicecontrol.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Android 14+ does not allow a microphone foreground service to be started
 * from BOOT_COMPLETED. We therefore never try to start the microphone here.
 * We only remind the user that the assistant can be resumed by opening it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("voice_control_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("start_on_boot", false)) return

        val channelId = "voice_control_boot"
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "المساعد الصوتي", NotificationManager.IMPORTANCE_LOW)
        )
        nm.notify(
            42,
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("المساعد الصوتي")
                .setContentText("افتح التطبيق مرة واحدة لاستئناف الاستماع بعد إعادة تشغيل الهاتف")
                .setAutoCancel(true)
                .build()
        )
    }
}
