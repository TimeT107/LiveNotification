package com.example.livenotification.island.util

/**
 * 锁屏岛的几何计算（纯函数，可直接单元测试）。
 *
 * ============ PixelOS 17 锁屏纵向布局（从反编译产物实测反推） ============
 *
 * 所有数值都来自 E:\ROM\work\sysui_full 里的实际资源与 smali 常量：
 *
 *   1. 大时钟顶部（ClockSection.applyConstraints）
 *        = statusBarHeight / 2 + keyguard_smartspace_top_offset
 *          + enhanced_smartspace_height
 *        = statusBarHeight/2 + 6dp + 48dp
 *
 *   2. 小时钟底部（ClockSection + dimens）
 *        = smallClockTopMargin + small_clock_height(114dp)
 *
 *   3. 快捷方式（DefaultShortcutsSection.applyConstraints）
 *        图片底部贴父容器底部，再减去
 *          keyguard_affordance_fixed_padding(12) + fixed_height(48)
 *          + vertical_offset(32)
 *        = 屏幕高 - 92dp
 *        图片实际绘制尺寸 = fixed_width/fixed_height(48dp) + 2×padding(24dp) = 96dp
 *        所以图片顶部 = 屏幕高 - 92dp - 96dp = 屏幕高 - 188dp
 *
 *   4. 指示区（DefaultIndicationAreaSection.applyConstraints）
 *        底部贴父容器底部，减 keyguard_indication_margin_bottom(32dp)
 *
 * 以 pagani（OnePlus 11，1440×3216 @ 525dpi，密度 3.0）代入：
 *        状态栏 ≈ 32dp  →  时钟顶部 ≈ 113dp ≈ 339px
 *        时钟底部 ≈ 300dp ≈ 900px
 *        快捷方式图片顶部 = 1072dp − 188dp = 884dp ≈ 2652px
 *
 * 所以「大时钟下方、快捷方式之间」对应一个相当宽的区间，而不是一个点。
 * 这就是为什么默认值给 68%，同时提供拖动微调 —— 而不是假装能算出一个唯一解。
 */
object LayoutMath {

    /**
     * 由垂直比例算出子视图的顶部偏移（与 ConstraintLayout verticalBias 语义一致）。
     *
     * parent 同时约束 top 和 bottom 时：
     *   top = (parentHeight - childHeight) * bias
     */
    fun topForBias(parentHeight: Int, childHeight: Int, biasFraction: Float): Int {
        val range = parentHeight - childHeight
        if (range <= 0) return 0
        return (range * biasFraction.coerceIn(0f, 1f)).toInt()
    }

    /**
     * 反解：要让子视图顶部落在 [targetTop]，bias 应取多少。
     * 用于把「期望像素位置」换算成设置里的百分比。
     */
    fun biasForTop(parentHeight: Int, childHeight: Int, targetTop: Int): Float {
        val range = parentHeight - childHeight
        if (range <= 0) return 0f
        return (targetTop.toFloat() / range).coerceIn(0f, 1f)
    }

    /** 设置里用 0..100 的整数，避免浮点滑块的精度噪音 */
    fun percentForTop(parentHeight: Int, childHeight: Int, targetTop: Int): Int =
        (biasForTop(parentHeight, childHeight, targetTop) * 100f).toInt().coerceIn(0, 100)

    /**
     * 判断岛的当前位置是否落在「时钟下方」。
     *
     * @return 是否在时钟底部之下（允许 16dp 的视觉宽容度）
     */
    fun isBelowClock(
        parentHeight: Int,
        childHeight: Int,
        biasFraction: Float,
        clockBottom: Int,
        tolerancePx: Int = 0
    ): Boolean = topForBias(parentHeight, childHeight, biasFraction) >= clockBottom - tolerancePx

    /**
     * 判断岛的当前位置是否落在「快捷方式之上」（即不与快捷方式图片重叠）。
     */
    fun isAboveShortcuts(
        parentHeight: Int,
        childHeight: Int,
        biasFraction: Float,
        shortcutsTop: Int
    ): Boolean {
        val top = topForBias(parentHeight, childHeight, biasFraction)
        return top + childHeight <= shortcutsTop
    }

    /** 位置是否处于合法可交互区间内 */
    fun isReachable(
        parentHeight: Int,
        childHeight: Int,
        biasFraction: Float,
        minFraction: Float,
        maxFraction: Float
    ): Boolean {
        if (biasFraction < minFraction || biasFraction > maxFraction) return false
        val top = topForBias(parentHeight, childHeight, biasFraction)
        return top >= 0 && top + childHeight <= parentHeight
    }

    // ==================================================================
    // 帧布局（NotificationShadeWindowView）锚点定位
    //
    // ============ 为什么这些计算必须可测试 ============
    //
    // 岛最终改成挂在 NotificationShadeWindowView（FrameLayout）上，
    // 位置由 leftMargin/topMargin 的字面值决定。这条路线的正确性
    // **完全依赖算术** —— 没有约束求解、没有系统兜底。
    //
    // 而我在真机上因算术/时序问题反复返工（top 停在 0、锚点漂移、
    // 宽度分配把按钮挤出边界）。把这些算法抽成纯函数并用测试锁住，
    // 就不必每次都靠真机试错来发现低级错误。
    // ==================================================================

    /**
     * 由「用户设定的垂直百分比」算出岛的**底部锚点**。
     *
     * 底部锚点是变形时保持不动的参考线：小岛与中岛的底边都钉在这里。
     */
    fun bottomAnchorForBias(
        parentHeight: Int,
        collapsedHeight: Int,
        biasFraction: Float
    ): Int = topForBias(parentHeight, collapsedHeight, biasFraction) + collapsedHeight

    /**
     * 让**底边固定**在 [bottomAnchor] 时，高度为 [height] 的子视图应有的 top。
     *
     * 这就是「以底部为锚点向上长高」的核心：高度变大时 top 相应变小。
     */
    fun topForBottomAnchor(bottomAnchor: Int, height: Int): Int =
        (bottomAnchor - height).coerceAtLeast(0)

    /** 水平居中的左边距 */
    fun centeredLeftMargin(containerWidth: Int, childWidth: Int): Int =
        ((containerWidth - childWidth) / 2).coerceAtLeast(0)

    /**
     * 岛的目标宽度：容器宽 × 百分比，再受最大/最小宽度夹紧。
     *
     * 夹紧顺序刻意是「百分比 → 上限 → 下限」，保证极端配置下也能得到合法宽度。
     */
    fun islandWidth(
        containerWidth: Int,
        widthPercent: Int,
        maxWidthPx: Int,
        minWidthPx: Int
    ): Int {
        if (containerWidth <= 0) return minWidthPx.coerceAtLeast(0)
        val byPercent = (containerWidth * widthPercent / 100f).toInt()
        return byPercent.coerceAtMost(maxWidthPx).coerceAtLeast(minWidthPx)
    }

    /**
     * 形变插值：按进度在收起高度与中岛高度之间取值。
     *
     * @param fraction 0..1（动画进度）
     */
    fun morphHeight(collapsedHeight: Int, expandedHeight: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return (collapsedHeight + (expandedHeight - collapsedHeight) * f).toInt()
    }

}
