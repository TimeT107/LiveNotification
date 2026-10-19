package com.example.livenotification.island.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 对比度计算（纯函数，可直接单元测试）。
 *
 * ============ 为什么需要它 ============
 *
 * 锁屏岛画在壁纸之上，壁纸是什么颜色完全不可控 —— 用户可能用纯白、
 * 纯黑、或者高饱和渐变。我最初凭感觉给半透明背景定了 60% 不透明度
 * （0x99），配白色文字。问题是：
 *
 *   纯白壁纸 + 60% 黑  →  合成色 #666666，白字对比度只有 5.74:1
 *
 * 5.74 虽然刚好过 WCAG AA（4.5:1），但只对纯白这一种极端情况成立，
 * 而且视觉上"灰底白字"的观感很差。这是典型的"盲写时凭感觉定值"。
 *
 * 所以把这件事变成可计算的：给定最亮/最暗壁纸，算出文字对比度，
 * 再反解出需要的背景不透明度。这样默认值就有依据，也有测试守着。
 *
 * 公式来自 WCAG 2.1：contrast = (L1 + 0.05) / (L2 + 0.05)
 */
object ContrastMath {

    const val WCAG_AA = 4.5
    const val WCAG_AAA = 7.0

    /** 8 位 sRGB 分量的相对亮度权重（线性化之后） */
    private val LUMA = doubleArrayOf(0.2126, 0.7152, 0.0722)

    /**
     * 把 sRGB 分量线性化（WCAG 规定的 gamma 解码）。
     * @param channel 0..1
     */
    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92
        else ((channel + 0.055) / 1.055).pow(2.4)

    /**
     * 相对亮度。输入打包的 ARGB（忽略 alpha）。
     */
    fun luminance(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return LUMA[0] * linearize(r) + LUMA[1] * linearize(g) + LUMA[2] * linearize(b)
    }

    /**
     * 两色之间的对比度，范围 1.0 .. 21.0。
     */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = max(la, lb)
        val darker = min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 把带 alpha 的前景色合成到不透明背景上，返回不透明 ARGB。
     *
     * 用途：半透明黑色胶囊盖在壁纸上时，实际呈现的颜色是
     * `over × alpha + backdrop × (1 - alpha)`。
     */
    fun composite(over: Int, backdrop: Int, alpha: Double): Int {
        val a = alpha.coerceIn(0.0, 1.0)
        fun mix(shift: Int): Int {
            val o = (over shr shift) and 0xFF
            val b = (backdrop shr shift) and 0xFF
            return (o * a + b * (1.0 - a)).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** 8 位 alpha 转 0..1 */
    fun alphaOf(argb: Int): Double = ((argb ushr 24) and 0xFF) / 255.0

    /**
     * 反解：要让 [text] 在「[scrimArgb] 以某不透明度盖在 [worstBackdrop] 上」之后
     * 仍达到 [targetRatio]，需要的最小不透明度。
     *
     * 二分查找。返回 0..1。
     */
    fun minAlphaForContrast(
        scrimArgb: Int,
        backdrop: Int,
        text: Int,
        targetRatio: Double
    ): Double {
        // 先用满不透明度确认"理论上可行"
        val solid = composite(scrimArgb, backdrop, 1.0)
        if (contrastRatio(text, solid) < targetRatio) return 1.0

        var lo = 0.0
        var hi = 1.0
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val composed = composite(scrimArgb, backdrop, mid)
            if (contrastRatio(text, composed) >= targetRatio) hi = mid else lo = mid
        }
        return hi
    }
}
