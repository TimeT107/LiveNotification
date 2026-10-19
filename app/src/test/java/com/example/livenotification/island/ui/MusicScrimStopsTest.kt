package com.example.livenotification.island.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音乐锁屏渐变 stop 的回归测试。
 *
 * ============ 为什么必须有 ============
 *
 * 真机事故：`GradientDrawable.setColors(colors, offsets)` 两个数组长度不等时，
 * 会在 **draw 时**抛
 *     IllegalArgumentException: color and position arrays must be of equal length
 * 异常在绘制路径上 → **SystemUI 直接崩溃**（点中岛必崩）。
 * 这类错误编译期发现不了，我当时改了颜色项数却没同步 offsets 就踩了。
 */
class MusicScrimStopsTest {

    @Test
    fun `colors and offsets have equal length`() {
        assertEquals(
            "颜色数与位置数必须相等，否则 GradientDrawable 绘制时会崩溃",
            MusicScrimStops.COLORS.size,
            MusicScrimStops.OFFSETS.size
        )
    }

    @Test
    fun `offsets are ascending and inside 0 to 1`() {
        val o = MusicScrimStops.OFFSETS
        assertEquals("第一档必须从 0 开始", 0f, o.first(), 0f)
        assertEquals("最后一档必须在 1.0（铺满整屏）", 1f, o.last(), 0f)
        for (i in o.indices) {
            assertTrue("offset[$i]=${o[i]} 超出 0..1", o[i] >= 0f && o[i] <= 1f)
            if (i > 0) {
                assertTrue(
                    "offset 必须单调递增：[$i]=${o[i]} <= [${i - 1}]=${o[i - 1]}",
                    o[i] > o[i - 1]
                )
            }
        }
    }

    @Test
    fun `top is dimmed so the system clock stays readable`() {
        // 系统时钟绘制在封面之上（KeyguardRootView 比音乐锁屏更靠上），
        // 浅色封面 + 浅色时钟会看不清，所以顶部必须压暗。
        val topAlpha = (MusicScrimStops.COLORS[0] ushr 24) and 0xFF
        assertTrue(
            "顶部必须有足够的压暗（alpha>=0x66）才能衬托时钟，实际=0x${Integer.toHexString(topAlpha)}",
            topAlpha >= 0x66
        )
    }

    @Test
    fun `middle stays transparent so the album cover is visible`() {
        // 顶部压暗之后必须回到透明，否则整张封面都被压灰、失去"封面"的意义
        val hasTransparentMiddle = MusicScrimStops.COLORS.indices.any { i ->
            val alpha = (MusicScrimStops.COLORS[i] ushr 24) and 0xFF
            alpha == 0 && MusicScrimStops.OFFSETS[i] in 0.30f..0.49f
        }
        assertTrue("30%~49% 之间必须有一档完全透明（让封面可见）", hasTransparentMiddle)
    }

    @Test
    fun `gradient becomes fully opaque at the end of the cover`() {
        // 封面只铺到屏高 50%，0.50 那一档必须已完全不透明，
        // 否则封面以下会露出锁屏壁纸（真机反馈"还是露出一小节壁纸"）。
        val idx = MusicScrimStops.OFFSETS.indexOfFirst { it >= MusicScrimStops.OPAQUE_FROM }
        assertTrue("找不到 ${MusicScrimStops.OPAQUE_FROM} 档", idx >= 0)
        val alpha = (MusicScrimStops.COLORS[idx] ushr 24) and 0xFF
        assertEquals(
            "封面结束处必须已完全不透明，否则壁纸会透出；实际 alpha=0x${Integer.toHexString(alpha)}",
            0xFF, alpha
        )
    }

    @Test
    fun `base color is 0F0F0F not pure black`() {
        // 参考项目 MediaControlOverlay.kt 用的基色是 #0F0F0F
        val rgb = MusicScrimStops.COLORS.last() and 0x00FFFFFF
        assertEquals("基色应为 #0F0F0F（参考项目用色）", 0x0F0F0F, rgb)
    }

    // ==================================================================
    // One UI 样式（背景整体压暗，封面卡片浮在暗面之上）
    // ==================================================================
    //
    // 注意：这一组停靠点是**常量**，不再按卡片位置算。
    // 之所以改成常量，是因为"按卡片位置算"的版本需要把比例传进来，
    // 而传参算错（NaN、越界）同样会在绘制时崩 SystemUI —— 常量从根上消掉这类风险。
    // 配套的结构改动见 MusicLockView.buildOneUiLayout：scrim 必须 addView 在 art **之前**。

    @Test
    fun `oneUi colors and offsets have equal length`() {
        assertEquals(
            "One UI 渐变的颜色数与位置数必须相等，否则 GradientDrawable 绘制时会崩溃 SystemUI",
            MusicScrimStops.COLORS_ONEUI.size,
            MusicScrimStops.OFFSETS_ONEUI.size
        )
    }

    @Test
    fun `oneUi offsets are strictly ascending and inside 0 to 1`() {
        val o = MusicScrimStops.OFFSETS_ONEUI
        assertEquals("第一档必须从 0 开始", 0f, o.first(), 0f)
        assertEquals("最后一档必须是 1.0（铺满整屏）", 1f, o.last(), 0f)
        for (i in o.indices) {
            assertTrue("offset[$i]=${o[i]} 超出 0..1", o[i] in 0f..1f)
            if (i > 0) {
                assertTrue(
                    "offset 必须单调递增：[$i]=${o[i]} <= [${i - 1}]=${o[i - 1]}",
                    o[i] > o[i - 1]
                )
            }
        }
    }

    @Test
    fun `oneUi top is dimmed so the system clock stays readable`() {
        val topAlpha = (MusicScrimStops.COLORS_ONEUI[0] ushr 24) and 0xFF
        assertTrue(
            "顶部必须压暗以衬托系统时钟，实际=0x${Integer.toHexString(topAlpha)}",
            topAlpha >= 0x66
        )
    }

    @Test
    fun `oneUi becomes fully opaque in the middle and stays dark`() {
        // 方案 A：背景整体压暗，卡片靠**层次**（画在遮罩之上）保持明亮，
        // 而不是靠"给卡片开透明窗口"。所以遮罩里**不能**有透明档，
        // 否则卡片周围又会露出高亮壁纸（就是之前那个很花的观感）。
        val c = MusicScrimStops.COLORS_ONEUI
        val o = MusicScrimStops.OFFSETS_ONEUI
        for (i in c.indices) {
            val alpha = (c[i] ushr 24) and 0xFF
            assertTrue(
                "offset=${o[i]} 处不允许完全透明（会露出高亮壁纸），实际 alpha=0x${Integer.toHexString(alpha)}",
                alpha > 0
            )
        }
        val endAlpha = (c.last() ushr 24) and 0xFF
        assertEquals("最后一档必须完全不透明", 0xFF, endAlpha)
        val endRgb = c.last() and 0x00FFFFFF
        assertEquals("基色应与旧样式一致（#0F0F0F）", 0x0F0F0F, endRgb)
    }
}
