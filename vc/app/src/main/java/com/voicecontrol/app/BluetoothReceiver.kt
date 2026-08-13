package com.voicecontrol.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * When "auto driving mode" is enabled, connecting to the car's Bluetooth
 * (audio system) automatically starts the listener service; disconnecting
 * pauses it. This avoids the mic running all day long, saving battery.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!CommandStore.isAutoDrivingModeEnabled(context)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val serviceIntent = Intent(context, VoiceListenerService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                if (VoiceListenerService.isRunning) {
                    context.stopService(Intent(context, VoiceListenerService::class.java))
                }
            }
        }
    }
}
