package com.example.livenotification.island.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * 音乐锁屏的封面层：中心裁切（等价 Compose 的 `ContentScale.Crop`）+ 可选圆角。
 *
 * ============ 为什么把"渐进模糊"删掉了 ============
 *
 * 最初这里做了"从清晰逐渐模糊"的效果，用「缩到 1/10 再放大回去」当廉价模糊。
 * 真机反馈是**看起来像马赛克**：双线性放大 10 倍得到的是方块状伪影，不是平滑虚化
 * （用户原话："下半部分也被模糊成马赛克了"）。
 *
 * 而且参照的项目 `MediaControlOverlay.kt` **压根没有模糊** —— 它的做法就是
 * 「封面铺满 + 一段多段竖向渐变压到 #0F0F0F」。所谓"渐变感"来自那段渐变，
 * 不是来自模糊。
 *
 * 所以这里只画清晰封面，层次的过渡交给渐变（见 [MusicScrimStops]）。
 *
 * ============ 为什么自绘而不是直接用 ImageView ============
 *
 * 转场时封面是**连续改布局边界**的（而不是缩放），需要每帧按新尺寸重新做中心裁切；
 * 同时圆角要能从"中岛卡片的 24dp"连续插值到"音乐锁屏的直角"。
 * ImageView 两者都做不到（scaleType 固定、圆角还要另配 outline），所以自绘。
 */
class BlurFadeCoverView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null

    private val paint =
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val clipPath = Path()
    private val clipRect = RectF()

    /**
     * 圆角半径（px）。中岛卡片是圆角、音乐锁屏是直角，
     * 转场时连续插值这个值，边角也跟着"长开"。
     */
    var cornerRadiusPx = 0f

    // 下面三个是历史遗留的对外接口（转场代码还在设置它们）。
    // 模糊已经移除，保留字段只为避免调用点大改，实际不再影响绘制。
    @Deprecated("模糊已移除：廉价模糊会产生马赛克观感")
    var solidTop = 0.54f

    @Deprecated("模糊已移除：廉价模糊会产生马赛克观感")
    var fadeStart = 0.62f

    @Deprecated("模糊已移除：廉价模糊会产生马赛克观感")
    var fadeEnd = 0.96f

    fun setCover(bmp: Bitmap?) {
        if (bmp === bitmap) return
        bitmap = bmp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val rounded = cornerRadiusPx > 0.5f
        if (rounded) {
            clipPath.reset()
            clipRect.set(0f, 0f, w, h)
            clipPath.addRoundRect(
                clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(clipPath)
        }

        // ============ 清晰层：向下"溶掉"，露出下层的高斯模糊 ============
        //
        // 构造说明（这是"真模糊"的正确做法）：
        //   · 本视图**下面**垫着一层同样封面的 ImageView，它带 RenderEffect 真高斯模糊
        //   · 本视图只画**清晰**封面，然后用竖向 alpha 渐变把自己从下往上擦掉
        //   · 于是从上到下：清晰 → 逐渐过渡 → 模糊（下层的），形成"毛玻璃"质感
        //
        // 之前我用"缩到 1/10 再放大"当模糊，出来是**马赛克方块**（真机反馈），
        // 所以删掉了。RenderEffect 是 API 31+ 的系统级高斯模糊，minSdk 33 可用，
        // 效果和代价都远好于土办法。
        // 如果两个停靠点重合（如卡片样式设成 sharpUntil=fadeOutEnd=1f），
        // 渐变会退化成"零长度"，遮罩行为未定义 —— 真机上表现为**卡片边缘变成波浪形**。
        // 这种情况下直接跳过遮罩：整张图保持清晰。
        val degenerate = fadeOutEnd <= sharpUntil + 0.001f

        val sc = canvas.saveLayer(0f, 0f, w, h, null)
        centerCrop(bmp, w, h, dstRect)
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)

        if (degenerate) {
            canvas.restoreToCount(sc)
            if (rounded) canvas.restore()
            return
        }

        maskPaint.shader = android.graphics.LinearGradient(
            0f, h * sharpUntil,
            0f, h * fadeOutEnd,
            intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        maskPaint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.DST_IN
        )
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        maskPaint.xfermode = null
        maskPaint.shader = null
        canvas.restoreToCount(sc)

        if (rounded) canvas.restore()
    }

    /** 到这个高度为止保持完全清晰（占高度比例） */
    // 真机反馈"专辑封面结束部分模糊看着有些劣质"——原因是过渡带太长（34%~72%），
    // 中间露出一大段"糊糊的湿痕"。收窄到封面结束处，让模糊只作为**软边**存在，
    // 而不是一段可见的涂抹区；配合上层渐变，观感干净得多。
    var sharpUntil = 0.44f

    /** 到这个高度时清晰层完全溶掉（露出模糊层） */
    var fadeOutEnd = 0.58f

    private val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** 中心裁切铺满（等价 Compose 的 ContentScale.Crop） */
    private fun centerCrop(src: Bitmap, w: Float, h: Float, out: RectF) {
        val scale = maxOf(w / src.width, h / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        out.set(left, top, left + dw, top + dh)
    }
}
