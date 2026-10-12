package com.example.livenotification

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import android.util.Log


/**
 * 从白名单 App 的通知里识别验证码
 */
object VerificationNotificationHandler {

    fun handle(context: Context, sbn: StatusBarNotification) {

        // 过滤自己的通知
        if (sbn.packageName == context.packageName) return

        // 白名单过滤
        if (!VerificationWhitelist.contains(sbn.packageName)) return

        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ?.toString() ?: ""

            val fullText = listOf(title, text, bigText, subText)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            val code = VerificationParser.parse(fullText) ?: return

            // 去重：相同 code + 相同 package 不重复
            val existing = VerificationStore.get()
            if (existing?.code == code &&
                existing.senderPackage == sbn.packageName
            ) {
                return
            }

            VerificationStore.set(
                VerificationData(
                    code = code,
                    rawText = fullText,
                    senderPackage = sbn.packageName,
                    createTime = System.currentTimeMillis()
                )
            )

            Log.d("VerifNotif", "通知识别验证码: $code (来自 ${sbn.packageName})")

            // 启动 Service（触发刷新）
            try {
                context.startForegroundService(
                    Intent(context, LiveService::class.java)
                )
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            Log.e("VerifNotif", "处理通知失败", e)
        }
    }
}