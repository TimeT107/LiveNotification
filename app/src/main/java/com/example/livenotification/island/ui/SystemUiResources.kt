package com.example.livenotification.island.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.example.livenotification.island.util.IslandLog

/**
 * 从 SystemUI 动态解析资源，用来让岛的样式与系统原生元素一致。
 *
 * ============ 为什么必须动态解析 ============
 *
 * 手电筒/相机快捷方式的背景是 `@drawable/keyguard_bottom_affordance_bg`：
 *   <solid android:color="@color/materialColorSurfaceContainerHigh" />
 *   <corners android:radius="@dimen/keyguard_affordance_fixed_radius" />
 *
 * `materialColorSurfaceContainerHigh` 是 **Material 主题属性**，不是固定色值 ——
 * 它会跟随 Material You 壁纸取色变化。所以硬编码一个颜色是错的，
 * 必须按名字去 SystemUI 那边解析，才能和快捷方式始终一致。
 *
 * 解析靠 `Resources.getIdentifier(name, type, "com.android.systemui")` +
 * `Theme.resolveAttribute`。这两个都是框架层 API，不涉及类加载器隔离问题。
 */
object SystemUiResources {

    private const val PKG = "com.android.systemui"

    private const val TAG = "[SystemUiRes]"

    /** 快捷方式背景的 drawable 名（与 DefaultShortcutsSection 用的完全一致） */
    private const val AFFORDANCE_BG_DRAWABLE = "keyguard_bottom_affordance_bg"

    /**
     * 快捷方式背景色。
     *
     * ============ 取色策略（按可靠性排序） ============
     *
     * `materialColorSurfaceContainerHigh` 是 **Material 库的颜色资源**，不在
     * SystemUI 自己的 res 里，所以 `getIdentifier(..., PKG)` 找不到它
     * （真机日志：`找不到 attr materialColorSurfaceContainerHigh`）。
     *
     * 更可靠的做法：直接加载 SystemUI 的 `keyguard_bottom_affordance_bg`，
     * 那正是手电筒/相机用的背景 —— 颜色**保证一致**，不用去猜属性名。
     *
     * 1) `createPackageContext(PKG)` 拿到 SystemUI 的 Resources，
     *    加载 drawable，从 GradientDrawable 里取 color
     * 2) 退化：按名字当 attr 解析
     */
    fun surfaceContainerHigh(ctx: Context): Int? {
        fromAffordanceDrawable(ctx)?.let { return it }
        return resolveThemeColor(ctx, "materialColorSurfaceContainerHigh")
    }

