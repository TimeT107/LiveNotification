package com.example.livenotification.island.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * MD3 波浪进度条（Material 3 expressive 的 wavy progress indicator）。
 *
 * ============ 设计要点 ============
 *
 * · 已播放部分画成**正弦波浪**，未播放部分是一条细直线 —— 这正是 MD3 波浪
 *   指示器的语义：动态部分有形态，静止部分是克制的细线。
 * · 波浪相位在播放时缓慢流动，让"正在播放"可感知；暂停时停住。
 * · 播放位置末端画一个实心圆点，充当拖动把手，也强化当前进度位置。
 *
 * ============ 为什么自己画，不用 ProgressBar ============
 *
 * 平台 ProgressBar 没有波浪形态；MD3 的 wavy indicator 在 Compose 里，
 * 而本模块是纯 View 世界（且要跨 ClassLoader 避免依赖 SystemUI 的那套类）。
 * 自己用 Path 画最省事，也不受主题/版本影响。
 *
 * 触摸：整条都可点可拖，用来 seek（对应原来的 SeekBar 功能）。
 */
class WavyProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ------------------------------------------------------------------
    // 可配置项（由 IslandView 按主题色写入）
    // ------------------------------------------------------------------

    /** 已播放部分的颜色 */
    var activeColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            activePaint.color = value
            invalidate()
        }

    /** 未播放部分（轨道）的颜色 */
    var trackColor: Int = 0x55FFFFFF
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    /** 波浪振幅（px）。MD3 大约 3dp。 */
    var amplitudePx: Float = dp(3f)
        set(value) {
            field = value
            invalidate()
        }

    /** 波浪波长（px）。MD3 大约 16dp 一个波峰周期。 */
    var wavelengthPx: Float = dp(18f)
        set(value) {
            field = value
            invalidate()
        }

    /** 线宽（px）。 */
    var strokePx: Float = dp(4f)
        set(value) {
            field = value
            activePaint.strokeWidth = value
            trackPaint.strokeWidth = value
            invalidate()
        }

    // ------------------------------------------------------------------

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(4f)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(4f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val wavePath = Path()

    /** 轨道波浪路径（与活动段同相位，保证整条进度条形状连续） */
    private val trackPath = Path()

    /**
     * 是否使用"平直"风格（细直线，而不是 MD3 正弦波浪）。
     *
     * **当前 = false（波浪）** —— 用户试过平直后要求改回波浪。
     *
     * 注意：One UI 样式的音乐锁屏**不靠这个开关**做平直，而是在
     * `MusicLockView.buildOneUiLayout` 里把 `amplitudePx` 设为 0（振幅为零 = 直线）。
     * 这样两种样式能共存、互不影响。
     */
    private val FLAT_STYLE = false

    /** 0..1 */
    private var fraction = 0f

    private var phase = 0f

    /**
     * 振幅缩放：0 = 完全平整（直线），1 = 满振幅波浪。
     * 由 [setAnimating] 在播放状态变化时插值，得到"波浪↔直线"的平滑过渡。
     */
    private var amplitudeScale = 0f

    private var amplitudeAnimator: ValueAnimator? = null

    /** 上一次的播放状态，避免轮询重复触发同一过渡 */
    private var lastAnimating = false

    private var phaseAnimator: ValueAnimator? = null

    /** 开始拖动（控制器据此暂停用轮询结果覆盖进度） */
    var onSeekStart: (() -> Unit)? = null

    /** 拖动中（0..1），用于实时刷新显示 */
    var onSeekMove: ((Float) -> Unit)? = null

    /** 松手提交（0..1），控制器据此真正 seek */
    var onSeekEnd: ((Float) -> Unit)? = null

    private var dragging = false

    fun setProgressFraction(value: Float) {
        val f = value.coerceIn(0f, 1f)
        if (f == fraction) return
        fraction = f
        invalidate()
    }

    fun getProgressFraction(): Float = fraction

    /**
     * 播放状态变化。
     *
     * ============ 行为（对齐系统控件） ============
     *
     * · 播放中：波浪流动（相位动画）+ 振幅回到满值
     * · 暂停时：波浪**变平整**（振幅动画到 0，成一条直线），相位停住
     *
     * 真机反馈："不播放的时候波浪变为平整（系统那种）"—— MD3 的 wavy indicator
     * 在非活动状态就是一条直线，只有活动时才起伏。所以这里不是"停住保持波浪"，
     * 而是把**振幅**动画到 0。
     */
    fun setAnimating(animating: Boolean) {
        // 状态没变就直接返回：控制器每秒轮询都会调 setPlaying →
        // 若每次都重建动画器，振幅会永远在过渡中被反复打断。
        if (animating == lastAnimating) return
        lastAnimating = animating

        // 振幅过渡：0 = 平整直线，1 = 满振幅波浪
        amplitudeAnimator?.cancel()
        amplitudeAnimator = ValueAnimator.ofFloat(amplitudeScale, if (animating) 1f else 0f).apply {
            duration = 260L
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                amplitudeScale = a.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (animating) {
            if (phaseAnimator?.isRunning == true) return
            phaseAnimator?.cancel()
            phaseAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
                duration = 1600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    // 相位取模，避免浮点无限增长后精度丢失
                    phase = (a.animatedValue as Float) % (2 * Math.PI).toFloat()
                    invalidate()
                }
                start()
            }
        } else {
            phaseAnimator?.cancel()
            phaseAnimator = null
        }
    }

    /** 当前是否处于波浪形态（供诊断日志用） */
    fun isWavy(): Boolean = amplitudeScale > 0.5f

    override fun onDetachedFromWindow() {
        phaseAnimator?.cancel()
        phaseAnimator = null
        amplitudeAnimator?.cancel()
        amplitudeAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val w = width.toFloat()
        if (w <= 0f) return

        // 未播放：细直线（MD3 里静止轨道是克制的）
        // ============ 轨道也画成波浪（真机反馈"灰色直条会露出来"）============
        //
        // 原来轨道是一条**直线**、活动段是波浪，两者形状不一致：
        // 波浪起伏时就会在上下露出直线轨道，看着像"背景条"很突兀。
        //
        // 现在轨道用与活动段**同相位、同振幅**的波浪线 ——
        // 整条进度条是一个连续的波浪，只是"已播放"部分用强调色、
        // "未播放"部分用低透明度同色。这样中岛与音乐锁屏的形状也一致。
        val trackAmp = if (FLAT_STYLE) 0f else amplitudePx * amplitudeScale
        trackPath.reset()
        trackPath.moveTo(0f, cy)
        val trackK = (2 * Math.PI / wavelengthPx.coerceAtLeast(1f)).toFloat()
        var tx = 2f
        while (tx <= w) {
            trackPath.lineTo(tx, cy + sin(tx * trackK + phase) * trackAmp)
            tx += 2f
        }
        trackPath.lineTo(w, cy + sin(w * trackK + phase) * trackAmp)
        canvas.drawPath(trackPath, trackPaint)

        val activeW = w * fraction
        if (activeW <= 0f) return

        // 已播放部分的振幅。
        //
        // 两条路都能得到"平直"，不要搞混：
        //   · 全局平直 → FLAT_STYLE = true
        //   · 只让某个实例平直（One UI 样式）→ 把该实例的 amplitudePx 设为 0
        // 当前两者都没启用（FLAT_STYLE=false，旧样式 amplitudePx 非零），所以是**波浪**。
        // 另外 amplitudeScale 会随播放状态插值：暂停时趋近 0 → 自动变成一条直线。
        val amp = if (FLAT_STYLE) 0f else amplitudePx * amplitudeScale
        wavePath.reset()
        wavePath.moveTo(0f, cy)
        val step = 2f
        var x = step
        val k = (2 * Math.PI / wavelengthPx.coerceAtLeast(1f)).toFloat()
        while (x <= activeW) {
            val y = cy + sin(x * k + phase) * amp
            wavePath.lineTo(x, y)
            x += step
        }
        // 收尾对齐到精确终点，避免波浪在末端被截断成奇怪的形状
        val endY = cy + sin(activeW * k + phase) * amp
        wavePath.lineTo(activeW, endY)

        activePaint.color = activeColor
        canvas.drawPath(wavePath, activePaint)

        // 末端圆点（同时充当拖动把手的视觉表示）
        dotPaint.color = activeColor
        canvas.drawCircle(activeW, endY, strokePx * 1.35f, dotPaint)
    }

    // ------------------------------------------------------------------
    // 触摸 seek
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // ============ 只有落在**轨道附近**才认作 seek（真机 bug 修复）============
                //
                // 用户反馈"媒体控制的按钮点击不了"。根因就在这里：
                // 这个视图无条件 `return true` 消费 ACTION_DOWN，而它在
                // `expandedContent` **之后**加入 -> 位于播放控件**之上** ->
                // 落在控制行上的点击被它吃掉，永远到不了按钮。
                //
                // 轨道画在视图**垂直居中**处，所以离中心超过一定距离的按下
                // 一律放行（return false），交给下面的按钮。
                val cy = height / 2f
                val band = 28f * resources.displayMetrics.density
                if (height > 0 && kotlin.math.abs(event.y - cy) > band) {
                    return false
                }
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeekStart?.invoke()
                applyTouch(event.x, commit = false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    applyTouch(event.x, commit = false)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    applyTouch(event.x, commit = true)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 取消不提交，保持当前显示
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyTouch(x: Float, commit: Boolean) {
        val w = width.toFloat()
        if (w <= 0f) return
        val f = (x / w).coerceIn(0f, 1f)
        fraction = f
        invalidate()
        if (commit) onSeekEnd?.invoke(f) else onSeekMove?.invoke(f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
