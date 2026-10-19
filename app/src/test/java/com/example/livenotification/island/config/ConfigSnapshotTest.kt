package com.example.livenotification.island.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConfigSnapshot 的纯 JVM 单元测试。
 *
 * ============ 为什么这个测试值得写 ============
 *
 * 这是「设置界面 → SystemUI」跨进程传递配置的唯一真相源。它一旦出错，
 * 症状是**用户改设置毫无反应，且不报任何错** —— 最难排查的一类故障。
 *
 * 而且这段逻辑不依赖任何 Android API，可以在开发机上直接跑，
 * 不需要设备、不需要模拟器、不需要装模块。属于「我能自己闭环验证」的部分。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 */
class ConfigSnapshotTest {

    private fun fullSnapshot(): Map<String, Any?> = mapOf(
        ConfigSnapshot.KEY_ENABLED to true,
        ConfigSnapshot.KEY_VERTICAL_BIAS to 63,
        ConfigSnapshot.KEY_WIDTH_PERCENT to 82,
        ConfigSnapshot.KEY_MAX_WIDTH_DP to 380,
        ConfigSnapshot.KEY_HEIGHT_DP to 56,
        ConfigSnapshot.KEY_EXPANDED_HEIGHT_DP to 108,
        ConfigSnapshot.KEY_CORNER_DP to -1,
        ConfigSnapshot.KEY_SHOW_ON_AOD to false,
        ConfigSnapshot.KEY_DEBUG_ALWAYS_SHOW to true,
        ConfigSnapshot.KEY_DEBUG_LOG to true,
        ConfigSnapshot.KEY_DRAG_ENABLED to true,
        // 每加一个配置键都必须补进这张表 —— 这个测试正是用来拦住"加了键却忘了登记"的
        ConfigSnapshot.KEY_MUSIC_STYLE to 1
    )

