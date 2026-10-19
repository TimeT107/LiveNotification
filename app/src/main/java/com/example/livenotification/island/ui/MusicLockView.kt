package com.example.livenotification.island.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.livenotification.island.util.IslandLog

/**
 * 音乐锁屏（中岛再进一步的全屏形态）。
 *
 * ============ 设计（模仿用户给的参考图） ============
 *
 *   ┌────────────────────────────┐
 *   │ （透明区：保留系统锁屏时钟） │   ← 刻意不画任何东西，露出系统时钟
 *   │                            │
 *   │        [专辑封面大图]       │
 *   │                            │
 *   │  Ropeway                    │   ← 左下：歌名
 *   │  Tele                       │
 *   │                            │
 *   │   ⏮    ( ▶/⏸ )    ⏭       │   ← 大圆形播放键（莫奈取色）
 *   │                            │
 *   │  ∿∿∿∿∿∿∿∿∿∿∿∿∿∿∿          │   ← MD3 波浪进度（播放时起伏，暂停时平直）
 *   │  2:07              3:06     │
 *   └────────────────────────────┘
 *
 * ============ 为什么要留顶部透明区 ============
 *
 * 用户要求"铺满整屏"，但同时选择"用系统原有的锁屏时钟"。
 * 本视图是 NotificationShadeWindowView 的最后一个子视图，**画在 KeyguardRootView 之上**，
 * 如果整屏铺满不透明背景就会把系统大时钟一起盖住。
 * 所以顶部留出一块透明区域，让系统时钟透出来 —— 两个要求同时满足。
 *
 * ============ 交互 ============
 *
 * 只有一种退出手势：**按住向下拖**（与中岛一致），回到中岛。
 * 点击空白处不做任何事，避免误触。
 */
