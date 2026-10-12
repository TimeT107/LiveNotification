package com.example.livenotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build


class ExpressNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "express"
        private const val NOTIFICATION_ID = 1002

        private val ICON_SMALL = R.drawable.ic_express
        private val ICON_LARGE = R.drawable.ic_express_large
    }

    // 去重状态
    private var lastHash: Int = 0
    private var createdWhen: Long = 0L

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "快递通知",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "待取快递提醒"
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("NewApi")
    fun show(items: List<ExpressData>) {

        // 没有快递 → 取消通知
        if (items.isEmpty()) {
            stop()
            return
        }

        // ============ 去重 ============
        val hash = items
            .map { "${it.location}|${it.code}" }
            .joinToString("|")
            .hashCode()
        if (hash == lastHash) return
        lastHash = hash

        if (createdWhen == 0L) {
            createdWhen = System.currentTimeMillis()
        }

        val count = items.size

        // 折叠副文本：第一条快递的取件地点 + 取件码
        val first = items.first()
        val collapsedLine = "${first.location} · ${first.code}"

        // 展开详情：全部快递列表
        val detail = buildString {
            append("共 $count 件待取\n")
            items.forEachIndexed { index, item ->
                append("${index + 1}. ${item.location}\n")
                append("   取件码：${item.code}\n")
            }
        }.trimEnd()

        // 点击通知跳 MainActivity
        val intent = Intent(context, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIcon = BitmapFactory.decodeResource(
            context.resources,
            ICON_LARGE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)

        builder
            .setSmallIcon(ICON_SMALL)
            .setLargeIcon(largeIcon)

            .setContentTitle("待取件 $count 件")
            .setContentText(collapsedLine)

            .setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle("共 $count 件待取")
                    .bigText(detail)
            )

            .setContentIntent(pendingIntent)

            .setOngoing(true)
            .setCategory(Notification.CATEGORY_REMINDER)

            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

            // ★ 固定时间戳，避免每次 notify 都被视为新通知
            .setWhen(createdWhen)
            .setUsesChronometer(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText("待取件")
        }

        manager.notify(
            NOTIFICATION_ID,
            builder.build()
        )
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
        lastHash = 0
        createdWhen = 0L
    }
}