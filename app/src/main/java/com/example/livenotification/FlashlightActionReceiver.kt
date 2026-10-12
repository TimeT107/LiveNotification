package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast


class FlashlightActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CLOSE = "com.example.livenotification.FLASHLIGHT_CLOSE"
        const val ACTION_OPEN_PANEL = "com.example.livenotification.FLASHLIGHT_OPEN_PANEL"
    }

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {

            ACTION_OPEN_PANEL -> {
                FloatingHotZone.showFlashlightPanel(context)
            }

            ACTION_CLOSE -> {
                FlashlightController.setTorch(false)
                FlashlightNotificationManager(context).stop()
                Toast.makeText(
                    context,
                    "手电筒已关闭",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}