@SuppressLint("ViewConstructor")
class MusicLockView(
    context: Context,
    /**
     * 是否使用 One UI 样式。
     *
     * 必须在构造时传入 —— 布局是在 `init` 里搭的，而 `init` 在构造期就执行完，
     * 事后 set 就来不及了（页面结构已经定死）。
     *
     *  false = 当前样式：封面铺满上半屏 + 大歌名 + 实心圆播放键
     *  true  = One UI 样式：封面独立圆角卡片 + 下方小字歌名 + 粗进度条 + 纯图标控制
     */
    val oneUiStyle: Boolean = false
) : FrameLayout(context) {

    companion object {
        private const val TAG = "[MusicLock]"

        /** 触发退出手势所需的纵向拖动距离（px）。
         *  取 420px（约 120dp）而不是原来的 168px —— 距离长一些才跟得住手指。 */
        private const val EXIT_DRAG_PX = 420f

        /**
         * 入场（中岛 → 音乐锁屏）的时长**已移到 `MusicExitMotion`**（`ENTRY_BASE_MS/ENTRY_SPAN_MS`）。
         *
         * 历史：420ms（初版）→ 300ms（v1.0x "松散"整改）→ 420ms（v1.41.3）→
         * v1.41.4 改成 `260 + 380·remain`，因为"固定值 × 剩余进度"这种**纯线性**表单
         * 在"拖到大半再松手"时只剩一百多毫秒（用户："中岛到音乐锁屏又太快了"）。
         * 这里不再保留常量：**时长只有一处定义**（AGENTS §4 那条"两个文件各写一份"的教训）。
         */

        /** 中岛卡片的圆角（dp）：转场时从它连续插值到终点的圆角 */
        private const val CARD_CORNER_DP = 24

        /**
         * One UI 样式封面卡片的圆角（dp）。
         *
         * 它必须是**转场插值的终点**，而不是只在建布局时设一次 ——
         * `applyHero` 每帧都会重写 `cornerRadiusPx`，建布局时设的值会被立刻覆盖。
         * 之前就是把终点写死 0（为"铺满封面→直角"的旧样式设计），
         * 于是 One UI 的圆角卡片被渲染成**直角**（真机截图确认）。
         */
        private const val ONEUI_CARD_CORNER_DP = 22

        /** mini 时钟的刷新间隔（20s：分钟级显示，不必每秒刷） */
        private const val CLOCK_TICK_MS = 20_000L

        /** One UI 卡片左右留白比例（0.14 → 卡片宽 72% 屏宽） */
        private const val ONEUI_CARD_SIDE_RATIO = 0.11f

        /**
         * One UI 卡片顶边位置比例（屏高的 13%）。
         *
         * 演变：0.30 →（用户"卡片再向上移动一些"）0.26 → 0.20
         * →（v1.38.0 加通知小岛）**0.13**。
         *
         * ⚠ 这次是**必须**上移，不是审美微调：真机实测音乐锁屏底部自由带只有 ~60px
         * （卡片下边框 ~2220、系统指纹圆圈 ~2280 起、手电/相机圆圈 ~2353 起），
         * 44dp 的通知小岛放不进去。上移后卡片底边 ~2042，通知岛落在 2098~2252，
         * 距指纹圆圈还有 ~28px。
         *
         * 上方安全余量的依据：音乐锁屏期间**系统时钟与日期都被我们隐藏**
         * （`IslandController.hideSystemClockForMusic`），顶部只有我们自绘的 mini 时钟行
         * （top = 屏高 7.5%，底边 ≈308px），所以卡片顶边 343px 不会撞到任何东西。
         */
        private const val ONEUI_CARD_TOP_RATIO = 0.13f

        /** 长按多久进入"可拖动"状态 */
        private const val LONG_PRESS_MS = 420L

        // ---- 通知小岛（音乐锁屏）----

        /** 通知小岛的高度（dp）。胶囊，圆角取高度一半 */
        private const val NOTIF_ISLAND_HEIGHT_DP = 44

        /** 通知轮播间隔 —— 用户指定 5s */
        private const val NOTIF_INTERVAL_MS = 5000L

        /** 滑动切换：旧内容滑出 / 新内容滑入的时长 */
        private const val NOTIF_SLIDE_OUT_MS = 180L
        private const val NOTIF_SLIDE_IN_MS = 220L

        /** 一次最多轮播几条（太多了要转很久才轮到新通知） */
        private const val NOTIF_MAX_ITEMS = 8
    }

    private val art = BlurFadeCoverView(context)

    // ⚠️ 这里原来有个 `artBlur`（垫在封面上方的高斯模糊副本，做"毛玻璃过渡"用）。
    //    v1.42.0 沉浸式改版后不再需要：封面**通栏铺满且全程清晰**，
    //    底部直接溶进莫奈纯色底（[monetFill]），毛玻璃那层只会让"封面到纯色"的边变脏。
    //    另外它是一张**全屏** RenderEffect 模糊图 —— 白扔一大块每帧离屏重绘的开销。

    /**
     * 全屏背景层：专辑封面放大 + 重模糊。
     *
     * 用户要求"背景用专辑封面的莫奈取色**模糊铺满**" —— 这是解决"太素"的根本：
     * 原来的纯灰阶渐变和当前封面**没有任何色彩关联**，所以看着空、平。
     * 现在背景是从封面来的模糊色块，整屏和封面是一体的。
     */
    private val backdrop = ImageView(context)

    /** 背景着色层：把专辑封面的莫奈色叠在模糊背景上（半透明，厚度由 setAccentFill 定） */
    private val tint = View(context)

    /**
     * mini 时钟（用户定义的"系统本来没有的形态"）。
     *
     * ============ 为什么自己画，而不是改系统时钟 ============
     *
     * 用户澄清：系统**始终处于小模式**，而音乐锁屏要的是 **mini** ——
     * 系统根本没有这个形态，所以不可能"复用系统机制"。
     *
     * 而"对系统时钟视图做 scale/translation"已被真机证伪
     * （`NumberOverlap*` 是插件里的**自绘**时钟，渲染不受容器 transform 影响；
     *  写入后回读正确、50ms 抢写循环也在跑，但截图毫无变化）。
     *
     * 所以改成最直接的做法：
     *   ① 进音乐锁屏时**隐藏系统时钟**（`INVISIBLE` 是 View 属性，插件不会重写它）
     *   ② **自己画**一个 mini 时钟 + 日期（同一排，参考 iOS / ColorOS）
     * 全部在我们自己可控的视图里 —— 想多小、放哪里、怎么排字体，都是确定性的。
     */
    private val miniTime = TextView(context)
    private val miniDate = TextView(context)

    /**
     * 输出设备胶囊（待办 4）。
     *
     * 本轮只做**显示**：查 AudioManager 的当前输出设备，显示图标 + 名称。
     * 切换设备需要 MediaRouter（RouteInfo.select()），下一步再做。
     */
    private val devicePill = TextView(context)

    /**
     * 胶囊里的图标（Google 风格、自绘，见 [Glyphs]）。
     *
     * 原来胶囊是用 emoji 拼的（`🔊 手机` / `🎧 xxx` / `🔌 xxx`）——
     * 用户要求"图标换成 Google 风格图标，倾向自绘"。emoji 还有个硬伤：
     * 不同 ROM 的字形差异很大，且和卡片里其它矢量图标不是一套语言。
     *
     * ⚠ 它是纯 Path 的 Drawable（**没有 intrinsic size**），
     * 所以放进布局时必须给**固定 dp 尺寸**（见 `buildOneUiLayout` 里的 pill 行），
     * 否则会以 0×0 布局 —— 真机现象是"胶囊里只剩文字，图标凭空消失"。
     */
    private val deviceIcon = ImageView(context)

    /**
     * 输出设备胶囊的**容器**（图标 + 设备名）。
     *
     * ⚠️ v1.41.2：它必须作为**整体**参与转场淡出。
     * 原来只登记了 `devicePill`（文字 TextView），**漏了同排的 `deviceIcon`** ——
     * 真机现象（用户截图放大后确认）：胶囊底色淡掉了，**里面的喇叭图标还完全不透明**，
     * 孤零零挂在卡片外面 = "音频输出胶囊漏在外面不好看"。
     * 所以字段提升到这里，并在 `buildLayerSpecs()` 里登记**容器**而不是文字。
     */
    private val pillRow = LinearLayout(context)

    // ==================================================================
    // 音乐锁屏"通知小岛"（用户规格：轮播所有通知 / 图标+标题一行 / 底部）
    // ==================================================================
    //
    // 位置：卡片下方、系统指纹圆圈上方（见 ONEUI_CARD_TOP_RATIO 的说明）。
    // 数据：`IslandController` 每 ~4s 采集一次，通过 [setNotifications] 灌进来。
    // 轮播：每 5s 换一条，**滑动**切换（用户指定）。
    private val notifIsland = LinearLayout(context)

    /** 滑动的那一层（图标 + 标题）。**只动它的 translationX/alpha**，胶囊背景不动。 */
    private val notifContent = LinearLayout(context)
    private val notifIcon = ImageView(context)
    private val notifTitle = TextView(context)

    private var notifItems: List<NotifItem> = emptyList()
    private var notifIndex = 0

    /** 上一次灌进来的通知签名（标题串）。相同就不打断轮播、不重绑。 */
    private var notifSignature = ""

    /**
     * 轮播定时器。
     *
     * 只在"有 ≥2 条通知"且"视图已挂上窗口"时挂；`onDetachedFromWindow` 里摘掉 ——
     * 否则音乐锁屏退出后还在后台每 5s 空转（这个坑在 mini 时钟上踩过，见 clockTick）。
     */
    private val notifTick = object : Runnable {
        override fun run() {
            advanceNotif()
            postDelayed(this, NOTIF_INTERVAL_MS)
        }
    }

    // ==================================================================
    // 转场的"静态层"（v1.41.0）
    // ==================================================================
    //
    // 背景/卡框/光/mini 时钟/设备胶囊/通知小岛这些层**不在 heroItems 里** ——
    // 它们没有"中岛里的对应元素"，所以一镜到底的插值管不到它们。
    // 结果就是退场时"文字和控件从一张铺满屏的底上飞出去，框和光留在原地，最后硬切"，
    // 与用户喜欢的"中岛→小岛（整卡收起来）"完全不是一回事。
    //
    // 现在给每层算一条"淡出 + 朝中岛卡片收缩"的路径（收缩目标 = 中岛卡片矩形，
    // 即中岛封面 rect），由同一个进度 k 驱动 —— 两边（入场/退场/跟手）自动对称。
    // ⚠ v1.41.1 修正：**清单**（LayerSpec）与**几何**（LayerMotion）分开。
    //   原来在 `computeHeroGeometry` 里一次性算好几何，并**静默跳过** `width<=0` 的层 ——
    //   真机上就会出现"某一层（设备胶囊 / 通知小岛）永远不淡出、孤零零留在卡片外面"
    //   （用户反馈："音频输出胶囊漏在外面不好看"），因为没登记的那层再也没人管，
    //   alpha 永远是 1。现在几何**延迟到真正要用时再算**，且任何拿不到几何的层
    //   也至少**随进度淡出**（兜底），绝不可能留到 k=0。
    private class LayerSpec(
        val v: View,
        val a0: Float,
        val a1: Float,
        /** 是否"朝中岛卡片收缩"（全屏背景层 false：只淡出，否则四周会提前露壁纸） */
        val shrink: Boolean
    )

    private class LayerMotion(
        val v: View,
        /** 收缩到目标时的缩放（等比，≤1：只收不放） */
        val scale: Float,
        val dx: Float,
        val dy: Float,
        val a0: Float,
        val a1: Float
    )

    private val layerSpecs = ArrayList<LayerSpec>()
    private val layerMotions = ArrayList<LayerMotion>()

    /** k≈0 时打印一次静态层状态（排查"漏在外面"用；每次 prepareHero 重置） */
    private var layerDumpLeft = 0

    /**
     * mini 时钟那一行。
     *
     * ⚠ 从 `buildOneUiLayout` 的局部变量提升成字段：它现在是转场的"静态层"之一，
     * 需要在 `applyHero` 里按 k 淡出。字段必须声明在 `init` **之前**
     * （布局构造函数不碰后置声明字段，见 AGENTS §4）。
     */
    private val miniRow = LinearLayout(context)

    /** 专辑强调色（用于把内容卡的磨砂底朝"中岛面色"插值） */
    private var accentForExit = 0xFF6750A4.toInt()

    /** 上一次设过的退出模糊半径（量化，避免每帧重建 RenderEffect） */
    private var lastExitBlurPx = -1f

    /**
     * 查询当前媒体输出设备并更新胶囊。
     *
     * 用 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)`：
     * 优先取"正在使用"的（isSink 且 type 为蓝牙/A2DP/有线耳机等），
     * 否则回退到内置扬声器。**全部包在 runCatching 里** ——
     * 查询失败绝不能影响音乐锁屏本身。
     */
    fun refreshOutputDevice() {
        runCatching {
            // ============ 首选：MediaRouter 的 selectedRoute ============
            //
            // 真机踩坑：只靠 AudioDeviceInfo 会显示成 "Galaxy Watch6"（手表）——
            // 手表在系统里**确实被登记为一个音频 sink**（LE Audio），
            // 所以 isSink 过滤也挡不住；而 getDevices **不告诉你"当前实际在用哪个"**。
            //
            // MediaRouter 的 selectedRoute 才是"媒体正在走哪条路"的权威来源。
            // 用**框架自带**的 android.media.MediaRouter（无需新增依赖）。
            val mr = context.getSystemService(android.content.Context.MEDIA_ROUTER_SERVICE)
                as? android.media.MediaRouter
            val routeName = runCatching {
                mr?.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.name
            }.getOrNull()
            if (!routeName.isNullOrBlank()) {
                // ⚠ 中文系统里本机叫"手机"，英文是 "Phone"/"This device" —— 都要认
                val isPhone = listOf("phone", "this device", "本机", "手机", "此设备")
                    .any { routeName.contains(it, ignoreCase = true) }
                applyDeviceIcon(if (isPhone) Glyphs.SPEAKER else Glyphs.HEADPHONES)
                devicePill.text = routeName
                return
            }
        }
        runCatching {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                as? android.media.AudioManager ?: return
            val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                ?: return
            // 编解码：蓝牙 > 有线 > USB > 内置（越"外接"越优先显示）
            var best: android.media.AudioDeviceInfo? = null
            var bestRank = -1
            for (d in outs) {
                // ⚠ 真机踩坑：不过滤会显示成 "Galaxy Watch6"（手表）——
                // 它虽然走蓝牙，但**不是音频输出设备**。只认 isSink。
                if (!d.isSink) continue
                val rank = when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> 4
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> 2
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
                    else -> 0
                }
                if (rank > bestRank) {
                    bestRank = rank
                    best = d
                    // 取"第一个最优"就够：再往下只可能同级或更差
                    if (rank >= 4) break
                }
            }
            val d = best
            if (d == null) {
                applyDeviceIcon(Glyphs.SPEAKER)
                devicePill.text = "本机"
            } else {
                applyDeviceIcon(deviceGlyphFor(d.type))
                devicePill.text = d.productName
            }
            if (devicePill.text.isNullOrBlank()) {
                applyDeviceIcon(Glyphs.SPEAKER)
                devicePill.text = "本机"
            }
        }
    }

    /** 输出设备类型 → Google 风格图标（自绘，见 [Glyphs]）。 */
    private fun deviceGlyphFor(type: Int): Int = when (type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> Glyphs.HEADPHONES

        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> Glyphs.HEADSET

        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> Glyphs.USB

        else -> Glyphs.SPEAKER
    }

    /** 给胶囊图标上色（与胶囊文字同色，避免"图标比字亮"的割裂感）。 */
    private fun applyDeviceIcon(glyph: Int) {
        deviceIcon.setImageDrawable(Glyphs.create(context, glyph, 0xCCFFFFFF.toInt()))
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateMiniClock()
            postDelayed(this, CLOCK_TICK_MS)
        }
    }

    private fun updateMiniClock() {
        val now = java.util.Calendar.getInstance()
        miniTime.text = String.format(
            java.util.Locale.getDefault(), "%02d:%02d",
            now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)
        )
        miniDate.text = java.text.SimpleDateFormat(
            "M月d日 EEEE", java.util.Locale.getDefault()
        ).format(now.time)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (oneUiStyle) {
            updateMiniClock()
            removeCallbacks(clockTick)
            postDelayed(clockTick, CLOCK_TICK_MS)
        }
        // 通知小岛轮播：挂上窗口才开始跑（数据由控制器灌进来）
        removeCallbacks(notifTick)
        if (notifItems.size > 1) postDelayed(notifTick, NOTIF_INTERVAL_MS)
    }

    /**
     * "整卡"的两层（用户原创样式）。
     *
     * 同一个 `GlowCardView` 用两次，因为填充与描边必须处在不同层次：
     *   · [cardFill]  只画磨砂填充 → 加在 art/col **之前**（在封面和文字下面）
     *   · [cardFrame] 只画描边 + 发光 → 加在**最后**（否则封面会把边框盖住）
     * 视图要比卡片大一圈（glowPad），发光才画得到卡片外面。
     */
    private val cardFill = GlowCardView(context)
    private val cardFrame = GlowCardView(context)

    /**
     * 封面自己的**光**（用户要求"图片周围也要发光"）。
     *
     * 只画光：没有填充、没有描边 —— 封面是大图，再给它加边框会"显廉价"
     * （用户原话："把专辑封面也框起来看起来有一些廉价"）。
     */
    private val coverGlow = GlowCardView(context)

    private val scrim = View(context)

    /**
     * 沉浸式（样式 0）的**下半屏底色**：从专辑封面取出的莫奈面色，纯色。
     *
     * 用户需求（v1.42.0）："上面用专辑铺满（宽度屏幕宽度），专辑其余位置用莫奈取色
     * 取色专辑封面，专辑到其他位置用该纯色过度。"
     *
     * 层级：垫在最底下（封面之下、内容列之上于它）。封面底部有一条渐变带
     * （[BlurFadeCoverView.sharpUntil]）溶进它 —— 所以"封面 → 纯色"没有硬边。
     * 颜色由 [setAccentFill] 算（跟着专辑封面走，不是壁纸色）。
     */
    private val monetFill = View(context)

    /** 当前下半屏底色（实际值）—— 前景文字/图标的对比度按它算 */
    private var immersiveSurface = 0xFF2A2830.toInt()
    private val title = TextView(context)
    private val artist = TextView(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)
    private val wavy = WavyProgressBar(context)

    /** 控制行（上一首 / 占位 / 下一首）。提升为字段：播放键需要跟随它的中心。 */
    private lateinit var ctrl: LinearLayout
    private val timeNow = TextView(context)
    private val timeTotal = TextView(context)

    /** 按住向下拖 → 请求退出（回到中岛） */
    var onExitRequest: (() -> Unit)? = null

    /** 媒体按钮回调：1=上一首 2=播放/暂停 3=下一首（与 IslandView 的约定一致） */
    var onMediaButton: ((Int) -> Unit)? = null

    /** 拖动进度条 seek（0..1） */
    var onSeekEnd: ((Float) -> Unit)? = null

    private var longPressFired = false
    private var armed = false

    /** 退出手势的实时进度（0..1），用于跟手反馈与"过半才退出"的判定 */
    private var dragProgress = 0f
    private var downRawY = 0f
    private val longPressRunnable = Runnable {
        longPressFired = true
        armed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        setWillNotDraw(false)
        // ============ 几何就绪前整体不可见（真机反馈的"几帧闪烁"总闸）============
        //
        // 视图刚建好时：封面还是 MATCH_PARENT（不是最终的半屏矩形）、
        // 播放键还在根容器的 (0,0)。这些"错位帧"逐个打补丁很容易漏。
        // 所以整个视图先 alpha=0，等 prepareHero/startHero 把几何算好再显示 ——
        // 从那以后画出来的每一帧都是正确的。
        alpha = 0f

        // 两套布局二选一，交给 buildLayout()。
        //
        // 为什么绕一层函数：`init { }` 里**不允许 `return`**（Kotlin 限制），
        // 而"One UI 样式搭完就结束、不要再执行另一套布局"需要提前退出。
        // 所以抽成普通函数，在里面 return。
        buildLayout()

        // ============ 公共接线：必须放在**样式分支之外** ============
        //
        // 🔴 真机 bug（v1.35.0，用户反馈"卡片式音乐锁屏的播放控件点不到"）：
        // 三个播放键的 `setOnClickListener`（以及 `wavy.onSeekEnd`）原来写在
        // `buildLayout()` 的**样式 0 分支里**，而 One UI 样式走的是
        //     if (oneUiStyle) { buildOneUiLayout(); return }
        // —— early return 把接线一起跳过了。于是卡片式的三个按钮
        // **从来就没有 click listener**（`git log -S` 证实：自 v0.75.0 基线起
        // 就一直只在样式 0 分支里）。
        //
        // 症状极具误导性：按钮**可见、位置也对**，只是"按下去没反应、
        // 日志里连'媒体按钮点击'都没有"。于是被误判成
        // "有层盖在按钮上吃掉了触摸"，白追了三轮（甚至把控制行上移 230px、
        // 又撤销回来 —— 那当然没用，因为根本没有东西在吃触摸，是没人接线）。
        //
        // 机制细节：按钮没接线时 DOWN 会漏到本视图的 `onTouchEvent`
        // （它无条件 `return true` 独占手势），UP 时 dragProgress=0 →
        // 走"补间回音乐锁屏"分支 → 视觉上完全无变化，日志也一片空白。
        //
        // ⛔ 规则：任何"两套样式都必须生效"的接线，一律放 `init`、放样式分支之外。
        wireMediaControls()
    }

    /**
     * 按样式搭建布局。
     *
     * 单独抽成**普通函数**（而不是写在 `init` 里）：Kotlin 禁止在 `init` 里 `return`，
     * 而这里需要"One UI 样式搭完就结束、不再执行另一套布局"。
     */
    private fun buildLayout() {
        if (oneUiStyle) {
            buildOneUiLayout()
            return
        }

        // ============ 分层结构（v1.42.0 沉浸式）============
        //
        //   [0] 底色层：**专辑莫奈纯色**（下半屏；封面之下，转场时随 k 淡入）
        //   [1] 封面层：按**屏宽铺满**顶部（方形），底部一条渐变带溶进该纯色
        //   [2] 内容列：从封面下方开始排歌名/控件/进度条
        //
        // 相对上一版的三点变化（用户需求）：
        //   · 封面不再"铺满整屏再被黑色渐变压掉"，而是**只占顶部方形**（宽=屏宽，高=宽）；
        //   · 封面底下不再是"模糊封面 + 黑渐变"，而是**一整块从封面取出的莫奈纯色**；
        //   · 封面到底色之间靠**封面自己底部的渐变带**过渡（不是盖一层黑雾）。
        val screenW = resources.displayMetrics.widthPixels
        val coverH = ImmersivePalette.coverHeightPx(screenW)

        // 底色层垫最底（match_parent），颜色/alpha 在 setAccentFill / applyHero 里给
        monetFill.setBackgroundColor(immersiveSurface)
        addView(monetFill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 封面：全程清晰，只在最底部留一条过渡带溶进底色。
        // `sharpUntil` 由 applyHero 每帧按 k 推（k=0 时退化=不留带，见 ImmersivePalette.fadeStart）
        art.sharpUntil = ImmersivePalette.fadeStart(1f)
        art.fadeOutEnd = 1f
        addView(art, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ⚠️ 旧的 `scrim`（黑色渐变压暗层）在沉浸式下**不再添加** ——
        //   用户要的是"其余位置用莫奈纯色"，盖一层黑就等于把纯色弄脏了。
        //   卡片式（One UI）仍然用它，那条路径不受影响。

        // 用"只改绘制顺序、不改布局顺序"的容器：
        // LinearLayout 的子视图顺序同时决定**布局顺序和绘制顺序**，
        // 所以对 LinearLayout 调 bringChildToFront() 会把控件**挪位置**
        // （我真机上就这么踩了：把控制行提到前面 → 按钮跑到了进度条下方）。
        val col = OrderedLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(
            col,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val side = (resources.displayMetrics.widthPixels * 0.065f).toInt()

        // 内容从**封面下沿**开始（沉浸式：封面高 = 屏宽，内容必须让开它）
        // 间距 18dp：实测时间行底边 ≈2286，离指纹行（top≈2296）还有余量
        col.addView(
            View(context),
            LinearLayout.LayoutParams(1, coverH + dp(18))
        )

        // ---- 歌名 / 歌手 ----
        title.apply {
            textSize = 30f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }
        artist.apply {
            textSize = 17f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xCCFFFFFF.toInt())
        }
        col.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = side; marginEnd = side; topMargin = dp(22) }
        )
        col.addView(
            artist,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = side; marginEnd = side; topMargin = dp(6) }
        )

        // ---- 控制行：上一首 | 大圆播放键 | 下一首 ----
        ctrl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        styleFlatIcon(btnPrev, Glyphs.PREV)
        styleFlatIcon(btnNext, Glyphs.NEXT)
        // 大圆形播放键（圆角=高度一半 → 正圆）
        btnPlay.setBackgroundColor(Color.TRANSPARENT)
        btnPlay.scaleType = ImageView.ScaleType.CENTER
        ctrl.addView(btnPrev, LinearLayout.LayoutParams(dp(40), dp(40)))
        // 播放键**不放在这一行里**：它要成为根容器的最后一个子视图，
        // 才能保证在整棵视图树里绘制顺序最靠后（任何兄弟分支都盖不住它）。
        // 这里放一个等大占位，保持 prev/next 的间距不变。
        ctrl.addView(
            View(context),
            LinearLayout.LayoutParams(dp(70), dp(70))
                .apply { marginStart = dp(56); marginEnd = dp(56) }
        )
        ctrl.addView(btnNext, LinearLayout.LayoutParams(dp(40), dp(40)))
        col.addView(
            ctrl,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20); marginStart = side; marginEnd = side }
        )

        // ---- 波浪进度 + 时间 ----
        col.addView(
            wavy,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { marginStart = side; marginEnd = side; topMargin = dp(20) }
        )
        val times = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        timeNow.apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()) }
        timeTotal.apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()) }
        times.addView(
            timeNow,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        times.addView(
            timeTotal,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        col.addView(
            times,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = side; marginEnd = side; topMargin = dp(14) }
        )

        // ============ 层级对齐中岛（真机反馈"弹一下"）============
        //
        // 中岛里播放键是**最后添加**的子视图，所以绘制在进度条之上；
        // 音乐锁屏这边，控制行（上一首/播放/下一首）先添加、波浪后添加，
        // 于是波浪画在播放键**之上**。
        //
        // 平时两者不重叠看不出差别，但转场中元素会被缩放到相邻位置而相互压叠，
        // 层级差异就暴露了；切回中岛时层级翻转 → 看起来"弹一下"。
        //
        // 把控制行提到波浪之上，两个界面的层级就完全一致了。
        col.drawLast = ctrl

        // ============ 播放键：根容器的最后一个子视图 = 最高层级 ============
        //
        // 用户要求"直接把暂停按钮的层级提到最高"。
        //
        // 之前我试过两条路，都不彻底：
        //   · bringChildToFront(ctrl) —— LinearLayout 里它会**改变布局顺序**，
        //     按钮被挪到进度条下面（我引入的回归）
        //   · getChildDrawingOrder 重排绘制 —— 只能在本容器内生效，
        //     换不了"别的分支比它晚绘制"这件事
        //
        // 放到根 FrameLayout 的**最后**：它在整棵树里最后绘制，
        // 没有任何元素（封面/遮罩/波浪/文字）能盖住它。
        addView(btnPlay, LayoutParams(dp(70), dp(70)))
        btnPlay.elevation = dp(12).toFloat()
        // 先隐藏：它现在是根容器的子视图，默认落在 (0,0)，
        // 要等 syncPlayPosition() 才能就位。不先藏起来的话，
        // 亮屏恢复（不走入场动画）时会看到"另一个播放键飞到左上角"（真机反馈）。
        btnPlay.alpha = 0f

        // 位置跟随控制行中心：行一布局完就把它对上去。
        // 这样布局仍由控制行决定，而层级在最上层。
        // 位置跟随控制行中心。
        //
        // 注意：监听器**必须在 ctrl 已经布局完之后也主动调一次** ——
        // 我第一版只注册了监听器，而注册发生在 ctrl 首次布局之后，
        // 于是再也没有回调，btnPlay 停在 (0,0)（真机截图：按钮跑到左上角）。
        // 两个回调都要挂：
        //  · ctrl 的布局变化 → 行位置变了要跟（但此刻 btnPlay 可能还没布局，
        //    所以延迟到下一轮）
        //  · **btnPlay 自己的布局变化** → 这时 ctrl 一定已经布局完，能取到有效尺寸
        //
        // 我第一版只挂了 ctrl 的回调且没有延迟：回调触发时 btnPlay.width 还是 0，
        // 被守卫挡掉，之后再无布局 → 按钮停在 (0,0)（真机截图：跑到左上角）。
        ctrl.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            post { syncPlayPosition() }
        }
        btnPlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncPlayPosition()
        }

        // 媒体按钮 / 进度条的接线**不在这里** —— 见 `init` 里的 `wireMediaControls()`。
        // ⛔ 不要把它搬回本函数：本函数在 One UI 样式下会 early return，
        //    接线写在分支里会被跳过（真机 bug v1.35.0：卡片式播放控件点不到）。

        IslandLog.i("$TAG 音乐锁屏视图已构建（封面通栏铺满上半屏）")
    }

    // ⚠ 样式 0（铺满封面）**没有**通知小岛：它的内容（时间行 y≈2163）比卡片式更靠下，
    //   底部自由带只剩 ~160px，塞不进 44dp 的胶囊而不压到快捷方式圆圈
    //   （真机实测手电/相机圆圈 top ≈2353）。要做的话得先像卡片式那样把整体上移，
    //   属于独立的布局调整 —— 记在 AGENTS §6 待办里。
    //   `notifIsland` 在样式 0 下不会被 addView，保持 GONE，控制器灌数据也不会显示。

    private fun styleFlatIcon(btn: ImageButton, glyph: Int, color: Int = Color.WHITE) {
        btn.setBackgroundColor(Color.TRANSPARENT)
        btn.setImageDrawable(Glyphs.create(context, glyph, color))
        btn.scaleType = ImageView.ScaleType.CENTER
        btn.setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    /**
     * 媒体按钮 / 进度条的**公共接线**（两套样式都必须生效）。
     *
     * ⛔ 只能从 `init` 调用 —— 不能放进 `buildLayout()` 的样式分支：
     * One UI 样式在那里的 early return 会把接线一起跳过，
     * 表现就是"卡片式的按钮点不到、日志也没有"（真机 bug，v1.35.0 修复）。
     */
    private fun wireMediaControls() {
        btnPrev.setOnClickListener { onMediaButton?.invoke(1) }
        btnPlay.setOnClickListener { onMediaButton?.invoke(2) }
        btnNext.setOnClickListener { onMediaButton?.invoke(3) }
        wavy.onSeekEnd = { f -> onSeekEnd?.invoke(f) }

        // 显式声明"可点击 + 不抢焦点"：
        // 卡片式里的按钮是**无背景的 ImageButton**，不能指望主题默认值；
        // 显式 clickable=true 才能保证 DOWN 被按钮消费，
        // 而不是漏到下面那个"无条件独占手势"的 `onTouchEvent`（拖拽退出）上。
        for (b in listOf(btnPrev, btnPlay, btnNext)) {
            b.isClickable = true
            b.isFocusable = false
        }
    }

    // ------------------------------------------------------------------
    // "一镜到底"入场动画
    // ------------------------------------------------------------------

    /**
     * 中岛 → 音乐锁屏的转场。
     *
     * ============ 做法 ============
     *
     * 不是整屏缩放（那样按钮和文字会一起被拉伸，观感很假），
     * 而是**每个元素各自**从它在**中岛里的对应位置与尺寸**出发，
     * 移动/缩放到自己在音乐锁屏里的位置 ——
     * 封面继续放大铺满上半屏，歌名/歌手/进度条/按钮各自"飞"到自己的位置。
     *
     * @param from 中岛各元素在窗口坐标系里的矩形（键：art/title/artist/progress/play/prev/next）
     */
    // ==================================================================
    // 一镜到底：把转场做成**一个可停在任意位置的进度 k**
    //
    // k = 0 → 完全等于中岛卡片的样子
    // k = 1 → 完全等于音乐锁屏的样子
    //
    // 入场、退场、以及"按住往下拖"都只是驱动同一个 k：
    //   · 点击进入：k 从 0 补间到 1
    //   · 按住下拖：k 直接跟着手指走（元素会随手指缩回中岛）
    //   · 松手：过半补间到 0（回中岛），否则补间回 1
    //
    // 这样才谈得上"一镜到底"——不是两个独立动画，而是同一套几何的连续插值。
    // ==================================================================

    private class HeroItem(
        val v: View,
        val startScaleX: Float,
        val startScaleY: Float,
        /** 中岛里对应的矩形；null 表示中岛没有这个元素（如时间标签） */
        val cardRect: android.graphics.Rect?,
        /**
         * 中岛里是否有对应元素。
         *
         * 有（封面/歌名/歌手/进度/按钮）→ 缩回中岛后依然可见，alpha 保持 1；
         * 没有（时间标签这种中岛卡片上不存在的元素）→ 必须**随转场淡出**，
         * 否则元素都缩回去了、它还留在音乐锁屏的位置，画面就花了
         * （真机截图：中岛卡片边缘挂着 2:04 / 2:07 的残留时间）。
         */
        val hasCounterpart: Boolean
    )

    private var heroItemsPrepared: List<HeroItem> = emptyList()
    private val coverStart = android.graphics.RectF()
    private val coverEnd = android.graphics.RectF()

    /**
     * One UI 封面卡片的几何**不存字段**，由 [oneUiCardRect] 现算。
     *
     * 原因（真机事故）：字段声明在 `init` 之后，构造期给它们赋值会被各自的初始化器
     * 覆盖成 0；而 `coverEnd`（val）在构造期更是**还是 null**，赋值直接 NPE 崩 SystemUI。
     * 详见 [buildOneUiLayout] 顶部与 [prepareHero] 里的注释。
     */

    /**
     * 转场进度回调（0=中岛，1=音乐锁屏），**每帧**调用。
     * 控制器用它让系统通知随拖动实时淡入淡出。
     */
    var onHeroProgress: ((Float) -> Unit)? = null

    /** 当前的转场进度（0=中岛，1=音乐锁屏） */
    @Volatile
    var heroProgress: Float = 1f
        private set

    private var heroAnim: ValueAnimator? = null

    /**
     * 预计算：把"中岛里的位置"换算成每个元素相对自己自然位置的缩放与位移。
     * 只需在进入音乐锁屏时算一次，之后 [applyHero] 就是纯插值，可以直接跟着手指跑。
     */
    fun prepareHero(cardRects: Map<String, android.graphics.Rect>?) {
        heroCardRects = cardRects

        // ============ One UI 的封面终点在这里定（关键：构造已完成）============
        //
        // 两个约束，缺一不可：
        //  1) **不能放在 buildOneUiLayout 里** —— 那是构造期，coverEnd(val) 还没初始化，
        //     赋值会 NPE 并直接崩掉 SystemUI（真机事故：每次进音乐锁屏都崩，7 次记录）。
        //  2) **必须在任何 applyHero 之前** —— 否则 applyHero 会拿 coverEnd 的初值
        //     （RectF 全 0 → 被 setCoverBounds 的 coerceAtLeast(1) 压成 1×1）去缩放 art，
        //     之后读 art.width 永远是 1 → 封面永久变成 1 像素（另一个真机事故）。
        if (oneUiStyle) coverEnd.set(oneUiCardRect())
        // ============ 先让"自然位置"落定，再计算位移（真机反馈"移到太下面"）============
        //
        // 每个元素的起点位移是 `中岛矩形 - 该元素此刻的窗口位置`。
        // 但播放键的位置是由 syncPlayPosition() 在布局回调里设定的，
        // 而它和本方法都是 post 出来的 —— 顺序不确定。
        // 一旦"读到位置"发生在"位置被同步"之前，算出来的位移就基于旧位置，
        // 拖到底时按钮就会落在中岛位置的**下方**（用户实测）。
        //
        // 所以这里分两步：
        //  1) 清掉残留 transform 并同步一次播放键位置（把布局"重新排好"）
        //  2) 再 post 一层，等这次布局真正跑完，才读取各元素的自然位置
        post {
            for ((_, v) in heroItems()) {
                v.translationX = 0f
                v.translationY = 0f
                v.scaleX = 1f
                v.scaleY = 1f
            }
            syncPlayPosition()
            post { computeHeroGeometry(cardRects) }
        }
    }

    /** 读取各元素的**自然位置**并预计算转场位移（必须在布局稳定后调用） */
    private fun computeHeroGeometry(cardRects: Map<String, android.graphics.Rect>?) {
        run {
            val items = ArrayList<HeroItem>()
            for ((key, v) in heroItems()) {
                if (key == "art") continue
                if (v.width <= 0 || v.height <= 0) continue
                val src = cardRects?.get(key)
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                v.pivotX = 0f
                v.pivotY = 0f
                var sx = if (src != null) src.width().toFloat() / v.width else 1f
                var sy = if (src != null) src.height().toFloat() / v.height else 1f
                // ============ 文字必须**等比**缩放（真机反馈"字体被压扁"）============
                //
                // 中岛的标题矩形是 930x116，音乐锁屏的标题是 ~1070x105 ——
                // 直接按宽高分别缩放会得到 0.87 横向 + 1.10 纵向，
                // 字形被**非等比压扁**；落回中岛时字体恢复常态，于是"跳一下"。
                //
                // 对文字强制取两个方向的最小值（等比），字形永远不会变形。
                // 取消/回弹时同理，所以来回都不会看到挤压。
                if (isTextKey(key)) {
                    val s = minOf(sx, sy)
                    sx = s
                    sy = s
                }
                items.add(HeroItem(v, sx, sy, src, hasCounterpart = src != null))
            }
            heroItemsPrepared = items

            // 封面终点：
            //  · 旧样式 = 通栏 + 上半屏（常量，不依赖任何实测值）
            //  · One UI 样式 = 卡片矩形，**已在 buildOneUiLayout 里确定**，这里不改写
            //
            // ⚠ 曾经在这里"用 art 的实时位置算 One UI 的 coverEnd"，结果封面整个消失：
            //   applyHero 会**先于本函数**执行，把 art 按尚未初始化的 coverEnd
            //   （RectF 全 0 → 被 setCoverBounds 压成 1×1）缩放，
            //   等本函数再读 art.width 时拿到的就是 1 → 算出 1×1 的 coverEnd 并永久锁死。
            //   真机日志证据：coverEnd=RectF(607.0, 0.0, 608.0, 1.0)，art 只有 1~2px 宽。
            //   旧样式没这个问题，因为它的 coverEnd 是常量。
            //   结论：One UI 的 coverEnd 必须来自**构造期几何**，绝不读实时尺寸。
            val selfLoc = IntArray(2)
            getLocationInWindow(selfLoc)
            if (!oneUiStyle) {
                // v1.42.0 沉浸式：封面**按屏宽铺满顶部**（专辑是方图 → 高 = 宽），
                // 不再"铺满整屏再压黑"。下面的其余位置交给莫奈纯色底（monetFill）。
                val w = resources.displayMetrics.widthPixels.toFloat()
                coverEnd.set(0f, 0f, w, ImmersivePalette.coverHeightPx(w.toInt()).toFloat())
            }
            val cr = cardRects?.get("art")
            if (cr != null && cr.width() > 0 && cr.height() > 0) {
                coverStart.set(
                    (cr.left - selfLoc[0]).toFloat(),
                    (cr.top - selfLoc[1]).toFloat(),
                    (cr.right - selfLoc[0]).toFloat(),
                    (cr.bottom - selfLoc[1]).toFloat()
                )
            } else {
                coverStart.set(coverEnd)
            }

            // 静态层的收缩目标就是**中岛卡片矩形**（= 中岛封面 rect，中岛是封面铺满）。
            // 和 heroItems 同一时刻算一次，避免读"已经被 transform 过的"实时尺寸（AGENTS §4）。
            buildLayerSpecs()
            layerDumpLeft = 2

            applyHero(1f)
            alpha = 1f   // 亮屏恢复路径：几何算好后直接以最终形态显示
            IslandLog.i(
                "${TAG} 转场几何已就绪（" + items.size + " 个元素 + 封面，" +
                    "封面 " + coverStart.width().toInt() + "x" + coverStart.height().toInt() +
                    " → " + coverEnd.width().toInt() + "x" + coverEnd.height().toInt() + "）"
            )
        }
    }

    /**
     * 把转场进度设到 k（0=中岛，1=音乐锁屏）。纯插值，可在触摸回调里逐帧调用。
     */
    fun applyHero(k: Float) {
        val kk = k.coerceIn(0f, 1f)
        heroProgress = kk
        for (h in heroItemsPrepared) {
            // ============ 每帧从**实时布局位置**反推，而不是用预先算好的位移 ============
            //
            // 真机事故：播放键的位置由 syncPlayPosition() 在布局回调里设定，
            // 而预计算位移发生在那次布局**之前**，于是算出来的位移基于旧位置 ——
            // 落位自检实测 play=偏差(dy=1575)，拖到底时按钮跑到中岛位置**下方 1575px**。
            //
            // 这里改成每帧读一次当前位置，再用"减去当前 translation"还原出
            // **不受 transform 影响的自然位置**。于是无论布局何时变化、
            // 播放键何时被挪动，位移都指向同一个目标，不会再有时序依赖。
            //
            // 注意 pivot 是 (0,0)，所以缩放不影响左上角，只有 translation 需要还原。
            val v = h.v
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            val naturalX = loc[0] - v.translationX
            val naturalY = loc[1] - v.translationY

            v.scaleX = h.startScaleX + (1f - h.startScaleX) * kk
            v.scaleY = h.startScaleY + (1f - h.startScaleY) * kk
            val r = h.cardRect
            if (r != null) {
                v.translationX = (r.left - naturalX) * (1f - kk)
                v.translationY = (r.top - naturalY) * (1f - kk)
            }
            // 中岛卡片上没有对应元素的（时间标签），随转场淡出 ——
            // 否则它们会停在自己的位置，在一张已经缩小的卡片上留下残留。
            // 有对应元素的 alpha 交给 syncPlayPosition 管理（播放键先隐藏、定位后再显示），
            // 这里**不能**统一置 1，否则会在 (0,0) 露出首帧（真机反馈）
            if (!h.hasCounterpart) h.v.alpha = kk
            // 转场过程中元素保持可点：它们缩小后依然是真实控件，
            // 播放/上一首/下一首在动画进行中也能点。
            // （注意：这里**不是**"禁用命中测试"——原注释与实现相反，已更正。
            //  真要让缩小后的元素不挡手势，应该设 isEnabled=false，但那会让
            //  转场途中的按钮点不动，与现有交互预期不符。）
            h.v.isEnabled = true
        }
        // 把转场进度广播出去：控制器据此**在拖动过程中**实时淡入淡出系统通知
        // （真机反馈："能不能在拖动的变化中就淡入淡出"）。
        // 通知的 alpha = 1 - k：k=1（音乐锁屏）时通知全隐，k=0（中岛）时全显，
        // 中间任意位置都是连续的。
        // ============ 静态层：淡出 + 朝中岛卡片收缩（v1.41.0）============
        //
        // 背景 / 卡框 / 封面光 / mini 时钟 / 设备胶囊 / 通知小岛都随 k 走 ——
        // 于是"整张卡收进中岛"（而不是内容飞走、框和光留在原地）。
        // 入场、退场、跟手拖动共用这一条路径，所以三个方向的观感自动一致。
        applyExitLayers(kk)

        onHeroProgress?.invoke(kk)

        // k 到 0（= 完全等于中岛）时做一次几何自检：
        // 逐个元素打印"实际矩形 vs 中岛里的目标矩形"，偏差一目了然。
        // 之前"转场中间露出不该有的元素/错位"这类问题，靠肉眼看截图很难定位，
        // 有这行日志就能直接判断是哪个元素没对齐。
        if (kk <= 0.001f) logHeroLanding()
        // 转场进行中的采样（每 12 次插值记一次，避免刷屏）。
        // 真机上拖动路径无法用 adb 复现，只能靠这份日志判断"中间态到底长什么样"：
        // 每个元素的实际窗口矩形、缩放与 alpha 都打出来。
        else if (kk < 0.999f && (heroSampleTick++ % 12 == 0)) logHeroSample(kk)
        // 封面走"动画边界"：宽高比每帧自然变化、CENTER_CROP 每帧重裁 → 真正连续
        setCoverBounds(
            coverStart.left + (coverEnd.left - coverStart.left) * kk,
            coverStart.top + (coverEnd.top - coverStart.top) * kk,
            coverStart.width() + (coverEnd.width() - coverStart.width()) * kk,
            coverStart.height() + (coverEnd.height() - coverStart.height()) * kk
        )
        // 圆角终点按**样式**取：
        //  · 旧样式：封面铺满整屏 → 终点是直角(0)
        //  · One UI：封面是圆角卡片 → 终点必须保持 22dp，否则卡片被渲染成直角
        val startCorner = dp(CARD_CORNER_DP).toFloat()
        val endCorner = if (oneUiStyle) dp(ONEUI_CARD_CORNER_DP).toFloat() else 0f
        art.cornerRadiusPx = startCorner + (endCorner - startCorner) * kk
        if (oneUiStyle) {
            scrim.alpha = kk
        } else {
            // ============ 沉浸式（样式 0）============
            //
            // 1) 莫奈纯色底随 k 淡入/淡出：k=0（中岛）时完全透明，不会盖住壁纸；
            //    转场中逐渐显出，退场时又逐渐让位 —— 与"封面收缩回中岛"同相。
            // 2) 封面底部的过渡带也随 k 打开：k=0 时退化（整张封面清晰，
            //    否则中岛封面底下会透出壁纸，出现"缺一角"）；k=1 时是 14% 的柔边。
            monetFill.alpha = kk
            art.sharpUntil = ImmersivePalette.fadeStart(kk)
            art.fadeOutEnd = 1f
        }
    }

    // ------------------------------------------------------------------
    // v1.41.0：退场"塌缩"（理念同中岛→小岛的 morphFallToCompact）
    // ------------------------------------------------------------------

    /**
     * 登记"静态层"清单（只登记视图与时间窗，**不算几何**）。
     *
     * 几何必须延迟到 [ensureLayerMotions] 里算 —— 因为本函数在
     * `computeHeroGeometry`（布局刚稳定）时调用，那一刻有些层（胶囊 / 通知小岛）
     * 可能还没量出尺寸，算出来就是 0，一算就废。
     */
    private fun buildLayerSpecs() {
        layerSpecs.clear()
        layerMotions.clear()
        if (!oneUiStyle) return
        // 背底层：只淡出（收缩会四周露壁纸），而且**早淡**（本来就该先让位给卡片）
        layerSpecs.add(LayerSpec(backdrop, 0f, 0.45f, shrink = false))
        layerSpecs.add(LayerSpec(tint, 0f, 0.45f, shrink = false))
        // ============ 没有"中岛对应元素"的附属物：必须**比卡片结构早淡** ============
        //
        // 🔴 v1.41.1 修正（用户反馈："音频输出胶囊漏在外面不好看"）：
        //   设备胶囊 / mini 时钟 / 通知小岛的窗口原来排在**卡框之后**（0.20~0.90），
        //   于是卡框已经淡到 26% 时胶囊还有 55% 可见 ——
        //   观感就是"一张快消失的卡片外面孤零零戳着一个胶囊"。
        //   **没有对应元素的层，一律要先于它所属的卡片结构消失**（这里收到 0.45 前）。
        // ⚠️ 登记的是**容器** `pillRow`（图标 + 文字整体），不是 `devicePill` 文字 ——
        //   只登记文字会让**图标**留下来（真机 bug：底色淡了、喇叭图标还挂在外面）
        layerSpecs.add(LayerSpec(pillRow, 0.05f, 0.40f, shrink = true))
        layerSpecs.add(LayerSpec(miniRow, 0.05f, 0.40f, shrink = true))
        layerSpecs.add(LayerSpec(notifIsland, 0.05f, 0.45f, shrink = true))
        // ============ 卡片自身的结构：**跟到落定为止**（v1.41.3）============
        //
        // 用户反馈"太轻了"。原来卡框在 q=0.75 就淡完了，落定那一刻卡片是**空的** ——
        // 读起来像"轻飘飘地散了"。现在让光/填充/描边一直保持到快落定，
        // 卡片是"带着实体重量落到中岛位置上"再交棒，分量感来自这里。
        layerSpecs.add(LayerSpec(coverGlow, 0.05f, 0.68f, shrink = true))
        layerSpecs.add(LayerSpec(cardFill, 0.10f, 0.80f, shrink = true))
        layerSpecs.add(LayerSpec(cardFrame, 0.15f, 0.88f, shrink = true))
    }

    /**
     * 现在才真正算几何（用当前的 `coverStart` 与各层的实时尺寸）。
     * 尺寸还没就绪的层**自动进入下一轮重试**，绝不静默丢弃。
     */
    private fun ensureLayerMotions() {
        if (layerMotions.size >= layerSpecs.size) return
        if (coverStart.width() <= 1f || coverStart.height() <= 1f) return
        layerMotions.clear()
        val selfLoc = IntArray(2)
        getLocationInWindow(selfLoc)
        for (s in layerSpecs) {
            val v = s.v
            if (v.width <= 0 || v.height <= 0) continue     // 下一轮再来
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            // ============ 设备胶囊：**跟着歌名走**，绝不朝卡片中心爬 ============
            //
            // 🔴 v1.41.2（用户反馈："音频输出胶囊漏在外面不好看"，指的就是它）：
            //   原来给它算的目标是**卡片中心**，于是转场中它离开歌名那一行、
            //   朝卡片中间爬；而卡片本身正在朝中岛缩小 —— 两者路径不同步，
            //   结果它一会儿悬在卡片上边、一会儿戳在卡片外面。
            //   现在与 `heroItems()` 里"文字"**完全同一条路径**（目标 = 中岛的歌名矩形、
            //   等比缩放、pivot=0）—— 它永远贴着歌名，再由尽早的淡出窗口收掉。
            //   ⚠ 坐标系：`heroCardRects` 是**窗口坐标**（与 heroItems 一致），
            //     所以这里用窗口坐标减，不要混进 self 空间的偏移。
            val titleRect = heroCardRects?.get("title")
            if (v === pillRow && titleRect != null &&
                titleRect.width() > 0 && titleRect.height() > 0
            ) {
                // ⚠ 必须夹到 ≤1：胶囊（225x85）比中岛的歌名矩形（891x115）**小**，
                //   比例会 >1 —— 不夹的话它会一边飞一边被放大 35%。
                val sc = minOf(
                    1f,
                    titleRect.width().toFloat() / v.width,
                    titleRect.height().toFloat() / v.height
                )
                v.pivotX = 0f
                v.pivotY = 0f
                layerMotions.add(
                    LayerMotion(
                        v, sc,
                        titleRect.left - (loc[0] - v.translationX),
                        titleRect.top - (loc[1] - v.translationY),
                        s.a0, s.a1
                    )
                )
                continue
            }
            // ⚠️ 必须用**自然位置**（实时位置 − 当前 translation）：
            //   几何是延迟算的（`ensureLayerMotions` 可能在转场中途才第一次跑），
            //   那时该层可能已经带着上一帧的位移 —— 不扣掉就会把"自然位置"算歪，
            //   之后所有 dx/dy 都建立在错误基准上（与 heroItems 里的做法保持一致）。
            val cx = loc[0] - v.translationX - selfLoc[0] + v.width / 2f
            val cy = loc[1] - v.translationY - selfLoc[1] + v.height / 2f
            val sc = if (s.shrink) {
                minOf(1f, coverStart.width() / v.width, coverStart.height() / v.height)
            } else {
                1f
            }
            if (s.shrink) {
                v.pivotX = v.width / 2f
                v.pivotY = v.height / 2f
            }
            val dx = if (s.shrink) coverStart.centerX() - cx else 0f
            val dy = if (s.shrink) coverStart.centerY() - cy else 0f
            layerMotions.add(LayerMotion(v, sc, dx, dy, s.a0, s.a1))
        }
    }

    /** 按 k 应用静态层：淡出 + 收缩；内容卡的磨砂底朝"中岛面色"插值 */
    private fun applyExitLayers(k: Float) {
        if (layerSpecs.isEmpty()) return
        ensureLayerMotions()
        val q = 1f - k
        // ============ k=1（音乐锁屏完全就位）必须**显式复位** ============
        //
        // 🔴 v1.41.5（真机 bug："时钟跑到下面去了"）：
        //   mini 时钟行被残留的 `translationY` 推到了卡片中心（= 静态层收缩目标），
        //   而 alpha 又是 1 —— 于是音乐锁屏上多出一行"时钟+日期"压在控件行上。
        //   根因是"复位路径没覆盖新增的变换"（AGENTS §4 里那条踩过两次的坑，这是第三次）。
        //   这里在 k≈1 时把每层**强制**写回布局原样（alpha/scale/translation），
        //   不依赖"f 恰好等于 0"这种隐含假设。
        if (k >= 0.999f) {
            for (s in layerSpecs) {
                s.v.alpha = 1f
                s.v.scaleX = 1f
                s.v.scaleY = 1f
                s.v.translationX = 0f
                s.v.translationY = 0f
            }
            if (layerDumpLeft > 0) {
                layerDumpLeft--
                val sb = StringBuilder("静态层(k=1)：")
                for (s in layerSpecs) {
                    val loc = IntArray(2)
                    s.v.getLocationInWindow(loc)
                    sb.append(s.v.javaClass.simpleName)
                        .append("(a=").append(String.format(java.util.Locale.US, "%.2f", s.v.alpha))
                        .append(" ty=").append(s.v.translationY.toInt())
                        .append(" y=").append(loc[1])
                        .append(") ")
                }
                IslandLog.i("$TAG $sb")
            }
            applyExitBlur(k)
            return
        }
        for (m in layerMotions) {
            val f = MusicExitMotion.layerProgress(q, m.a0, m.a1)
            m.v.alpha = 1f - f
            if (m.scale != 1f || m.dx != 0f || m.dy != 0f) {
                m.v.scaleX = 1f + (m.scale - 1f) * f
                m.v.scaleY = 1f + (m.scale - 1f) * f
                m.v.translationX = m.dx * f
                m.v.translationY = m.dy * f
            }
        }
        // ============ 兜底：几何还没算出来的层，也必须随进度淡出 ============
        //
        // 这一条是为真机 bug 加的（用户："音频输出胶囊漏在外面不好看"）：
        // 只要某一层因为"登记那一帧尺寸为 0"而没进 layerMotions，
        // 它就会**永远保持 alpha=1**，在整段转场里（包括 k=0 落定后）孤零零留在外面。
        // 现在无论几何能不能算，至少保证 `alpha ≤ 1 - f` —— 最坏情况是"不收缩、只淡出"。
        for (s in layerSpecs) {
            var has = false
            for (m in layerMotions) {
                if (m.v === s.v) { has = true; break }
            }
            if (has) continue
            val f = MusicExitMotion.layerProgress(q, s.a0, s.a1)
            val target = 1f - f
            if (s.v.alpha > target) s.v.alpha = target
        }
        // 内容卡的磨砂底：专辑色 → **中岛面色**。
        // 这是"中岛→小岛"里"封面缩小时露出的背景 = 小岛面色"的对应物：
        // 封面收缩时露出的那圈底色要跟目标形态一致，否则会闪一下别的颜色。
        if (oneUiStyle) {
            val face = com.example.livenotification.island.util.AlbumColor.surface(accentForExit)
            val c = lerpRgb(accentForExit, face, q.coerceIn(0f, 1f))
            cardFill.fillColor = (c and 0x00FFFFFF) or 0x24000000
            cardFill.invalidate()
        }
        applyExitBlur(k)
        // 排查用：接近落定时把每层状态打一次（"谁还留着"一眼可见）
        if (k <= 0.02f && layerDumpLeft > 0) {
            layerDumpLeft--
            val sb = StringBuilder("静态层(k≈0)：")
            for (s in layerSpecs) {
                var hasGeom = false
                for (m in layerMotions) {
                    if (m.v === s.v) { hasGeom = true; break }
                }
                sb.append(s.v.javaClass.simpleName).append("(a=")
                    .append(String.format(java.util.Locale.US, "%.2f", s.v.alpha))
                    .append(" vis=").append(s.v.visibility)
                    .append(if (hasGeom) " 有几何" else " 无几何")
                    .append(") ")
            }
            IslandLog.i("$TAG $sb")
        }
    }

    /**
     * 交棒前把全屏模糊摘掉（幂等，可随时调）。
     *
     * 🔴 v1.41.6（用户："**到中岛位置后的模糊时间还是太长，看着有种未响应的感觉**"）：
     *   交棒链（展开中岛 → 同步数据 → 重扫通知 → 重算可见性 → 撤本视图）要跑好几帧，
     *   这几帧里屏幕上还是这张全屏视图。若它此刻带着 `RenderEffect` 模糊，
     *   就会被**冻成一张糊图 + 每帧离屏重绘掉帧** —— 观感正是"糊很久 + 像卡住"。
     *   驼峰曲线已经保证 `k=0` 时强度为 0，这里再显式兜一刀：任何路径进交棒前都不许带模糊。
     */
    fun clearExitBlur() {
        lastExitBlurPx = 0f
        runCatching { setRenderEffect(null) }
    }

    /**
     * 模糊遮瑕：驼峰曲线（中段最糊、落地前归零，见 [MusicExitMotion.blurStrength]）。
     * 半径量化到 1px 一档，避免每帧重建 RenderEffect（全屏模糊本身不便宜）。
     */
    private fun applyExitBlur(k: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val px = dp(MusicExitMotion.BLUR_PEAK_DP.toInt().coerceAtLeast(1)).toFloat() *
            MusicExitMotion.blurStrength(k)
        if (kotlin.math.abs(px - lastExitBlurPx) < 1f) return
        lastExitBlurPx = px
        runCatching {
            if (px < 1f) {
                setRenderEffect(null)
            } else {
                setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        px, px, android.graphics.Shader.TileMode.CLAMP
                    )
                )
            }
        }
    }

    /** 逐通道线性插值（忽略 alpha，调用方自己定透明度） */
    private fun lerpRgb(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * f).toInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * 退场：音乐锁屏 → 中岛，**塌缩**到位（用户要求"理念类似中岛退到小岛"）。
     *
     * 与旧的 `startExitHero`（300·k ms + 强调减速）的区别：
     *   · 时长 `140 + 180·k`、曲线 `(0.55,0.05,0.2,1)` —— 与 morphFallToCompact 同源
     *   · 到位时**几何逐元素重合**，然后由控制器交棒（瞬时切换 + 中岛侧模糊淡出）
     * 参数都在 [MusicExitMotion] 里（有单测钉住），这里不写字面量。
     */
    fun startExitCollapse(to: Map<String, android.graphics.Rect>?, onLanded: () -> Unit) {
        if (heroItemsPrepared.isEmpty()) prepareHero(to)
        post {
            val from = heroProgress
            val dur = MusicExitMotion.fallDurationMs(from)
            heroAnim?.cancel()
            heroAnim = ValueAnimator.ofFloat(from, 0f).apply {
                duration = dur
                interpolator = android.view.animation.PathInterpolator(
                    MusicExitMotion.CURVE_X1, MusicExitMotion.CURVE_Y1,
                    MusicExitMotion.CURVE_X2, MusicExitMotion.CURVE_Y2
                )
                addUpdateListener { a -> applyHero(a.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // 精确落在中岛几何上（顺带触发一次落位自检日志）
                        applyHero(0f)
                        onLanded()
                    }
                })
                start()
            }
            IslandLog.i(
                "$TAG 退场塌缩：k $from → 0（${dur}ms，曲线 " +
                    MusicExitMotion.CURVE_X1 + "/" + MusicExitMotion.CURVE_Y1 + "/" +
                    MusicExitMotion.CURVE_X2 + "/" + MusicExitMotion.CURVE_Y2 + "）"
            )
        }
    }

    /**
     * 跟手未过半就松手 → **回弹**到音乐锁屏。
     * 140ms（与 collapse 的 morphSpringBack 同一个数）；封面/模糊/内容都由同一个 k 驱动，
     * 天然同相，不会出现"封面回来了、模糊还留着"。
     */
    private fun springBackHero() {
        heroAnim?.cancel()
        val from = heroProgress
        heroAnim = ValueAnimator.ofFloat(from, 1f).apply {
            duration = MusicExitMotion.SPRING_MS
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a -> applyHero(a.animatedValue as Float) }
            start()
        }
    }

    /** 补间到目标进度（入场 → 1，退场/回弹 → 0） */
    fun animateHeroTo(target: Float, onEnd: (() -> Unit)? = null) {
        heroAnim?.cancel()
        val from = heroProgress
        // ⚠ v1.41.4：时长按**剩余行程**给"基数 + 跨度"，不再是 `HERO_MS × remain` ——
        //   后者在"拖到大半再松手"时只剩一百多毫秒，用户反馈"中岛→音乐锁屏又太快了"。
        val dur = MusicExitMotion.entryDurationMs(kotlin.math.abs(target - from))
        heroAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = dur
            interpolator = android.view.animation.PathInterpolator(
                MusicExitMotion.CURVE_X1, MusicExitMotion.CURVE_Y1,
                MusicExitMotion.CURVE_X2, MusicExitMotion.CURVE_Y2
            )
            addUpdateListener { a -> applyHero(a.animatedValue as Float) }
            if (onEnd != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                })
            }
            start()
        }
    }

    /** 入场：中岛 → 音乐锁屏 */
    fun startHero(from: Map<String, android.graphics.Rect>?) {
        prepareHero(from)
        post {
            applyHero(0f)
            alpha = 1f   // 几何已就绪，可以显示了
            animateHeroTo(1f)
            IslandLog.i(
                "$TAG 一镜到底入场：k 0 → 1（" + MusicExitMotion.entryDurationMs(1f) + "ms，曲线 " +
                    MusicExitMotion.CURVE_X1 + "/" + MusicExitMotion.CURVE_Y1 + "/" +
                    MusicExitMotion.CURVE_X2 + "/" + MusicExitMotion.CURVE_Y2 + "）"
            )
        }
    }

    /** 退场：音乐锁屏 → 中岛 */
    fun startExitHero(to: Map<String, android.graphics.Rect>?) {
        if (heroItemsPrepared.isEmpty()) prepareHero(to)
        post {
            animateHeroTo(0f)
            IslandLog.i("${TAG} 一镜到底退场：k → 0")
        }
    }

    /**
     * k=0 时的几何自检：把每个转场元素的实际矩形与中岛里的目标矩形对比。
     *
     * 存在的意义：转场是否"真正一镜到底"，取决于"k=0 时是否与中岛卡片逐像素重合"。
     * 这件事靠截图肉眼看很难判断（拖动手势 adb 送不到、也没法稳定复现中间帧），
     * 所以直接在落位瞬间把偏差打出来 —— 是哪个元素没对齐一目了然。
     */
    private fun logHeroLanding() {
        val sb = StringBuilder()
        for ((key, v) in heroItems()) {
            if (v.width <= 0 || v.height <= 0) continue
            val src = heroCardRects?.get(key)
            if (src == null) {
                sb.append(key).append("=无对应(alpha=")
                    .append((v.alpha * 100).toInt()).append("%) ")
            } else {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                val dl = loc[0] - src.left
                val dt = loc[1] - src.top
                // 注意：v.width/height 是**布局尺寸，不含 scale**。
                // 渲染出来的实际尺寸要乘上 scaleX/scaleY —— 第一版自检漏了这一步，
                // 于是所有元素都被误报成"尺寸偏差"（真机日志确认）。
                val dw = (v.width * v.scaleX).toInt() - src.width()
                val dh = (v.height * v.scaleY).toInt() - src.height()
                val ok = kotlin.math.abs(dl) <= 2 && kotlin.math.abs(dt) <= 2 &&
                    kotlin.math.abs(dw) <= 2 && kotlin.math.abs(dh) <= 2
                sb.append(key).append(if (ok) "=OK" else "=偏差(dx=")
                if (!ok) sb.append(dl).append(",dy=").append(dt)
                    .append(",dw=").append(dw).append(",dh=").append(dh).append(')')
                sb.append(' ')
            }
        }
        IslandLog.i("${TAG} 落位自检(k=0)：" + sb)
    }

    private var heroSampleTick = 0

    /**
     * 转场进行中的采样日志：每个元素的实际窗口矩形 + 渲染尺寸 + alpha。
     *
     * 拖动路径在 adb 上无法复现（MOVE 事件送不到应用），所以"中间态到底长什么样"
     * 只能靠日志还原：用户复现一次，就能看出是哪个元素跑偏，
     * 还是"岛与音乐锁屏同时可见"造成的叠影。
     */
    private fun logHeroSample(k: Float) {
        val sb = StringBuilder()
        for ((key, v) in heroItems()) {
            if (v.width <= 0 || v.height <= 0) continue
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            sb.append(key).append('[').append(loc[0]).append(',').append(loc[1])
                .append(' ').append((v.width * v.scaleX).toInt()).append('x')
                .append((v.height * v.scaleY).toInt())
                .append(" a=").append((v.alpha * 100).toInt()).append("] ")
        }
        IslandLog.i("${TAG} 转场采样 k=" + k + " " + sb)
    }

    /** 进入转场时缓存的中岛元素矩形，供落位自检使用 */
    private var heroCardRects: Map<String, android.graphics.Rect>? = null

    /** 直接改封面的布局边界（px，本视图坐标系） */
    private fun setCoverBounds(l: Float, t: Float, w: Float, h: Float) {
        val lp = art.layoutParams as? LayoutParams ?: return
        lp.leftMargin = l.toInt()
        lp.topMargin = t.toInt()
        lp.width = w.toInt().coerceAtLeast(1)
        lp.height = h.toInt().coerceAtLeast(1)
        art.layoutParams = lp
    }

    /** 文字类元素：转场时必须等比缩放，否则字形会被压扁（真机反馈"字体被压扁→跳一下"） */
    private fun isTextKey(key: String): Boolean =
        key == "title" || key == "artist" || key == "time_now" || key == "time_total"

    private fun heroItems(): List<Pair<String, View>> = listOf(
        "art" to (art as View),
        "title" to (title as View),
        "artist" to (artist as View),
        "progress" to (wavy as View),
        "play" to (btnPlay as View),
        "prev" to (btnPrev as View),
        "next" to (btnNext as View),
        // 时间标签在中岛卡片上**没有对应元素**（"time_now"/"time_total" 不在
        // 中岛的 expandedElementRects 里），所以会被标记为无对应并随转场淡出。
        // 之前漏了这两个，真机上就会出现"卡片边缘挂着残留时间"的破图。
        "time_now" to (timeNow as View),
        "time_total" to (timeTotal as View)
    )

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    fun setTexts(main: String, sub: String?) {
        title.text = main
        artist.text = sub.orEmpty()
        artist.visibility = if (sub.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setArt(bitmap: Bitmap?) {
        // One UI 样式的全屏背景层：同一张封面（会被重模糊成色块）
        if (oneUiStyle) backdrop.setImageBitmap(bitmap)
        // 封面层自己负责"清晰 + 逐渐模糊"两层位图的绘制
        art.setCover(bitmap)
        // 没有封面时给个中性底色，避免整屏透出壁纸
        art.setBackgroundColor(if (bitmap == null) 0xFF2A2830.toInt() else Color.TRANSPARENT)
        // 沉浸式：没有封面时底色也要有个兜底（否则下半屏会是纯黑）
        if (!oneUiStyle) monetFill.setBackgroundColor(immersiveSurface)
    }

    fun setPlaying(playing: Boolean) {
        btnPlay.setImageDrawable(
            Glyphs.create(context, if (playing) Glyphs.PAUSE else Glyphs.PLAY, Color.WHITE)
        )
        wavy.setAnimating(playing)
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        val d = durationMs.coerceAtLeast(1L)
        wavy.setProgressFraction((positionMs.toFloat() / d).coerceIn(0f, 1f))
        timeNow.text = fmt(positionMs)
        timeTotal.text = fmt(durationMs)
    }

    /**
     * 主题强调色（莫奈取色，由控制器传入）。
     *
     * 用户要求"进度条也要同步莫奈取色"，所以这里同时设置：
     *  · 大圆播放键的填充色
     *  · 波浪进度条的活动色与轨道色
     */
    fun setAccentFill(color: Int) {
        // One UI 样式下播放键是**纯图标**（用户明确要求"不要实心圆"），
        // 所以这里不能再给它涂 OVAL 底色 —— 否则会把 buildOneUiLayout 里设的
        // 透明背景覆盖掉（真机截图里就是这个现象：要求去掉实心圆，它却回来了）。
        if (!oneUiStyle) {
            val d = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            btnPlay.background = d

            // ============ 沉浸式（样式 0）：下半屏 = 专辑莫奈纯色底 ============
            //
            // 用户需求："专辑其余位置用莫奈取色取色专辑封面，专辑到其他位置用该纯色过度"。
            // 颜色来自**专辑封面**（controller 传入的正是 AlbumColor.accent(封面)），
            // 再经 AlbumColor.surface() 降饱和压暗成"面色" —— 与中岛/小岛同一套配色。
            val surface = ImmersivePalette.surface(color)
            immersiveSurface = surface
            monetFill.setBackgroundColor(surface)

            // 底色可能是很亮的专辑色（黄/白系），白字会糊 → 按 WCAG 对比度选前景色，
            // 同时把次级文字/图标按同一前景色降透明度，保证整块区域可读性一致。
            val fg = ImmersivePalette.foreground(surface)
            title.setTextColor(fg)
            // 文字压在**纯色**上，不需要描边阴影；白字时才留一点点压暗兜底
            if (fg == android.graphics.Color.WHITE) {
                title.setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
            } else {
                title.setShadowLayer(0f, 0f, 0f, 0)
            }
            artist.setTextColor(ImmersivePalette.withAlpha(fg, 0xCC))
            timeNow.setTextColor(ImmersivePalette.withAlpha(fg, 0xB3))
            timeTotal.setTextColor(ImmersivePalette.withAlpha(fg, 0xB3))
            styleFlatIcon(btnPrev, Glyphs.PREV, fg)
            styleFlatIcon(btnNext, Glyphs.NEXT, fg)
            // 进度条轨道用前景色低透明度：强调色若与底色接近，光靠同色轨道会看不见
            wavy.trackColor = ImmersivePalette.withAlpha(fg, 0x42)
        }

        // 进度条同步莫奈：活动部分用强调色，未播放轨道用同色低透明度
        wavy.activeColor = color
        wavy.trackColor = (color and 0x00FFFFFF) or 0x59000000
        wavy.invalidate()
        // One UI 样式：把这层强调色**半透明地**叠到模糊背景上
        // （用户要求"背景用专辑封面的莫奈取色" —— 用 22% 让模糊的封面纹理仍可见）
        if (oneUiStyle) {
            // v1.41.0：退场时内容卡的磨砂底要朝"中岛面色"插值，这里记住专辑强调色
            accentForExit = color
            tint.setBackgroundColor((color and 0x00FFFFFF) or 0x38000000)

            // ============ "整卡"的磨砂填充 + 专辑色发光边框（用户原创样式）============
            //
            // 填充：专辑色**很低的 alpha**（约 14%）—— "微透"，能透出后面的模糊封面
            // 描边：专辑色较高 alpha（约 55%），保证边框看得出来
            // 发光：专辑色，由 GlowCardView 用多圈递减 alpha 描边模拟（无软件层开销）
            val rgb = color and 0x00FFFFFF
            // 磨砂填充：专辑色 14%（"微透"，能透出后面的模糊封面）
            cardFill.fillColor = rgb or 0x24000000
            cardFill.invalidate()
            // 光：只给**色相**，亮度由 GlowCardView 内的方向渐变决定
            cardFrame.lightColor = rgb
            cardFrame.invalidate()
            // 封面自己的光用同一个专辑色（用户要求"图片周围也要发光"）
            coverGlow.lightColor = rgb
            coverGlow.invalidate()
        }
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ------------------------------------------------------------------
    // 手势：只有"按住向下拖"能退出
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                // ============ 不再要求"长按"才能下拖 ============
                //
                // 用户要求："降阶的触发逻辑改成按住向下拖，而不是长按住触发才能向下拖"。
                // 原来要长按 420ms 才 armed=true，手感很别扭。
                // 现在按下即可拖；误触由"必须拖够 EXIT_DRAG_PX 的一半才退出"来兜底，
                // 轻点或小幅移动不会误退出。
                armed = true
                longPressFired = true   // 不再触发长按逻辑（也就不会再走 performClick 的点击分支）
                downRawY = event.rawY
                // 独占触摸，避免父级（锁屏面板）把这个手势当成下拉通知栏
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (armed) {
                    // "跟手"：拖动进度直接驱动转场进度 k = 1 - p。
                    // 于是元素（封面/歌名/按钮/进度条）是**随手指一起缩回中岛**的，
                    // 而不是整屏平移。
                    //
                    // p 被 coerceIn(0,1) 夹住 → 一旦缩到中岛位置（k=0），
                    // 再往下拖也不会继续动（用户明确要求）。
                    val dy = event.rawY - downRawY
                    if (dy > 0f) {
                        val p = (dy / EXIT_DRAG_PX).coerceIn(0f, 1f)
                        dragProgress = p
                        applyHero(1f - p)
                    }
                    return true
                }
                if (kotlin.math.abs(event.rawY - downRawY) > 24f) {
                    removeCallbacks(longPressRunnable)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                if (armed) {
                    armed = false
                    val p = dragProgress
                    dragProgress = 0f
                    if (p >= 0.5f) {
                        // 过半 → 交给控制器完成退场（它从当前 k 接上补间到 0）
                        onExitRequest?.invoke()
                    } else {
                        // 未过半 → 回弹回音乐锁屏（140ms，与 collapse 的回弹同源）
                        springBackHero()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        // mini 时钟的刷新循环也要停（否则视图已移除还在定时刷新，白耗电）
        removeCallbacks(clockTick)
        // 通知轮播同理：不摘掉的话退出音乐锁屏后还在后台每 5s 转一次
        removeCallbacks(notifTick)
        notifContent.animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * One UI 封面卡片的矩形（本视图坐标系）。
     *
     * **现算**而不是读字段：`oneUiCardTop`/`oneUiCardSize` 声明在 `init` 之后，
     * 构造期给它们赋值会被各自的初始化器覆盖成 0（Kotlin 按声明顺序初始化）。
     * 现算就没有这个顺序问题，也保证与 [buildOneUiLayout] 用同一组比例常量、不会漂移。
     */
    private fun oneUiCardRect(): android.graphics.RectF {
        val dm = resources.displayMetrics
        val side = (dm.widthPixels * ONEUI_CARD_SIDE_RATIO).toInt()
        val size = dm.widthPixels - side * 2
        val top = (dm.heightPixels * ONEUI_CARD_TOP_RATIO).toInt()
        val left = (dm.widthPixels - size) / 2
        return android.graphics.RectF(
            left.toFloat(),
            top.toFloat(),
            (left + size).toFloat(),
            (top + size).toFloat()
        )
    }

    /**
     * One UI 样式的音乐锁屏（用户从 One UI 宣传片里指定的那张）。
     *
     *   （系统时钟/日期，不由我们画）
     *      ┌──────────────────┐
     *      │   专辑封面卡片     │  ← 圆角矩形，宽 78% 屏宽，近正方形
     *      └──────────────────┘
     *   Suck next to you           ← 歌名（左对齐 17sp）
     *   Today                      ← 歌手（13sp 60% 白）
     *   ═════════●══════════       ← 粗进度条 + 大圆手柄
     *   00:44              1:39
     *                    ⏮  ⏸  ⏭   ← 纯图标控制行（无实心圆）
     *
     * 注意两点（否则会出错）：
     *  · **不加 artBlur**：卡片样式不需要那层模糊垫底
     *  · **封面不做"渐溶"**（sharpUntil=1f）：否则卡片下缘会被擦掉
     */
    private fun buildOneUiLayout() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val side = (screenW * ONEUI_CARD_SIDE_RATIO).toInt()   // 卡片宽 = 屏幕 - 两侧留白
        val cardSize = screenW - side * 2
        // 卡片起点：真机实测系统日期基线约在屏高 29%。
        // 27% 让卡片落在日期下方，同时给下面的文字/进度条/控制键留出足够空间。
        val cardTop = (screenH * ONEUI_CARD_TOP_RATIO).toInt()

        // ⛔ 这里**绝对不能**碰 coverEnd / oneUiCardTop / oneUiCardSize！
        //
        // 它们都是**声明在 `init` 之后**的属性（本文件第 428 行以后）。
        // Kotlin 按**声明顺序**初始化：`init { buildLayout() }` 在第 132 行，
        // 跑的时候那些属性还是 null / 未初始化 ——
        //   · 给 `coverEnd`（val）赋值 → NullPointerException
        //   · 给 `oneUiCardTop`（var）赋值 → 会被它自己的初始化器 `= 0` 覆盖掉
        //
        // 真机事故（每次进音乐锁屏都崩，crash buffer 里 7 次记录，SystemUI 反复重启）：
        //   NullPointerException: RectF.set(...) on a null object reference
        //     at MusicLockView.buildOneUiLayout(MusicLockView.kt:943)
        //
        // 所以一条铁律：**`buildOneUiLayout` 只搭视图，不写任何"后置声明"的字段。**
        // 卡片几何统一由 `oneUiCardRect()` 现算；`coverEnd` 在 `prepareHero()`
        // （构造完成之后、任何 applyHero 之前）赋值。

        // ============ 层次：暗底在下，卡片在上（顺序不能反）============
        //
        // 之前把 scrim 加在 art **之后** → 遮罩盖住卡片 → 只能靠"给卡片位置开透明窗口"
        // 来让卡片可见，而那个窗口会露出一大块高亮壁纸（观感很花，也是我误判
        // "卡片是波浪形"的根源）。
        //
        // 现在反过来：**先加 scrim（暗底），再加 art（卡片浮在暗面上）**。
        // 于是遮罩是一层不关心卡片位置的纯背景，卡片完全不受影响 —— 即用户选的方案 A。
        // ============ 背景层：封面重模糊 + 专辑色着色（用户要求）============
        //
        // 层次（从下到上）：
        //   backdrop（封面重模糊）→ tint（专辑莫奈色半透明）→ scrim（暗化渐变）→ 卡片
        //
        // 为什么不是"纯色背景"：真机反馈"太素了没有质感"。根因是原来的纯灰阶渐变
        // 和当前封面没有任何色彩关联。用**封面自己的模糊放大**当底，
        // 色彩就从封面里"渗"出来，整屏与封面成为一体（Apple Music / 三星都是这个思路）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                // 半径很大：要把封面糊成"色块"，不是"稍微虚化"
                backdrop.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        120f, 120f, android.graphics.Shader.TileMode.CLAMP
                    )
                )
            }
        }
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 着色层默认给一个中性半透明黑，等 setAccentFill() 拿到专辑色再替换
        tint.setBackgroundColor(0x40000000)
        addView(tint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scrim.background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            // 常量停靠点，受单测保护（长度相等、单调、顶部压暗、结尾不透明）
            setColors(MusicScrimStops.COLORS_ONEUI, MusicScrimStops.OFFSETS_ONEUI)
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        art.cornerRadiusPx = dp(ONEUI_CARD_CORNER_DP).toFloat()
        art.sharpUntil = 1f
        art.fadeOutEnd = 1f

        // ============ "整卡"：一圈把封面 + 下方内容框住（用户要求）============
        //
        // 卡片矩形按**构造期几何**确定（不读实时布局，避免又踩初始化顺序的坑）：
        //   上/左/右 = 卡片封面外扩 cardPad
        //   下       = 封面底 + 下方内容总高（歌名/歌手/进度条/时间/控件，见 col 的间距设定）
        val cardPad = dp(12)
        val cardLeft = (screenW - cardSize) / 2
        val glowPad = dp(16)

        // ============ 两个框（用户反馈：把封面也框进去"看起来廉价"）============
        //
        // 用户要求：**只框播放控件和音乐名**，封面**不框**；
        // 封面本身**放大**并**自己发光**；框下移、**底边不碰指纹**。
        //
        // 所以：
        //   ① coverGlow —— 只画**光**（无填充无描边），围住放大的封面
        //   ② 内容卡（cardFill + cardFrame）—— 只框 [歌名/歌手/进度条/时间/控件]，
        //      top/bottom 由内容块单独算，**不再从封面顶部起算**，因此整体下移
        val coverL = cardLeft
        val coverT = cardTop
        val coverR = cardLeft + cardSize
        val coverB = cardTop + cardSize

        // 封面下方的内容块：歌名/歌手/进度条/时间/控件，总高约 178dp（见 col 里各段间距）
        val gapAfterCover = dp(24)
        val blockTop = coverB + gapAfterCover
        val blockBottom = blockTop + dp(178)

        // 内容卡矩形（外扩 cardPad）
        val cardL = coverL - cardPad
        val cardT = blockTop - cardPad
        val cardR = coverR + cardPad
        val cardB = blockBottom + cardPad

        // 发光要画到矩形外面，所以视图 bounds 比矩形大一圈 glowPad
        val frameW = (cardR - cardL) + glowPad * 2
        val frameH = (cardB - cardT) + glowPad * 2
        val frameLp = LayoutParams(frameW, frameH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = cardL - glowPad
            topMargin = cardT - glowPad
        }

        // 封面光的视图矩形（比封面大一圈 glowPad，光才画得出去）
        val coverGlowLp = LayoutParams(cardSize + glowPad * 2, cardSize + glowPad * 2).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = coverL - glowPad
            topMargin = coverT - glowPad
        }
        // 封面光：只画光 —— 没有填充、没有描边（用户要"图片周围也要发光"）
        coverGlow.cornerPx = dp(ONEUI_CARD_CORNER_DP).toFloat()
        coverGlow.glowPadPx = glowPad.toFloat()
        coverGlow.lightFromTopLeft = true
        coverGlow.drawFill = false
        coverGlow.drawBorder = false
        coverGlow.drawGlow = true
        coverGlow.drawHighlight = false
        addView(coverGlow, coverGlowLp)

        // 填充层：磨砂玻璃底（在封面/文字下面）
        cardFill.cornerPx = dp(ONEUI_CARD_CORNER_DP).toFloat()
        cardFill.glowPadPx = glowPad.toFloat()
        cardFill.drawFill = true
        cardFill.drawBorder = false
        cardFill.drawGlow = false
        addView(cardFill, frameLp)

        // 光层：边缘亮线 + 外侧环境光 + 内侧顶部高光（三者共用同一束方向光）
        cardFrame.cornerPx = dp(ONEUI_CARD_CORNER_DP).toFloat()
        cardFrame.glowPadPx = glowPad.toFloat()
        cardFrame.borderWidthPx = dp(1.6f.toInt().coerceAtLeast(1)).toFloat()
        cardFrame.lightFromTopLeft = true      // 光源在左上（iOS 惯例）
        cardFrame.drawFill = false
        cardFrame.drawBorder = true
        cardFrame.drawGlow = true
        cardFrame.drawHighlight = true         // 卡内顶部高光 → "有厚度"

        addView(art, LayoutParams(cardSize, cardSize).apply {
                // ⚠ 这里必须用 LEFT/START + 显式 leftMargin，**不能用 CENTER_HORIZONTAL**。
                //
                // 原因：`setCoverBounds()` 每帧都会写 `leftMargin`（转场要连续改边界）。
                // 而 CENTER_HORIZONTAL 的布局公式是
                //     left = (parentW - width)/2 + leftMargin - rightMargin
                // 于是 leftMargin 被**叠加在居中偏移之上**：卡片被推到偏右、
                // 右侧直接被屏幕切掉（真机截图确认）。
                //
                // 用 LEFT + leftMargin 让"我们设的边距"就是最终位置，语义唯一、不叠加。
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (screenW - cardSize) / 2
                topMargin = cardTop
            }
        )

        val col = OrderedLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 卡下留白：原来 14dp 太紧，歌名几乎贴着卡片下缘（真机截图确认）→ 24dp
        col.addView(View(context), LinearLayout.LayoutParams(1, cardTop + cardSize + dp(24)))

        title.textSize = 17f
        title.setTextColor(Color.WHITE)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END

        // ============ 歌名 + 输出设备胶囊：**同一行**，胶囊贴右（用户要求）============
        //
        // 用户原话：胶囊从"卡片左下方"移到"卡片里歌名横着对过去的右边"。
        // 所以做成一行水平容器：[歌名 weight=1（可省略号收缩）] + [胶囊]
        //
        // ⚠ 歌名必须 weight=1 + width=0（**有界**），ellipsize 才会生效；
        //   胶囊给 WRAP_CONTENT，但限制 maxWidth —— 否则设备名一长（"Galaxy Buds3 Pro
        //   (L)"之类）就会把歌名挤成两个字。折中是各占一半：歌名至少还有一半宽度。
        deviceIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        devicePill.textSize = 12f
        devicePill.setTextColor(0xCCFFFFFF.toInt())
        devicePill.includeFontPadding = false
        devicePill.maxLines = 1
        devicePill.ellipsize = TextUtils.TruncateAt.END
        devicePill.maxWidth = ((screenW - side * 2) / 2)

        // 胶囊本体：图标 + 设备名（原来胶囊底是直接画在 TextView 上的，
        // 现在要同时包住图标，所以换成一个内层 LinearLayout 承担背景与内边距）
        // ⚠ 复用字段（不是新建局部实例）：它现在是转场的静态层之一，要在 applyHero 里按 k 淡出
        //   —— 而且必须是**整个容器**，否则图标会留下来（真机 bug）
        val pillRow = this.pillRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(11), dp(4))
            background = GradientDrawable().apply {
                setColor(0x33000000)
                cornerRadius = dp(14).toFloat()
            }
        }
        // ⚠ 图标必须给固定尺寸：Glyphs 的 Drawable 没有 intrinsic size，
        //   WRAP_CONTENT 会算成 0×0（真机现象 = 图标不显示）
        pillRow.addView(deviceIcon, LinearLayout.LayoutParams(dp(15), dp(15)))
        pillRow.addView(
            devicePill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        )

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        titleRow.addView(
            pillRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        )
        col.addView(
            titleRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = side; marginEnd = side }
        )

        artist.textSize = 13f
        artist.setTextColor(0x99FFFFFF.toInt())
        artist.maxLines = 1
        artist.ellipsize = TextUtils.TruncateAt.END
        col.addView(
            artist,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = side; marginEnd = side; topMargin = dp(2) }
        )

        // 粗进度条：线宽加到 4dp、振幅归零 → 观感是"一根粗线 + 大圆手柄"
        // 上边距 27→18dp：真机截图里控制键和底部快捷方式圆圈撞上了，
        // 整列往下排的总高度超出可用空间，所以把各段间距整体收紧。
        wavy.strokePx = dp(4).toFloat()
        wavy.amplitudePx = 0f
        col.addView(
            wavy,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
                .apply { marginStart = side; marginEnd = side; topMargin = dp(10) }
        )

        timeNow.textSize = 13f
        timeNow.setTextColor(0x99FFFFFF.toInt())
        timeTotal.textSize = 13f
        timeTotal.setTextColor(0x99FFFFFF.toInt())
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(timeNow, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        times.addView(
            timeTotal,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
        col.addView(
            times,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = side; marginEnd = side; topMargin = dp(2) }
        )

        // ============ 控制行：**水平居中** ============
        //
        // ⚠ 更正：我原先按参考图实现成"靠右下角"，那是**误判** ——
        //   参考图是斜视图（等距透视），右下角是透视错觉，控件本来就在中间。
        //   用户明确要求"居中"。
        ctrl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
        }
        wireFlatIcon(btnPrev, Glyphs.PREV)
        wireFlatIcon(btnNext, Glyphs.NEXT)
        // 播放键也去掉实心圆（用户要求：①卡片封面 ②不要实心圆）
        wireFlatIcon(btnPlay, Glyphs.PLAY)
        ctrl.addView(btnPrev, LinearLayout.LayoutParams(dp(44), dp(44)))
        ctrl.addView(
            btnPlay,
            LinearLayout.LayoutParams(dp(56), dp(56))
                .apply { marginStart = dp(14); marginEnd = dp(14) }
        )
        ctrl.addView(btnNext, LinearLayout.LayoutParams(dp(44), dp(44)))

        // ============ 控制行：放在内容列里（时间下方），靠**收紧上方间距**整体上提 ============
        //
        // 曾经把它独立锚到屏幕底部（gravity=BOTTOM + bottomMargin），结果是
        // 它和"两端时间"撞在同一行（2:46 ⏮ ▶ ⏭ 4:44）—— 因为列的结束位置正好和它重合。
        //
        // 所以放回列里（自上而下：封面→歌名→歌手→进度条→时间→控制），
        // "往上移"通过减小上面几段的间距实现，位置关系始终是确定的。
        col.addView(
            ctrl,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { marginStart = side; marginEnd = side; topMargin = dp(2) }
        )

        // 描边 + 发光层：**最后**加，否则封面和文字会把边框盖住
        addView(cardFrame, frameLp)

        // ============ 覆盖层显式声明"不参与触摸" ============
        //
        // 排查"卡片式按钮点不到"时怀疑过"有层盖在按钮上吃掉了 DOWN"。结论：
        // 这三层都是**纯绘制的非 clickable View**，`View.onTouchEvent` 对非 clickable
        // 一律 `return false`，`ViewGroup` 会继续把事件交给下一个兄弟 ——
        // **它们不是元凶**（真凶是样式分支里漏掉的接线，见 `init` / `wireMediaControls`）。
        // 这里显式写出来，把"覆盖层不吃触摸"固化成不可误改的约定：
        // 以后谁给它们加 `clickable`/`onTouchEvent`，按钮的 DOWN 就会被真的吃掉。
        cardFill.isClickable = false
        cardFrame.isClickable = false
        coverGlow.isClickable = false

        // ============ mini 时钟 + 日期（同一排，参考 iOS / ColorOS）============
        //
        // 用户要求：音乐锁屏要把时钟**缩小并上移**，且**时钟与日期并排**。
        //
        // 而系统时钟是插件里的**自绘**时钟，transform 对它无效（已被真机证伪）。
        // 所以这里**自己画**：控制器负责把系统时钟和日期设为 INVISIBLE，
        // 我们用自己完全可控的 TextView 画一个"系统没有的 mini 形态"。
        //
        // 位置：与卡片左边缘对齐，放在屏幕上方（比系统时钟更高）。
        miniTime.textSize = 30f
        miniTime.setTextColor(Color.WHITE)
        miniTime.includeFontPadding = false

        miniDate.textSize = 13f
        miniDate.setTextColor(0x99FFFFFF.toInt())
        miniDate.includeFontPadding = false

        // ⚠ 复用字段（不是新建局部实例）：它现在是转场的静态层之一，要在 applyHero 里按 k 淡出
        val miniRow = this.miniRow.apply {
            orientation = LinearLayout.HORIZONTAL
            // 底对齐：让日期与时间数字的底部对齐（视觉上更稳）
            gravity = Gravity.BOTTOM
        }
        miniRow.addView(
            miniTime,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        miniRow.addView(
            miniDate,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12); bottomMargin = dp(4) }
        )
        addView(
            miniRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = cardLeft
                // 放在屏幕上方 ~7.5% 处（比系统时钟更高，"上移"的要求）
                topMargin = (screenH * 0.075f).toInt()
            }
        )

        // ============ 输出设备胶囊 —— 已在**歌名那一行**里挂好了 ============
        //
        // ⚠ 别再把它 addView 到根容器（原来的做法是卡片**左下方**）。
        // 用户要求移到"卡片里歌名横着对过去的右边"，所以它的父视图现在是
        // 上面的 `titleRow`；在这里再 addView 一次会把它从 titleRow 里**搬走**
        // （一个 View 只能有一个父），歌名那行的右侧就空了。
        //
        // 本轮仍然只做**显示**（`MediaRouter.selectedRoute` + AudioManager 兜底）；
        // 点胶囊切换设备需要 RouteInfo.select()，属于下一步。
        refreshOutputDevice()

        // ============ 通知小岛：卡片下方、系统指纹圆圈上方 ============
        //
        // ⚠ 位置是**用户选定的方案 A**：卡片整体上移（ONEUI_CARD_TOP_RATIO 0.20 → 0.13）
        //    给通知岛腾地方。真机实测底部自由带只有 ~60px
        //    （卡片下边框 ~2220、指纹圆圈 ~2280 起、手电/相机圆圈 ~2353 起），
        //    所以不挪卡片是放不下的 —— 详见 AGENTS §6。
        buildNotifIsland()
        addView(notifIsland, LayoutParams(cardSize, dp(NOTIF_ISLAND_HEIGHT_DP)).apply {
            gravity = Gravity.TOP or Gravity.START
            // 与封面同宽、同左边界（居中）
            leftMargin = (screenW - cardSize) / 2
            topMargin = cardB + dp(16)
        })

        IslandLog.i("${TAG} 已构建 One UI 样式音乐锁屏（封面卡片 " + cardSize + "px）")
    }

    /** One UI 样式里的纯图标按钮（无背景） */
    private fun wireFlatIcon(btn: ImageButton, glyph: Int) {
        btn.setBackgroundColor(Color.TRANSPARENT)
        btn.scaleType = ImageView.ScaleType.CENTER
        btn.setImageDrawable(Glyphs.create(context, glyph, Color.WHITE))
    }

    // ------------------------------------------------------------------
    // 音乐锁屏"通知小岛"
    // ------------------------------------------------------------------

    /**
     * 搭建通知小岛的视图结构（**两套样式共用**，位置由各自的 build 函数给）。
     *
     * 结构：
     *   notifIsland   ← 胶囊背景 + 内边距，**固定宽高**（轮播时宽度不能抖）
     *     └ notifContent ← 滑动的那一层（图标 + 标题），只动它的 translationX/alpha
     *         ├ notifIcon  (20dp)
     *         └ notifTitle (14sp，单行省略号，weight=1)
     *
     * ⚠ 两处容易错：
     *  · `Glyphs`/通知小图标的 Drawable 没有 intrinsic size → 图标必须给固定 dp；
     *  · 胶囊必须 `clipChildren=true`（ViewGroup 默认）才能把滑出/滑入的内容**裁在胶囊内**，
     *    否则内容会滑到胶囊外面。
     */
    private fun buildNotifIsland() {
        notifIcon.scaleType = ImageView.ScaleType.FIT_CENTER

        notifTitle.textSize = 14f
        notifTitle.setTextColor(0xE6FFFFFF.toInt())
        notifTitle.maxLines = 1
        notifTitle.ellipsize = TextUtils.TruncateAt.END
        notifTitle.includeFontPadding = false

        notifContent.orientation = LinearLayout.HORIZONTAL
        notifContent.gravity = Gravity.CENTER_VERTICAL
        notifContent.addView(notifIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        notifContent.addView(
            notifTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        )

        notifIsland.orientation = LinearLayout.HORIZONTAL
        notifIsland.gravity = Gravity.CENTER_VERTICAL
        notifIsland.setPadding(dp(14), 0, dp(16), 0)
        notifIsland.background = GradientDrawable().apply {
            setColor(0x40000000)
            cornerRadius = dp(NOTIF_ISLAND_HEIGHT_DP / 2).toFloat()
        }
        notifIsland.addView(
            notifContent,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        notifIsland.visibility = GONE
    }

    /**
     * 灌入要轮播的通知（由 `IslandController` 每 ~4s 调一次，主线程）。
     *
     * 签名（标题串）不变就**直接返回** —— 否则每 4s 一次的数据刷新会把轮播重置到第一条，
     * 用户永远只看到第一条（真机很容易忽略的一个坑）。
     */
    fun setNotifications(items: List<NotifItem>) {
        // 样式 0（铺满封面）没有通知小岛 —— 视图没进树就什么都别做，
        // 否则日志会谎报"轮播 N 条"（见 buildLayout 末尾的说明）。
        if (notifIsland.parent == null) return

        val sig = items.joinToString("\u0001") { it.title }
        if (sig == notifSignature) return
        notifSignature = sig
        notifItems = items.take(NOTIF_MAX_ITEMS)
        notifIndex = 0

        if (notifItems.isEmpty()) {
            notifIsland.visibility = GONE
            removeCallbacks(notifTick)
            IslandLog.i("$TAG 通知小岛：无通知（隐藏）")
            return
        }

        bindNotif()
        notifIsland.visibility = VISIBLE
        // 只有一条就不轮播（省电，也避免"同一条反复滑进滑出"的怪观感）
        removeCallbacks(notifTick)
        if (notifItems.size > 1 && isAttachedToWindow) {
            postDelayed(notifTick, NOTIF_INTERVAL_MS)
        }
        IslandLog.i(
            "$TAG 通知小岛：${notifItems.size} 条 -> " +
                notifItems.joinToString(" / ") { it.title.take(14).toString() }
        )
    }

    /** 把当前 [notifIndex] 那条绑到视图上 */
    private fun bindNotif() {
        val item = notifItems.getOrNull(notifIndex) ?: return
        notifTitle.text = item.title
        if (item.icon != null) {
            notifIcon.setImageDrawable(item.icon)
            // 通知小图标基本是**单色蒙版**（由 SystemUI 统一染色），
            // 这里染白保证它在深色胶囊上可读
            notifIcon.setColorFilter(Color.WHITE)
        } else {
            notifIcon.setImageDrawable(Glyphs.create(context, Glyphs.MUSIC, 0xE6FFFFFF.toInt()))
            notifIcon.setColorFilter(null)
        }
    }

    /**
     * 换到下一条：**滑动**切换（用户指定）。
     *
     * 旧内容向左滑出 + 淡出 → 换绑数据 → 从右侧滑入 + 淡入。
     * 全部是**纯渲染变换**（translationX/alpha），不写 layoutParams（见 AGENTS §4）。
     */
    private fun advanceNotif() {
        if (notifItems.size < 2 || !isAttachedToWindow) return
        notifIndex = (notifIndex + 1) % notifItems.size

        val w = notifContent.width.takeIf { it > 0 } ?: dp(180)
        notifContent.animate().cancel()
        notifContent.animate()
            .translationX(-w * 0.35f)
            .alpha(0f)
            .setDuration(NOTIF_SLIDE_OUT_MS)
            .withEndAction {
                // 动画结束时视图可能已经被移除（退出音乐锁屏）—— 再兜一次
                if (!isAttachedToWindow) return@withEndAction
                bindNotif()
                notifContent.translationX = w * 0.35f
                notifContent.alpha = 0f
                notifContent.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(NOTIF_SLIDE_IN_MS)
                    .start()
            }
            .start()
    }

    /**
     * 把根容器里的播放键对齐到控制行中心。
     *
     * 播放键是根容器的最后一个子视图（保证层级最高），所以它的位置不再由
     * 控制行自动决定，需要手动跟随。`ctrl` 的 left/top 是相对 `col` 的，
     * 而 `col` 在根容器里位于 (0,0)，所以两者坐标系一致，可直接赋值 x/y。
     */
    private fun syncPlayPosition() {
        if (ctrl.height == 0 || btnPlay.height == 0) return
        val lp = btnPlay.layoutParams as? LayoutParams ?: return
        val newTop = (ctrl.top + ctrl.height / 2f - btnPlay.height / 2f).toInt()
        val newGravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        if (lp.topMargin == newTop && lp.gravity == newGravity) return
        lp.gravity = newGravity
        lp.topMargin = newTop
        btnPlay.layoutParams = lp
        // 位置算准后**等这一轮布局跑完**再显示 —— 只设 layoutParams 不会立刻改变
        // left/top，直接置 alpha=1 的话仍会在 (0,0) 露出首帧（真机反馈）。
        btnPlay.post { if (btnPlay.alpha != 1f) btnPlay.alpha = 1f }
    }

    /**
     * 纵向容器，**只改变绘制顺序**：把 [drawLast] 指定的子视图放到最后绘制。
     *
     * ============ 为什么需要它 ============
     *
     * LinearLayout 的子视图顺序同时决定布局顺序与绘制顺序。所以
     * `bringChildToFront()` 对 LinearLayout 用会把控件**挪位置** ——
     * 真机上我为了让"播放键压住波浪"调了它，结果控制行被挪到波浪下面，
     * 按钮跑到了进度条下方（用户截图确认）。
     *
     * 这个子类通过 ViewGroup 的 `setChildrenDrawingOrderEnabled` +
     * `getChildDrawingOrder` 只重排绘制次序：布局位置完全不变，
     * 但 ctrl 会最后绘制 → 播放键压在波浪之上，与中岛层级一致。
     */
    private class OrderedLinearLayout(context: Context) : LinearLayout(context) {

        var drawLast: View? = null

        init {
            setChildrenDrawingOrderEnabled(true)
        }

        override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
            val last = drawLast ?: return drawingPosition
            val lastIndex = indexOfChild(last)
            if (lastIndex < 0) return drawingPosition
            return when {
                drawingPosition == childCount - 1 -> lastIndex
                drawingPosition >= lastIndex -> drawingPosition + 1
                else -> drawingPosition
            }
        }
    }
}
