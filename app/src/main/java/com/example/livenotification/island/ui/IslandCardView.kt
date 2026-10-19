package com.example.livenotification.island.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.livenotification.island.util.IslandLog

/**
 * 从中岛「浮出」的媒体卡片。
 *
 * ============ 为什么做成独立视图，而不是把岛撑高 ============
 *
 * 原来让 IslandView 自己从 196 长到 378 来实现展开。但岛是
 * `KeyguardRootView`（一个 ConstraintLayout）的子视图，高度一变就要求父容器
 * 重新解算约束 —— 而真机实测证明**它不会重解**：
 *     预计top=1870  实际top=2053
 * 位置纹丝不动，展开视觉上完全无效。
 *
 * 强制重解的唯一 API `ConstraintSet.clone(ConstraintLayout)` 又被
 * 类加载器隔离挡住（`Cannot cast KeyguardRootView to ConstraintLayout`），
 * 因为 SystemUI 实例的 ConstraintLayout 与模块拿到的那份不是同一个 Class。
 *
 * 解法：**根本不去改变岛的形态**。岛始终是 196 高的小胶囊；
 * 点击后在**同一个父容器里、岛的上方**放一张独立卡片，
 * 位置由我们按屏幕坐标直接 setX/setY 指定。
 * 这个容器（NotificationShadeWindowView，FrameLayout）不用约束布局，
 * 子视图坐标就是给定的值，不存在"重解约束"问题。
 *
 * 视觉上就是"从小岛里向上长出一张卡片"。
 */
class IslandCardView(context: Context) : FrameLayout(context) {

    companion object {
        const val BTN_PREV = 1
        const val BTN_PLAY = 2
        const val BTN_NEXT = 3

        private const val TAG = "[IslandCard]"
    }

    private val cover = ImageView(context)
    private val title = TextView(context)
    private val artist = TextView(context)
    private val seek = SeekBar(context)
    private val timeNow = TextView(context)
    private val timeTotal = TextView(context)
    private val btnPrev = ImageButton(context)
    private val btnPlay = ImageButton(context)
    private val btnNext = ImageButton(context)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val rect = RectF()

    private var cornerRadius = 0f
    private var foregroundColor = 0xFFFFFFFF.toInt()

    /** 点击卡片本体：收起 */
    var onDismiss: (() -> Unit)? = null

    /** 媒体按钮回调 */
    var onMediaButton: ((Int) -> Unit)? = null

    /** 拖动进度条 */
    var onSeek: ((Int) -> Unit)? = null

    private var userSeeking = false
    private var durationMs = 0L

