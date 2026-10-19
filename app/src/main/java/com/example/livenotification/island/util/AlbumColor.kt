package com.example.livenotification.island.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 从**专辑封面**里取一个强调色。
 *
 * ============ 为什么要单独做这件事 ============
 *
 * 之前小岛按钮用的是**壁纸的莫奈色**（SystemUiResources.monetContainer）。
 * 壁纸色的问题：它和当前播放的封面**没有任何关系**，碰上封面本身是橙红色、
 * 壁纸取色也是橙红的时候，按钮就"糊"在封面里看不见了。
 *
 * 用户要求（也符合三星锁屏媒体控件的做法）：按钮色跟随**专辑封面**，
 * 这样颜色永远和封面协调。
 *
 * ============ 取色策略 ============
 *
 * 封面缩到 24×24 后逐像素打分，取"**饱和度**高 **且** 亮度居中"的颜色：
 *   · 只要饱和度 → 会挑到刺眼的纯色
 *   · 只要亮度居中 → 会挑到一大片灰色
 *   · 两者加权 → 更像"这张图的品牌色"
 *
 * 整张封面是黑白/灰阶时没有可用强调色，回退到**平均色**（保证至少和封面同调）。
 * 完全取不到（bitmap 为空）返回 null，调用方回退到壁纸莫奈色。
 */
object AlbumColor {

    fun accent(bitmap: Bitmap?): Int? {        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null

        return runCatching {
            val w = 24
            val h = 24
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            // 只回收我们自己创建的那张（createScaledBitmap 在尺寸相同时会返回原图）
            if (small !== bitmap) small.recycle()

            var best = px[0]
            var bestScore = -1f
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L

            for (c in px) {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                sumR += r
                sumG += g
                sumB += b

                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val midness = 1f - kotlin.math.abs(luma - 0.55f) * 2f
                val score = sat * 2f + midness
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }

            // 灰阶封面（打分上不去）→ 用平均色兜底，至少与封面同调
            if (bestScore <= 0.25f) {
                val n = px.size
                Color.rgb((sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt())
            } else {
                Color.rgb(Color.red(best), Color.green(best), Color.blue(best))
            }
        }.getOrNull()
    }

    /**
     * 由强调色推出一个**面（surface）色**，用于小岛背景。
     *
     * ============ 为什么不能直接用强调色当背景 ============
     *
     * 真机事故：小岛背景和播放键**都用同一个强调色**时，播放键的实心圆
     * 与背景完全同色 → **按钮"消失"了**，只剩中间的白色三角。
     *
     * 所以必须分出层次（Material 的 surface / container 两级就是这个道理）：
     *   · 背景 = **降饱和 + 压暗**的"面色"
     *   · 按钮 = **原样鲜艳**的强调色 → 在面色上自然凸显
     *
     * 做法刻意保持简单（向灰度混合 + 按比例压暗），不引入 HSL 往返：
     * 在"保持色相、拉开明度/饱和差"这件事上已经够用，且开销可以忽略。
     */
    fun surface(accent: Int, desaturate: Float = 0.35f, darken: Float = 0.62f): Int {
        // ⚠️ 这里**不用** android.graphics.Color.red/green/blue/rgb ——
        //   它们在 JVM 单测里是 "not mocked"（会抛 RuntimeException），
        //   而这个函数被 ImmersivePalette 的纯计算单测直接调用。
        //   位运算等价且没有 Android 依赖。
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        fun mix(v: Int) = ((v * (1f - desaturate) + gray * desaturate) * darken)
            .toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }
}