    @Test
    fun `encode then decode round trips every key`() {
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(fullSnapshot()))
        assertEquals(ConfigSnapshot.ALL_KEYS.size, decoded.size)
        for (key in ConfigSnapshot.ALL_KEYS) {
            assertTrue("missing key after round trip: $key", decoded.containsKey(key))
        }
    }

    @Test
    fun `round trips a negative corner value`() {
        // corner_dp = -1 是"用全圆角胶囊"的哨兵值，很容易被解析成正数而悄悄变方角
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(fullSnapshot()))
        assertEquals(-1, ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_CORNER_DP], 99))
    }

    @Test
    fun `round trips booleans in both directions`() {
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(fullSnapshot()))
        assertTrue(ConfigSnapshot.toBoolean(decoded[ConfigSnapshot.KEY_ENABLED], false))
        assertFalse(ConfigSnapshot.toBoolean(decoded[ConfigSnapshot.KEY_SHOW_ON_AOD], true))
    }

    @Test
    fun `unknown keys are dropped`() {
        val decoded = ConfigSnapshot.decode("unknown_key=1\nvertical_bias=70\n")
        assertFalse(decoded.containsKey("unknown_key"))
        assertEquals(70, ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_VERTICAL_BIAS], -1))
    }

    @Test
    fun `malformed lines do not crash and do not pollute`() {
        val dirty = buildString {
            append("garbage line without equals\n")
            append("=enabled\n")                      // 空 key
            append("vertical_bias=abc\n")             // 非数字
            append("width_percent=82\n")              // 合法
            append("# a comment\n")
            append("\n")
            append("height_dp=\n")                    // 空 value
        }
        val decoded = ConfigSnapshot.decode(dirty)

        assertFalse(decoded.containsKey(""))
        assertEquals(82, ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_WIDTH_PERCENT], -1))
        // 非数字必须回落到调用方给的默认值，而不是抛异常或变成 0
        assertEquals(68, ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_VERTICAL_BIAS], 68))
    }

    @Test
    fun `null and blank input yield empty map`() {
        assertTrue(ConfigSnapshot.decode(null).isEmpty())
        assertTrue(ConfigSnapshot.decode("").isEmpty())
        assertTrue(ConfigSnapshot.decode("   \n\n  ").isEmpty())
    }

    @Test
    fun `boolean parser accepts the usual spellings and falls back otherwise`() {
        assertTrue(ConfigSnapshot.toBoolean("true", false))
        assertTrue(ConfigSnapshot.toBoolean("1", false))
        assertTrue(ConfigSnapshot.toBoolean("TRUE", false))
        assertFalse(ConfigSnapshot.toBoolean("false", true))
        assertFalse(ConfigSnapshot.toBoolean("0", true))
        // 无法识别时必须回落到默认值
        assertTrue(ConfigSnapshot.toBoolean("maybe", true))
        assertFalse(ConfigSnapshot.toBoolean(null, false))
    }

    @Test
    fun `int parser falls back on garbage rather than throwing`() {
        assertEquals(68, ConfigSnapshot.toInt("abc", 68))
        assertEquals(68, ConfigSnapshot.toInt(null, 68))
        assertEquals(68, ConfigSnapshot.toInt("", 68))
        assertEquals(-5, ConfigSnapshot.toInt("-5", 0))
    }

    @Test
    fun `encode skips keys that are absent`() {
        val text = ConfigSnapshot.encode(mapOf(ConfigSnapshot.KEY_ENABLED to true))
        val decoded = ConfigSnapshot.decode(text)
        assertEquals(1, decoded.size)
        assertTrue(ConfigSnapshot.toBoolean(decoded[ConfigSnapshot.KEY_ENABLED], false))
    }

    @Test
    fun `encoded text is stable for the same input`() {
        // 稳定性很重要：设置界面会把它当"是否变化"的比较依据
        val a = ConfigSnapshot.encode(fullSnapshot())
        val b = ConfigSnapshot.encode(fullSnapshot())
        assertEquals(a, b)
    }

    @Test
    fun `no key in the snapshot list escapes the allow list`() {
        // 防止以后加配置项时忘了往 ALL_KEYS 里登记 —— 那种 bug 表现为
        // "这一项设置改了不生效"，而且完全静默
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(fullSnapshot()))
        for (key in fullSnapshot().keys) {
            assertTrue("key not registered in ALL_KEYS: $key", decoded.containsKey(key))
        }
        assertNull(decoded["not_a_real_key"])
    }

    // ------------------------------------------------------------------
    // v1.39.0：拦"设置界面写快照时漏键"这一类静默 bug
    // ------------------------------------------------------------------

    @Test
    fun `snapshot defaults cover every cross-process key`() {
        // 加了配置键却忘了登记默认值 → 写出的快照就会漏掉它（模块读到默认值）。
        // 真机事故：music_lock_style 被漏掉，打开设置界面即把它从共享快照里抹掉。
        assertEquals(
            "IslandConfig.SNAPSHOT_DEFAULTS 必须覆盖 ConfigSnapshot.ALL_KEYS 的每个键",
            ConfigSnapshot.ALL_KEYS.toSet(),
            IslandConfig.SNAPSHOT_DEFAULTS.keys
        )
    }

    @Test
    fun `settings snapshot carries every key even when prefs are empty`() {
        val values = ConfigSnapshot.buildValues(emptyMap(), IslandConfig.SNAPSHOT_DEFAULTS)
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(values))
        assertEquals(ConfigSnapshot.ALL_KEYS.size, decoded.size)
        for (key in ConfigSnapshot.ALL_KEYS) {
            assertTrue("快照里缺键: $key", decoded.containsKey(key))
        }
        // 本条最重要的断言：样式键必须在（漏了它就会"样式悄悄回到铺满封面"）
        assertEquals(
            IslandConfig.DEF_MUSIC_STYLE.toString(),
            decoded[ConfigSnapshot.KEY_MUSIC_STYLE]
        )
    }

    @Test
    fun `stored values win over defaults`() {
        val values = ConfigSnapshot.buildValues(
            mapOf(
                ConfigSnapshot.KEY_MUSIC_STYLE to IslandConfig.MUSIC_STYLE_ONEUI,
                ConfigSnapshot.KEY_VERTICAL_BIAS to 63
            ),
            IslandConfig.SNAPSHOT_DEFAULTS
        )
        val decoded = ConfigSnapshot.decode(ConfigSnapshot.encode(values))
        assertEquals(
            IslandConfig.MUSIC_STYLE_ONEUI,
            ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_MUSIC_STYLE], -1)
        )
        assertEquals(63, ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_VERTICAL_BIAS], -1))
        // 没存过的键仍然要有值（默认值）
        assertTrue(decoded.containsKey(ConfigSnapshot.KEY_DEBUG_LOG))
    }
}
