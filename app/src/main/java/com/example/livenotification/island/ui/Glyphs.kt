package com.example.livenotification.island.ui

import android.content.Context
import android.graphics.Path
import android.graphics.drawable.Drawable

/**
 * 纯代码矢量图标。
 *
 * 不用 PNG、不用 SystemUI 的 drawable 资源 —— 避免换 ROM 就找不到资源。
 * 需要新图标时只要在这里加一个 case。
 */
object Glyphs {

    const val PREV = 1
    const val PLAY = 2
    const val PAUSE = 3
    const val NEXT = 4
    const val CHARGING = 5
    const val HEADSET = 6
    const val TIMER = 7

    /** 无封面时的占位音符 */
    const val MUSIC = 8

    // ============ 输出设备（Google 风格，用户要求"换成 Google 图标"）============
    //
    // 自绘而不是引 material-icons-extended：那个库会让 APK 多几 MB，
    // 而我们只要 4 个图标。形状按 Material Symbols（filled）的轮廓手工转成 Path，
    // 与其它 glyph 同一套语言（24 视口 + 单色填充），风格自然统一。
    const val SPEAKER = 9
    const val HEADPHONES = 10
    const val USB = 11

    private const val VIEWPORT = 24f

    fun create(context: Context, glyph: Int, color: Int): Drawable {
        val path = Path()
        when (glyph) {
            PREV -> {
                path.moveTo(19f, 5f); path.lineTo(19f, 19f); path.lineTo(9f, 12f); path.close()
                path.addRect(5f, 5f, 7.4f, 19f, Path.Direction.CW)
            }

            NEXT -> {
                path.moveTo(5f, 5f); path.lineTo(5f, 19f); path.lineTo(15f, 12f); path.close()
                path.addRect(16.6f, 5f, 19f, 19f, Path.Direction.CW)
            }

            PLAY -> {
                path.moveTo(7f, 4.5f); path.lineTo(20f, 12f); path.lineTo(7f, 19.5f); path.close()
            }

            PAUSE -> {
                path.addRect(6.5f, 4.5f, 10.5f, 19.5f, Path.Direction.CW)
                path.addRect(13.5f, 4.5f, 17.5f, 19.5f, Path.Direction.CW)
            }

            CHARGING -> {
                // 电池轮廓
                path.addRect(4f, 7f, 18f, 17f, Path.Direction.CW)
                path.addRect(18.6f, 10f, 20f, 14f, Path.Direction.CW)
                // 闪电
                val bolt = Path().apply {
                    moveTo(12.6f, 6.5f); lineTo(8.6f, 13f); lineTo(11.4f, 13f)
                    lineTo(10.4f, 17.5f); lineTo(15f, 11.2f); lineTo(12.2f, 11.2f)
                    close()
                }
                path.op(bolt, Path.Op.DIFFERENCE)
            }

            HEADSET -> {
                // 头梁
                path.addRect(5f, 9f, 7.4f, 16f, Path.Direction.CW)
                path.addRect(16.6f, 9f, 19f, 16f, Path.Direction.CW)
                val arc = Path().apply {
                    addArc(4.4f, 4.4f, 19.6f, 19.6f, 190f, 160f)
                }
                path.op(arc, Path.Op.UNION)
            }

            TIMER -> {
                path.addRect(10.6f, 3f, 13.4f, 5.2f, Path.Direction.CW)
                val ring = Path().apply { addCircle(12f, 14f, 7.6f, Path.Direction.CW) }
                val inner = Path().apply { addCircle(12f, 14f, 5.6f, Path.Direction.CW) }
                path.op(ring, inner, Path.Op.DIFFERENCE)
            }

            /**
             * 八分音符（无封面占位）。
             *
             * 形状：一个实心圆符头 + 一条竖符干 + 顶部的旗。
             * 这是真机截图暴露的问题 —— 原来无封面时只画一个纯色方块，
             * 看起来像"图片加载失败"，换成音符占位才像正常的空状态。
             */
            MUSIC -> {
                path.addCircle(8.5f, 17f, 4.2f, Path.Direction.CW)
                path.addRect(11.6f, 5f, 14.0f, 17f, Path.Direction.CW)
                val flag = Path().apply {
                    moveTo(14f, 5f)
                    lineTo(20f, 7.4f)
                    lineTo(20f, 10.6f)
                    lineTo(14f, 8.2f)
                    close()
                }
                path.op(flag, Path.Op.UNION)
            }

            /**
             * 扬声器（本机外放）。
             *
             * Material "speaker" 的填充版：一个圆角箱体，上面一个小孔（高音）、
             * 下面一个大孔（喇叭单元）。两个孔用 DIFFERENCE 挖出来 ——
             * 单色 Drawable 没法画两种颜色，挖孔反而更接近原图。
             */
            SPEAKER -> {
                path.addRoundRect(5f, 2f, 19f, 22f, 2.4f, 2.4f, Path.Direction.CW)
                val holes = Path().apply {
                    addCircle(12f, 6.4f, 2.0f, Path.Direction.CW)
                    addCircle(12f, 14.6f, 4.6f, Path.Direction.CW)
                }
                path.op(holes, Path.Op.DIFFERENCE)
            }

            /**
             * 头戴耳机（蓝牙/BLE）。
             *
             * Material "headphones" 的填充版：一条**半圆环**头梁 + 左右两个耳罩。
             * 头梁做法：大圆 - 小圆 = 圆环，再与"上半矩形"取交集只留上半，
             * 最后与两个耳罩 UNION（否则头梁和耳罩接缝处会有细缝）。
             */
            HEADPHONES -> {
                val band = Path().apply { addCircle(12f, 12f, 8.4f, Path.Direction.CW) }
                val bandInner = Path().apply { addCircle(12f, 12f, 6.0f, Path.Direction.CW) }
                band.op(bandInner, Path.Op.DIFFERENCE)
                val topHalf = Path().apply { addRect(2f, 1f, 22f, 12.5f, Path.Direction.CW) }
                band.op(topHalf, Path.Op.INTERSECT)
                path.op(band, Path.Op.UNION)
                val cups = Path().apply {
                    addRoundRect(3.4f, 10f, 8.2f, 18.6f, 2.2f, 2.2f, Path.Direction.CW)
                    addRoundRect(15.8f, 10f, 20.6f, 18.6f, 2.2f, 2.2f, Path.Direction.CW)
                }
                path.op(cups, Path.Op.UNION)
            }

            /**
             * USB 输出设备。
             *
             * Material "usb" 的三叉戟：竖干 + 顶端三角 + 左臂圆点 + 右臂方块 + 底端圆点。
             * 比原图简化了一点（原图有刻度细节，16dp 下画不出来也不该画）。
             */
            USB -> {
                path.addRect(11.2f, 6f, 12.8f, 19f, Path.Direction.CW)
                val head = Path().apply {
                    moveTo(12f, 3f); lineTo(9f, 7.4f); lineTo(15f, 7.4f); close()
                }
                path.op(head, Path.Op.UNION)

                path.addRect(6.6f, 10.4f, 11.2f, 11.9f, Path.Direction.CW)
                val dotLeft = Path().apply { addCircle(6.6f, 11.15f, 1.5f, Path.Direction.CW) }
                path.op(dotLeft, Path.Op.UNION)

                path.addRect(12.8f, 13.4f, 17.4f, 14.9f, Path.Direction.CW)
                path.addRect(16.0f, 12.6f, 18.8f, 15.7f, Path.Direction.CW)

                val dotBottom = Path().apply { addCircle(12f, 20.2f, 1.7f, Path.Direction.CW) }
                path.op(dotBottom, Path.Op.UNION)
            }
        }

        return TintedPathDrawable(path, color, VIEWPORT)
    }

    /**
     * 极简 Path -> Drawable。只做等比缩放 + 填充，够用且零依赖。
     */
    private class TintedPathDrawable(
        private val path: Path,
        private val color: Int,
        private val viewport: Float
    ) : Drawable() {

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@TintedPathDrawable.color
            style = android.graphics.Paint.Style.FILL
        }
        private val scaleMatrix = android.graphics.Matrix()
        private val bounds = android.graphics.RectF()
        private val transformed = Path()

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0f || b.height() <= 0f) return
            val s = minOf(b.width(), b.height()) / viewport
            scaleMatrix.reset()
            scaleMatrix.setScale(s, s)
            scaleMatrix.postTranslate(
                b.left + (b.width() - viewport * s) / 2f,
                b.top + (b.height() - viewport * s) / 2f
            )
            transformed.set(path)
            transformed.transform(scaleMatrix)
            canvas.drawPath(transformed, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(cf: android.graphics.ColorFilter?) {
            paint.colorFilter = cf
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun onBoundsChange(b: android.graphics.Rect) {
            super.onBoundsChange(b)
            bounds.set(b)
        }
    }
}
