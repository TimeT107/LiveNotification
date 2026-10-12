package com.example.livenotification

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 从专辑封面提取主色，莫奈化处理
 * 带缓存，供多个界面共用
 */
object ColorExtractor {

    private const val FALLBACK_COLOR = 0xFFE07A7A.toInt()

    private val cache = mutableMapOf<Int, Int>()

    /** 获取专辑封面的莫奈化主色，失败时返回兜底色 */
    fun getAlbumColor(bitmap: Bitmap?): Int {
        if (bitmap == null) return FALLBACK_COLOR

        val key = bitmap.hashCode()
        cache[key]?.let { return it }

        val raw = extractDominantColor(bitmap)
        val monetized = monetizeColor(raw)
        cache[key] = monetized

        if (cache.size > 20) {
            val firstKey = cache.keys.first()
            cache.remove(firstKey)
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
}