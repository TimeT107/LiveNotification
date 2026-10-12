package com.example.livenotification

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface


class LiveNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "live"

        /** 火车通知 ID */
        private const val NOTIFICATION_ID_TRAIN = 1001

        /** 航班通知 ID */
        private const val NOTIFICATION_ID_FLIGHT = 1011
    }

    // 去重状态（按类型分别记录）
    private var lastHashTrain: Int = 0
    private var lastHashFlight: Int = 0
    private var createdWhenTrain: Long = 0L
    private var createdWhenFlight: Long = 0L

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
     * 把车次/航班号压缩成符合 PixelOS Live Chip 限制的短文本
     * 高铁：G1234 / D123 / C3456
     * 航班：CA1234 / MU5678 / CZ9012
     * 都是纯 ASCII，可直接复用
     */
    private fun chipText(data: LiveData): String {
        val code = data.title
            .replace("次", "")
            .trim()

        if (code.isNotEmpty() &&
            code.length <= 6 &&
            code.all { it.code < 128 }
        ) {
            return code
        }

        val autoStatus = CountdownManager.calcAutoStatus(data.departureTime)
        if (autoStatus.length in 1..4 &&
            autoStatus.all { it.code > 127 }
        ) {
            return autoStatus
        }

        return if (data.type == "flight") "FLIGHT" else "TRAIN"
    }

    /**
     * 把 emoji 渲染成 Bitmap，用作 ProgressStyle 的 tracker 图标
     */
    private fun createEmojiBitmap(emoji: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            sizePx, sizePx, Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx * 0.85f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val metrics = paint.fontMetrics
        val baseline = (sizePx - metrics.bottom - metrics.top) / 2f
        canvas.drawText(emoji, sizePx / 2f, baseline, paint)
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(data: LiveData): Notification.ProgressStyle {

        val completedColor = context.getColor(R.color.progress_completed)
        val remainingColor = context.getColor(R.color.progress_remaining)

        val completedLength = data.progress.coerceIn(1, 99)
        val remainingLength = 100 - completedLength

        return Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(data.progress.coerceIn(0, 100))
            .setProgressTrackerIcon(
                if (data.type == "flight") {
                    Icon.createWithBitmap(createEmojiBitmap("✈️", 96))
                } else {
                    Icon.createWithResource(context, R.drawable.ic_train_head)
                }
            )
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

        val isFlight = data.type == "flight"
        val notificationId = if (isFlight) NOTIFICATION_ID_FLIGHT
        else NOTIFICATION_ID_TRAIN

        // ============ 去重 ============
        val autoStatus = CountdownManager.calcAutoStatus(data.departureTime)
        val hash = "${data.title}|${data.progress}|$autoStatus|${data.seat}"
            .hashCode()

        val lastHash = if (isFlight) lastHashFlight else lastHashTrain
        if (hash == lastHash) return

        if (isFlight) {
            lastHashFlight = hash
        } else {
            lastHashTrain = hash
        }

        // ============ 时间戳 ============
        var createdWhen = if (isFlight) createdWhenFlight else createdWhenTrain
        if (createdWhen == 0L) {
            createdWhen = System.currentTimeMillis()
            if (isFlight) createdWhenFlight = createdWhen
            else createdWhenTrain = createdWhen
        }

        // ============ 文本 ============
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

        // 折叠副文本
        val collapsedLine = if (isFlight) {
            // 航班：机场 → 机场 · 登机口 · 状态
            buildString {
                append("${data.startStation} → ${data.endStation}")
                if (data.boardingGate.isNotBlank()) {
                    append(" · 登机口 ${data.boardingGate}")
                }
                append(" · $autoStatus")
            }
        } else {
            // 火车：站 → 站 · 座位 · 状态
            "${data.startStation} ${data.startTime} → ${data.endStation} ${data.endTime} · ${data.seat} · $autoStatus"
        }

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

        // ============ 通知 ============
        val builder = Notification.Builder(context, CHANNEL_ID)

        builder
            .setSmallIcon(
                if (isFlight) R.drawable.flight_chip
                else R.drawable.train_chip
            )
            .setLargeIcon(icon)

            .setContentTitle(if (isFlight) "✈️ ${data.title}" else data.title)
            .setContentText(collapsedLine)

            .setContentIntent(pendingIntent)

            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)

            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

            .setVisibility(Notification.VISIBILITY_PUBLIC)

            .setWhen(data.arrivalTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(buildProgressStyle(data))
            enablePromotedOngoing(builder, chipText(data))
        } else {
            // 低版本回退
            val detail = if (isFlight) {
                buildString {
                    append("✈️ ${data.title} · $shortDate\n")
                    if (data.airline.isNotBlank()) {
                        append("${data.airline}\n")
                    }
                    append("$autoStatus\n")
                    append("${data.startStation} ${data.startTime} → ${data.endStation} ${data.endTime}\n")
                    if (data.terminal.isNotBlank()) {
                        append("航站楼 ${data.terminal}   ")
                    }
                    if (data.boardingGate.isNotBlank()) {
                        append("登机口 ${data.boardingGate}   ")
                    }
                    append("\n")
                    if (data.seat.isNotBlank()) {
                        append("座位 ${data.seat}   ")
                    }
                    append(countdown)
                    append("\n${data.bottomText}")
                }
            } else {
                buildString {
                    append("🚄 ${data.title} · $shortDate\n")
                    append("$autoStatus\n")
                    append("${data.startStation} ${data.startTime} → ${data.endStation} ${data.endTime}\n")
                    append("座位 ${data.seat}   $countdown\n")
                    append(data.bottomText)
                }
            }

            builder.setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle("${data.title} · $shortDate")
                    .bigText(detail)
            )
            builder.setProgress(100, data.progress, false)
        }

        manager.notify(notificationId, builder.build())
    }

    fun update(data: LiveData) {
        show(data)
    }

    /**
     * 兼容：停止火车通知
     * 旧代码调用 stop() 等价于 stopTrain()
     */
    fun stop() = stopTrain()

    fun stopTrain() {
        manager.cancel(NOTIFICATION_ID_TRAIN)
        lastHashTrain = 0
        createdWhenTrain = 0L
    }

    fun stopFlight() {
        manager.cancel(NOTIFICATION_ID_FLIGHT)
        lastHashFlight = 0
        createdWhenFlight = 0L
    }

    fun stopAll() {
        stopTrain()
        stopFlight()
    }
}