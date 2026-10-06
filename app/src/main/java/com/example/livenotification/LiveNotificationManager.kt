package com.example.livenotification

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi


class LiveNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "live"
        private const val NOTIFICATION_ID = 1001
    }

    private var currentData: LiveData? = null

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "实时通知",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Pixel Live Chip 动态通知"
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("NewApi")
    private fun enablePromotedOngoing(
        builder: Notification.Builder,
        text: String
    ) {
        builder.setRequestPromotedOngoing(true)
        builder.setShortCriticalText(text)
    }

    /**
     * 把车次压缩成符合 PixelOS Live Chip 限制的短文本
     */
    private fun chipText(data: LiveData): String {
        val trainNo = data.title.replace("次", "").trim()

        if (trainNo.isNotEmpty() &&
            trainNo.length <= 6 &&
            trainNo.all { it.code < 128 }
        ) {
            return trainNo
        }

        val autoStatus = CountdownManager.calcAutoStatus(data.departureTime)
        if (autoStatus.length in 1..4 &&
            autoStatus.all { it.code > 127 }
        ) {
            return autoStatus
        }

        return "TRAIN"
    }

    /**
     * 构建 Android 16 (API 36) 的 ProgressStyle 进度条
     * 含火车头图标，位于进度条头部
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(data: LiveData): Notification.ProgressStyle {

        val completedColor = context.getColor(R.color.progress_completed)
        val remainingColor = context.getColor(R.color.progress_remaining)

        // 防止 0 / 100 时段落长度为 0 引起异常
        val completedLength = data.progress.coerceIn(1, 99)
        val remainingLength = 100 - completedLength

        return Notification.ProgressStyle()
            .setStyledByProgress(false)
            // ★ 火车头位置：跟随当前进度
            .setProgress(data.progress.coerceIn(0, 100))
            .setProgressTrackerIcon(
                Icon.createWithResource(context, R.drawable.ic_train_head)
            )
            // ★ 着色分界：两段，已完成 / 未完成
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(completedLength)
                        .setColor(completedColor),
                    Notification.ProgressStyle.Segment(remainingLength)
                        .setColor(remainingColor)
                )
            )
    }

    fun show(data: LiveData) {

        currentData = data

        // ============ 文本 ============
        val autoStatus = CountdownManager.calcAutoStatus(data.departureTime)

        val shortDate = try {
            val list = data.departureDate.split("-")
            "${list[1]}月${list[2]}日"
        } catch (e: Exception) {
            data.departureDate
        }

        val countdown = CountdownManager.getCountdownText(
            data.departureTime,
            data.arrivalTime
        )

        // 折叠副文本 / 展开详情（ProgressStyle 下只会显示第一行）
        // 折叠副文本 / 展开详情（ProgressStyle 下只会显示第一行）
        val collapsedLine =
            "${data.startStation} ${data.startTime} → ${data.endStation} ${data.endTime} · ${data.seat} · $autoStatus"

        // ============ Intent ============
        val intent = Intent(context, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.train_logo
        )

        // ============ 构建通知 ============
        val builder = Notification.Builder(context, CHANNEL_ID)

        builder
            .setSmallIcon(R.drawable.train_chip)
            .setLargeIcon(icon)

            // 胶囊主文本
            .setContentTitle(data.title)
            // 折叠副文本（ProgressStyle 下只显示这一行）
            .setContentText(collapsedLine)

            .setContentIntent(pendingIntent)

            // Live Chip 关键
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)

            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

            // 锁屏可见性
            .setVisibility(Notification.VISIBILITY_PUBLIC)

            // 倒计时：以到达时间为终点
            .setWhen(data.arrivalTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Android 16+：使用 ProgressStyle（带火车头图标）
            builder.setStyle(buildProgressStyle(data))
            enablePromotedOngoing(builder, chipText(data))
        } else {
            // 低版本回退：使用 BigTextStyle + setProgress
            builder.setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle("${data.title} · $shortDate")
                    .bigText("""
$autoStatus
${data.startStation} ${data.startTime} → ${data.endStation} ${data.endTime}
座位 ${data.seat}   $countdown
${data.bottomText}
""".trimIndent())
            )
            builder.setProgress(100, data.progress, false)
        }

        manager.notify(
            NOTIFICATION_ID,
            builder.build()
        )
    }

    fun update(data: LiveData) {
        show(data)
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
    }
}