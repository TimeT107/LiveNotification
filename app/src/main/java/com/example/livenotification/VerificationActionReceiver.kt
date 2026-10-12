package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast


class VerificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COPY = "com.example.livenotification.VERIF_COPY"
        const val ACTION_MARK_READ = "com.example.livenotification.VERIF_MARK_READ"
        const val EXTRA_CODE = "code"
    }

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {

            ACTION_COPY -> {
                val code = intent.getStringExtra(EXTRA_CODE) ?: return
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("验证码", code)
                    )
                    Toast.makeText(
                        context,
                        "已复制验证码",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {
                }
            }

            ACTION_MARK_READ -> {
                VerificationStore.clear()
                VerificationNotificationManager(context).stop()
                Toast.makeText(
                    context,
                    "已读",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}