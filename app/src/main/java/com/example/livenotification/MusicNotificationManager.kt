package com.example.livenotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi


class MusicNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val SUMMARY_CHANNEL_ID = "music_summary"
        private const val NOTIFICATION_ID = 1003
        private const val SUMMARY_NOTIFICATION_ID = 9999

        private val ICON_SMALL = R.drawable.ic_music

        private const val FALLBACK_COLOR = 0xFFE07A7A.toInt()
    }

    private var lastHash: Int = 0
    private var createdWhen: Long = 0L

    private val colorCache = mutableMapOf<Int, Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null

    fun createChannel() {
        val mainChannel = NotificationChannel(
            CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "正在播放的音乐"
            setSound(null, null)                 // ★ 无声音
            enableVibration(false)                // ★ 无震动
        }
        manager.createNotificationChannel(mainChannel)

        val summaryChannel = NotificationChannel(
            SUMMARY_CHANNEL_ID,
            " ",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = " "
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(summaryChannel)
    }

    @SuppressLint("NewApi")
    fun show(data: MusicData) {

        // ★ 开关关闭 → 取消通知并退出
        if (!MusicChipPreference.isEnabled(context)) {
            if (lastHash != 0) {
                stop()
            }
            return
        }

        val controller = MediaActionReceiver.currentController
        val metadata = controller?.metadata

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: data.title

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: data.artist

        val albumArt: Bitmap? =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: data.albumArt

        val position = controller?.playbackState?.position ?: 0L
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val progressPercent: Int = if (duration > 0) {
            ((position.toFloat() / duration) * 100)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val positionSecond = (position / 1000L).toInt()

        val hash = buildString {
            append(title).append('|')
            append(artist).append('|')
            append(data.isPlaying).append('|')
            append(albumArt?.hashCode()).append('|')
            append(positionSecond)
        }.hashCode()

        if (hash == lastHash) {
            if (albumArt == null) scheduleCoverRetry(data)
            return
        }
        lastHash = hash

        if (createdWhen == 0L) {
            createdWhen = System.currentTimeMillis()
        }

        // ★ chip 文字（带省略号版本）
        val chip = chipText(title, artist)
        Log.d("MusicNotif", "chip=$chip, progress=$progressPercent%")

        // ★ 副文本：完整歌名 + 短歌手
        val rawArtist = artist.split("/").firstOrNull()?.trim() ?: artist
        val displayLine = if (title.length + rawArtist.length > 40) {
            // 太长只显示完整歌名
            title
        } else {
            "$title · $rawArtist"
        }

        val controlIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_MEDIA_CONTROL, true)
        }
        val controlPending = PendingIntent.getActivity(
            context,
            0,
            controlIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val albumColor = getAlbumColor(albumArt)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON_SMALL)
            .setContentTitle(chip)
            .setContentText(displayLine)          // ★ 完整歌名
            .setContentIntent(controlPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setWhen(createdWhen)
            .setUsesChronometer(false)
            .setGroup("music_group")
            .setGroupSummary(false)
            .setColor(albumColor)
            .setSound(null)          // ★ 无声音
            .setVibrate(null)        // ★ 无震动
            .setDefaults(0)          // ★ 关闭默认提醒
            .apply {
                albumArt?.let { art ->
                    setLargeIcon(
                        android.graphics.drawable.Icon.createWithBitmap(
                            scaleBitmap(art, 256)
                        )
                    )
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(buildProgressStyle(progressPercent, albumColor))
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(chip)
        } else {
            builder.setProgress(100, progressPercent, false)
        }

        manager.notify(NOTIFICATION_ID, builder.build())

        val summaryBuilder = Notification.Builder(context, SUMMARY_CHANNEL_ID)
            .setSmallIcon(ICON_SMALL)
            .setGroup("music_group")
            .setGroupSummary(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .setVisibility(Notification.VISIBILITY_SECRET)
        manager.notify(SUMMARY_NOTIFICATION_ID, summaryBuilder.build())

        if (albumArt == null) scheduleCoverRetry(data)
    }

    private fun scheduleCoverRetry(data: MusicData) {
        pendingRetry?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val controller = MediaActionReceiver.currentController
            val art = controller?.metadata?.getBitmap(
                MediaMetadata.METADATA_KEY_ALBUM_ART
            ) ?: controller?.metadata?.getBitmap(
                MediaMetadata.METADATA_KEY_ART
            )

            if (art != null) {
                lastHash = 0
                show(data)
            }
        }
        pendingRetry = r
        handler.postDelayed(r, 300L)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildProgressStyle(
        progress: Int,
        albumColor: Int
    ): Notification.ProgressStyle {

        val remainingColor = 0x33FFFFFF.toInt()
        val completedLength = progress.coerceIn(1, 99)
        val remainingLength = 100 - completedLength

        return Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(progress.coerceIn(0, 100))
            .setProgressTrackerIcon(
                android.graphics.drawable.Icon.createWithResource(
                    context,
                    R.drawable.ic_progress_dot
                )
            )
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(completedLength)
                        .setColor(albumColor),
                    Notification.ProgressStyle.Segment(remainingLength)
                        .setColor(remainingColor)
                )
            )
    }

    private fun getAlbumColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return FALLBACK_COLOR

        val key = bitmap.hashCode()
        colorCache[key]?.let { return it }

        val raw = extractDominantColor(bitmap)
        val monetized = monetizeColor(raw)
        colorCache[key] = monetized

        if (colorCache.size > 20) {
            val firstKey = colorCache.keys.first()
            colorCache.remove(firstKey)
        }

        return monetized
    }

    private fun extractDominantColor(bitmap: Bitmap): Int {
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)

                val a = (pixel shr 24) and 0xFF
                if (a < 128) continue

                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val brightness = (r + g + b) / 3
                if (brightness < 30 || brightness > 225) continue

                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0f else (max - min).toFloat() / max
                if (saturation < 0.15f) continue

                rSum += r
                gSum += g
                bSum += b
                count++
            }
        }

        if (count == 0) return FALLBACK_COLOR

        val r = (rSum / count).toInt().coerceIn(0, 255)
        val g = (gSum / count).toInt().coerceIn(0, 255)
        val b = (bSum / count).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun monetizeColor(raw: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        hsv[1] = 0.45f
        hsv[2] = 0.72f
        return Color.HSVToColor(hsv)
    }

    private fun scaleBitmap(src: Bitmap, maxSize: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSize && h <= maxSize) return src
        val ratio = maxSize.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            src,
            (w * ratio).toInt(),
            (h * ratio).toInt(),
            true
        )
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(SUMMARY_NOTIFICATION_ID)
        lastHash = 0
        createdWhen = 0L
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun chipText(title: String, artist: String): String {
        val titleChip = compress(title)
        if (titleChip.length <= 2 && artist.isNotBlank()) {
            val combined = "$titleChip · $artist"
            val combinedChip = compress(combined)
            if (combinedChip.length > titleChip.length) {
                return combinedChip
            }
        }
        return titleChip
    }

    /**
     * ★ 带省略号版本
     * 中文：4 字不截 / 3 字 + …
     * ASCII：6 字符不截 / 5 字符 + …
     */
    /**
     * 压缩规则（实测）：
     * - 纯 ASCII ≤ 6 字符：不截断
     * - 纯 ASCII 超长：4 字符 + "..." = 7 字符 ❌ 超限
     *                  3 字符 + "..." = 6 字符 ✅ 刚好
     * - 纯中文 ≤ 4 字：不截断
     * - 纯中文超长：4 字硬截断（中文没有 ASCII 省略号，用「…」会混排）
     */
    /**
     * 压缩规则：
     * - 全角字符（CJK 汉字 + 平假名 + 片假名）≤ 4 字：原样
     * - 全角字符 > 4 字：前 4 字硬截断
     * - 纯 ASCII ≤ 6 字符：原样
     * - 纯 ASCII > 6 字符：前 3 字符 + "..."
     * - 空白：返回 "MUSIC"
     */
    private fun compress(text: String): String {

        // ★ 全角字符：CJK 汉字 + 平假名 + 片假名
        val fullWidthOnly = text.filter { code ->
            code.code in 0x4E00..0x9FFF ||   // CJK 统一汉字
                    code.code in 0x3040..0x309F ||   // 平假名
                    code.code in 0x30A0..0x30FF ||   // 片假名
                    code.code in 0xFF66..0xFF9D      // 半角片假名
        }
        if (fullWidthOnly.isNotEmpty()) {
            return fullWidthOnly.take(4)
        }

        // 纯 ASCII
        val asciiOnly = text.filter { it.code in 0x20..0x7E }.trim()
        if (asciiOnly.isNotEmpty()) {
            return if (asciiOnly.length <= 6) {
                asciiOnly
            } else {
                asciiOnly.take(3) + "..."
            }
        }

        return "MUSIC"
    }
}