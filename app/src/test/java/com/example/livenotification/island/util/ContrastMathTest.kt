package com.example.livenotification.island.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContrastMath 的纯 JVM 单元测试。
 *
 * 这个测试的作用不是"证明代码自洽"，而是**给一个凭感觉定的视觉参数
 * 补上依据**：锁屏岛背景的不透明度必须是算出来的，不能是猜的。
 */
class ContrastMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // 旧值：我盲写时凭感觉给的 60% 黑
    private val oldScrimAlpha = 0x99

    @Test
    fun `contrast extremes match the known WCAG values`() {
        // 纯黑白是 21:1，这是 WCAG 定义的上界；用它能验证公式没写错
        assertEquals(21.0, ContrastMath.contrastRatio(white, black), 0.01)
        assertEquals(1.0, ContrastMath.contrastRatio(white, white), 0.01)
        assertEquals(1.0, ContrastMath.contrastRatio(black, black), 0.01)
    }

    @Test
    fun `luminance of pure black and white are the canonical 0 and 1`() {
        assertEquals(0.0, ContrastMath.luminance(black), 0.0001)
        assertEquals(1.0, ContrastMath.luminance(white), 0.0001)
    }

    @Test
    fun `composite is identity at full alpha and pure backdrop at zero`() {
        assertEquals(
            "满不透明度时应当是前景色本身",
            white, ContrastMath.composite(white, black, 1.0)
        )
        assertEquals(
            "零不透明度时应当是背景色本身",
            black, ContrastMath.composite(white, black, 0.0)
        )
        // 50% 白盖黑应当是中性灰
        val half = ContrastMath.composite(white, black, 0.5)
        val channel = (half shr 16) and 0xFF
        assertTrue("50% 合成结果 $channel 应当接近 127", kotlin.math.abs(channel - 127) <= 2)
    }

    /**
     * 这是本轮的核心断言：**证明旧值不够用**。
     *
     * 如果这条断言失败，说明 0x99 其实够用，那我当初改它的理由就不成立。
     */
    @Test
    fun `the old 60 percent scrim only barely passes AA on a white wallpaper`() {
        val scrim = 0x99000000.toInt()
        val alpha = ContrastMath.alphaOf(scrim)
        assertEquals(0.6, alpha, 0.005)

        val composed = ContrastMath.composite(scrim, white, alpha)
        val ratio = ContrastMath.contrastRatio(white, composed)

        // 只比 AA 门槛高一点点 —— 属于"勉强合格"，不该作为默认值
        assertTrue("旧值对比度 $ratio 太低，应当接近门槛", ratio >= ContrastMath.WCAG_AA)
        assertTrue(
            "旧值对比度 $ratio 竟然很宽裕，那本轮修改就没必要了",
            ratio < 6.5
        )
    }

    /**
     * 新默认值必须在**所有**壁纸极端情况下都达到 AA。
     * 这是把"凭感觉定值"换成"算出来"的直接证据。
     */
    @Test
    fun `the new scrim guarantees AA contrast on every extreme wallpaper`() {
        val scrim = 0xB8000000.toInt()   // 新默认值：72% 黑
        val alpha = ContrastMath.alphaOf(scrim)
        assertEquals(0.722, alpha, 0.005)

        val backdrops = mapOf(
            "纯白壁纸" to white,
            "纯黑壁纸" to black,
            "亮黄" to 0xFFFFEB3B.toInt(),
            "亮青" to 0xFF00E5FF.toInt(),
            "中灰" to 0xFF808080.toInt()
        )

        for ((name, backdrop) in backdrops) {
            val composed = ContrastMath.composite(scrim, backdrop, alpha)
            val ratio = ContrastMath.contrastRatio(white, composed)
            assertTrue(
                "$name 上白字对比度只有 $ratio，未达 AA(${ContrastMath.WCAG_AA})",
                ratio >= ContrastMath.WCAG_AA
            )
        }
    }

    @Test
    fun `minimum alpha solver agrees with the chosen default`() {
        // 反解"在纯白壁纸上达到 AA 所需的最小不透明度"
        val required = ContrastMath.minAlphaForContrast(
            scrimArgb = black,
            backdrop = white,
            text = white,
            targetRatio = ContrastMath.WCAG_AA
        )
        // 我们选的 0.722 必须 >= 算出来的最小值，否则默认值会不达标
        val chosen = ContrastMath.alphaOf(0xB8000000.toInt())
        assertTrue(
            "算出的最小不透明度 $required 大于所选默认值 $chosen，默认值不达标",
            chosen >= required
        )
    }

    @Test
    fun `solver returns full alpha when the target is unreachable`() {
        // 黑字配黑底，无论多不透明都达不到 4.5:1
        val required = ContrastMath.minAlphaForContrast(
            scrimArgb = black,
            backdrop = black,
            text = black,
            targetRatio = ContrastMath.WCAG_AA
        )
        assertEquals(1.0, required, 0.0001)
    }

    @Test
    fun `solver handles a zero target gracefully`() {
        val required = ContrastMath.minAlphaForContrast(black, white, white, 0.0)
        assertTrue("目标为 0 时不该返回异常值", required in 0.0..1.0)
    }

    /**
     * 次级文字（歌手名/时间轴）。
     *
     * ============ 这个测试纠正了我一个错误的修改理由 ============
     *
     * 我原本以为 70% 白的次级文字在中灰壁纸上"只有约 3.3:1、不达标"，
     * 据此把它提到了 85%。加上诊断输出后真实数值是：
     *     surface=0xff232323
     *     新(85%) = 10.50:1
     *     旧(70%) =  8.37:1
     * 旧值**本来就远超 AA**，我的修改理由不成立。
     *
     * 所以断言改成有真实区分度的判据：新值要达到 AAA(7:1) 有余量，
     * 旧值虽然过 AA 但离 AAA 更近。这既不虚报，也说明了提高的理由
     * 只是"更宽裕"，不是"修复缺陷"。
     */
    @Test
    fun `secondary text grey level is validated against mid-grey wallpaper`() {
        val scrim = 0xB8000000.toInt()
        val scrimAlpha = ContrastMath.alphaOf(scrim)
        val midGrey = 0xFF808080.toInt()

        fun effectiveRatio(secondary: Int): Double {
            val surface = ContrastMath.composite(scrim, midGrey, scrimAlpha)
            // 文字的 alpha 只应作为混合系数用一次。不能把 secondary 整个
            // 丢进 composite —— 那会把它自带的 alpha 再用一次，得出虚高值。
            val textRgb = secondary or 0xFF000000.toInt()
            val textOnSurface = ContrastMath.composite(
                textRgb, surface, ContrastMath.alphaOf(secondary)
            )
            return ContrastMath.contrastRatio(textOnSurface, surface)
        }

        val newRatio = effectiveRatio(0xCCFFFFFF.toInt())   // 85%
        val oldRatio = effectiveRatio(0xB3FFFFFF.toInt())   // 70%

        // 两者都必须过 AA —— 旧值没过的话，当初就该当成 bug 修
        assertTrue("次级文字新值对比度 $newRatio 未达 AA", newRatio >= ContrastMath.WCAG_AA)
        assertTrue("次级文字旧值对比度 $oldRatio 未达 AA", oldRatio >= ContrastMath.WCAG_AA)

        // 新值的实际意义：留出 AAA 级别的余量（抗反锯齿与低亮度屏幕）
        assertTrue(
            "次级文字新值 $newRatio 应当达到 AAA(7:1) 余量",
            newRatio >= ContrastMath.WCAG_AAA
        )
        assertTrue("新值应当优于旧值", newRatio > oldRatio)
    }

    @Test
    fun `main text colour reaches AAA on every extreme wallpaper`() {
        // 主标题（歌名）是必读信息，目标定高一点：AAA(7:1)
        val scrim = 0xB8000000.toInt()
        val alpha = ContrastMath.alphaOf(scrim)
        for (backdrop in intArrayOf(
            white, black, 0xFFFFEB3B.toInt(), 0xFF00E5FF.toInt(), 0xFF808080.toInt()
        )) {
            val composed = ContrastMath.composite(scrim, backdrop, alpha)
            val ratio = ContrastMath.contrastRatio(white, composed)
            assertTrue(
                "主标题在 backdrop=0x${Integer.toHexString(backdrop)} 上对比度 $ratio 未达 AAA",
                ratio >= ContrastMath.WCAG_AAA
            )
        }
    }
}
