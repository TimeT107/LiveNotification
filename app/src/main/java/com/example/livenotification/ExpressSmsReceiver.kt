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

        ExpressStore.init(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?: return

        if (messages.isEmpty()) return

        val sender = messages[0].displayOriginatingAddress ?: ""
        val fullBody = messages.joinToString("") { it.displayMessageBody ?: "" }

        Log.d("ExpressSms", "收到短信 来自=$sender 正文=$fullBody")

        // ============ ① 快递识别 ============
        val parsedExpress = ExpressSmsParser.parse(fullBody)
        if (parsedExpress != null) {
            handleExpress(context, parsedExpress)
        }

        // ============ ② 验证码识别 ============
        // Gated by the feature switch: still rough, off by default.
        if (FeaturePreferences.isVerificationEnabled(context)) {
            val code = VerificationParser.parse(fullBody)
            if (code != null) {
                handleVerification(context, code, fullBody)
            }
        }
    }

    // ============================================================
    private fun handleExpress(context: Context, data: ExpressData) {

        val exists = ExpressStore.getAll().any { it.code == data.code }
        if (exists) {
            Log.d("ExpressSms", "取件码已存在，跳过: ${data.code}")
            return
        }

        ExpressStore.add(data)
        Log.d("ExpressSms", "已添加: ${data.location} - ${data.code}")

        try {
            ExpressNotificationManager(context).show(ExpressStore.getAll())
            Log.d("ExpressSms", "通知已刷新")
        } catch (e: Exception) {
            Log.e("ExpressSms", "刷新通知失败", e)
        }

        try {
            context.startForegroundService(
                Intent(context, LiveService::class.java)
            )
            Log.d("ExpressSms", "Service 已启动")
        } catch (e: Exception) {
            Log.e("ExpressSms", "启动 Service 失败", e)
        }
    }

    // ============================================================
    private fun handleVerification(context: Context, code: String, rawText: String) {

        val smsPackage = try {
            Telephony.Sms.getDefaultSmsPackage(context) ?: ""
        } catch (_: Exception) {
            ""
        }

        val existing = VerificationStore.get()
        if (existing?.code == code) {
            Log.d("VerifSms", "验证码相同，跳过: $code")
            return
        }

        VerificationStore.set(
            VerificationData(
                code = code,
                rawText = rawText,
                senderPackage = smsPackage,
                createTime = System.currentTimeMillis()
            )
        )
        Log.d("VerifSms", "短信识别验证码: $code")

        try {
            context.startForegroundService(
                Intent(context, LiveService::class.java)
            )
        } catch (_: Exception) {
        }
    }
}