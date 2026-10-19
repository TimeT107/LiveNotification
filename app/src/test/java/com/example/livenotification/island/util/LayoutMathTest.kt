package com.example.livenotification.island.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LayoutMath 的纯 JVM 单元测试。
 *
 * 用 **pagani（OnePlus 11）的真实屏幕参数**做验证：1440×3216 @ 525dpi。
 * 这样这些数字就不是抽象的自洽，而是和你的设备直接对应。
 */
class LayoutMathTest {

    private val screenW = 1440
    private val screenH = 3216
    private val density = 3.0f

    private fun dp(v: Int) = (v * density).toInt()

    /** 岛收起态高度 56dp */
    private val islandH = dp(56)

    // 从反编译资源算出的关键分界线（见 LayoutMath 注释里的推导）
    private val clockTop = dp(18 + 48) + dp(32) / 2      // ≈ 346px
    private val clockBottom = clockTop + dp(112)          // 大时钟约 112dp 高
    private val shortcutsTop = screenH - dp(188)

    @Test
    fun `default 68 percent lands below the clock and above the shortcuts`() {
        val bias = 0.68f
        val top = LayoutMath.topForBias(screenH, islandH, bias)

        assertTrue(
            "岛顶部 $top 应该在时钟底部 $clockBottom 之下",
            top >= clockBottom
        )
        assertTrue(
            "岛底部 ${top + islandH} 应该在快捷方式顶部 $shortcutsTop 之上",
            top + islandH <= shortcutsTop
        )
    }

    @Test
    fun `the whole reachable range 30 to 82 percent stays on screen`() {
        for (percent in 30..82) {
            val bias = percent / 100f
            assertTrue(
                "percent=$percent 超出屏幕",
                LayoutMath.isReachable(screenH, islandH, bias, 0.30f, 0.82f)
            )
        }
    }

    @Test
    fun `bias 0 pins the island to the top and bias 1 to the bottom`() {
        assertEquals(0, LayoutMath.topForBias(screenH, islandH, 0f))
        assertEquals(screenH - islandH, LayoutMath.topForBias(screenH, islandH, 1f))
    }

    @Test
    fun `topForBias and biasForTop are inverses`() {
        for (target in intArrayOf(0, 500, 1000, 2073, 3000, screenH - islandH)) {
            val bias = LayoutMath.biasForTop(screenH, islandH, target)
            val back = LayoutMath.topForBias(screenH, islandH, bias)
            // 允许 1px 的取整误差
            assertTrue(
                "target=$target 往返后变成 $back",
                kotlin.math.abs(back - target) <= 1
            )
        }
    }

    @Test
    fun `percentForTop converts a pixel target into the settings value`() {
        // 想让岛顶部落在屏幕正中间偏下一点
        val target = (screenH * 0.65f).toInt()
        val percent = LayoutMath.percentForTop(screenH, islandH, target)
        val actual = LayoutMath.topForBias(screenH, islandH, percent / 100f)
        assertTrue(
            "换算成 $percent% 后实际顶部 $actual，偏离目标 $target 太多",
            kotlin.math.abs(actual - target) < screenH / 100
        )
    }

    @Test
    fun `degenerate inputs do not divide by zero or go negative`() {
        assertEquals(0, LayoutMath.topForBias(100, 200, 0.5f))   // 子比父还高
        assertEquals(0, LayoutMath.topForBias(0, 0, 0.5f))
        assertEquals(0f, LayoutMath.biasForTop(100, 200, 50), 0.0001f)
    }

    @Test
    fun `bias is clamped so a bad config can never push the island off screen`() {
        val top = LayoutMath.topForBias(screenH, islandH, 5f)   // 非法的大值
        assertEquals(screenH - islandH, top)
        val topNeg = LayoutMath.topForBias(screenH, islandH, -3f)
        assertEquals(0, topNeg)
    }

    @Test
    fun `below clock and above shortcuts predicates agree with manual math`() {
        // 时钟正下方一点点：应当判定为"在时钟下方"
        assertTrue(LayoutMath.isBelowClock(screenH, islandH, 0.30f, clockBottom))
        // 屏幕最顶部：不应当在时钟下方
        assertFalse(LayoutMath.isBelowClock(screenH, islandH, 0.0f, clockBottom))
        // 82% 时岛底部仍应在快捷方式之上吗？不一定 —— 这正是需要真实探针的原因
        val topAt82 = LayoutMath.topForBias(screenH, islandH, 0.82f)
        assertEquals(
            topAt82 + islandH <= shortcutsTop,
            LayoutMath.isAboveShortcuts(screenH, islandH, 0.82f, shortcutsTop)
        )
    }

