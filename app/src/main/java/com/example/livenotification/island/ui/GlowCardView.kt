package com.example.livenotification.island.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * 音乐锁屏"整卡"的卡片层。
 *
 * ============ 光模型（参考 iOS / ColorOS 的玻璃质感）============
 *
 * 用户要的是"那种**光的感觉**"。关键不是"一圈彩色光晕"——
 * 那是均匀的，看着像模糊的边，不像光。真正的光有**方向**：
 *
 *   ① **边缘亮线（rim light）**：光从左上来，于是左边/上边被"打亮"成一条亮线，
 *      向右下逐渐变暗。这是玻璃边缘最标志性的特征。
 *   ② **外侧环境光（ambient glow）**：沿边缘向外扩散的柔和光晕，
 *      **同样有方向** —— 左上更亮。
 *   ③ **内侧顶部高光（inner highlight）**：紧贴卡片内侧顶部有一道很淡的白色高光，
 *      向下 ~28% 处消失。它让卡片"有厚度"，是 iOS 毛玻璃观感的核心。
 *
 * 所以描边、光晕、高光**三者共用同一个 45° 线性渐变**
 * （左上亮 → 右下暗），整体才像"同一束光打在玻璃上"。
 *
 * ============ 为什么不用 BlurMaskFilter ============
 *
 * 真正的发光一般用 `BlurMaskFilter`，但它要求视图走**软件层**；
 * 而这张卡片在转场中是**逐帧变化**的，全屏软件光栅化代价太大。
 * 这里用"**多圈递减 alpha 的描边**"模拟光晕 —— 零软件层开销，
 * 真机上与模糊发光几乎看不出差别。
 *
 * 视图自身的绘制会被裁剪在 bounds 内，所以发光要画到卡片外面，
 * 视图就必须**比卡片大一圈**（`glowPadPx`），由调用方负责给足 padding。
 */
class GlowCardView(context: Context) : View(context) {

    /** 卡片圆角（px） */
    var cornerPx = 0f

    /** 磨砂填充色（建议：专辑莫奈色 + 很低的 alpha） */
    var fillColor = 0

    /** 光的**色相**来源（建议：专辑封面的莫奈色）。alpha 由渐变决定，不用这里的 alpha */
    var lightColor = 0

    /** 内侧高光色（通常是白，alpha 很淡） */
    var highlightColor = 0x14FFFFFF

    /** 发光向外扩散的距离（px）。视图 bounds 必须比卡片矩形大出这么多 */
    var glowPadPx = 0f

    var borderWidthPx = 0f

    /** 光晕圈数：越多越柔和（代价线性增长） */
    var glowSteps = 18

    /** 光源方向：亮的一端（"左上"=光从左上打来，右下变暗） */
    var lightFromTopLeft = true

    var drawFill = false
    var drawBorder = true
    var drawGlow = true
    var drawHighlight = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val hiPath = Path()

    override fun onDraw(canvas: Canvas) {
        val pad = glowPadPx
        rect.set(pad, pad, width - pad, height - pad)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        // ============ 一束光：45° 线性渐变，左上亮 → 右下暗 ============
        // 描边、光晕、高光**共用**它，整体才像同一束光。
        val rgb = lightColor and 0x00FFFFFF
        val gx0 = if (lightFromTopLeft) rect.left else rect.right
        val gy0 = rect.top
        val gx1 = if (lightFromTopLeft) rect.right else rect.left
        val gy1 = rect.bottom

        // ① 外侧环境光：多圈由外向内，alpha 递增；越靠内越亮
        if (drawGlow && glowPadPx > 1f) {
            strokePaint.style = Paint.Style.STROKE
            // ⚠ 线宽必须**大于**圈间距，让相邻圈**重叠** ——
            // 否则每一圈都是独立可见的线，出现"同心圈分层"（banding），看着像画了几条线
            // 而不是一片光。真机上第一版就是这么翻车的（8 圈 × 2px，间距 7px）。
            val spacing = glowPadPx / glowSteps
            strokePaint.strokeWidth = (spacing * 2.4f).coerceAtLeast(2f)
            for (i in glowSteps downTo 1) {
                val inset = spacing * i
                // 最外圈最淡，贴近边框那圈最亮；圈数多且重叠，所以峰值要压低
                val peak = (0x22 * (1f - (i - 1).toFloat() / glowSteps)).toInt().coerceIn(0, 0xFF)
                strokePaint.shader = LinearGradient(
                    gx0, gy0, gx1, gy1,
                    intArrayOf(
                        rgb or (peak shl 24),
                        rgb or ((peak / 3) shl 24),
                        rgb or ((peak / 8) shl 24)
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                rect.inset(-inset, -inset)
                canvas.drawRoundRect(rect, cornerPx + inset, cornerPx + inset, strokePaint)
                rect.inset(inset, inset)
            }
            strokePaint.shader = null
        }

        // ② 磨砂填充
        if (drawFill && (fillColor ushr 24) != 0) {
            fillPaint.color = fillColor
            canvas.drawRoundRect(rect, cornerPx, cornerPx, fillPaint)
        }

        // ③ 内侧顶部高光：让卡片"有厚度"（iOS 毛玻璃的核心特征）
        if (drawHighlight && (highlightColor ushr 24) != 0) {
            val h = rect.height() * 0.28f
            hiPaint.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + h,
                highlightColor, 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
            hiPath.reset()
            hiPath.addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
            canvas.save()
            // 只在高光带内绘制（否则会覆盖整张卡片）
            canvas.clipRect(rect.left, rect.top, rect.right, rect.top + h)
            canvas.drawPath(hiPath, hiPaint)
            canvas.restore()
            hiPaint.shader = null
        }

        // ④ 边缘亮线（rim light）：同样带方向，左上最亮
        if (drawBorder && borderWidthPx > 0f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = borderWidthPx
            strokePaint.shader = LinearGradient(
                gx0, gy0, gx1, gy1,
                intArrayOf(
                    rgb or 0xE6000000.toInt(),   // 左上：接近实色的亮线
                    rgb or 0x80000000.toInt(),   // 中段
                    rgb or 0x33000000             // 右下：几乎消失
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            val half = borderWidthPx / 2f
            rect.inset(half, half)
            canvas.drawRoundRect(rect, cornerPx, cornerPx, strokePaint)
            rect.inset(-half, -half)
            strokePaint.shader = null
        }
    }
}
