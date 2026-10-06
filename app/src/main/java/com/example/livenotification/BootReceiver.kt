package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 只在收到开机完成广播时触发
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 启动前台服务，让通知继续在后台运行
            val serviceIntent = Intent(context, LiveService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}