    @Test
    fun `measured zone between clock and shortcuts is wide enough to be useful`() {
        // 这个断言的意义：如果区间过窄，说明我对布局的推导有误，
        // 默认值 68% 就不可信了。它给"推导是否正确"设了一道闸。
        val zone = shortcutsTop - clockBottom
        assertTrue(
            "时钟(底部 $clockBottom) 到快捷方式(顶部 $shortcutsTop) 之间的可用区只有 $zone px，太窄，推导可能有误",
            zone > dp(200)
        )
        // 默认值必须落在这个区间里
        val defaultTop = LayoutMath.topForBias(screenH, islandH, 0.68f)
        assertTrue(
            "默认 68% 落在 $defaultTop，不在可用区 [$clockBottom, $shortcutsTop] 内",
            defaultTop >= clockBottom && defaultTop + islandH <= shortcutsTop
        )
    }
}

/**
 * 帧布局锚点定位与形变的几何测试。
 *
 * ============ 全部使用真机实测数字 ============
 *
 * 这些常量来自 PKX110 上实测的日志与探针输出（不是推算）：
 *   容器(NotificationShadeWindowView) = 1216 × 2640
 *   密度 = 3.5（岛高 56dp 实测 196px → 196/56 = 3.5）
 *   岛宽 = 1070（88% 且未超 maxWidth 380dp=1330）
 *   水平左边距 = 73   → (1216-1070)/2 = 73 ✓
 *   收起高度 = 196（56dp）
 *   中岛高度 = 378（108dp）
 *   指纹图标顶部 = 2296，期望岛底部 = 2256 → 实测 bottom = 2248
 *
 * 用真实数字的用意：这些断言一旦失败，说明几何算法与那台设备上
 * 已经验证过的行为不符 —— 而不是抽象的自洽性检查。
 */
class FrameLayoutGeometryTest {

    private val containerW = 1216
    private val containerH = 2640
    private val density = 3.5f

    private fun dp(v: Int) = (v * density).toInt()

    private val islandW = 1070
    private val collapsedH = dp(56)      // 196
    private val expandedH = dp(108)      // 378

    /** 真机上用户设定的垂直百分比（对应底部锚点 2248） */
    private val bias = 0.84f

    @Test
    fun `collapsed geometry reproduces the values verified on device`() {
        val anchor = LayoutMath.bottomAnchorForBias(containerH, collapsedH, bias)
        val top = LayoutMath.topForBottomAnchor(anchor, collapsedH)

        assertEquals("收起态高度应为 196", 196, collapsedH)
        assertEquals("中岛高度应为 378", 378, expandedH)
        assertEquals("水平左边距应为 73", 73, LayoutMath.centeredLeftMargin(containerW, islandW))
        assertEquals("底部锚点应为 2248", 2248, anchor)
        assertEquals("收起态 top 应为 2052", 2052, top)
    }

    @Test
    fun `morphing keeps the bottom edge fixed`() {
        val anchor = LayoutMath.bottomAnchorForBias(containerH, collapsedH, bias)

        // 沿整条形变路径检查底边恒定
        for (step in 0..10) {
            val h = LayoutMath.morphHeight(collapsedH, expandedH, step / 10f)
            val top = LayoutMath.topForBottomAnchor(anchor, h)
            assertEquals(
                "进度 ${step * 10}%（高度 $h）时底边漂移了",
                anchor, top + h
            )
        }
    }

    @Test
    fun `expanding moves the top upward by exactly the height gain`() {
        val anchor = LayoutMath.bottomAnchorForBias(containerH, collapsedH, bias)
        val topCollapsed = LayoutMath.topForBottomAnchor(anchor, collapsedH)
        val topExpanded = LayoutMath.topForBottomAnchor(anchor, expandedH)

        assertEquals(
            "top 的上移量应等于高度增量",
            expandedH - collapsedH, topCollapsed - topExpanded
        )
    }

    @Test
    fun `morph height interpolation hits both endpoints exactly`() {
        assertEquals(collapsedH, LayoutMath.morphHeight(collapsedH, expandedH, 0f))
        assertEquals(expandedH, LayoutMath.morphHeight(collapsedH, expandedH, 1f))
        // 越界进度被夹紧，不会算出非法高度
        assertEquals(collapsedH, LayoutMath.morphHeight(collapsedH, expandedH, -1f))
        assertEquals(expandedH, LayoutMath.morphHeight(collapsedH, expandedH, 2f))
    }

    @Test
    fun `island width respects percent max and min`() {
        // 88% of 1216 = 1070，未触上限
        assertEquals(1070, LayoutMath.islandWidth(containerW, 88, dp(380), dp(120)))
        // 容器比 maxWidth 宽时，100% 应被 maxWidth 夹住
        assertEquals(dp(380), LayoutMath.islandWidth(2000, 100, dp(380), dp(120)))
        // 百分比很小时被 minWidth 托住
        assertEquals(dp(120), LayoutMath.islandWidth(containerW, 1, dp(380), dp(120)))
        // 容器还没测量时不崩，返回最小值
        assertEquals(dp(120), LayoutMath.islandWidth(0, 88, dp(380), dp(120)))
    }


}