    /**
     * 加载 SystemUI 的快捷方式背景 drawable，取出它的填充色。
     *
     * 注意用 `CONTEXT_IGNORE_SECURITY`：跨 UID 访问 SystemUI 的资源需要它。
     * 这里只读资源、不加载对方代码，是安全的。
     */
    private fun fromAffordanceDrawable(ctx: Context): Int? {
        return try {
            @Suppress("DEPRECATION")
            val sysUiCtx = ctx.createPackageContext(
                PKG,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val res = sysUiCtx.resources
            val id = res.getIdentifier(AFFORDANCE_BG_DRAWABLE, "drawable", PKG)
            if (id == 0) {
                IslandLog.w("$TAG 找不到 drawable $AFFORDANCE_BG_DRAWABLE")
                return null
            }
            val d = res.getDrawable(id, sysUiCtx.theme) ?: return null
            val color = extractSolidColor(d)
            if (color != null) {
                IslandLog.i(
                    "$TAG $AFFORDANCE_BG_DRAWABLE -> #${Integer.toHexString(color)}" +
                        "（与手电筒/相机背景同源）"
                )
            } else {
                IslandLog.w("$TAG $AFFORDANCE_BG_DRAWABLE 不是纯色 shape，无法取色")
            }
            color
        } catch (t: Throwable) {
            IslandLog.w("$TAG 读取 SystemUI 资源失败: ${t.message}")
            null
        }
    }

    /**
     * 前景色（文字/图标）。对应 Material 的 `colorOnSurface` ——
     * 快捷方式里的手电筒/相机图标就是用这个色。
     *
     * ============ 为什么必须跟随主题 ============
     *
     * 我最初把文字写死成白色，并假定背景是深色（还为此写了对比度测试）。
     * 但背景一旦跟随系统取色变成浅粉（#fae3e0），白字就完全看不清了 ——
     * 真机截图直接暴露了这个问题。
     * 所以前景色也必须从同一套主题里取，才能保证任何取色下都可读。
     */
    fun onSurface(ctx: Context): Int? {
        resolveThemeColor(ctx, "materialColorOnSurface")?.let { return it }
        // 退化：Android 12+ 的标准属性名
        return resolveThemeColor(ctx, "android:colorForeground")
    }

    /**
     * 「莫奈取色」（Material You / Monet）的容器色，用于中岛主按钮背景。
     *
     * ============ 为什么要逐个候选尝试 ============
     *
     * 真机教训：`materialColorOnSurface` 这类 Material 属性在 SystemUI 的
     * theme 里**查不到**（上次日志里就是 `找不到 attr materialColorOnSurface`），
     * 它们来自 Material 库而不是 SystemUI 自己的资源。
     *
     * 所以这里按优先级尝试多个候选名，返回第一个能解析出来的：
     *   1. materialColorPrimaryContainer —— MD3 里承载主操作按钮的标准容器色
     *   2. materialColorSurfaceContainerHighest —— 系统媒体卡片的播放键用的就是这类
     *   3. materialColorSurfaceVariant
     *   4. android:colorAccent / android:colorPrimary —— 平台兜底
     *
     * 全部失败时返回 null，由调用方退化为半透明白（至少保证可见性）。
     */
    fun monetContainer(ctx: Context): Int? {
        val candidates = listOf(
            "materialColorPrimaryContainer",
            "materialColorSurfaceContainerHighest",
            "materialColorSurfaceVariant",
            "materialColorSecondaryContainer",
            "android:colorAccent",
            "android:colorPrimary"
        )
        for (name in candidates) {
            val c = resolveThemeColor(ctx, name)
            if (c != null) {
                IslandLog.i("$TAG 按钮容器色取自 $name = #${Integer.toHexString(c)}")
                return c
            }
        }
        IslandLog.w("$TAG 莫奈容器色全部候选都解析失败，按钮将使用半透明白")
        return null
    }

    /** 从各种可能的 Drawable 形态里挖出纯色 */
    private fun extractSolidColor(d: android.graphics.drawable.Drawable): Int? {
        when (d) {
            is GradientDrawable -> {
                // GradientDrawable 的 color 是 API 24+，且可能是 ColorStateList
                val cs = d.color
                if (cs != null) return cs.defaultColor
                // 没有 color 说明可能是渐变
                return null
            }

            is android.graphics.drawable.ShapeDrawable -> {
                val paint = d.paint
                return paint.color
            }

            is android.graphics.drawable.InsetDrawable -> {
                val inner = d.drawable
                return if (inner != null) extractSolidColor(inner) else null
            }

            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until d.numberOfLayers) {
                    extractSolidColor(d.getDrawable(i))?.let { return it }
                }
                return null
            }

            is android.graphics.drawable.RippleDrawable -> {
                // 手电筒/相机可能包了 ripple，取它的内容层
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    d.getDrawable(0)?.let { return extractSolidColor(it) }
                }
                return null
            }
        }
        return null
    }

    /** 快捷方式的圆角半径（px）；解析失败返回 null */
    fun affordanceRadiusPx(ctx: Context): Int? =
        resolveDimensionPx(ctx, "keyguard_affordance_fixed_radius", PKG)

    /**
     * 构造与快捷方式完全一致的背景。
     *
     * 注意这里的圆角用的是**快捷方式的 radius 资源**（24dp），
     * 而不是"高度的一半"——虽然数值上对 48dp 高度的圆角矩形而言结果一样，
     * 但直接取资源才能在系统改样式时跟着变。
     */
    fun shortcutLikeBackground(ctx: Context, cornerPx: Int): GradientDrawable? {
        val color = surfaceContainerHigh(ctx)
        if (color == null) {
            IslandLog.w("$TAG 无法解析 materialColorSurfaceContainerHigh，回退到默认深色")
        }
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        // 解析不到时用一个中性的深色（视觉上仍可接受，且保证文字对比度）
        d.setColor(color ?: 0xE61C1B1F.toInt())
        d.cornerRadius = cornerPx.toFloat()
        return d
    }

    // ------------------------------------------------------------------

    private fun resolveThemeColor(ctx: Context, attrName: String): Int? {
        return try {
            val res = ctx.resources
            val attrId = res.getIdentifier(attrName, "attr", PKG)
            if (attrId == 0) {
                IslandLog.w("$TAG 找不到 attr $attrName")
                return null
            }
            val tv = android.util.TypedValue()
            // resolveAttribute 返回的值可能是颜色，也可能是指向颜色的资源引用
            val found = ctx.theme.resolveAttribute(attrId, tv, true)
            if (!found) {
                IslandLog.w("$TAG 主题里没有 $attrName")
                return null
            }
            when {
                tv.type == android.util.TypedValue.TYPE_INT_COLOR_ARGB8 ||
                    tv.type == android.util.TypedValue.TYPE_INT_COLOR_RGB8 ||
                    tv.type == android.util.TypedValue.TYPE_INT_COLOR_ARGB4 ||
                    tv.type == android.util.TypedValue.TYPE_INT_COLOR_RGB4 -> tv.data

                tv.resourceId != 0 -> {
                    val c = res.getColor(tv.resourceId, ctx.theme)
                    IslandLog.i(
                        "$TAG $attrName -> 资源 0x${Integer.toHexString(tv.resourceId)} " +
                            "= #${Integer.toHexString(c)}"
                    )
                    c
                }

                else -> {
                    IslandLog.i("$TAG $attrName -> 直接色值 #${Integer.toHexString(tv.data)}")
                    tv.data
                }
            }
        } catch (t: Throwable) {
            IslandLog.w("$TAG 解析 $attrName 失败: ${t.message}")
            null
        }
    }

    private fun resolveDimensionPx(ctx: Context, name: String, pkg: String): Int? {
        return try {
            val id = ctx.resources.getIdentifier(name, "dimen", pkg)
            if (id == 0) {
                IslandLog.w("$TAG 找不到 dimen $name")
                return null
            }
            ctx.resources.getDimensionPixelSize(id).also {
                IslandLog.i("$TAG $name = ${it}px")
            }
        } catch (t: Throwable) {
            IslandLog.w("$TAG 解析 $name 失败: ${t.message}")
            null
        }
    }
}
