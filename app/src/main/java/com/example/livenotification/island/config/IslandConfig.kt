package com.example.livenotification.island.config

/**
 * 锁屏岛配置。
 *
 * 步骤 1 先把所有键位和默认值定下来，后续设置界面直接改这些值即可，
 * 不需要再改 hook 侧代码。
 *
 * 注意：hook 运行在 SystemUI 进程，设置界面运行在本模块进程，
 * 所以读取时统一走 [ConfigStore]，避免两套逻辑漂移。
 */
object IslandConfig {

    /**
     * 解析不到系统主题色时的兜底背景。
     *
     * 72% 黑不是随手定的：ContrastMathTest 里对纯白/纯黑/亮黄/亮青/中灰
     * 五种极端壁纸做过验证，白字对比度均达 WCAG AA 以上。
     * 正常路径下会用 SystemUI 的 materialColorSurfaceContainerHigh（与快捷方式一致），
     * 这个值只是那条路走不通时的保险。
     */
    const val DEFAULT_SCRIM_COLOR = 0xB8000000.toInt()

    /**
     * 诊断开关：展开态画一圈洋红描边。
     *
     * 已完成使命：靠它证明了「展开后看不到岛」不是透明、不是被盖住，
     * 而是 bringToFront 把岛移出了视图树（parent=null）。
     * 排查完毕，关掉。
     */
    const val DEBUG_OUTLINE = false

    const val PREFS_NAME = "lockscreen_island"

    /** 总开关 */
    const val KEY_ENABLED = "enabled"
    const val DEF_ENABLED = true

    /**
     * 垂直位置，0..100。数值越大越靠下。
     *
     * 默认 84 = 「岛底部贴在指纹图标上方约 40px」。
     * 依据是用户真机探针实测的坐标（PKX110，1216x2640）：
     *   指纹图标(UdfpsAccessibilityOverlay 上方的 DeviceEntryIconView) 顶部 = 2296
     *   岛收起高度 = 196
     *   期望 top = 2296 - 40 - 196 = 2060
     *   bias = 2060 / (2640 - 196) ≈ 0.843 → 84%
     *
     * 想回到「大时钟下方」就用 26%（同样来自真机自动标定）。
     */
    const val KEY_VERTICAL_BIAS = "vertical_bias"
    const val DEF_VERTICAL_BIAS = 84

    /** 宽度占屏幕宽度百分比，0..100（留出左右快捷方式的空间） */
    const val KEY_WIDTH_PERCENT = "width_percent"
    const val DEF_WIDTH_PERCENT = 88

    /** 胶囊最大宽度（dp），最终宽度取 min(屏幕宽*百分比, 该值) */
    const val KEY_MAX_WIDTH_DP = "max_width_dp"
    const val DEF_MAX_WIDTH_DP = 380

    /** 胶囊高度（dp） */
    const val KEY_HEIGHT_DP = "height_dp"
    const val DEF_HEIGHT_DP = 56

    /**
     * 中岛（展开态）高度（dp）。
     *
     * 新卡片设计需要容纳：封面铺底 + 左下歌名/歌手 + 底部进度行 + 右侧播放键。
     * 108dp 太挤（原来那种"横向三段"才够用），重新设计后取 190dp。
     * 底边锚点不变，所以卡片是**向上**长高。
     */
    /**
     * 音乐锁屏的样式。
     *
     *  0 = 当前样式：封面**铺满**上半屏 + 大歌名 + 实心圆播放键
     *  1 = One UI 样式：封面是**独立圆角卡片** + 歌名在下方 + 粗进度条（大圆手柄）
     *      + 纯图标控制键（无实心圆）+ 输出设备胶囊
     *
     * 默认 0，保证不动用户现在的观感。
     */
    const val KEY_MUSIC_STYLE = "music_lock_style"
    const val DEF_MUSIC_STYLE = 0
    const val MUSIC_STYLE_CURRENT = 0
    const val MUSIC_STYLE_ONEUI = 1

    const val KEY_EXPANDED_HEIGHT_DP = "expanded_height_dp"
    const val DEF_EXPANDED_HEIGHT_DP = 190

    /** 圆角半径（dp），-1 表示使用高度的一半（全圆角胶囊） */
    const val KEY_CORNER_DP = "corner_dp"
    const val DEF_CORNER_DP = -1

    /**
     * 息屏显示（AOD）时是否显示。
     *
     * 固定为 false 且**不再从配置读取**：用户明确要求"AOD 上不要显示岛"。
     * 保留这个 key 只是为了避免旧配置文件里的字段变成未知项。
     */
    const val KEY_SHOW_ON_AOD = "show_on_aod"
    const val DEF_SHOW_ON_AOD = false

    /** 是否在锁屏上把岛画在最上层（盖住时钟下方的空白区域） */
    const val KEY_ELEVATED = "elevated"
    const val DEF_ELEVATED = true

    /** 允许在锁屏上长按拖动岛来调位置 */
    const val KEY_DRAG_ENABLED = "drag_enabled"
    const val DEF_DRAG_ENABLED = true

    /**
     * 拖动/缩放产生的微调量。
     * SystemUI 进程写不了模块的 prefs，所以由 SystemUI 发广播回模块进程落盘。
     * 两个 extra 都是可选的：接收端按存在的 key 分别更新。
     */
    const val EXTRA_VERTICAL_BIAS = "vertical_bias"
    const val EXTRA_WIDTH_PERCENT = "width_percent"

    /** 详细日志 */
    const val KEY_DEBUG_LOG = "debug_log"
    const val DEF_DEBUG_LOG = true

