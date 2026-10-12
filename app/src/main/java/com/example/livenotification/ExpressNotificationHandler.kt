package com.example.livenotification

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log


/**
 * 从任意应用的通知中提取快递取件信息
 * 复用 ExpressSmsParser 的解析逻辑
 */
object ExpressNotificationHandler {

    fun handle(context: Context, sbn: StatusBarNotification) {

        // 过滤自己的通知，避免自监听
        if (sbn.packageName == context.packageName) return

        try {
            // 确保 ExpressStore 已初始化
            ExpressStore.init(context)

            // 拼接所有可能的文本字段
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                ?.toString() ?: ""
            val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                ?.toString() ?: ""

            val fullText = listOf(title, text, bigText, subText, summaryText)
                .filter { it.isNotBlank() }
                .joinToString("\n")

            if (fullText.isBlank()) return

            // 解析
            val data = ExpressSmsParser.parse(fullText) ?: return

            // 去重：相同取件码已存在就跳过
            val exists = ExpressStore.getAll().any { it.code == data.code }
            if (exists) {
                Log.d("ExpressNotif", "取件码已存在，跳过: ${data.code}")
                return
            }

            // 添加
            ExpressStore.add(data)
            Log.d("ExpressNotif", "从通知识别取件: ${data.location} - ${data.code}")

            // 立即刷新快递通知
            try {
                ExpressNotificationManager(context).show(ExpressStore.getAll())
            } catch (e: Exception) {
                Log.e("ExpressNotif", "刷新快递通知失败", e)
            }

            // 启动 Service（确保 chip 侧同步）
            try {
                val svcIntent = android.content.Intent(context, LiveService::class.java)
                context.startForegroundService(svcIntent)
            } catch (_: Exception) {
            }

        } catch (e: Exception) {
            Log.e("ExpressNotif", "处理通知失败", e)
        }
    }
}