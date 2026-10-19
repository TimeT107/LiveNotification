package com.example.livenotification.island.ui

import com.example.livenotification.island.util.ContrastMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸式（样式 0）纯计算部分的单测。
 *
 * 这些值决定了"封面多大、底色多深、字多亮"，改坏了**只有真机能看出来**
 * （而且用户对配色/位置的反馈一向很细），所以用测试钉住。
 */
class ImmersivePaletteTest {

    @Test
    fun `cover is square at screen width`() {
        // 用户需求："上面用专辑铺满（宽度屏幕宽度）"→ 方图按屏宽铺 = 高等于宽
        assertEquals(1216, ImmersivePalette.coverHeightPx(1216))
        // 极端输入不能返回 0（否则 art 高度 0、封面完全不显示）
        assertEquals(1, ImmersivePalette.coverHeightPx(0))
        assertEquals(1, ImmersivePalette.coverHeightPx(-50))
    }

    @Test
    fun `fade band is open only when the music lock is in place`() {
        // k=1（音乐锁屏到位）：留一条过渡带，封面底部溶进纯色
        assertEquals(1f - ImmersivePalette.FADE_BAND, ImmersivePalette.fadeStart(1f), 1e-6f)
        // k=0（中岛）：必须退化（=1.0）—— 否则中岛封面的底部会透出壁纸，看着缺一角
        assertEquals(1f, ImmersivePalette.fadeStart(0f), 1e-6f)
        // 单调：转场中带子逐渐打开
        assertTrue(ImmersivePalette.fadeStart(0.2f) > ImmersivePalette.fadeStart(0.8f))
        // 越界值不能把带子搞成负数
        assertEquals(1f, ImmersivePalette.fadeStart(-3f), 1e-6f)
        assertEquals(1f - ImmersivePalette.FADE_BAND, ImmersivePalette.fadeStart(9f), 1e-6f)
        // 带子必须落在封面内部（0..1）
        assertTrue(ImmersivePalette.FADE_BAND > 0f && ImmersivePalette.FADE_BAND < 1f)
    }

    @Test
    fun `immersive surface is darker than the album accent`() {
        val bright = 0xFFFFD54F.toInt() // 亮黄专辑
        val surface = ImmersivePalette.surface(bright)
        assertTrue(
            "下半屏底色必须比专辑强调色暗，否则白字/图标没有对比",
            ContrastMath.luminance(surface) < ContrastMath.luminance(bright)
        )
        // 不允许算出纯黑（那是失去莫奈取色的意义）
        assertTrue(ContrastMath.luminance(surface) > 0.0)
    }

    @Test
    fun `foreground is readable on both dark and light surfaces`() {
        val darkSurface = ImmersivePalette.surface(0xFF1B3A5C.toInt())  // 深蓝专辑
        val lightSurface = ImmersivePalette.surface(0xFFFFF3B0.toInt()) // 很亮的米黄专辑

        val fgOnDark = ImmersivePalette.foreground(darkSurface)
        val fgOnLight = ImmersivePalette.foreground(lightSurface)

        assertEquals("深色底色上应该用白字", ImmersivePalette.WHITE, fgOnDark)
        assertEquals("亮色底色上必须换成近黑字，否则白字会糊", ImmersivePalette.DARK, fgOnLight)

        // 两种极端下都要达到 WCAG AA（4.5:1）
        assertTrue(ContrastMath.contrastRatio(fgOnDark, darkSurface) >= 4.5)
        assertTrue(ContrastMath.contrastRatio(fgOnLight, lightSurface) >= 4.5)
    }

    @Test
    fun `withAlpha keeps rgb and only rewrites alpha`() {
        val fg = ImmersivePalette.WHITE
        val dim = ImmersivePalette.withAlpha(fg, 0xCC)
        assertEquals(0xCC, (dim ushr 24) and 0xFF)
        assertEquals(fg and 0x00FFFFFF, dim and 0x00FFFFFF)
        // 越界 alpha 要被夹住（避免把 0xCC 写成 0x1CC 之类把红分量弄脏）
        assertEquals(0xFF, (ImmersivePalette.withAlpha(fg, 999) ushr 24) and 0xFF)
        assertEquals(0x00, (ImmersivePalette.withAlpha(fg, -5) ushr 24) and 0xFF)
    }
}