    /** 调试模式：即使没有媒体/状态也显示胶囊，方便确认注入是否成功 */
    const val KEY_DEBUG_ALWAYS_SHOW = "debug_always_show"
    const val DEF_DEBUG_ALWAYS_SHOW = true

    /** 模块自检：hook 成功时写入的时间戳（由 SystemUI 进程写） */
    const val KEY_LAST_HOOK_AT = "last_hook_at"

    /** 模块自检：hook 成功的进程名/说明 */
    const val KEY_LAST_HOOK_INFO = "last_hook_info"

    /** 模块自检：日志文件路径（由 SystemUI 进程回报） */
    const val KEY_LAST_LOG_PATH = "last_log_path"

    // ---- 运行时广播，用于设置界面实时通知 SystemUI ----
    const val ACTION_CONFIG_CHANGED = "com.example.livenotification.CONFIG_CHANGED"

    /**
     * SystemUI 里的 hook 把自检状态回传给设置界面。
     * 用自定义 action + setPackage，不依赖任何权限。
     */
    const val ACTION_STATUS_REPORT = "com.example.livenotification.STATUS_REPORT"
    const val EXTRA_STATUS = "status"
    const val EXTRA_AT = "at"
    const val EXTRA_LOG_PATH = "log_path"

    /** SystemUI 里拖动岛之后，把微调量发回模块进程落盘 */
    const val ACTION_NUDGE_CHANGED = "com.example.livenotification.NUDGE_CHANGED"

    /**
     * 熔断紧急停用。
     *
     * SystemUI 里的岛一旦检测到布局死循环，会立刻摘掉自己并广播这个动作，
     * 由模块进程把 enabled 写成 false —— 保证用户即使在锁屏卡死的边缘，
     * 下次重启也不会再挂载。这是给用户的退路，不能省。
     */
    const val ACTION_EMERGENCY_DISABLE = "com.example.livenotification.EMERGENCY_DISABLE"

    /**
     * 设置界面把**完整配置快照**推给 SystemUI。
     * 不依赖 hook 能否读模块的 prefs / 外部文件，所以这是
     * 「改设置一定生效」的兜底通道。
     */
    const val ACTION_CONFIG_SNAPSHOT = "com.example.livenotification.CONFIG_SNAPSHOT"
    const val EXTRA_SNAPSHOT = "snapshot"

    /** 配置快照文件名（放在模块外部私有目录） */
    const val SNAPSHOT_FILE_NAME = "config.txt"

    /** 模块包名（跨进程通信用） */
    const val MODULE_PACKAGE = "com.example.livenotification"

    /**
     * 快照文件的候选路径（相对外部存储根）。
     *
     * 设置界面会往**全部**候选路径各写一份，SystemUI 侧按顺序尝试读取第一个
     * 存在的。这样做是因为：
     *   - `Context.getExternalFilesDir()` 在部分 ROM/存储挂载方式下返回 null
     *     （尤其是外部存储未挂载或用了非标准路径时）
     *   - 不同 Android 版本对 `/sdcard/Android/data` 的可访问性策略有差异
     * 双写双读的代价只是多写一个几百字节的小文件，换来的是配置通道不会
     * 因为一个路径解析失败就整条断掉 —— 这条通道断了，用户改设置就毫无反应。
     */
    val SNAPSHOT_RELATIVE_PATHS: List<String> = listOf(
        "Android/data/$MODULE_PACKAGE/files/$SNAPSHOT_FILE_NAME",
        "Android/media/$MODULE_PACKAGE/$SNAPSHOT_FILE_NAME"
    )

    /**
     * 每个跨进程键的**默认值**。
     *
     * ============ 为什么需要这张表（真机 bug 换来的）============
     *
     * 设置界面写快照时必须**把所有键都写进去** —— 少一个键，模块就读到默认值。
     * 原来是在 `SettingsActivity.writeConfigSnapshot()` 里**手抄一份 key 列表**，
     * 结果漏了 `music_lock_style`。真机症状：
     * **只要打开一次设置界面**（`onResume` 就会写快照）共享 `config.txt` 里的
     * `music_lock_style` 就被抹掉，重启 SystemUI 后样式悄悄回到 0，
     * 用户只能靠 `adb push` 配置文件切样式。
     *
     * 现在以 `ConfigSnapshot.ALL_KEYS` 为唯一来源，缺的值用这张表补齐；
     * `ConfigSnapshotTest.snapshot defaults cover every cross-process key`
     * 拦住"加了键却忘了登记"。
     */
    val SNAPSHOT_DEFAULTS: Map<String, Any> = mapOf(
        KEY_ENABLED to DEF_ENABLED,
        KEY_VERTICAL_BIAS to DEF_VERTICAL_BIAS,
        KEY_WIDTH_PERCENT to DEF_WIDTH_PERCENT,
        KEY_MAX_WIDTH_DP to DEF_MAX_WIDTH_DP,
        KEY_HEIGHT_DP to DEF_HEIGHT_DP,
        KEY_EXPANDED_HEIGHT_DP to DEF_EXPANDED_HEIGHT_DP,
        KEY_CORNER_DP to DEF_CORNER_DP,
        KEY_SHOW_ON_AOD to DEF_SHOW_ON_AOD,
        KEY_DEBUG_ALWAYS_SHOW to DEF_DEBUG_ALWAYS_SHOW,
        KEY_DEBUG_LOG to DEF_DEBUG_LOG,
        KEY_DRAG_ENABLED to DEF_DRAG_ENABLED,
        KEY_MUSIC_STYLE to DEF_MUSIC_STYLE
    )

    /** 坐标合法性收敛 */
    fun clampPercent(v: Int): Int = v.coerceIn(0, 100)
}
