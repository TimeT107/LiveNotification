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

            // 标题：待取件 X 件
            .setContentTitle("待取件 $count 件")
            // 折叠副文本：第一条取件码
            .setContentText(collapsedLine)

            // 展开详情：所有快递列表
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

            // ★ 让胶囊文字显示的"有时间性"标记
            //   副作用：通知右上角会出现一个正计时（从创建时间起）
            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true)
            // ★ 胶囊文本：纯中文 3 字，符合 PixelOS 的实测规则
            builder.setShortCriticalText("待取件")
        }

        manager.notify(
            NOTIFICATION_ID,
            builder.build()
        )
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
    }
}