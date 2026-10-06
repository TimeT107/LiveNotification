package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class ExpressSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        // 确保 Store 已初始化
        ExpressStore.init(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return

        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: ""
        val fullBody = messages.joinToString("") { it.displayMessageBody ?: "" }

        Log.d("ExpressSms", "收到短信 来自=$sender 正文=$fullBody")

        val expressData = ExpressSmsParser.parse(fullBody) ?: return

        val exists = ExpressStore.getAll().any { it.code == expressData.code }
        if (exists) {
            Log.d("ExpressSms", "取件码已存在，跳过: ${expressData.code}")
            return
        }

        ExpressStore.add(expressData)
        Log.d("ExpressSms", "已添加: ${expressData.location} - ${expressData.code}")

        // ★ 关键 1：直接弹通知（不依赖 Service）
        try {
            ExpressNotificationManager(context).show(ExpressStore.getAll())
            Log.d("ExpressSms", "通知已刷新")
        } catch (e: Exception) {
            Log.e("ExpressSms", "刷新通知失败", e)
        }

        // ★ 关键 2：尝试启动 Service 做后续管理
        try {
            context.startForegroundService(
                Intent(context, LiveService::class.java)
            )
            Log.d("ExpressSms", "Service 已启动")
        } catch (e: Exception) {
            Log.e("ExpressSms", "启动 Service 失败", e)
        }
    }
}