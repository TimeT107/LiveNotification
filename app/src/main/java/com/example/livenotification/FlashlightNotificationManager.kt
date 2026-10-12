package com.example.livenotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi


class FlashlightNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "flashlight"
        private const val NOTIFICATION_ID = 1031

        private val ICON_SMALL = android.R.drawable.ic_menu_camera
    }

    /** 系统莫奈主色 */
    private fun monetColor(): Int {
        return try {
            context.getColor(android.R.color.system_accent1_500)
        } catch (_: Exception) {
            0xFFFFC107.toInt()
        }
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "手电筒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "手电筒状态提醒"
            setSound(null, null)                 // ★ 无声音
            enableVibration(false)                // ★ 无震动
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("NewApi")
    fun show(data: FlashlightData) {

        val accent = monetColor()

        // ============ 关闭按钮 ============
        val closeIntent = Intent(
            context,
            FlashlightActionReceiver::class.java
        ).apply {
            action = FlashlightActionReceiver.ACTION_CLOSE
        }
        val closePending = PendingIntent.getBroadcast(
            context,
            0,
            closeIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val closeIcon = Icon.createWithResource(
            context,
            android.R.drawable.ic_menu_close_clear_cancel
        ).apply {
            // ★ 染莫奈色
            setTint(accent)
        }

        // ============ 亮度按钮 ============
        val brightnessIntent = Intent(
            context,
            FlashlightActionReceiver::class.java
        ).apply {
            action = FlashlightActionReceiver.ACTION_OPEN_PANEL
        }
        val brightnessPending = PendingIntent.getBroadcast(
            context,
            1,
            brightnessIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val brightnessIcon = Icon.createWithResource(
            context,
            android.R.drawable.ic_menu_preferences
        ).apply {
            setTint(accent)
        }

        // ============ 副标题 ============
        val subText = if (data.supportsBrightness) {
            "亮度 ${data.currentLevel}/${data.maxLevel}"
        } else {
            "已开启"
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON_SMALL)
            .setContentTitle("手电筒已开启")
            .setContentText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)

            // ★ 通知强调色也用莫奈色
            .setColor(accent)

            // 亮度按钮
            .addAction(
                Notification.Action.Builder(
                    brightnessIcon,
                    "亮度",
                    brightnessPending
                ).build()
            )
            // 关闭按钮
            .addAction(
                Notification.Action.Builder(
                    closeIcon,
                    "关闭",
                    closePending
                ).build()
            )

            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(buildProgressStyle(data))
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText("手电筒")
        }

        manager.notify(NOTIFICATION_ID, builder.build())
        Log.d("FlashlightNotif", "显示手电筒通知")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(data: FlashlightData): Notification.ProgressStyle {

        // ★ 进度条颜色也用莫奈色
        val accent = monetColor()
        val track = 0x33FFFFFF.toInt()

        val percent = if (data.maxLevel > 0) {
            ((data.currentLevel.toFloat() / data.maxLevel) * 100)
                .toInt()
                .coerceIn(1, 99)
        } else 50

        return Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(percent)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(percent)
                        .setColor(accent),
                    Notification.ProgressStyle.Segment(100 - percent)
                        .setColor(track)
                )
            )
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
    }
}