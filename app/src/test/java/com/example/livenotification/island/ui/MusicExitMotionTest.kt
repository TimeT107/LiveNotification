package com.example.livenotification.island.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音乐锁屏 ↔ 中岛 转场参数的单测。
 *
 * ============ 为什么值得写 ============
 *
 * 这套数值是**用户明确说过"舒服了"的那套**（中岛→小岛的 morphFallToCompact）的镜像。
 * 它们有两个特点：
 *   1. 改坏了**只有真机肉眼能发现**（而且是"感觉不对"，很难截图取证）；
 *   2. 很容易被顺手改（"这里乘个 1.2 试试"）。
 * 所以把"不许变"的部分钉在这里，配合 `MusicExitMotion` 的常量，改了就红。
 */
class MusicExitMotionTest {

    @Test
    fun `fall duration is 380ms when there is nothing left to travel`() {
        // remain=0：只剩基数时长。**不能**回到中岛→小岛那套 140ms —— 见下面的"行程"测试。
        assertEquals(380L, MusicExitMotion.fallDurationMs(0f))
    }

    @Test
    fun `fall duration grows to 640ms at full progress`() {
        assertEquals(640L, MusicExitMotion.fallDurationMs(1f))
        assertEquals(510L, MusicExitMotion.fallDurationMs(0.5f))
    }

    @Test
    fun `fall duration clamps out-of-range remain`() {
        assertEquals(640L, MusicExitMotion.fallDurationMs(4f))
        assertEquals(380L, MusicExitMotion.fallDurationMs(-3f))
        assertTrue(MusicExitMotion.fallDurationMs(-9f) >= MusicExitMotion.FALL_MIN_MS)
    }

    /**
     * 这条测试的用途是**防止有人把它调回"中岛→小岛"的数值**（用户反馈过"傻快、太轻"）。
     *
     * 中岛→小岛的 `140 + 180·remain` 是按 **469px 行程**（岛高 665→196）调的；
     * 音乐锁屏→中岛的行程是 **~1400px**（封面 950²@y185 → 1070×665@y1583），是它的三倍。
     * 同样的时长跑三倍距离，读起来就是"嗖一下没了、没有分量"。
     */
    @Test
    fun `music exit is deliberately slower than the island collapse`() {
        // 中岛→小岛的基数是 140ms（不要 import IslandView，只在这里写死做对照）
        assertTrue(
            "音乐锁屏退场的落定基数必须明显大于 140ms（那是 469px 行程的数）",
            MusicExitMotion.FALL_BASE_MS >= 300f
        )
        assertTrue(MusicExitMotion.fallDurationMs(1f) >= 600L)
    }

    @Test
    fun `curve and blur stay tuned for a full-screen travel`() {
        // (0.32, 0, 0.08, 1)：起步有迟滞（有质量）、末段早早减速（稳稳落定）
        assertEquals(0.32f, MusicExitMotion.CURVE_X1)
        assertEquals(0f, MusicExitMotion.CURVE_Y1)
        assertEquals(0.08f, MusicExitMotion.CURVE_X2)
        assertEquals(1f, MusicExitMotion.CURVE_Y2)
        // 回弹也是一屏级行程，不能还是 140ms
        assertEquals(220L, MusicExitMotion.SPRING_MS)
    }

    /**
     * 🔴 v1.41.6 用户反馈："**音乐锁屏到中岛位置后的模糊时间还是太长，看着有种未响应的感觉**"。
     *
     * 真因（两条，都能量化）：
     *   · **落定那一刻是满档模糊**（旧公式 `blurStrength(0)=1`）：交棒链要跑好几帧
     *     （展开中岛 → 重扫通知 → 重算可见性 → 撤视图），这几帧把糊图冻在屏幕上；
     *   · 模糊峰值 8dp（density 3.5 → 28px）挂在**全屏视图**上，每帧离屏重绘 → 掉帧。
     *
     * 现在改成驼峰：中段最糊、**落地前必须回到 0**；峰值降到 4dp。
     */
    @Test
    fun `collapse blur is a hump that is already gone at landing`() {
        assertTrue(
            "全屏模糊峰值必须 ≤4dp（8dp=28px 会掉帧、看着像未响应）",
            MusicExitMotion.BLUR_PEAK_DP <= 4f
        )
        // 关键回归点：落地瞬间（k=0，交棒链开始）必须**没有模糊**
        assertEquals(
            "k=0 时模糊必须为 0，否则交棒那几帧会冻住一张糊图",
            0f, MusicExitMotion.blurStrength(0f), 1e-6f
        )
        // 峰值在中段
        assertEquals(1f, MusicExitMotion.blurStrength(MusicExitMotion.BLUR_PEAK_K), 1e-6f)
        // 还在全屏态时完全不糊
        assertEquals(0f, MusicExitMotion.blurStrength(1f), 1e-6f)
        assertEquals(0f, MusicExitMotion.blurStrength(MusicExitMotion.BLUR_RISE_K), 1e-6f)
        assertEquals(0f, MusicExitMotion.blurStrength(0.8f), 1e-6f)
        // 先升后降（驼峰），且不给"后半程整段是软的"留后门
        assertTrue(MusicExitMotion.blurStrength(0.4f) > MusicExitMotion.blurStrength(0.5f))
        assertTrue(MusicExitMotion.blurStrength(0.12f) > MusicExitMotion.blurStrength(0.05f))
        assertTrue(MusicExitMotion.BLUR_RISE_K <= 0.60f)
    }

