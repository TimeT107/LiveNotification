package com.example.livenotification.island.config

/**
 * 配置快照：纯 key=value 文本，用于跨进程传递配置。
 *
 * ============ 为什么需要它 ============
 *
 * hook 跑在 SystemUI 进程（uid 1000+），设置界面跑在模块进程，两者
 * SharedPreferences 不互通。原来靠 `createPackageContext(pkg, IGNORE_SECURITY)`
 * + `MODE_WORLD_READABLE` 去读模块的 prefs，但：
 *   - `MODE_WORLD_READABLE` 从 Android 7 起就被禁用
 *   - 依赖 LSPosed 的 `xposedsharedprefs` 重映射，行为随版本变化
 *
 * 结果是「能不能读到用户设置」变成了不确定的事 —— 这不能接受，
 * 因为 enabled/位置/宽度全部依赖它。
 *
 * 所以改成两条**确定性**通道：
 *  1. 设置界面每次改动都把快照写到模块的外部私有目录
 *     `/sdcard/Android/data/<模块包名>/files/config.txt`，
 *     SystemUI 以 system uid 运行，一定能读；hook 每次启动都会读一次，
 *     这样重启 SystemUI 也不会丢配置。
 *  2. 设置界面同时把**完整快照**广播给 SystemUI，实现实时生效
 *     （不依赖 hook 能否读文件）。
 *
 * 本类只做文本编解码，不碰任何 Android API，两个进程共用同一份实现，
 * 避免两边解析逻辑漂移。
 */
object ConfigSnapshot {

    /** 快照里的键（与 IslandConfig 的 prefs key 保持一致，便于互转） */
    const val KEY_ENABLED = IslandConfig.KEY_ENABLED
    const val KEY_VERTICAL_BIAS = IslandConfig.KEY_VERTICAL_BIAS
    const val KEY_WIDTH_PERCENT = IslandConfig.KEY_WIDTH_PERCENT
    const val KEY_MAX_WIDTH_DP = IslandConfig.KEY_MAX_WIDTH_DP
    const val KEY_HEIGHT_DP = IslandConfig.KEY_HEIGHT_DP
    const val KEY_EXPANDED_HEIGHT_DP = IslandConfig.KEY_EXPANDED_HEIGHT_DP
    const val KEY_CORNER_DP = IslandConfig.KEY_CORNER_DP
    const val KEY_SHOW_ON_AOD = IslandConfig.KEY_SHOW_ON_AOD
    const val KEY_DEBUG_ALWAYS_SHOW = IslandConfig.KEY_DEBUG_ALWAYS_SHOW
    const val KEY_DEBUG_LOG = IslandConfig.KEY_DEBUG_LOG
    const val KEY_DRAG_ENABLED = IslandConfig.KEY_DRAG_ENABLED
    const val KEY_MUSIC_STYLE = IslandConfig.KEY_MUSIC_STYLE

    /** 需要跨进程同步的全部键 */
    val ALL_KEYS: List<String> = listOf(
        KEY_ENABLED,
        KEY_VERTICAL_BIAS,
        KEY_WIDTH_PERCENT,
        KEY_MAX_WIDTH_DP,
        KEY_HEIGHT_DP,
        KEY_EXPANDED_HEIGHT_DP,
        KEY_CORNER_DP,
        KEY_SHOW_ON_AOD,
        KEY_DEBUG_ALWAYS_SHOW,
        KEY_DEBUG_LOG,
        KEY_DRAG_ENABLED,
        // 音乐锁屏样式。**必须登记在这里**，否则跨进程序列化时会被白名单丢掉 ——
        // 真机上就出现过"配置文件里有 music_lock_style=1 但模块读到 0"。
        KEY_MUSIC_STYLE
    )

    fun encode(values: Map<String, Any?>): String = buildString {
        for (key in ALL_KEYS) {
            val v = values[key] ?: continue
            append(key).append('=').append(v.toString()).append('\n')
        }
    }

    /**
     * 拼出**完整**的快照 values：已存的值优先，缺的用 [defaults] 补。
     *
     * ⚠ 唯一来源是 [ALL_KEYS] —— **不要在调用侧手抄 key 列表**。
     * 手抄漏键的后果是"打开一次设置界面就把某个键从共享快照里抹掉"，
     * 而且**完全不报错**（真机 bug：`music_lock_style` 就这么被抹掉的，
     * 用户只能靠 adb push 配置文件切样式）。
     * 由 `ConfigSnapshotTest` 的两条测试兜住：默认值必须覆盖全部键、
     * 空 prefs 下写出的快照也必须含全部键。
     */
    fun buildValues(stored: Map<String, Any?>, defaults: Map<String, Any>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (key in ALL_KEYS) out[key] = stored[key] ?: defaults[key]
        return out
    }

    /**
     * 解析快照。
     * @return 解析出的键值；无法解析的行直接忽略（永不抛异常）
     */
    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf('=')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            if (key !in ALL_KEYS) continue
            out[key] = line.substring(i + 1).trim()
        }
        return out
    }

    fun toBoolean(v: String?, def: Boolean): Boolean = when (v?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> def
    }

    fun toInt(v: String?, def: Int): Int = v?.toIntOrNull() ?: def
}
