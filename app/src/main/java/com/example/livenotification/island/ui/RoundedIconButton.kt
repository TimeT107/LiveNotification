package com.example.livenotification.island.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import com.example.livenotification.island.util.IslandLog

/**
 * 圆角矩形图标按钮：按下时圆角**动画变圆**。
 *
 * ============ 为什么单独写一个 ============
 *
 * 设计稿要求"按钮为圆角矩形，点下去时变圆一下"。平台 ImageButton 做不到
 * 圆角随按压状态动起来 —— 只能自己持有 GradientDrawable 并插值 cornerRadius。
 *
 * 水波纹不在本类里做：设计要的是**整张卡片**一起泛开水波纹，
 * 而不是每个按钮各自一小圈。那由 IslandView 的卡片前景 RippleDrawable
 * 统一处理（见 IslandView.dispatchTouchEvent）。
 * 本类只负责：填充色、圆角、按下时的圆角动画、图标。
 */
class RoundedIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageButton(context, attrs) {

    private val shape = GradientDrawable()

    /** 常态圆角 */
    private var baseRadiusPx = 0f

    /** 按下时的圆角（= 高度一半，即完全圆润） */
    private var roundRadiusPx = 0f

    private var currentRadius = 0f

    private var radiusAnimator: ValueAnimator? = null

    init {
        shape.shape = GradientDrawable.RECTANGLE
        background = shape
        scaleType = ScaleType.CENTER
        isClickable = true
        isFocusable = false
    }

    /**
     * @param fillColor 填充色（半透明表面色，压在专辑封面上）
     * @param cornerPx 常态圆角
     */
    fun configure(fillColor: Int, cornerPx: Int) {
        shape.setColor(fillColor)
        baseRadiusPx = cornerPx.toFloat()
        // 正圆（OVAL）不吃 cornerRadius；尺寸已知时顺带重判一次形状
        val circle = baseRadiusPx >= minOf(width, height) / 2f - 1f
        if (circle && width > 0 && height > 0) {
            isCircle = true
            shape.shape = GradientDrawable.OVAL
            shape.cornerRadius = 0f
        } else if (!isPressed) {
            currentRadius = baseRadiusPx
            shape.cornerRadius = currentRadius
        }
        invalidate()
    }

    /** 设置图标；[glyph] 为 Glyphs 里的编号 */
    fun setGlyph(glyph: Int, color: Int) {
        setImageDrawable(Glyphs.create(context, glyph, color))
    }

    /** 是否为正圆（申请圆角 >= 短边一半时即视为圆） */
    private var isCircle = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        roundRadiusPx = h / 2f

        // ============ 为什么用 OVAL 而不是"大圆角的矩形" ============
        //
        // 真机现象：中岛右侧的播放键画出来像**平底半圆**（转场结束后有一帧定住这样），
        // 但布局诊断显示它是 245x245、申请圆角 = 高度一半，本该是正圆。
        //
        // 与其继续依赖 `cornerRadius` 的取整/夹紧行为（不同版本、不同硬件层缓存下
        // 表现可能不一致），不如在"想要圆"的时候**直接声明成椭圆**：
        // 正方形 + OVAL 在几何上就是正圆，不存在被裁掉一半的可能。
        isCircle = baseRadiusPx >= minOf(w, h) / 2f - 1f
        shape.shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (isCircle) {
            shape.cornerRadius = 0f
        } else if (!isPressed) {
            currentRadius = baseRadiusPx.coerceAtMost(roundRadiusPx)
            shape.cornerRadius = currentRadius
        }
        // 诊断：真机截图里播放键看起来像"平底半圆"，但布局诊断显示它是 245x245、
        // 圆角=高度一半（本应是正圆）。所以要看**背景 Drawable 的实际绘制边界**
        // 是否与 View 边界一致 —— 不一致就会画出被裁一半的圆。
        post {
            IslandLog.i(
                "按钮几何自检：view=" + width + "x" + height +
                    " drawableBounds=" + shape.bounds +
                    " cornerRadius=" + shape.cornerRadius +
                    " baseRadius=" + baseRadiusPx +
                    " roundRadius=" + roundRadiusPx +
                    " 类型=" + javaClass.simpleName
            )
        }
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed == isPressed) {
            super.setPressed(pressed)
            return
        }
        super.setPressed(pressed)
        // 按住 → 变圆；松开 → 回到圆角矩形
        val target = if (pressed) roundRadiusPx else baseRadiusPx
        animateRadiusTo(target)
    }

    private fun animateRadiusTo(target: Float) {
        // 正圆没有"变圆"的余地：形状恒为 OVAL，按下反馈交给卡片水波纹
        if (isCircle) return
        radiusAnimator?.cancel()
        val from = currentRadius
        val to = target.coerceAtMost(roundRadiusPx.coerceAtLeast(baseRadiusPx))
        if (kotlin.math.abs(to - from) < 0.5f) return
        radiusAnimator = ValueAnimator.ofFloat(from, to).apply {
            // 按下快、回弹稍慢，符合 Material 的"按下立即反馈"
            duration = if (to > from) 120L else 180L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { a ->
                currentRadius = a.animatedValue as Float
                shape.cornerRadius = currentRadius
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        radiusAnimator?.cancel()
        radiusAnimator = null
        super.onDetachedFromWindow()
    }
}
