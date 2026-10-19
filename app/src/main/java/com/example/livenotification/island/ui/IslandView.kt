package com.example.livenotification.island.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.livenotification.island.util.AlbumColor
import com.example.livenotification.island.util.IslandLog
import kotlin.math.abs
import kotlin.math.min

/**
 * 锁屏岛本体。
 *
 * 两个形态（MD3E 的 shape morph）：
 *  - 收起：圆角胶囊，一行 —— 状态点 + 标题 + 歌手 + 律动条
 *  - 展开：大圆角卡片，封面 + 标题/歌手 + 进度 + 上一首/播放暂停/下一首
 *
 * 绘制完全自绘（GradientDrawable/Paint），不引用 SystemUI 任何资源，
 * 这样换 ROM 版本不会被资源 id 变动搞崩。
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : FrameLayout(context), View.OnClickListener {

    // ---------- 子视图 ----------
    private val content = LinearLayout(context)

    /** 收起态左侧圆点/状态指示 */
    private val dot = View(context)

    /** 收起态左侧状态图标（充电/耳机等） */
    private val chipIcon = ImageView(context)

    /**
     * 中岛的**专辑封面背景**（铺满整张卡片）。
     * 展开态不再用「左侧小封面 + 右侧信息」的排布，而是封面整张铺底。
     */
    private val cover = ImageView(context)

    /** 收起态小封面（有封面时才显示） */
    private val compactCover = ImageView(context)

    private val title = TextView(context)
    private val subtitle = TextView(context)

    /** 收起态：3 根律动条 */
    private val bars = EqualizerBars(context)

    /** 小岛右侧的圆形播放/暂停键（取代原来的频谱条） */
    private val compactPlay = RoundedIconButton(context)

    /** 小岛的上一首 / 下一首（参考图样式：**白底圆 + 深色图标**） */
    private val compactPrev = RoundedIconButton(context)
    private val compactNext = RoundedIconButton(context)

    /**
     * 小岛按钮的强调色来源：**专辑封面**优先，取不到才回退壁纸莫奈色。
     *
     * 用户要求按钮色跟封面走（三星锁屏媒体控件也是这么做的）——
     * 壁纸色和当前封面无关，碰上撞色时按钮会糊在封面里看不出来。
     */
    private var albumAccent: Int? = null

    /** 无背景的纯图标按钮（供小岛的上一首/下一首使用） */
    private fun wireFlatIcon(btn: ImageButton, glyph: Int, id: Int) {
        btn.id = id
        btn.setOnClickListener(this)
        btn.setBackgroundColor(Color.TRANSPARENT)
        btn.scaleType = ImageView.ScaleType.CENTER
        btn.setImageDrawable(Glyphs.create(context, glyph, Color.WHITE))
    }

    /** 小岛按钮的强调色：专辑封面优先 → 壁纸莫奈色 → 半透明白兜底 */
    private fun islandAccent(): Int =
        albumAccent ?: monetContainerColor ?: 0x33FFFFFF

    /**
     * 小岛**背景**色：专辑封面取色优先（用户要求："小岛的背景也要莫奈取色专辑封面"），
     * 取不到才回退系统快捷方式底色。
     *
     * 背景一改，前景（文字/图标）的对比色必须跟着重算 ——
     * 否则浅色背景上白字会看不清（这个坑之前踩过一次）。
     */
    private fun applyIslandBackground() {
        // ⚠ 背景**不能**直接用强调色：那样播放键的实心圆会和背景同色而"消失"
        // （真机截图确认）。背景走降饱和压暗的"面色"，按钮保留鲜艳强调色，层次才分得开。
        val accent = albumAccent
        val c = if (accent != null) {
            AlbumColor.surface(accent)
        } else {
            shortcutBgColor
                ?: com.example.livenotification.island.config.IslandConfig.DEFAULT_SCRIM_COLOR
        }
        backgroundPaint.color = c
        applyForegroundColor(autoContrastOn(c))
        invalidate()
    }

    /**
     * 给小岛三个圆按钮上色（参考图样式）。
     *
     *   · 上一首/下一首 = **白底圆 + 强调色图标**
     *   · 播放 = **强调色实心圆 + 白色图标**
     *
     * 每次封面变化、播放状态变化、壁纸取色刷新时都要调用 —— 集中一处避免三处漂移。
     */
    private fun applyCompactButtonColors() {
        val c = islandAccent()

        // 播放键：实心强调色圆 + 白图标（图标随播放状态切换）
        compactPlay.configure(
            fillColor = c,
            cornerPx = dp(COMPACT_PLAY_DP / 2)      // 半径 = 边长一半 → 正圆
        )
        compactPlay.setGlyph(
            if (compactPlaying) GLYPH_PAUSE else GLYPH_PLAY,
            Color.WHITE
        )

        // 上/下一首：白底圆 + 强调色图标
        compactPrev.configure(
            fillColor = Color.WHITE,
            cornerPx = dp(COMPACT_SIDE_DP / 2)
        )
        compactPrev.setGlyph(GLYPH_PREV, c)
        compactNext.configure(
            fillColor = Color.WHITE,
            cornerPx = dp(COMPACT_SIDE_DP / 2)
        )
        compactNext.setGlyph(GLYPH_NEXT, c)

        // ============ 中岛的播放键也用同一套"专辑莫奈取色"（用户要求）============
        //
        // 之前中岛播放键的颜色在 configureButton 里写死用 `monetContainerColor`
        // （壁纸莫奈色），**不跟专辑封面**；而小岛的按钮是跟专辑取色的 —— 两者不一致。
        //
        // 这里用同一个 `c`（islandAccent：**专辑强调色 -> 壁纸莫奈色 -> 半透明白**的回退链）
        // 给中岛播放键上色，圆角仍沿用 PLAY_BUTTON_DP/2（正圆），
        // 与音乐锁屏的播放键保持一致 —— 转场时按钮才不会"变形"。
        btnPlay.configure(fillColor = c, cornerPx = dp(PLAY_BUTTON_DP / 2))
        btnPlay.setGlyph(
            if (compactPlaying) GLYPH_PAUSE else GLYPH_PLAY,
            Color.WHITE
        )
    }

    /** 播放状态（供 applyCompactButtonColors 决定播放键图标） */
    private var compactPlaying = false

    // ---------- 中岛（展开态）----------
    /** 封面之上的渐变遮罩，保证左下角文字在亮色封面上也可读 */
    private val artScrim = View(context)

    /** 左下角：歌名 */
    private val titleExpanded = TextView(context)

    /** 左下角：歌手 */
    private val artistExpanded = TextView(context)

    /** 底部：上一首 | 波浪进度 | 下一首 */
    private val progressRow = LinearLayout(context)
    private val wavy = WavyProgressBar(context)

    /**
     * 上一首 / 下一首：**无背景的纯图标**（参考系统媒体控件）。
     * 不再用圆角矩形按钮 —— 设计上它们是次要操作，视觉上不该有块面。
     */
    private val btnPrev = ImageButton(context)
    private val btnNext = ImageButton(context)

    /** 右侧中部：播放/暂停（有背景，颜色跟随莫奈取色） */
    private val btnPlay = RoundedIconButton(context)

    /** 中岛卡片本体（FrameLayout，便于叠放封面/遮罩/文字/控件） */
    private val expandedContent = FrameLayout(context)

    // ---------- 绘制 ----------
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DEFAULT_SCRIM
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HAIRLINE
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    private var cornerRadius = 0f

    /**
     * 展开态（卡片）的圆角。压缩时要把它插值到"高度/2"（胶囊）。
     *
     * ============ 形状插值 —— 消除"卡片轮廓 vs 胶囊轮廓"的最后一块 ============
     *
     * 用户早就指出："中岛在被压缩过程中**形状没有变成小岛一样的形状**，最终会跳一下。"
     *
     * 关键洞察：`cornerRadius` 的**收起值本来就是 `高度/2`（胶囊）**，
     * 而压缩是靠 `scaleY` 整个缩放 —— 圆角会被**同比缩放**，
     * 所以只要在压缩过程中把圆角插值到 `height/2`，
     * p=1 时的**视觉圆角 = 压缩后高度的一半 = 正是小岛的胶囊圆角** ✓
     *
     * 于是"2/3 处换布局"那一刻，形状已经一致 —— 交接几乎看不出来。
     */
    private var cardCornerRadius = 0f
    private var progress = 0f

    /** 收起/展开两态的目标高度，由 [applyStyle] 写入 */
    @Volatile
    private var collapsedHeight = 0

    @Volatile
    private var expandedHeight = 0

    @Volatile
    private var expanded = false

    private var seekListener: SeekBar.OnSeekBarChangeListener? = null

    init {
        id = VIEW_ID
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // 与 onDraw 用同一个圆角来源（快捷方式的 24dp 资源），
                // 否则裁剪轮廓和实际绘制会对不上，圆角处会出现硬边。
                val r = (shortcutRadiusPx?.toFloat() ?: (view.height / 2f))
                    .coerceAtMost(view.height / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        setWillNotDraw(false)

        buildCollapsed()
        buildExpanded()
        // state 的初值由字段默认值给出（STATE_IDLE），不在这里赋值：
        // 自定义 setter 会碰尚未初始化的子视图。
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    private fun buildCollapsed() {
        content.orientation = LinearLayout.HORIZONTAL
        content.gravity = Gravity.CENTER_VERTICAL
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.CENTER_VERTICAL
        addView(content, lp)

        dot.setBackgroundColor(ACCENT)
        val d = dp(8)
        val dotLp = LinearLayout.LayoutParams(d, d).apply { marginEnd = dp(12) }
        content.addView(dot, dotLp)

        chipIcon.visibility = GONE
        chipIcon.scaleType = ImageView.ScaleType.FIT_CENTER
        content.addView(
            chipIcon,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        )

        // 收起态的小封面：媒体播放时显示，让胶囊一眼可辨是"正在播放什么"
        compactCover.visibility = GONE
        compactCover.scaleType = ImageView.ScaleType.CENTER_CROP
        content.addView(
            compactCover,
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
        )

        title.setTextColor(Color.WHITE)
        title.textSize = 12f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        content.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        subtitle.setTextColor(SECONDARY)
        subtitle.textSize = 11f
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )

        // 频谱条保留但不再显示（用户要求把这一块换成圆形按钮）
        bars.setVisibility(GONE)
        content.addView(bars, LinearLayout.LayoutParams(0, 0))

        // ============ 小岛右侧：三个圆形按钮（参考图样式）============
        //
        // 参考图（用户提供）：
        //   ⏮ 白底圆 + 深色图标
        //   ▶ **实心圆**（强调色）+ 白色图标
        //   ⏭ 白底圆 + 深色图标
        //
        // 强调色来自**专辑封面**（`albumAccent`），取不到才回退壁纸莫奈色。
        // 尺寸：播放 40dp（视觉中心），上/下首 34dp（略小，但仍成组）。
        compactPrev.id = BTN_PREV_ID
        compactPrev.setOnClickListener(this)
        content.addView(
            compactPrev,
            LinearLayout.LayoutParams(dp(COMPACT_SIDE_DP), dp(COMPACT_SIDE_DP))
        )

        compactPlay.id = BTN_PLAY_ID
        compactPlay.setOnClickListener(this)
        content.addView(
            compactPlay,
            LinearLayout.LayoutParams(dp(COMPACT_PLAY_DP), dp(COMPACT_PLAY_DP))
                .apply { marginStart = dp(4); marginEnd = dp(4) }
        )

        compactNext.id = BTN_NEXT_ID
        compactNext.setOnClickListener(this)
        content.addView(
            compactNext,
            LinearLayout.LayoutParams(dp(COMPACT_SIDE_DP), dp(COMPACT_SIDE_DP))
        )

        // 建完就立刻上色（封面可能还没到，那时用莫奈色兜底）
        applyCompactButtonColors()
    }

    /**
     * 构造中岛（展开态）—— 卡片式设计。
     *
     * ============ 布局（按设计稿） ============
     *
     *   ┌────────────────────────────────────┐
     *   │  专辑封面铺满整张卡片              │
     *   │                          ┌────┐    │
     *   │                          │ ▶/⏸ │    │ ← 右侧中部
     *   │                          └────┘    │
     *   │  歌名                              │ ← 左下
     *   │  歌手                              │
     *   │  [⏮]  ∿∿∿∿∿∿∿∿∿∿∿∿  [⏭]         │ ← 底部：上一首｜波浪进度｜下一首
     *   └────────────────────────────────────┘
     *
     * 卡片是 FrameLayout（叠放：封面 → 渐变遮罩 → 文字 → 控件）；
     * 前景放 RippleDrawable，配合 dispatchTouchEvent 里的 hotspot 设置，
     * 让水波纹从触点**扩散到整张卡片**（设计要求的"整面泛开"效果）。
     */
    private fun buildExpanded() {
        expandedContent.visibility = GONE
        // 注意：卡片**不再**响应点击。设计上"中岛变回小岛"只由
        // 「按住向下拖」触发（避免误触），所以这里不设 OnClickListener，
        // 点击空白处什么也不做。
        addView(
            expandedContent,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // 1) 专辑封面铺满
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        expandedContent.addView(
            cover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2) 渐变遮罩：封面可能是任何亮度的图，左下角文字必须有稳定的可读性。
        //    用带 offset 的多段渐变，让下半部分快速变暗（而不是整幅均匀压暗）：
        //      0%   完全透明（保留封面观感）
        //      45%  约 25% 黑
        //      100% 约 88% 黑（文字与控件所在区域）
        //    setColors(colors, offsets) 需要 API 29+，本项目 minSdk 33，安全。
        artScrim.background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(
                intArrayOf(0x00000000, 0x40000000, 0xE0000000.toInt()),
                floatArrayOf(0f, 0.45f, 1f)
            )
        }
        expandedContent.addView(
            artScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 3) 左下：歌名 + 歌手
        titleExpanded.apply {
            textSize = 22f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        artistExpanded.apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xE6FFFFFF.toInt())
            setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
        }
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textBlock.addView(titleExpanded)
        textBlock.addView(artistExpanded)
        expandedContent.addView(
            textBlock,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = dp(CARD_PADDING_DP)
                marginEnd = dp(CARD_PADDING_DP)
                // 让出底部进度行的位置
                bottomMargin = dp(CARD_PADDING_DP + PROGRESS_ROW_DP + 6)
            }
        )

        // 4) 底部：上一首 | 波浪进度 | 下一首
        progressRow.orientation = LinearLayout.HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL

        configureButton(btnPrev, GLYPH_PREV, BTN_PREV_ID)
        configureButton(btnNext, GLYPH_NEXT, BTN_NEXT_ID)
        progressRow.addView(
            btnPrev,
            LinearLayout.LayoutParams(dp(NAV_TOUCH_DP), dp(NAV_TOUCH_DP))
        )
        // 波浪进度条：吃掉中间剩余宽度
        progressRow.addView(
            wavy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = dp(6); marginEnd = dp(6) }
        )
        progressRow.addView(
            btnNext,
            LinearLayout.LayoutParams(dp(NAV_TOUCH_DP), dp(NAV_TOUCH_DP))
        )
        expandedContent.addView(
            progressRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(PROGRESS_ROW_DP)
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(CARD_PADDING_DP)
                marginEnd = dp(CARD_PADDING_DP)
                bottomMargin = dp(CARD_PADDING_DP)
            }
        )

        // 5) 右侧中部：播放 / 暂停
        //
        // bottomMargin 决定它在卡片里的垂直位置（FrameLayout 会在这个留白之上居中）：
        //   · 越大 → 按钮越靠上；越小 → 越靠下。
        // 底线是**底边不得压到进度行顶部**：
        //   卡片 665px、进度行顶 = 665 − 70 − 168 = 427px，按钮高 224px
        //   bottomMargin = 0 时中心 = 332.5 → 底边 444.5 **会重叠**
        //   bottomMargin = 63px(18dp) 时中心 = 301 → 底边 413，留 14px ✓
        // 所以取 18dp：这是"能往下放到的最低安全位置"再留一点余量。
        configureButton(btnPlay, GLYPH_PLAY, BTN_PLAY_ID)
        expandedContent.addView(
            btnPlay,
            FrameLayout.LayoutParams(dp(PLAY_BUTTON_DP), dp(PLAY_BUTTON_DP)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(CARD_PADDING_DP)
                bottomMargin = dp(PLAY_BUTTON_BOTTOM_MARGIN_DP)
            }
        )
        // 强制把播放键提到卡片最上层。
        //
        // 真机反馈"按钮的上半部分被遮住了"——不管具体是被封面/遮罩/进度行中的哪一个压住，
        // 只要它不在最后一个绘制位就可能在转场的压叠瞬间被盖住。
        // 这里显式置顶，等于把"播放键永远可见"变成结构上的保证，而不是依赖添加顺序。
        expandedContent.bringChildToFront(btnPlay)

        // 6) 整张卡片的前景水波纹
        //    mask 传 null → 波纹以视图自身边界为范围，也就是整张卡片。
        expandedContent.foreground = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            null,
            null
        )

        // 进度条 seek 回调：转发给控制器（比例 0..1）
        wavy.onSeekStart = { onSeekStart?.invoke() }
        wavy.onSeekMove = { f -> onSeekMove?.invoke(f) }
        wavy.onSeekEnd = { f -> onSeekEnd?.invoke(f) }
    }

    /**
     * 中岛的按钮着色。
     *
     * · 播放/暂停：有背景（RoundedIconButton），填充色来自**莫奈取色**
     *   （解析不到时退化为半透明白）。
     * · 上一首 / 下一首：**无背景**，只有图标 —— 参考系统媒体控件的做法，
     *   次要操作不该有块面。触摸热区仍给足尺寸，但图标本身较小。
     */
    private fun configureButton(btn: View, glyph: Int, id: Int) {
        btn.id = id
        btn.setOnClickListener(this)
        when (btn) {
            is RoundedIconButton -> {
                // 莫奈容器色（已解析时用它；否则半透明白）
                val fill = monetContainerColor ?: 0x33FFFFFF
                // 形状跟随音乐锁屏的播放键：**正圆**（半径 = 边长一半）。
                // 两个界面保持一致，转场时按钮才不会"变形"。
                btn.configure(fillColor = fill, cornerPx = dp(PLAY_BUTTON_DP / 2))
                btn.setGlyph(glyph, buttonGlyphColor)
            }

            is ImageButton -> {
                // 无背景，图标压在封面上 → 用白色 + 阴影保证在浅色封面也可读
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setImageDrawable(Glyphs.create(context, glyph, Color.WHITE))
                btn.scaleType = ImageView.ScaleType.CENTER
                // 热区给足（48dp），但图标本身较小（内边距把它压到 ~26dp）
                btn.setPadding(
                    dp(NAV_ICON_PADDING_DP), dp(NAV_ICON_PADDING_DP),
                    dp(NAV_ICON_PADDING_DP), dp(NAV_ICON_PADDING_DP)
                )
            }
        }
    }

    /** 解析到的莫奈容器色（null 表示解析失败，用兜底色） */
    @Volatile
    private var monetContainerColor: Int? = null

    /**
     * 中岛各元素在**窗口坐标系**里的矩形，供"一镜到底"转场使用。
     *
     * 中岛 → 音乐锁屏时，音乐锁屏的封面/歌名/歌手/进度条/按钮分别从
     * 这里返回的对应矩形"移动"到自己在音乐锁屏里的位置，所以需要拿到
     * 中岛里每个元素真实的上屏位置（不能用本视图内的坐标）。
     *
     * 注意：中岛展开时封面自己可能带 scale/translation（小岛→中岛的转场动画），
     * 但进入音乐锁屏是在中岛已经稳定的状态下触发的，此时封面已回到 identity，
     * getLocationInWindow 给出的就是它真实的位置。
     */
    fun expandedElementRects(): Map<String, android.graphics.Rect> {
        val out = HashMap<String, android.graphics.Rect>()

        fun put(key: String, v: View) {
            if (v.width <= 0 || v.height <= 0) return
            val loc = IntArray(2)
            v.getLocationInWindow(loc)
            out[key] = android.graphics.Rect(
                loc[0], loc[1], loc[0] + v.width, loc[1] + v.height
            )
        }

        put("art", cover)
        put("title", titleExpanded)
        put("artist", artistExpanded)
        put("progress", wavy)
        put("play", btnPlay)
        put("prev", btnPrev)
        put("next", btnNext)
        return out
    }

    /** 按钮图标颜色：白（俯在专辑封面上，需与文字一致的固定白） */
    private val buttonGlyphColor: Int get() = Color.WHITE

    /**
     * 展开态布局诊断（保持轻量：新布局是叠放式，只需确认尺寸与关键元素）。
     */
    fun logExpandedLayout() {
        // 播放键与进度行的几何关系：用来确认"按钮已下移"且"没有压到进度行"
        val playBottom = btnPlay.bottom
        val progressTop = progressRow.top
        val overlap = playBottom > progressTop

        IslandLog.i(
            "中岛布局诊断: 岛=${width}x$height 位置=($left,$top)-($right,$bottom) " +
                "isShown=$isShown vis=$visibility 父=${parent?.javaClass?.simpleName} " +
                "卡片=${expandedContent.width}x${expandedContent.height} " +
                "封面=${cover.width}x${cover.height} " +
                "标题=${titleExpanded.width}x${titleExpanded.height} " +
                "进度行=${progressRow.width} 波浪=${wavy.width} " +
                "上一首=${btnPrev.width} 下一首=${btnNext.width} " +
                "播放=${btnPlay.width} 播放底边=$playBottom 进度行顶=$progressTop " +
                (if (overlap) "❌与进度行重叠" else "✅未重叠") + " " +
                "★播放按钮右边界=${btnPlay.right + dp(CARD_PADDING_DP)}(卡片宽=$width) " +
                (if (btnPlay.right + dp(CARD_PADDING_DP) > width) "❌溢出 " else "✅未溢出 ") +
                "进度=${(wavy.getProgressFraction() * 100).toInt()}% " +
                "波形=${if (wavy.isWavy()) "波浪" else "平整"} " +
                "alpha=${expandedContent.alpha}"
        )
    }


    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /** 状态：0=空闲（无媒体无状态），1=媒体，2=系统状态 */
    @Volatile
    var state: Int = STATE_IDLE
        set(value) {
            if (field == value) return
            field = value
            applyState()
        }

    private fun applyState() {
        when (state) {
            STATE_MEDIA -> {
                dot.visibility = View.GONE
                chipIcon.visibility = View.GONE
                // 展开/收起两行是同一个 FrameLayout 的兄弟，必须互斥显示。
                // 之前两个都保持 VISIBLE，导致展开时收起态的文字画在封面上面
                // （由真机实例坐标报告定位：收起行 [0,112]-[892,170] 与封面
                //   [49,26]-[269,246] 的 x 区间重叠）。
                content.visibility = if (expanded) View.GONE else View.VISIBLE
                expandedContent.visibility = if (expanded) View.VISIBLE else View.GONE
                bars.visibility = if (expanded) View.GONE else View.VISIBLE
                compactCover.visibility =
                    if (compactCover.drawable != null) View.VISIBLE else View.GONE
            }

            STATE_STATUS -> {
                dot.visibility = View.GONE
                chipIcon.visibility = View.VISIBLE
                content.visibility = View.VISIBLE
                expandedContent.visibility = GONE
                bars.visibility = GONE
                compactCover.visibility = GONE
                expanded = false
            }

            else -> {
                dot.visibility = View.VISIBLE
                chipIcon.visibility = View.GONE
                content.visibility = View.VISIBLE
                expandedContent.visibility = GONE
                bars.visibility = GONE
                compactCover.visibility = GONE
                expanded = false
            }
        }
        requestLayout()
    }

    /** 设置收起态左侧图标（充电/耳机等）。glyph=0 表示不用图标。 */
    fun setChipGlyph(glyph: Int, color: Int = foregroundColor) {
        currentChipGlyph = glyph
        if (glyph <= 0) {
            chipIcon.visibility = GONE
            return
        }
        chipIcon.setImageDrawable(Glyphs.create(context, glyph, color))
        if (state == STATE_STATUS) chipIcon.visibility = VISIBLE
    }

    fun setExpanded(value: Boolean) {
        if (value == expanded) return
        expanded = value
        val toExpanded = value && state == STATE_MEDIA

        // 诊断：展开时打描边，便于在真机上确认视图边界
        debugOutline = toExpanded && com.example.livenotification.island.config.IslandConfig.DEBUG_OUTLINE

        if (state == STATE_MEDIA) {
            // 收起/展开两行必须互斥：同时 VISIBLE 会让收起态的标题
            // 画在展开态封面之上（真机定位到的重叠问题）。
            content.visibility = if (toExpanded) View.GONE else View.VISIBLE
            expandedContent.visibility = if (toExpanded) View.VISIBLE else View.GONE
            bars.visibility = if (toExpanded) View.GONE else View.VISIBLE
        } else {
            content.visibility = View.VISIBLE
            expandedContent.visibility = View.GONE
        }

        // ============ 高度不再由本视图自己动画（重要修正） ============
        //
        // 原来这里调 animateHeightTo()，它会持续改写 layoutParams.height。
        // 而 IslandController.applyConstraints() 也在写同一个字段 ——
        // 两者互相覆盖，真机表现是「预计top=1870 实际top=2053」：
        // 高度变了但父容器按旧高度解算，位置纹丝不动，展开视觉上无效。
        //
        // 现在高度唯一归属 IslandController（它同时管 bias 与约束），
        // 本视图只负责内容切换与淡入。控制器在该变化后驱动一次布局。
        content.alpha = 1f
        if (toExpanded) {
            expandedContent.alpha = 0f
            expandedContent.animate().alpha(1f).setDuration(180L).start()
            bars.animate().alpha(0f).setDuration(120L).start()
            // 封面从小图"长大"铺满中岛
            animateCoverHero(expand = true)
        } else {
            expandedContent.alpha = 1f
            bars.alpha = 1f
            animateCoverHero(expand = false)
        }
    }

    // ------------------------------------------------------------------
    // 封面转场动画（小图 → 铺满中岛）
    // ------------------------------------------------------------------

    private var heroAnimator: ValueAnimator? = null

    /**
     * 卡片背景是否跟随封面边界（小岛→中岛转场期间为 true）。
     * 避免封面长大过程中露出一圈浅色"白边"（真机反馈）。
     */
    private var backgroundFollowsCover = false

    /**
     * 让中岛的封面**从收起态的小封面位置逐步放大**到铺满整张卡片。
     *
     * ============ 为什么逐帧重算缩放 ============
     *
     * 卡片高度在同一段时间里由控制器从 196px 动画到 665px，
     * 而封面是 MATCH_PARENT，所以它自身的高度也在变。
     * 如果只按固定比例缩放，前几帧会明显偏小/偏大。
     * 因此每帧都用**当前**高度重算起始缩放，再按进度插值到 1。
     *
     * 起点取的是收起态小封面（compactCover）在岛坐标系里的矩形；
     * 终点是铺满卡片（scale=1、位移=0）。
     */
    private fun animateCoverHero(expand: Boolean) {
        heroAnimator?.cancel()
        val cardW = width.toFloat()
        if (cardW <= 0f) return

        // 小封面相对本视图的矩形
        val thumb = IntArray(2)
        compactCover.getLocationInWindow(thumb)
        val mine = IntArray(2)
        getLocationInWindow(mine)
        val thumbLeft = (thumb[0] - mine[0]).toFloat()
        val thumbTop = (thumb[1] - mine[1]).toFloat()
        val thumbW = compactCover.width.toFloat()
        val thumbH = compactCover.height.toFloat()
        if (thumbW <= 0f || thumbH <= 0f) return

        cover.pivotX = 0f
        cover.pivotY = 0f

        val from = if (expand) 0f else 1f
        val to = if (expand) 1f else 0f
        // 转场期间让卡片背景跟随封面边界（消除"白边"），结束后恢复整卡背景
        backgroundFollowsCover = true
        invalidate()
        heroAnimator = ValueAnimator.ofFloat(from, to).apply {
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    backgroundFollowsCover = false
                    invalidate()
                }
            })
            // 320 -> **240ms**（"松散"整改 A）：这是"背景跟随封面"的形变，
            // 属于同一次 morph 的一部分，必须跟着主 morph（220ms）一起收住，
            // 否则会出现"高度已经停住、背景还在慢慢变形"的拖尾感。
            // 下一轮做分层时序（C）时，这里会改为"比容器晚 ~20ms 起步"。
            duration = 240L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                // 当前卡片高度（随控制器的高度动画在变）
                val curH = height.toFloat().coerceAtLeast(1f)
                val sx = thumbW / cardW
                val sy = thumbH / curH
                cover.scaleX = sx + (1f - sx) * t
                cover.scaleY = sy + (1f - sy) * t
                cover.translationX = thumbLeft * (1f - t)
                cover.translationY = thumbTop * (1f - t)
            }
            start()
        }
    }

    /**
     * 高度变化的回调。控制器设置它，以便在形态切换后
     * 重新计算底部锚点并重新施加约束。
     */
    var onHeightChangeRequest: (() -> Unit)? = null

    // ============ animateHeightTo 已删除（真机事故） ============
    //
    // 它原本用 ValueAnimator 连续改写 layoutParams.height 来实现形态过渡动画，
    // 但 IslandController.applyConstraints() 也在写同一个字段，两者互相覆盖：
    // 真机日志「预计top=1870 实际top=2053」—— 高度变了、父容器却按旧高度解算，
    // 展开视觉上完全无效。
    //
    // 现在高度的唯一归属方是 IslandController，它同时负责 bias 与约束重解。
    // 平滑动画以后要在控制器那一层做（改高度 + 重解约束一起插值），
    // 而不是在视图内部单独动 layoutParams。

    fun isExpanded(): Boolean = expanded

    /** 收起态文字 */
    fun setCollapsedText(main: String, secondary: String?) {
        title.text = main
        subtitle.text = secondary.orEmpty()
        subtitle.visibility = if (secondary.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** 展开态文字（左下角：歌名 + 歌手） */
    fun setExpandedText(main: String, secondary: String?) {
        titleExpanded.text = main
        artistExpanded.text = secondary.orEmpty()
        artistExpanded.visibility = if (secondary.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setCover(bitmap: android.graphics.Bitmap?) {
        if (bitmap == null) {
            // 中岛背景没有封面时用一个中性深色渐变，而不是留白 ——
            // 否则白字会直接压在壁纸上，几乎不可读。
            cover.setImageDrawable(null)
            cover.scaleType = ImageView.ScaleType.CENTER
            cover.setBackgroundColor(0xFF2A2830.toInt())
            // 收起态没有封面就不显示小封面，避免出现一个空方块
            compactCover.visibility = GONE
        } else {
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.setBackgroundColor(Color.TRANSPARENT)
            cover.setImageBitmap(bitmap)
            compactCover.setImageBitmap(bitmap)
            // ============ 小岛的强调色改从**专辑封面**取（用户要求）============
            // 之前用壁纸的莫奈色 —— 它和当前播放的封面无关，撞色时按钮会糊在封面里。
            // 这里现算：封面缩到 24×24 后打分取色，开销很小（只在封面变化时做一次）。
            albumAccent = AlbumColor.accent(bitmap)
            applyCompactButtonColors()
            // 背景也改用专辑封面取色（用户要求）—— 并重算前景对比色
            applyIslandBackground()
            if (state == STATE_MEDIA) compactCover.visibility = View.VISIBLE
        }
    }

    fun setPlaying(playing: Boolean) {
        // 小岛右侧的圆形按钮同步播放状态（图标 + 颜色统一由 applyCompactButtonColors 处理）
        compactPlaying = playing
        applyCompactButtonColors()
        bars.setAnimating(playing)
        // 中岛的播放按钮与底部波浪一起反映播放状态
        btnPlay.setGlyph(if (playing) GLYPH_PAUSE else GLYPH_PLAY, Color.WHITE)
        wavy.setAnimating(playing)
    }
    /** 当前前景色（文字与图标统一用它，随系统主题变化） */
    @Volatile
    private var foregroundColor: Int = 0xFFFFFFFF.toInt()

    fun setProgress(positionMs: Long, durationMs: Long) {
        val d = durationMs.coerceAtLeast(1L)
        progress = (positionMs.toFloat() / d).coerceIn(0f, 1f)
        // 波浪进度条按比例显示；用户正在拖动时不要覆盖（控制器用 userSeeking 控制是否调用）
        wavy.setProgressFraction(progress)
        invalidate()
    }

    /** 兼容旧调用点；波浪条按比例工作，不需要 max */
    fun setSeekBarMax(@Suppress("UNUSED_PARAMETER") max: Int) = Unit

    fun setAccent(color: Int) {
        dot.setBackgroundColor(color)
        progressPaint.color = color
        wavy.activeColor = color
        invalidate()
    }

    /** 中岛进度条 seek 回调（比例 0..1） */
    var onSeekStart: (() -> Unit)? = null
    var onSeekMove: ((Float) -> Unit)? = null
    var onSeekEnd: ((Float) -> Unit)? = null

    fun setOnMediaButtonListener(listener: (Int) -> Unit) {
        mediaButtonListener = listener
    }

    private var mediaButtonListener: ((Int) -> Unit)? = null

    override fun onClick(v: View) {
        val cb = mediaButtonListener ?: return
        when (v.id) {
            BTN_PREV_ID -> cb(BTN_PREV)
            BTN_PLAY_ID -> cb(BTN_PLAY)
            BTN_NEXT_ID -> cb(BTN_NEXT)
        }
    }

    // ------------------------------------------------------------------
    // 长按拖动调位置
    //
    // 设计：拖动改的是「垂直位置百分比」，由 IslandController 落盘。
    // 长按 450ms 触发，避免和「点击展开」「拖动进度条」抢手势。
    // ------------------------------------------------------------------

    private var dragEnabled = false
    private var dragging = false
    private var longPressFired = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downRawY = 0f

    /**
     * 形态手势回调：true = 展开成中岛，false = 收回小岛。
     *
     * 取代了原来的 onDragPosition/onDragFinished（那两个用于调位置）。
     * 位置调节现在只在设置界面里做，避免一个手势承担两个语义。
     */
    var onMorphGesture: ((Boolean) -> Unit)? = null

    /** 拖动过程中的实时进度（0..1，1 表示已到"该收起"的程度）——用于跟手反馈 */
    var onMorphDrag: ((Float) -> Unit)? = null

    /**
     * 拖动压缩的目标比例 = 收起高度 / 展开高度。
     *
     * IslandView 自己不知道配置（配置由控制器读），所以由控制器在
     * `setMorphExpanded` 里按 `heightDp / expandedHeightDp` 注入。
     * 默认 0.30 只是兜底，正常路径一定会被覆盖。
     */
    var compressToRatio: Float = 0.30f

    /**
     * 中岛封面在压缩过程中**等比缩小**并**朝小岛封面位置靠**（用户要求）。
     *
     * ============ 为什么要"反向补偿父级 scaleY" ============
     *
     * 压缩是靠**整个岛**的 `scaleY = s`（底边为支点）实现的，
     * 而**子元素的 transform 会被父级再乘一次**。所以子元素要设的值
     * 必须**除以 s**，才能得到想要的"视觉上"的效果。
     *
     * 父级映射（pivot = H，即岛高/底边）：  y' = H + (y - H) · s
     *   视觉顶部 = H + (oy + ty - H) · s  = vy   ->   ty = (vy - H)/s + H - oy
     *   视觉高度 = oh · sc · s            = vh   ->   sc = vh / (oh · s)
     *
     * 用户要"**等比**缩小"，所以宽高用**同一个比例**（按宽度算），
     * 高度那边再除以 s 把父级的非等比压扁抵消掉 —— 这样封面**不会变形**。
     *
     * 全部包在 runCatching 里：几何拿不到就静默跳过（封面保持原样），
     * 绝不让它影响拖拽本身。
     */
    private fun applyCoverTowardThumb(p: Float, s: Float) {
        runCatching {
            if (cover.width <= 0 || cover.height <= 0) return

            // 只在**真的布局好**时刷新（中岛态下小岛布局是 GONE，现算无效）；
            // 正常路径由 onSizeChanged 在**小岛形态**时就抓好缓存，这里只是兜底。
            captureThumbCache()
            // ============ 兜底：缓存无效也**不能直接 return** ============
            //
            // 上一版就是"缓存无效 -> return"，而缓存一直没被填上，
            // 结果封面**完全不动、跟着整块被压扁**（用户实测）。
            // 所以这里退化到"直接现算"——哪怕落点不精确，也**至少会动**。
            if (!thumbCacheValid) {
                captureThumbFallback()
            }
            if (!thumbCacheValid) return

            // 封面起点同样用缓存（onSizeChanged 在"无拖动变换"时抓）
            captureCoverStartIfIdle()
            if (cachedCoverStart.isEmpty) {
                captureCoverStartFallback()
            }
            if (cachedCoverStart.isEmpty) return

            val ox = cachedCoverStart.left
            val oy = cachedCoverStart.top
            val ow = cachedCoverStart.width()
            val oh = cachedCoverStart.height()
            if (ow <= 0f || oh <= 0f) return

            val cx = cachedThumbLeft
            // 由"距底边的偏移"换算回**当前（中岛）坐标系**里的顶部位置
            val cy = height.toFloat() - cachedThumbBottomInset - cachedThumbH
            val cw = cachedThumbW

            // 逐帧插值出"想要的视觉矩形"
            val vx = ox + (cx - ox) * p
            val vy = oy + (cy - oy) * p
            val vw = ow + (cw - ow) * p

            val H = height.toFloat().coerceAtLeast(1f)
            val sSafe = s.coerceAtLeast(0.05f)

            cover.pivotX = 0f
            cover.pivotY = 0f
            // 等比：用宽度比例
            val sc = (vw / ow).coerceIn(0.05f, 1f)
            cover.scaleX = sc
            cover.scaleY = sc / sSafe      // 抵消父级的非等比压扁 -> 视觉上等比
            cover.translationX = vx - ox
            cover.translationY = (vy - H) / sSafe + H - oy

            // 形状插值：圆角 卡片 -> 胶囊（三条路径共用，见 applyMorphCorner 说明）
            applyMorphCorner(p)
            // 其他岛内元素的几何插值（歌名等；新增元素只需在 applyAllElementMorphs 里加一行）
            applyAllElementMorphs(p, s)

            if (coverDiagLogged < 4) {
                coverDiagLogged++
                android.util.Log.i(
                    TAG_DIAG,
                    "coverDiag p=$p s=$s 起点=($ox,$oy,${ow}x$oh) 目标=($cx,$cy,${cw}x$cachedThumbH) " +
                        "视觉=($vx,$vy,${vw}) sc=$sc => tx=${cover.translationX} ty=${cover.translationY} " +
                        "coverScale=(${cover.scaleX},${cover.scaleY}) 岛高=$height"
                )
            }
        }
    }

    /** 复位封面的插值变换（松手/取消时必须调用，否则封面会一直停在中间态） */
    private fun resetCoverInterpolation() {
        runCatching {
            cover.scaleX = 1f
            cover.scaleY = 1f
            cover.translationX = 0f
            cover.translationY = 0f
            // 圆角也要复位回"卡片"，否则岛会一直保持着胶囊圆角
            if (cardCornerRadius > 0f) {
                cornerRadius = cardCornerRadius
                invalidate()
            }
            // ============ 所有元素的插值变换也要复位 ============
            //
            // 真机 bug："马上从小岛变回中岛大小会出问题"。
            // 根因：拖动时给 titleExpanded 等元素加的 scale/translation **从没被复位** ——
            // 上面只复位了封面与圆角。于是收起成小岛后，元素还停在"缩小+偏移"的状态，
            // 再展开回中岛时就是错的。
            //
            // 放在这个公共复位点里，保证"松手 / 回弹结束 / 换布局"三条路径都会清干净。
            for ((from, _) in elementPairs()) {
                runCatching {
                    from.scaleX = 1f
                    from.scaleY = 1f
                    from.translationX = 0f
                    from.translationY = 0f
                }
            }
        }
    }

    /**
     * 单个元素的"几何插值"缓存（与封面同一套思路）。
     *
     * 收起态元素的位置同样按**距底边**存储 —— 展开高 665 / 收起高 196，
     * 顶部差 469px，而小岛元素都在底部区域；存绝对坐标会整体偏上（封面踩过这个坑）。
     */
    private class ElementMorph {
        var valid = false
        var toLeft = 0f
        var toBottomInset = 0f
        var toW = 0f
        var toH = 0f
        val start = android.graphics.RectF()
    }

    /** 每个"展开→收起"元素对一份缓存（key = 展开态视图） */
    private val elementMorphs = HashMap<android.view.View, ElementMorph>()

    private fun morphOf(v: android.view.View): ElementMorph =
        elementMorphs.getOrPut(v) { ElementMorph() }

    /**
     * 通用元素几何插值：把展开态元素朝收起态对应元素的**位置 + 尺寸**插值。
     *
     * 数学与封面完全一致（子元素 transform 会被父级 scaleY 再乘一次，必须反向补偿）：
     *   视觉顶部 vy -> translationY = (vy - H)/s + H - oy
     *   视觉等比   -> scaleY = sc / s
     * 而收起态的几何**按距底边存储**，避免按顶部坐标产生 469px 偏移。
     *
     * ============ 为什么抽成通用函数 ============
     *
     * 用户要求"其他岛内元素也要移动，最终目的地以小岛对应元素在**整个屏幕上的
     * 相对位置**为参考"。逐个复制封面的代码既冗长又容易漏 —— 所以抽成一个函数，
     * 后续每加一个元素只需一行 `applyElementMorph(...)`。
     */
    private fun applyElementMorph(
        from: android.view.View,
        to: android.view.View,
        p: Float,
        s: Float,
        isMedia: Boolean
    ) {
        runCatching {
            val m = morphOf(from)
            // 收起态元素真的布局好时才刷新缓存（展开态下它通常是 GONE，现算无效）
            if (to.width > 0 && to.height > 0 && to.isShown) {
                val mine = IntArray(2)
                getLocationInWindow(mine)
                val tv = IntArray(2)
                to.getLocationInWindow(tv)
                m.toLeft = (tv[0] - mine[0]).toFloat()
                m.toW = to.width.toFloat()
                m.toH = to.height.toFloat()
                m.toBottomInset = height.toFloat() - (tv[1] - mine[1]).toFloat() - m.toH
                m.valid = true
            }
            if (!m.valid) return

            // 起点：展开态元素的自然矩形。只在"岛没有拖动变换"时刷新，避免被缩放污染
            if (m.start.isEmpty || (scaleX == 1f && scaleY == 1f)) {
                val mine = IntArray(2)
                getLocationInWindow(mine)
                val fv = IntArray(2)
                from.getLocationInWindow(fv)
                val l = (fv[0] - mine[0]).toFloat()
                val t = (fv[1] - mine[1]).toFloat()
                m.start.set(l, t, l + from.width.toFloat(), t + from.height.toFloat())
            }
            if (m.start.isEmpty) return

            val H = height.toFloat().coerceAtLeast(1f)
            val sSafe = s.coerceAtLeast(0.05f)
            val ox = m.start.left
            val oy = m.start.top
            val ow = m.start.width()
            if (ow <= 0f) return

            // 目标顶部：由"距底边"换算回当前坐标系
            val cy = height.toFloat() - m.toBottomInset - m.toH
            val vx = ox + (m.toLeft - ox) * p
            val vy = oy + (cy - oy) * p
            val vw = ow + (m.toW - ow) * p

            from.pivotX = 0f
            from.pivotY = 0f
            val sc = (vw / ow).coerceIn(0.05f, 1.2f)
            from.scaleX = sc
            from.scaleY = sc / sSafe
            from.translationX = vx - ox
            from.translationY = (vy - H) / sSafe + H - oy
        }
    }

    /** 所有"展开→收起"元素对（**新增元素只改这里**） */
    private fun elementPairs(): List<Pair<android.view.View, android.view.View>> = listOf(
        // 歌名：中岛左下角的大字 -> 小岛的小字
        titleExpanded to title,
        // 三个控件：中岛的大按钮 -> 小岛的小圆钮
        btnPrev to compactPrev,
        btnPlay to compactPlay,
        btnNext to compactNext,
        // 进度条：中岛的波浪条 -> 小岛的频谱（形态不同，但"位置与尺寸"插值依然成立）
        progressRow to bars
    )

    /**
     * 抓"收起态元素"的几何缓存 —— **必须在布局回调里抓**。
     *
     * ⚠ 与封面**完全同一个坑**（真机实测"没有动"）：
     * 展开态下收起那套布局是 **GONE**，`getLocationInWindow` 拿不到有效值；
     * 而 `applyElementMorph` 只在拖动时被调用，那时 to 视图正是 GONE
     * -> 缓存**永远填不上** -> 元素完全不动。
     *
     * 所以缓存要在 onLayout 里抓（那时岛可能是小岛形态，to 才有真实尺寸）。
     * 这里**只读几何写缓存，绝不改布局**（改布局会自激循环，见 AGENTS.md §4）。
     */
    private fun captureElementCaches() {
        for ((from, to) in elementPairs()) {
            runCatching {
                if (to.width <= 0 || to.height <= 0 || !to.isShown) return@runCatching
                val m = morphOf(from)
                val mine = IntArray(2)
                getLocationInWindow(mine)
                val tv = IntArray(2)
                to.getLocationInWindow(tv)
                m.toLeft = (tv[0] - mine[0]).toFloat()
                m.toW = to.width.toFloat()
                m.toH = to.height.toFloat()
                m.toBottomInset = height.toFloat() - (tv[1] - mine[1]).toFloat() - m.toH
                m.valid = true
            }
        }
    }

    /** 把所有"展开→收起"元素对都跑一遍（新增元素只需改 elementPairs） */
    private fun applyAllElementMorphs(p: Float, s: Float) {
        for ((from, to) in elementPairs()) {
            applyElementMorph(from, to, p, s, true)
        }
    }

    /**
     * 形状插值：把圆角从"卡片"渐变到"高度/2"（胶囊）。
     *
     * 放在 [applyCoverTowardThumb] 内部调用 —— 那里已经是"拖动 / 下落 / 回弹"
     * 三条路径的公共入口，所以一次就能覆盖全部场景。
     */
    private fun applyMorphCorner(p: Float) {
        if (cardCornerRadius <= 0f) return
        val h = height.toFloat()
        if (h <= 0f) return
        val pill = h / 2f
        cornerRadius = cardCornerRadius + (pill - cardCornerRadius) * p
        invalidate()
    }

    /**
     * 封面插值用的几何（**缓存**而非每帧现算）。
     *
     * 小岛封面用"**距底边的偏移**"存储，不用绝对坐标 ——
     * 小岛高 196、中岛高 665，顶部差 469px，而小岛封面在**底部**；
     * 存绝对坐标会让中岛态的目标整体偏上（真机"落点不准"）。
     * 底边是钉死的（`stableBottomAnchor`），所以相对底边才稳定。
     *
     * ⚠ 中岛展开时 `compactCover` 是 GONE（未布局），现算会得到 (0,0) ——
     * 所以只在它**真的布局好**（小岛形态）时刷新缓存。
     */
    private var cachedThumbLeft = 0f
    private var cachedThumbW = 0f
    private var cachedThumbH = 0f
    private var cachedThumbBottomInset = 0f
    private var thumbCacheValid = false

    /** 中岛封面的起点矩形（岛坐标系），只在 p 很小时刷新，避免被父级缩放污染 */
    private val cachedCoverStart = android.graphics.RectF()

    /** 拖动诊断（logcat 过滤 IslandMorphDiag），只打前几帧避免刷屏 */
    private var coverDiagLogged = 0

    /**
     * 缓存封面插值所需的几何。
     *
     * ============ 为什么必须在"布局回调"里抓（两次真机回归的教训）============
     *
     * 1) 中岛展开时，小岛那套布局是 **GONE** —— `compactCover` 没有尺寸，
     *    `getLocationInWindow` 也无效（返回 0,0）。拖动时现算 -> 目标错误 = "落点不准"。
     * 2) 于是我改成"只在确实布局好时刷新 + 否则用缓存"，但**缓存从没被填过**
     *    （因为刷新点也只在拖动时调用，而那时小岛是 GONE）-> `return` -> 封面**完全不动**。
     *
     * 正确做法：在**布局回调**里抓 —— 小岛形态下 `compactCover` 才有真实尺寸。
     * 这里是**只读**几何写缓存，**绝不改布局**（onLayout 里改布局会自激循环，见 §4）。
     */
    /**
     * 布局回调里抓缓存。
     *
     * ============ 为什么必须是 onLayout，而不是 onSizeChanged ============
     *
     * 真机日志（小岛形态）：
     *   `onSizeChanged 岛=1070x196 compactCover w=0 h=0 vis=8 isShown=false`
     * —— 小岛形态下 compactCover 仍是 **GONE 且没有尺寸**，因为它的可见性是
     *    `if (compactCover.drawable != null) VISIBLE else GONE`：
     *    **封面图还没加载出来**的那一刻它就是 GONE。
     *
     * 而 `onSizeChanged` 只在**尺寸变化**时触发 —— 恰好在"尺寸已经定了、
     * 封面随后才出现"这种情况下**永远抓不到**（本次回归的根因）。
     *
     * `onLayout` 每次布局都会跑，所以能等到封面真正布局出来的那一刻。
     * ⚠ 这里**只读几何写缓存，绝不改布局**（改布局会自激循环，见 AGENTS.md §4）。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        captureThumbCache()
        captureCoverStartIfIdle()
        captureElementCaches()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 诊断：看清 compactCover 到底有没有被布局（真机无日志说明它一直没尺寸）
        if (diagLayoutCount < 6) {
            diagLayoutCount++
            android.util.Log.i(
                TAG_DIAG,
                "onSizeChanged 岛=${w}x$h compactCover w=${compactCover.width} " +
                    "h=${compactCover.height} vis=${compactCover.visibility} " +
                    "isShown=${compactCover.isShown} cover=${cover.width}x${cover.height} " +
                    "expanded=$expanded"
            )
        }
        captureThumbCache()
        captureCoverStartIfIdle()
        // 收起态元素的几何也要在这里抓（拖动时它们是 GONE，那时抓不到 —— 实测"没有动"）
        captureElementCaches()
    }

    private var diagLayoutCount = 0

    /** 触摸诊断计数（见 dispatchTouchEvent 的 DOWN 探针） */
    private var touchDiagCount = 0

    /** 抓小岛封面几何（相对**底边**存储，见 cachedThumbBottomInset 的说明） */
    private fun captureThumbCache() {
        runCatching {
            if (compactCover.width <= 0 || compactCover.height <= 0) return
            if (!compactCover.isShown) return
            val mine = IntArray(2)
            getLocationInWindow(mine)
            val th = IntArray(2)
            compactCover.getLocationInWindow(th)
            cachedThumbLeft = (th[0] - mine[0]).toFloat()
            cachedThumbW = compactCover.width.toFloat()
            cachedThumbH = compactCover.height.toFloat()
            cachedThumbBottomInset =
                height.toFloat() - (th[1] - mine[1]).toFloat() - cachedThumbH
            thumbCacheValid = true
            android.util.Log.i(TAG_DIAG, "thumbCache OK left=$cachedThumbLeft w=$cachedThumbW h=$cachedThumbH inset=$cachedThumbBottomInset 岛高=$height")
        }
    }

    /**
     * 兜底：直接用 getLocationInWindow 现算小岛封面几何（不要求 isShown）。
     *
     * 为什么需要：真机上小岛那套布局可能是 GONE，导致 captureThumbCache 永远不生效，
     * 而"缓存无效就 return"会让封面**完全不动**（已发生过一次）。
     * 这里放宽到"只要拿到了非 0 的尺寸就用"，至少保证封面**会动**。
     */
    private fun captureThumbFallback() {
        runCatching {
            if (compactCover.width <= 0 || compactCover.height <= 0) return
            val mine = IntArray(2)
            getLocationInWindow(mine)
            val th = IntArray(2)
            compactCover.getLocationInWindow(th)
            cachedThumbLeft = (th[0] - mine[0]).toFloat()
            cachedThumbW = compactCover.width.toFloat()
            cachedThumbH = compactCover.height.toFloat()
            cachedThumbBottomInset =
                height.toFloat() - (th[1] - mine[1]).toFloat() - cachedThumbH
            thumbCacheValid = true
            android.util.Log.i(TAG_DIAG, "thumbCache FALLBACK left=$cachedThumbLeft w=$cachedThumbW h=$cachedThumbH inset=$cachedThumbBottomInset")
        }
    }

    /** 兜底：起点直接现算（不做 scale 检查） */
    private fun captureCoverStartFallback() {
        runCatching {
            if (cover.width <= 0 || cover.height <= 0) return
            val mine = IntArray(2)
            getLocationInWindow(mine)
            val cov = IntArray(2)
            cover.getLocationInWindow(cov)
            val l = (cov[0] - mine[0]).toFloat()
            val t = (cov[1] - mine[1]).toFloat()
            cachedCoverStart.set(l, t, l + cover.width.toFloat(), t + cover.height.toFloat())
        }
    }

    /** 抓中岛封面起点 —— 只在"岛没有任何拖动变换"时抓，避免被缩放污染 */
    private fun captureCoverStartIfIdle() {        runCatching {
            if (!thumbCacheValid) return
            if (scaleX != 1f || scaleY != 1f || translationY != 0f) return
            if (cover.width <= 0 || cover.height <= 0) return
            val mine = IntArray(2)
            getLocationInWindow(mine)
            val cov = IntArray(2)
            cover.getLocationInWindow(cov)
            val l = (cov[0] - mine[0]).toFloat()
            val t = (cov[1] - mine[1]).toFloat()
            cachedCoverStart.set(l, t, l + cover.width.toFloat(), t + cover.height.toFloat())
        }
    }

    /**
     * 压缩过程中的模糊遮瑕（峰值 [MORPH_BLUR_DP]，随进度增长）。
     *
     * 用 `RenderEffect.createBlurEffect`（真正的 GPU 模糊，API 31+）——
     * **不要**用"缩图再放大"那种马赛克做法（见 AGENTS.md 禁止事项）。
     * p<=0 时清空 RenderEffect，不留下任何开销。
     */
    private fun applyMorphBlur(p: Float, peakDp: Float = MORPH_BLUR_DP.toFloat()) {
        runCatching {
            if (p <= 0.02f) {
                setRenderEffect(null)
            } else {
                val r = dp(peakDp.toInt().coerceAtLeast(1)).toFloat() * p
                setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        r, r, android.graphics.Shader.TileMode.CLAMP
                    )
                )
            }
        }
    }

    private var morphDragProgress = 0f

    /**
     * "落到位后再换布局"的请求标志。
     *
     * 完成收起时，IslandView 先用动画**自然地落**到压缩位置；
     * 落到位的那一帧置位本标志并回调 `onMorphGesture(false)`，
     * 控制器据此走 `instantMorph`（瞬时换布局）——
     * 因为此刻几何已经重合，观感就是"**落到那个位置就不动了**"（用户要求），
     * 而不是先弹回全尺寸再缩一下（那一下就是"跳"）。
     */
    private var instantCollapseRequested = false

    /** 控制器取用后即清除（一次性） */
    fun consumeInstantCollapseRequest(): Boolean {
        val v = instantCollapseRequested
        instantCollapseRequested = false
        return v
    }

    /**
     * 回弹动画：把压缩状态用 ~140ms 收回（而不是瞬间归位）。
     *
     * 为什么必须"用 scaleY 反推进度"：封面插值与模糊都是 p 的函数，
     * 如果 scaleY 动画归位、而封面/模糊瞬间清掉，三者不同相 -> 依然会跳。
     * 所以统一由 scaleY 反推 p，三者始终同步。
     */
    /**
     * 换布局**之后**把模糊淡出（约 120ms）。
     *
     * 用户要求："**边框也模糊**"。
     * 原来交接那一刻直接 `applyMorphBlur(0f)` 把模糊清掉 —— 边框瞬间变**锐利**，
     * 于是"卡片轮廓 vs 胶囊轮廓"的形状差异立刻暴露，就是那一下破绽。
     *
     * 所以：换布局后**继续模糊**，再在 ~120ms 内淡到 0 ——
     * 边框在交接时是糊的，等形状稳定了再清晰起来。
     */
    private fun fadeOutMorphBlur(
        durationMs: Long = 120L,
        peakDp: Float = MORPH_BLUR_DP.toFloat()
    ) {
        val anim = android.animation.ValueAnimator.ofFloat(1f, 0f)
        anim.duration = durationMs
        anim.interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        anim.addUpdateListener { a ->
            val f = a.animatedValue as Float
            runCatching {
                if (f <= 0.02f) {
                    setRenderEffect(null)
                } else {
                    val r = dp(peakDp.toInt().coerceAtLeast(1)).toFloat() * f
                    setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(
                            r, r, android.graphics.Shader.TileMode.CLAMP
                        )
                    )
                }
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                runCatching { setRenderEffect(null) }
            }
        })
        anim.start()
    }

    /**
     * 音乐锁屏退场"接棒"用的模糊（v1.41.0）。
     *
     * 与 collapse 的 [fadeOutMorphBlur] 完全同源：交棒瞬间给中岛挂上**满档**遮瑕，
     * 再约 120ms 淡掉 —— 否则"全屏排版 → 中岛原生排版"的锐利切换会被一眼看穿
     * （音乐锁屏那边 k=0 的文字是"缩放出来的字形"，中岛是原生排版，形状有细微差别）。
     *
     * 由 `IslandController` 在瞬时交棒前调用；与 `MORPH_BLUR_DP`、时长共用同一批常量，
     * 所以两条接棒路径（中岛→小岛 / 音乐锁屏→中岛）观感一致。
     */
    fun handoffBlurFromMusicExit() {
        runCatching {
            // v1.41.4：用**专给音乐锁屏交接**的一组值（4dp / 70ms）。
            // 用户反馈"退回中岛后模糊时间太长了" —— 原来这里走的是写死的 6dp/120ms，
            // 那是"中岛→小岛"收缩时用的强遮瑕；音乐锁屏交接两套排版已几乎重合，
            // 只需要"糊一下接上"，糊久了就是"落到中岛还在发虚"。
            val peak = MusicExitMotion.HANDOFF_BLUR_DP
            applyMorphBlur(1f, peak)
            fadeOutMorphBlur(MusicExitMotion.HANDOFF_BLUR_FADE_MS, peak)
        }
    }
    /**
     * "自然下落"到位（完成收起时的收尾动画）。
     *
     * 用户要求："做出一种**自然下落**的感觉，**落到那个位置就不要动了**。"
     *
     * - 曲线 `PathInterpolator(0.4, 0, 0.2, 1)`：起步略加速、末段稳稳停住，
     *   **不带过冲** —— 过冲就会"多动一下"，违背"落到位就不动"。
     * - 时长按**剩余距离**缩放（90~220ms）：已经压得多就落得快，更自然。
     * - 落到位后回调 onLanded()：那里会置 `instantCollapseRequested` 并回调控制器，
     *   由控制器走 instantMorph **瞬时**换布局 —— 几何已重合，所以看不出来。
     */
    private fun morphFallToCompact(onLanded: () -> Unit) {
        val target = compressToRatio.coerceIn(0.1f, 1f)
        val from = scaleY
        val span = (1f - target).coerceAtLeast(0.05f)
        val remain = ((from - target) / span).coerceIn(0f, 1f)
        if (remain <= 0.01f) {
            onLanded()
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(from, target)
        // ============ 曲线：中段放慢、结尾快速但仍减速（用户选定的方案）============
        //
        // 讨论过"前 2/3 慢 + 后 1/3 快"，但**不这么做**，原因是：
        //   1) 在 2/3 处会造成**速度突变** —— 眼睛读到顿挫，与用户反复反馈的
        //      "跳一下"是同一类感知（速度不连续）。
        //   2) 用户自己的要求是"落到那个位置就不要动了" —— 那要求**减速入场**。
        //   3) 最后 1/3 已交给新布局 + 模糊淡出，加速它也改不到东西。
        //
        // 所以用 (0.55, 0.05, 0.2, 1)：前 2/3 明显放慢（看清封面走位），
        // 最后一段**快速但仍在减速**地落定 —— 全程单调减速，2/3 处不顿挫。
        anim.duration = 140L + (180L * remain).toLong()     // 140~320ms（用户反馈"太快了"）
        anim.interpolator = android.view.animation.PathInterpolator(0.55f, 0.05f, 0.2f, 1f)
        // 用户要求："在动画进入**三分之二**时强制进入小岛"
        // -> 不再等动画跑完，走到 2/3 就换布局；剩下 1/3 由模糊淡出 + 新布局接管。
        var landed = false
        anim.addUpdateListener { a ->
            val v = a.animatedValue as Float
            scaleY = v
            val pp = ((1f - v) / span).coerceIn(0f, 1f)
            applyCoverTowardThumb(pp, v)
            applyMorphBlur(pp)
            expandedContent.alpha = 1f - pp * 0.55f
            if (!landed && (1f - pp) <= (1f / 3f) + 0.001f) {
                landed = true
                onLanded()
                runCatching { a.cancel() }   // 剩下的交给新布局 + 模糊淡出
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (!landed) {
                    landed = true
                    onLanded()
                }
            }
        })
        anim.start()
    }

    private fun morphSpringBack() {        val from = scaleY
        if (from >= 0.999f) {
            // 本来就没压缩（没真正拖动），直接归位
            scaleY = 1f
            resetCoverInterpolation()
            applyMorphBlur(0f)
            expandedContent.alpha = 1f
            return
        }
        val anim = android.animation.ValueAnimator.ofFloat(from, 1f)
        anim.duration = 140L
        anim.interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        anim.addUpdateListener { a ->
            val v = a.animatedValue as Float
            scaleY = v
            val pp = ((1f - v) / (1f - compressToRatio).coerceAtLeast(0.05f))
                .coerceIn(0f, 1f)
            applyCoverTowardThumb(pp, v)
            applyMorphBlur(pp)
            expandedContent.alpha = 1f - pp * 0.55f
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                scaleY = 1f
                resetCoverInterpolation()
                applyMorphBlur(0f)
                expandedContent.alpha = 1f
            }
        })
        anim.start()
    }

    /** 本次手势是否真的移动过（>touchSlop）。用于在"按下即可拖"之后区分点击与拖动 */
    private var movedDuringDrag = false

    /** 触发形态切换所需的纵向拖动距离 */
    private val morphDragThresholdPx: Float
        get() = dp(MORPH_DRAG_THRESHOLD_DP).toFloat()

    var onResize: ((Float) -> Unit)? = null
    var onResizeFinished: (() -> Unit)? = null

    /** 当前垂直位置比例，由控制器同步进来 */
    private var currentBiasFraction = 0.68f

    /** 当前宽度占屏比，由控制器同步进来 */
    private var currentWidthPercent = 88

    fun setDragEnabled(enabled: Boolean) {
        dragEnabled = enabled
    }

    fun setBiasFraction(fraction: Float) {
        currentBiasFraction = fraction.coerceIn(0f, 1f)
    }

    fun setWidthPercent(percent: Int) {
        currentWidthPercent = percent.coerceIn(10, 100)
    }

    /** 双指捏合的基准信息 */
    private var pinchStartSpan = 0f
    private var pinchStartWidthPercent = 88
    private var pinching = false

    private val longPressRunnable = Runnable {
        if (!dragEnabled) return@Runnable
        longPressFired = true
        dragging = true
        isPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * 整张卡片的水波纹。
     *
     * ============ 为什么在 dispatchTouchEvent 而不是按钮自己 ============
     *
     * 设计要求「按下时水波纹覆盖到**一整个卡片**」。若把 ripple 放在每个按钮上，
     * 看到的只是按钮范围内的一小圈。这里把 ripple 作为**卡片的前景**
     * （`expandedContent.foreground`，尺寸即整张卡片），
     * 并在任何手指落下时把 hotspot 设为触点 —— 波纹就从触点扩散到整张卡片。
     *
     * 放在 dispatchTouchEvent 是因为要覆盖两种情况：
     *   · 手指落在按钮上（按钮会消费事件，父容器收不到 pressed 状态）
     *   · 手指落在卡片空白处（卡片自己会成为 pressed）
     * 统一在这里驱动，两种情况下观感一致。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // 诊断（用户反馈"媒体按钮点击不了"）：记录岛有没有收到 DOWN。
                // 打前 20 次即可，避免刷屏。
                if (touchDiagCount < 20) {
                    touchDiagCount++
                    android.util.Log.i(
                        TAG_DIAG,
                        "touch DOWN x=${ev.x.toInt()} y=${ev.y.toInt()} " +
                            "expanded=$expanded state=$state dragEnabled=$dragEnabled " +
                            "岛=${width}x$height"
                    )
                }
                // ============ 独占触摸，避免父容器把拖动当成"下拉通知栏" ============
                //
                // 真机 bug：岛挂在 NotificationShadeWindowView 上，
                // 按住岛向上/下滑动时，父级（锁屏通知栏/面板）会拦截这个手势
                // 并把通知栏拉下来 —— 形态手势因此永远拿不到 MOVE 事件。
                //
                // requestDisallowInterceptTouchEvent(true) 要求父容器在本次手势期间
                // 不得拦截，直到 UP/CANCEL。必须放在 dispatchTouchEvent（而不是
                // onTouchEvent）才能覆盖"手指落在按钮上"的情况 —— 那时按钮会消费
                // 事件，本视图的 onTouchEvent 根本不会被调用。
                parent?.requestDisallowInterceptTouchEvent(true)

                if (expanded && expandedContent.visibility == VISIBLE) {
                    val fx = expandedContent.foreground
                    if (fx != null) {
                        // 坐标转到卡片坐标系，让水波纹从触点扩散到整张卡片
                        fx.setHotspot(
                            ev.x - expandedContent.left,
                            ev.y - expandedContent.top
                        )
                        fx.state = intArrayOf(android.R.attr.state_pressed)
                    }
                }
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                expandedContent.foreground?.state = intArrayOf()
            }
        }
        // 诊断分岔点：consumed=true 说明**有子视图吃掉了**（继续二分定位是哪个）；
        // consumed=false 说明**没有任何子视图消费** -> 问题在"按钮根本不在命中范围"
        //（被布局挤开 / isEnabled=false 等），那时该去量按钮的实际屏幕矩形。
        val consumed = super.dispatchTouchEvent(ev)
        if (touchDiagCount < 20) {
            touchDiagCount++
            android.util.Log.i(
                TAG_DIAG,
                "dispatch action=${ev.actionMasked} consumed=$consumed x=${ev.x.toInt()} y=${ev.y.toInt()}"
            )
        }
        return consumed
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!dragEnabled) return super.onTouchEvent(event)

        // 双指捏合 = 调大小。优先于单指拖动判定。
        if (event.pointerCount >= 2) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_POINTER_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    removeCallbacks(longPressRunnable)
                    dragging = false
                    val span = spanOf(event)
                    if (!pinching || event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                        pinching = true
                        pinchStartSpan = span
                        pinchStartWidthPercent = currentWidthPercent
                        if (event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    } else if (pinchStartSpan > 0f) {
                        val ratio = span / pinchStartSpan
                        val next = (pinchStartWidthPercent * ratio)
                            .toInt()
                            .coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)
                        if (next != currentWidthPercent) {
                            currentWidthPercent = next
                            onResize?.invoke(next.toFloat())
                        }
                    }
                    return true
                }

                android.view.MotionEvent.ACTION_POINTER_UP,
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (pinching) {
                        pinching = false
                        onResizeFinished?.invoke()
                    }
                    return true
                }
            }
            return true
        }

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                pinching = false
                downRawY = event.rawY
                // ============ 不再要求"长按"才能下拖 / 上拖 ============
                //
                // 用户要求降阶（以及展开）改成"按住拖"即可，不再需要先长按 450ms。
                // 误触防护：
                //   · 中岛向下拖：要拖过 morphDragThresholdPx 的一半才收起
                //   · 小岛向上拖：要拖过完整阈值才展开
                // 轻点仍然是点击（不会拖动，因为 dragging 需要位移才产生效果）。
                dragging = true
                isPressed = true
                movedDuringDrag = false   // 用于区分"点击"与"拖动"
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dy) > touchSlop) movedDuringDrag = true

                    // ============ "跟手"：以**底边为支点**压缩（用户反馈"一直被压缩成小岛"）============
                    //
                    // 演进过程（两次真机事故）：
                    //   1) 原来 `translationY = dy`（无上限）-> 往下拖多远滑多远，不松手一直滑。
                    //   2) 改成每帧写 layoutParams 驱动高度 -> **触发布局自激循环**，
                    //      3000ms 内 applyConstraints 被调用 151 次 -> **熔断**，
                    //      模块自己关掉、岛整个消失。
                    //
                    // 最终用**纯渲染变换**：以**底边为支点**做 scaleY。
                    //   · 底边（pivotY = 卡片高度）永远不动 ✓
                    //   · 上边框与全部元素**等比向下压缩** ✓ —— 正是"压缩成小岛"的观感
                    //   · **完全不写 layoutParams** -> 不可能触发布局循环 ✓
                    if (expanded) {
                        val p = (dy / morphDragThresholdPx).coerceIn(0f, 1f)
                        morphDragProgress = p
                        translationY = 0f
                        pivotY = height.toFloat().coerceAtLeast(1f)
                        val s = 1f - p * (1f - compressToRatio)
                        scaleY = s
                        // 元素几何插值：封面**等比缩小**并朝小岛封面位置移动
                        applyCoverTowardThumb(p, s)
                        // ============ 模糊遮瑕（用户要求"酌情添加模糊来遮瑕"）============
                        //
                        // 为什么需要：压缩过程中岛的**形状**只是被压扁，并没有真正变成
                        // 小岛的胶囊形（圆角/宽度都不同），所以交接时必然"跳一下"。
                        // 在压缩过程中逐渐加一点模糊，把"形状对不上"的细节糊掉，
                        // 观感就顺很多 —— 这是动效里常用的"瑕疵遮盖"手法。
                        //
                        // 峰值 6dp，随 p 线性增长（越接近小岛越糊）；松手后清除。
                        applyMorphBlur(p)
                        expandedContent.alpha = 1f - p * 0.55f
                        onMorphDrag?.invoke(p)
                    } else if (dy <= -morphDragThresholdPx) {
                        // 小岛 → 中岛：越过阈值即触发（向上长高的动画本身够跟手）
                        dragging = false
                        isPressed = false
                        translationY = 0f
                        onMorphGesture?.invoke(true)
                    }
                    return true
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    dragging = false
                    isPressed = false
                    translationY = 0f
                    val p = morphDragProgress
                    morphDragProgress = 0f
                    if (expanded && p >= 0.5f) {
                        // ============ 完成收起：先"自然落"到压缩位置，再瞬时换布局 ============
                        //
                        // 用户："松手还是跳，我们需要做出一种自然下落的感觉，
                        //       落到那个位置就不要动了。"
                        //
                        // 所以不再"瞬回 scaleY=1 + 立刻 morph"（那会让岛先弹回全尺寸再缩），
                        // 而是：动画把 scaleY 继续**落到 compressToRatio**（自然下落曲线），
                        // 落到位的那一帧再请求控制器**瞬时**换布局（几何已重合 -> 看不出来）。
                        morphFallToCompact {
                            scaleY = 1f
                            resetCoverInterpolation()
                            // "边框也模糊"：不立刻清模糊，改为淡出（否则边框瞬间锐利、露出形状差异）
                            fadeOutMorphBlur()
                            expandedContent.alpha = 1f
                            instantCollapseRequested = true
                            onMorphGesture?.invoke(false)
                        }
                    } else {
                        // 回弹：动画收回（封面与模糊同相收回，不再跳）
                        morphSpringBack()
                        onMorphDrag?.invoke(0f)
                    }
                    if (p < 0.5f) {
                        // 回弹路径：动画由 morphSpringBack 负责收尾
                    }
                    // 没真正移动过 → 当成点击（现在 dragging 按下就为 true，
                    // 所以必须靠这个判定把"点击展开"救回来）
                    if (!movedDuringDrag && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        performClick()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 两指间距 */
    private fun spanOf(event: android.view.MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 写入尺寸参数。只记录目标值，实际高度由外层（IslandController）的
     * ConstraintLayout.LayoutParams 决定，展开/收起时的过渡在 [setExpanded] 里做动画。
     */
    fun applyStyle(heightPx: Int, cornerPx: Int, expandedHeightPx: Int) {
        collapsedHeight = heightPx
        expandedHeight = expandedHeightPx

        val target = if (expanded && state == STATE_MEDIA) expandedHeightPx else heightPx
        cornerRadius = if (cornerPx < 0) target / 2f else cornerPx.toFloat()
        // 记下"卡片圆角"，压缩时据此插值到胶囊（见 cardCornerRadius 的说明）
        cardCornerRadius = cornerRadius

        // 收起态仍是"胶囊"：内容左右留出圆形端帽的空间
        val padH = (heightPx * 0.34f).toInt()
        content.setPadding(padH, 0, padH, 0)

        // ============ 中岛：不再是横向三段，而是整张卡片 ============
        //
        // 新设计里封面铺满整张卡片，文字与控件各自带外边距叠在上面，
        // 所以这里**不能**再给 expandedContent 加统一内边距（那会让封面
        // 缩进、露出底色），也不再需要计算"封面/文字列/按钮组"的宽度分配 ——
        // 那套算术随旧布局一起去掉了。
        //
        // 波浪进度条的线宽/振幅按卡片高度轻微缩放，高卡片上不会显得太细。
        val scale = (expandedHeightPx / 665f).coerceIn(0.8f, 1.4f)   // 665px = 190dp @3.5
        wavy.strokePx = dp(4).toFloat() * scale
        wavy.amplitudePx = dp(3).toFloat() * scale
        wavy.wavelengthPx = dp(18).toFloat()

        invalidateOutline()
    }

    override fun onDetachedFromWindow() {
        // 注意：形态切换会刻意 removeView + addView，这会触发本回调。
        // 所以这里只做轻量清理，不要取消与布局相关的待执行任务。
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /** 诊断用的绘制计数（判断 onDraw 到底有没有被调用） */
    @Volatile
    var drawCount: Int = 0
        private set

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCount++
        val h = height.toFloat()
        // 用快捷方式的圆角资源（24dp）。只有解析不到时才退化为"高度一半"的胶囊。
        val r = (shortcutRadiusPx?.toFloat() ?: (h / 2f)).coerceAtMost(h / 2f)
        rect.set(0.5f, 0.5f, width - 0.5f, h - 0.5f)
        // 小岛→中岛转场期间，让卡片背景**跟着封面的边界走**。
        //
        // 真机反馈："小岛变中岛时专辑封面放大的过程中会有一个白边，然后封面逐渐覆盖"。
        // 原因就是这个背景（跟手电筒/相机同源的浅色）在封面还没铺满时露出来一圈。
        // 把背景矩形收缩到封面当前的边界，白边就消失了；封面长满时背景自然铺满整卡。
        if (backgroundFollowsCover && cover.width > 0 && cover.height > 0) {
            rect.set(
                cover.left.toFloat(), cover.top.toFloat(),
                cover.right.toFloat(), cover.bottom.toFloat()
            )
        }
        canvas.drawRoundRect(rect, r, r, backgroundPaint)
        canvas.drawRoundRect(rect, r, r, strokePaint)
        if (debugOutline) {
            canvas.drawRect(3f, 3f, width - 3f, h - 3f, debugOutlinePaint)
        }
    }

    /**
     * 诊断描边：在视图边界画一圈洋红粗边。
     *
     * 起因：展开态在真机看板上看不到，但日志显示 alpha=1.0、尺寸 1070x378、
     * 背景色 #fffae3e0 全都不透明 —— 只能靠描边把"视图边界"与
     * "实际可见内容"区分开，判断到底是没画、被裁、还是被盖住。
     */
    @Volatile
    var debugOutline: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val debugOutlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        .apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
            color = 0xFFFF00FF.toInt()
        }

    /** 展开态时在底部画一条进度线，强化"正在播放"的感知 */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (progress > 0f && state == STATE_MEDIA && !expanded) {
            val h = height.toFloat()
            canvas.drawRect(0f, h - dp(3), width * progress, h, progressPaint)
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // 与系统快捷方式一致的样式
    //
    // 手电筒/相机那两个按钮的背景是 @drawable/keyguard_bottom_affordance_bg：
    //   solid = materialColorSurfaceContainerHigh（Material 主题属性，跟随取色）
    //   corners = keyguard_affordance_fixed_radius（24dp）
    // 这里动态解析同样的值，保证岛和它们观感一致、且跟随 Material You 换色。
    // ------------------------------------------------------------------

    /** 解析到的快捷方式背景色；null 表示还没解析 */
    private var shortcutBgColor: Int? = null

    /** 解析到的快捷方式圆角（px） */
    private var shortcutRadiusPx: Int? = null

    @Volatile
    private var styleResolved = false

    /**
     * 重新解析主题/莫奈色（**换壁纸后不跟着变色**的修复）。
     *
     * 原来 `resolveShortcutStyle()` 有 `styleResolved` 守卫，一生只跑一次；
     * 而系统换壁纸后会重新取色（莫奈色变了），岛却还留着旧色。
     *
     * 由控制器在"锁屏重新出现"时调用即可：那时系统已经完成新一轮取色，
     * 重解析一次就同步上了。代价只有几次资源查询。
     */
    fun refreshThemeColors() {
        styleResolved = false
        resolveShortcutStyle()
        invalidate()
    }

    private fun resolveShortcutStyle() {
        if (styleResolved) return
        styleResolved = true
        shortcutBgColor = SystemUiResources.surfaceContainerHigh(context)
        shortcutRadiusPx = SystemUiResources.affordanceRadiusPx(context)
        // 背景色交给 applyIslandBackground()：它优先用**专辑封面取色**，
        // 系统快捷方式底色只是兜底（用户要求：小岛背景也用专辑封面的莫奈色）。
        applyIslandBackground()

        // ============ 播放键背景：跟随莫奈取色 ============
        //
        // 设计要求"按钮背景要跟随莫奈取色"。用 SystemUiResources.monetContainer()
        // 逐个候选尝试 Material 主题色（见那里的说明：Material 属性不一定都能
        // 从 SystemUI 的 theme 里查到，所以要按优先级兜底）。
        // 解析不到时保留半透明白，至少保证按钮在任意封面上可见。
        monetContainerColor = SystemUiResources.monetContainer(context)
        // 形状与音乐锁屏的播放键一致：正圆
        btnPlay.configure(
            fillColor = monetContainerColor ?: 0x33FFFFFF,
            cornerPx = dp(PLAY_BUTTON_DP / 2)
        )

        // ============ 前景色也必须跟随主题 ============
        //
        // 我最初把文字写死成白色并假定背景是深色。但背景跟随系统取色后
        // 变成浅粉（#fae3e0），白字直接看不清 —— 真机截图暴露的。
        // 快捷方式里的手电筒/相机图标用的是 materialColorOnSurface，
        // 这里取同一个色，保证任何取色下都可读。
        val fg = SystemUiResources.onSurface(context)
        if (fg == null) {
            IslandLog.w("无法解析 onSurface 色，回退：按背景明度自动选黑/白字")
        }
        applyForegroundColor(fg ?: autoContrastOn(backgroundPaint.color))

        // ============ 莫奈取色：中岛/小岛的进度条与频谱（用户要求）============
        //
        // 音乐锁屏那边已经用莫奈强调色了；这里把岛上的三处也统一过去：
        //   · 中岛的波浪进度条（活动段 + 轨道）
        //   · 小岛的进度条（progressPaint）
        //   · 小岛的频谱条（bars）
        // 这样三个形态的颜色是一套，转场时不会出现颜色突变。
        monetContainerColor?.let { m ->
            wavy.activeColor = m
            wavy.trackColor = (m and 0x00FFFFFF) or 0x59000000
            bars.setBarColor(m)
            progressPaint.color = m
            // 小岛三个圆按钮统一上色。
            // 注意：这里**不能**直接给 compactPlay 涂莫奈色 —— 小岛的按钮色优先取
            // **专辑封面**（albumAccent），壁纸莫奈色只是兜底。
            // 统一走 applyCompactButtonColors()，由 islandAccent() 决定优先级。
            applyCompactButtonColors()
        }
        invalidate()
    }

    /** 把前景色应用到所有文字与图标 */
    fun applyForegroundColor(color: Int) {
        foregroundColor = color
        title.setTextColor(color)
        subtitle.setTextColor(withAlpha(color, 0xB3))
        // 中岛文字固定为白色：它压在**专辑封面**上，而封面亮度不可预知，
        // 由 artScrim 渐变保证对比度。跟随主题色反而会在浅色封面上失效。
        titleExpanded.setTextColor(Color.WHITE)
        artistExpanded.setTextColor(0xE6FFFFFF.toInt())
        bars.setBarColor(color)
        // 中岛按钮压在封面上：播放键用莫奈容器色 + 白色图标，
        // 上/下一首无背景、纯白图标
        btnPrev.setImageDrawable(Glyphs.create(context, GLYPH_PREV, Color.WHITE))
        btnNext.setImageDrawable(Glyphs.create(context, GLYPH_NEXT, Color.WHITE))
        btnPlay.setGlyph(
            if (bars.isAnimating()) GLYPH_PAUSE else GLYPH_PLAY, Color.WHITE
        )
        // 收起态的三个按钮沿用主题前景色；
        // 中岛的波浪与文字一样固定在**白色**系：它压在专辑封面上，
        // 而封面亮度不可预知 —— 跟随主题前景色（本机是近黑 #1c1b20）
        // 会在深色封面上彻底看不见（真机截图确认）。
        wavy.activeColor = Color.WHITE
        wavy.trackColor = 0x59FFFFFF
        // 状态胶囊图标也用同一前景色
        if (currentChipGlyph > 0) {
            chipIcon.setImageDrawable(Glyphs.create(context, currentChipGlyph, color))
        }
        strokePaint.color = withAlpha(color, 0x33)
    }

    /** 背景太亮就用深色字，否则用白字（仅在解析不到主题色时兜底） */
    private fun autoContrastOn(bg: Int): Int {
        val lum = com.example.livenotification.island.util.ContrastMath.luminance(bg)
        return if (lum > 0.5) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    /** 记住当前 chip 图标，换主题色时可以重建 */
    @Volatile
    private var currentChipGlyph: Int = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveShortcutStyle()
    }

    // ------------------------------------------------------------------
    // 律动条
    // ------------------------------------------------------------------

    /**
     * 三根律动条。用一个 ValueAnimator 驱动相位，开销很低。
     */
    private class EqualizerBars(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private var phase = 0f
        private var animator: ValueAnimator? = null

        /** 律动条颜色跟随主题前景色 */
        fun setBarColor(color: Int) {
            paint.color = color
            invalidate()
        }

        fun isAnimating(): Boolean = animator?.isRunning == true

        fun setAnimating(animating: Boolean) {
            if (animating) {
                if (animator?.isRunning == true) return
                animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                    duration = 900L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                animator?.cancel()
                animator = null
                phase = 0f
                invalidate()
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val barW = w / 7f
            val gap = (w - barW * 3f) / 2f
            for (i in 0 until 3) {
                val offset = i * 1.1f
                val scale = 0.35f + 0.65f * abs(Math.sin((phase + offset).toDouble())).toFloat()
                val bh = h * scale
                val left = i * (barW + gap)
                val top = (h - bh) / 2f
                canvas.drawRoundRect(
                    left, top, left + barW, top + bh,
                    barW / 2f, barW / 2f, paint
                )
            }
        }
    }

    companion object {
        const val VIEW_ID = 0x1A150001

        /** 拖动压缩的诊断日志 tag（logcat -s IslandMorphDiag） */
        private const val TAG_DIAG = "IslandMorphDiag"

        // ---------------- 中岛（卡片）尺寸 ----------------

        /** 卡片四周留白：文字与控件据此内缩，封面仍然铺满整张卡片 */
        private const val CARD_PADDING_DP = 20

        /** 底部进度行高度（上一首 / 波浪 / 下一首 所在行） */
        private const val PROGRESS_ROW_DP = 48

        /** 上一首 / 下一首的**触摸热区**边长（图标本身无背景、更小） */
        private const val NAV_TOUCH_DP = 40

        /**
         * 上一首 / 下一首的图标内边距。
         *
         * 热区 44dp 减去两侧内边距 → 图标实际约 26dp，
         * 接近系统媒体控件的次要按钮体量（原来 48dp 的块面按钮太抢眼）。
         */
        private const val NAV_ICON_PADDING_DP = 10

        /**
         * 播放/暂停按钮边长。
         *
         * 刻意与音乐锁屏（MusicLockView 里的 dp(70)）**取同一个值**：
         * 两个界面一致，转场时按钮就不需要缩放，看起来才是同一个按钮在长大。
         */
        private const val PLAY_BUTTON_DP = 70

        /**
         * 播放按钮的下方留白（dp），决定它在卡片里的垂直位置。
         *
         * 越小越靠下。18dp 是"能往下放到的最低安全位置"（底边距进度行顶还有约 14px），
         * 再小就会压到进度行上。
         */
        private const val PLAY_BUTTON_BOTTOM_MARGIN_DP = 18

        /** 按钮常态圆角；按下时动画到高度一半（完全圆润） */
        private const val BUTTON_CORNER_DP = 16

        /** 长按多久进入拖动模式 */
        /** 小岛圆形播放键的直径（dp） */
        private const val COMPACT_PLAY_DP = 40

        /** 小岛上/下一首圆钮的直径（dp）：比播放键略小，但仍成组 */
        private const val COMPACT_SIDE_DP = 34

        private const val LONG_PRESS_MS = 450L

        /**
         * 触发形态切换所需的纵向拖动距离（dp）。
         *
         * 太小会误触（长按时的轻微手抖就切形态），太大则要拖很久。
         * 48dp 大约是小岛高度（56dp）的八成，手感上"明显往上/下推了一下"。
         */
        private const val MORPH_DRAG_THRESHOLD_DP = 48

        /** 压缩过程中的模糊峰值（dp）—— 用来遮"形状没变成小岛"的瑕疵 */
        private const val MORPH_BLUR_DP = 6
        /**
         * 拖动允许的垂直范围。
         *
         * ============ 上限必须覆盖默认值（真机 bug） ============
         *
         * 原来上限是 0.82，但默认值是 84%（「岛底部贴指纹上方」）——
         * 也就是说岛一开局就在允许范围**之外**，用户一拖就被夹回 82%，
         * 而且**永远无法把岛拖回到默认位置**。
         *
         * 真机日志证据：
         *   拖动中：垂直位置 -> 82%（在 84% 处拖动只能到 82）
         *
         * 重新推算上限：岛底边不得低于快捷方式顶部（实测 2360）。
         *   bottom = (H - h) * bias + h ≤ 2360
         *   代入 H=2640、h=196：(2444)*bias + 196 ≤ 2360 → bias ≤ 0.885
         * 取 0.88 留一点余量，这样 84% 的默认值与往上拖都有空间。
         * 下限 0.30 对应 top ≈ 733px，仍在大时钟底部（597）之下。
         */

        /** 双指缩放允许的宽度范围（占屏百分比） */
        private const val MIN_WIDTH_PERCENT = 40
        private const val MAX_WIDTH_PERCENT = 100

        /**
         * 中岛里每个媒体按钮的边长。
         *
         * 真机实测（密度 3.5）：36dp 时按钮组共 378px，占岛宽 35%，
         * 把文字列挤到 339px；而图标本身只要约 24dp。
         * 收到 30dp → 按钮组 315px，文字列回升到约 402px，
         * 且按钮组右边界仍在岛内（1004 < 1070，实测确认不溢出）。
         */
        private const val BUTTON_SIZE_DP = 30

        const val STATE_IDLE = 0
        const val STATE_MEDIA = 1
        const val STATE_STATUS = 2

        const val BTN_PREV = 1
        const val BTN_PLAY = 2
        const val BTN_NEXT = 3

        private const val TITLE_EXPANDED_ID = 0x1A150011
        private const val SUBTITLE_EXPANDED_ID = 0x1A150012
        private const val TIME_NOW_ID = 0x1A150013
        private const val TIME_TOTAL_ID = 0x1A150014
        private const val SEEK_ID = 0x1A150015
        private const val BTN_PREV_ID = 0x1A150016
        private const val BTN_PLAY_ID = 0x1A150017
        private const val BTN_NEXT_ID = 0x1A150018

        private const val GLYPH_PREV = 1
        private const val GLYPH_PLAY = 2
        private const val GLYPH_PAUSE = 3
        private const val GLYPH_NEXT = 4

        private const val ACCENT = 0xFFD0BCFF.toInt()

        /**
         * 次级文字（歌手名、时间轴）。
         *
         * 85% 白同样有测试守着：它必须在中灰壁纸（比纯白更"吃"对比度的
         * 中间地带）上仍达到 AA。70% 白在中灰壁纸上只有约 3.3:1，不达标。
         */
        private const val SECONDARY = 0xCCFFFFFF.toInt()

        /**
         * 胶囊背景。
         *
         * 72% 黑不是凭感觉定的：ContrastMathTest 里对纯白/纯黑/亮黄/亮青/中灰
         * 五种极端壁纸做了验证，白字对比度均达到 WCAG AA（4.5:1）以上。
         *
         * 最初我写的是 0x99（60%），在纯白壁纸上白字对比度只有约 5.7:1 ——
         * 勉强合格，而且"灰底白字"观感很差。改成 72% 后最低也有 11:1。
         */
        private const val DEFAULT_SCRIM = 0xB8000000.toInt()

        private const val HAIRLINE = 0x33FFFFFF
    }
}