    init {
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val r = cornerRadius.coerceAtMost(view.height / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        build()
        // 卡片整体背景是自绘的（保证圆角与颜色可控）
        setBackgroundColor(0x00000000)
    }

    private fun build() {
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val pad = dp(14)
        root.setPadding(pad, pad, pad, pad)
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 左：封面
        // 真机问题：原来 84dp 太宽，把中间文字列挤到约 230px，
        // 歌名截断成「Cabin …」、进度条被压成一个点。改成 60dp 让出空间。
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(cover, LinearLayout.LayoutParams(dp(60), dp(60)))

        // 中：标题 / 歌手 / 进度
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.apply {
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artist.apply {
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(title)
        col.addView(artist)

        val seekRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        timeNow.textSize = 9f
        timeTotal.textSize = 9f
        // 时间标签不设内边距，并给进度条留出最小宽度，避免被压成一个点
        seekRow.addView(timeNow)
        seekRow.addView(
            seek,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(6); marginEnd = dp(6) }
        )
        seekRow.addView(timeTotal)
        col.addView(seekRow)
        root.addView(
            col,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12); marginEnd = dp(8) }
        )

        // 右：三个按钮
        val btns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((btn, glyph) in listOf(
            btnPrev to Glyphs.PREV,
            btnPlay to Glyphs.PLAY,
            btnNext to Glyphs.NEXT
        )) {
            btn.setBackgroundColor(0x00000000)
            btn.scaleType = ImageView.ScaleType.CENTER
            btn.setPadding(dp(5), dp(5), dp(5), dp(5))
            btn.setOnClickListener {
                when (btn) {
                    btnPrev -> onMediaButton?.invoke(BTN_PREV)
                    btnPlay -> onMediaButton?.invoke(BTN_PLAY)
                    btnNext -> onMediaButton?.invoke(BTN_NEXT)
                }
            }
            btns.addView(btn, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
        root.addView(btns)

        // 点击收起。
        //
        // 真机问题：只在卡片本身上设 clickListener 不够 —— 进度条（SeekBar）等
        // 交互控件会吃掉触摸事件，用户点在卡片中部时收起不会触发。
        // 所以把封面与标题/歌手区也设为可点收起（这些区域大且不承载交互），
        // 只有进度条与三个按钮保持各自的独立行为。
        cover.setOnClickListener { onDismiss?.invoke() }
        title.setOnClickListener { onDismiss?.invoke() }
        artist.setOnClickListener { onDismiss?.invoke() }
        setOnClickListener { onDismiss?.invoke() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || durationMs <= 0) return
                timeNow.text = fmt(durationMs * progress / sb!!.max.coerceAtLeast(1))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                if (durationMs <= 0) return
                onSeek?.invoke((durationMs * sb!!.progress / sb.max.coerceAtLeast(1)).toInt())
            }
        })
    }

    // ------------------------------------------------------------------
    // 对外接口
    // ------------------------------------------------------------------

    fun setTexts(main: String, sub: String?) {
        title.text = main
        artist.text = sub.orEmpty()
        artist.visibility = if (sub.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setCover(bmp: android.graphics.Bitmap?) {
        if (bmp != null) cover.setImageBitmap(bmp) else cover.setImageDrawable(null)
    }

    fun setProgress(posMs: Long, durMs: Long) {
        durationMs = durMs
        if (durMs > 0) {
            seek.max = 1000
            if (!userSeeking) {
                seek.progress = ((posMs * 1000) / durMs).toInt().coerceIn(0, 1000)
            }
            timeNow.text = fmt(posMs)
            timeTotal.text = fmt(durMs)
        } else {
            seek.progress = 0
            timeNow.text = "--:--"
            timeTotal.text = "--:--"
        }
    }

    fun setPlaying(playing: Boolean) {
        btnPlay.setImageDrawable(
            Glyphs.create(context, if (playing) Glyphs.PAUSE else Glyphs.PLAY, foregroundColor)
        )
    }

    /** 套用与快捷方式同源的配色（背景、圆角、前景色） */
    fun applyShortcutStyle() {
        val ctx = context
        bgPaint.color = SystemUiResources.surfaceContainerHigh(ctx)
            ?: com.example.livenotification.island.config.IslandConfig.DEFAULT_SCRIM_COLOR
        cornerRadius = (SystemUiResources.affordanceRadiusPx(ctx) ?: dp(24)).toFloat()
        foregroundColor = SystemUiResources.onSurface(ctx) ?: 0xFF1C1B1F.toInt()

        title.setTextColor(foregroundColor)
        artist.setTextColor(withAlpha(foregroundColor, 0xB3))
        timeNow.setTextColor(withAlpha(foregroundColor, 0x99))
        timeTotal.setTextColor(withAlpha(foregroundColor, 0x99))
        strokePaint.color = withAlpha(foregroundColor, 0x33)

        btnPrev.setImageDrawable(Glyphs.create(ctx, Glyphs.PREV, foregroundColor))
        btnNext.setImageDrawable(Glyphs.create(ctx, Glyphs.NEXT, foregroundColor))
        btnPlay.setImageDrawable(Glyphs.create(ctx, Glyphs.PLAY, foregroundColor))

        invalidateOutline()
        invalidate()
        IslandLog.i(
            "$TAG 样式已套用 bg=#${Integer.toHexString(bgPaint.color)} " +
                "corner=${cornerRadius.toInt()} fg=#${Integer.toHexString(foregroundColor)}"
        )
    }

    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val r = cornerRadius.coerceAtMost(h / 2f)
        rect.set(0.5f, 0.5f, width - 0.5f, h - 0.5f)
        canvas.drawRoundRect(rect, r, r, bgPaint)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
