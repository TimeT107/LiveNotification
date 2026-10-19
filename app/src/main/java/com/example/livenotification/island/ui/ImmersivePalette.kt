package com.example.livenotification.island.ui

import com.example.livenotification.island.util.AlbumColor
import com.example.livenotification.island.util.ContrastMath

/**
 * 沉浸式音乐锁屏（样式 0）的**纯计算**部分 —— 可单测，不碰 View。
 *
 * ============ 用户需求（v1.42.0）============
 *
 * "沉浸式锁屏样式中，上面用专辑铺满（宽度屏幕宽度），
 *  专辑其余位置用莫奈取色取色专辑封面，专辑到其他位置用该纯色过度。"
 *
 * 拆成三件事，全部在这里：
 *   1. **封面高度** = 屏幕宽度（专辑是方形 → 顶部按屏宽铺满，不留黑边/裁切）；
 *   2. **下半屏底色** = 由专辑封面取出的莫奈面色的**纯色**（不用壁纸莫奈色，跟着封面走）；
 *   3. **过渡**：封面最底部一条渐变带溶进该纯色 —— 于是封面与底色之间没有硬边。
 *
 * 为什么前景色也要算：底色是从封面推出来的，可能很亮（黄/白系专辑），
 * 白字会糊在底色上。所以按 WCAG 对比度在"白 / 近黑"里取更可读的那个。
 */
object ImmersivePalette {

    /** 封面高度 = **屏幕宽度**：专辑是方图，按屏宽铺满就是 1:1，不留黑边 */
    fun coverHeightPx(screenWidthPx: Int): Int = screenWidthPx.coerceAtLeast(1)

    /** 封面底部过渡带占封面高度的比例（14% ≈ 170px，肉眼是一条柔边而不是一段渐变墙） */
    const val FADE_BAND = 0.14f

    /**
     * 过渡带起点（占封面高度比例）：`k=1`（音乐锁屏）时是 [FADE_BAND] 之前，
     * `k=0`（中岛）时必须回到 **1.0** —— 那时封面就是中岛的卡片封面，
     * 底下透出的只会是锁屏壁纸（底色层 alpha 还是 0），留过渡带会看到"封面缺一角"。
     */
    fun fadeStart(k: Float): Float = 1f - FADE_BAND * k.coerceIn(0f, 1f)

    /**
     * 下半屏底色：由专辑强调色推出的莫奈**面色**（降饱和 + 压暗）。
     *
     * 与中岛/小岛用同一个函数（[AlbumColor.surface]），所以"岛内露出的底色"
     * 与"沉浸式下半屏"是同一个颜色体系 —— 转场时不会两套配色打架。
     */
    fun surface(accent: Int): Int = AlbumColor.surface(accent, desaturate = 0.40f, darken = 0.62f)

    /** 在底色上可读的前景色：白与近黑里取对比度更高的那个 */
    fun foreground(surface: Int): Int {
        // ⚠️ 用字面量而不是 android.graphics.Color.WHITE：本文件是**纯计算**、
        //   要在 JVM 单测里跑（`Color` 在单测里是 not-mocked）。
        val white = WHITE
        val dark = DARK
        return if (ContrastMath.contrastRatio(white, surface) >=
            ContrastMath.contrastRatio(dark, surface)
        ) white else dark
    }

    /** 白 / 近黑（沉浸式底色的两种前景候选） */
    const val WHITE: Int = -1 // 0xFFFFFFFF
    const val DARK: Int = 0xFF121212.toInt()

    /** 给前景色加透明度（用于次级文字/图标） */
    fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