    /**
     * 🔴 v1.41.4 / v1.41.6 两轮用户反馈："**退回中岛后模糊时间太长了**"。
     *
     * 交棒那一刻两套排版已经几乎重合，遮瑕只需要"糊一下接上"：
     * 峰值 3dp、淡出 50ms（原来走的是 `IslandView` 里写死的 6dp/120ms）。
     * 这条测试把"接缝遮瑕"钉成**短而轻**，防止有人再把它调回"运动模糊"的量级。
     */
    @Test
    fun `handoff blur is a short light seam cover`() {
        assertTrue("交接遮瑕峰值 ≤3dp", MusicExitMotion.HANDOFF_BLUR_DP <= 3f)
        assertTrue(
            "交接遮瑕必须短（≤60ms），否则落到中岛后还在发虚",
            MusicExitMotion.HANDOFF_BLUR_FADE_MS <= 60L
        )
        assertTrue(MusicExitMotion.HANDOFF_BLUR_FADE_MS >= 30L)
    }

    /**
     * 🔴 v1.41.4 用户反馈："**中岛到音乐锁屏又太快了**"。
     *
     * 原来入场是 `420 × remain`（纯线性）：拖到大半再松手、剩余 0.4 时只剩 **168ms**
     * 要跑 40% 的行程（同样剩余下退场是 484ms）—— 等于"撒手就窜上去"。
     * 现在与退场同构：`基数 + 跨度·remain`。
     */
    @Test
    fun `entry duration scales from base plus span, not plain linear`() {
        assertEquals(640L, MusicExitMotion.entryDurationMs(1f))
        assertEquals(412L, MusicExitMotion.entryDurationMs(0.4f))
        assertEquals(260L, MusicExitMotion.entryDurationMs(0f))
        // 关键回归点：剩 40% 行程时**不能再是一百多毫秒**
        assertTrue(
            "剩余 0.4 的入场时长必须 ≥360ms（旧实现只有 168ms）",
            MusicExitMotion.entryDurationMs(0.4f) >= 360L
        )
        // 单调：剩得越多越久
        assertTrue(MusicExitMotion.entryDurationMs(1f) > MusicExitMotion.entryDurationMs(0.5f))
    }

    @Test
    fun `layer progress is zero before its window and one after`() {
        assertEquals(0f, MusicExitMotion.layerProgress(0.1f, 0.2f, 0.8f), 1e-6f)
        assertEquals(0f, MusicExitMotion.layerProgress(0.2f, 0.2f, 0.8f), 1e-6f)
        assertEquals(1f, MusicExitMotion.layerProgress(0.8f, 0.2f, 0.8f), 1e-6f)
        assertEquals(1f, MusicExitMotion.layerProgress(1f, 0.2f, 0.8f), 1e-6f)
    }

    @Test
    fun `layer progress is monotonic and soft at both ends`() {
        var prev = -1f
        var q = 0f
        while (q <= 1f) {
            val f = MusicExitMotion.layerProgress(q, 0.1f, 0.9f)
            assertTrue("layer progress must not go backwards at q=$q", f >= prev)
            prev = f
            q += 0.01f
        }
        // smoothstep：窗口两端导数为 0（淡出不起棱角）
        val nearStart = MusicExitMotion.layerProgress(0.1f + 0.005f, 0.1f, 0.9f)
        val nearEnd = MusicExitMotion.layerProgress(0.9f - 0.005f, 0.1f, 0.9f)
        assertTrue(nearStart < 0.001f)
        assertTrue(nearEnd > 0.999f)
    }

    @Test
    fun `degenerate layer window does not divide by zero`() {
        assertEquals(1f, MusicExitMotion.layerProgress(0.5f, 0.4f, 0.4f), 1e-6f)
        assertEquals(0f, MusicExitMotion.layerProgress(0.3f, 0.4f, 0.4f), 1e-6f)
    }

    @Test
    fun `blur only exists near the landing`() {
        // 还在全屏态（k 大）时不能糊 —— 否则整场退场都是糊的
        assertEquals(0f, MusicExitMotion.blurStrength(1f), 1e-6f)
        assertEquals(0f, MusicExitMotion.blurStrength(MusicExitMotion.BLUR_RISE_K), 1e-6f)
        assertEquals(0f, MusicExitMotion.blurStrength(0.9f), 1e-6f)
        // 落定瞬间也必须是 0（v1.41.6：交棒那几帧不能冻住一张糊图）
        assertEquals(0f, MusicExitMotion.blurStrength(0f), 1e-6f)
        // 中段才是峰值
        assertEquals(1f, MusicExitMotion.blurStrength(MusicExitMotion.BLUR_PEAK_K), 1e-6f)
        assertTrue(MusicExitMotion.blurStrength(0.3f) > MusicExitMotion.blurStrength(0.5f))
    }
}
