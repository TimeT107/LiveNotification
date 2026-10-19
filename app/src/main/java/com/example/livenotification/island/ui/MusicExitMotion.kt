package com.example.livenotification.island.ui

/**
 * 音乐锁屏 ↔ 中岛 转场的"质感件"参数（**纯计算、零 Android 依赖 → 可单测**）。
 *
 * ============ 理念来源 ============
 *
 * 与"中岛→小岛"的 `IslandView.morphFallToCompact` **同源**（同样是"一个进度驱动 + 模糊遮瑕 +
 * 落到位就不动"），但 ⚠️ **时长/曲线不能照抄** —— 这是 v1.41.3 修正的重点：
 *
 *   · 中岛→小岛 的 `140 + 180·remain` 是按**469px 行程**（岛高 665→196）调出来的；
 *   · 音乐锁屏→中岛 的行程是**将近一屏**（封面 950×950@y185 → 1070×665@y1583，
 *     竖着走 ~1400px，是前者的三倍）。
 *
 * 照抄那套数字的后果，用户的原话是"**动画傻快傻快的太轻了**"：同样的 140~320ms
 * 跑三倍距离，读起来就是"嗖一下没了、没有分量"。
 * 所以这里按**行程**重调：`380 + 260·remain`（380~640ms）+ 起步更有分量、落定更稳的曲线
 * `(0.32, 0, 0.08, 1)`；模糊遮瑕也提前并加大（8dp / k<0.62），让落定被"垫"住。
 *
 * 回弹 220ms（原来 140ms）—— 回弹同样是一屏级的行程，太短会像"弹了一下就没了"。
 *
 * 把参数放在这里 + `MusicExitMotionTest`，是为了防止以后被谁随手调坏
 * （AGENTS §4 有"两处硬编码必须一起改"的血泪教训）。
 *
 * 这里**不放**任何 View 操作 —— 视图侧只调用这些函数。
 */
object MusicExitMotion {

    // ---- 入场（中岛 → 音乐锁屏）----

    /**
     * 入场时长：`260 + 380·remain`（260~640ms）。
     *
     * 🔴 v1.41.4 修正（用户反馈："**中岛到音乐锁屏又太快了**"）：
     *   原来入场是 `420 × remain`（纯线性）—— **拖到大半再松手时剩余 0.4，只剩 168ms**
     *   要跑 40% 的行程（同样剩余下退场是 484ms），等于"撒手就窜上去"。
     *   表单结构本身就不对：入场也该是 `基数 + 跨度·remain`，跟退场同构。
     */
    const val ENTRY_BASE_MS = 260f
    const val ENTRY_SPAN_MS = 380f

    // ---- 落定（塌缩）----

    /**
     * 落定时长的基数与跨度：duration = BASE + SPAN · remain。
     *
     * 380~640ms —— 为**一屏级行程**（~1400px）调的。
     * ⚠ 不要再抄"中岛→小岛"的 140~320ms（那是 469px 行程的数），用户会明显觉得"傻快、没分量"。
     */
    const val FALL_BASE_MS = 380f
    const val FALL_SPAN_MS = 260f

    /** 时长的硬下限：k 已经很小的时候不要瞬移（观感是"跳"） */
    const val FALL_MIN_MS = 220L

    /** 回弹时长（原来跟 collapse 一样是 140ms；一屏级行程太短 → 220ms） */
    const val SPRING_MS = 220L

    /**
     * 曲线控制点：`(0.32, 0, 0.08, 1)`
     *   · 起步 x1=0.32 且 y1=0 → **起步有一点"迟滞"**（有质量的东西不会瞬间到速度）；
     *   · 末段 x2=0.08 且 y2=1 → 早早进入减速、稳稳落定（"落到位置就不动"）。
     * 与 collapse 的 (0.55,0.05,0.2,1) 相比：更慢的起步 + 更早的减速 = 更重。
     */
    const val CURVE_X1 = 0.32f
    const val CURVE_Y1 = 0f
    const val CURVE_X2 = 0.08f
    const val CURVE_Y2 = 1f

    // ---- 模糊遮瑕 ----

    /**
     * 模糊"起糊"的 k：k < 此值才开始糊。
     *
     * 🔴 v1.41.6：`0.62 → 0.55`。原来"后半程整段是软的"= 全程 62% 都在糊，
     * 加上退场时长 380~640ms，等于糊了三四百毫秒。
     */
    const val BLUR_RISE_K = 0.55f

    /**
     * 模糊**峰值**所在的 k。
     *
     * 🔴 v1.41.6 的核心修正（用户："**音乐锁屏到中岛位置后的模糊时间还是太长，
     *   看着有种未响应的感觉**"）：
     *   旧公式是"越接近落定越糊、`blurStrength(0)=1`"—— 也就是**落定那一刻是满档模糊**。
     *   而紧接着交棒链（展开中岛 → 同步数据 → 重扫通知 → 重算可见性 → 撤视图）
     *   要跑好几帧；这几帧里全屏那张**糊图**就被冻在屏幕上，还每帧离屏重绘掉帧。
     *   于是"糊得久"和"像卡住"其实是同一件事 —— 这也解释了为什么把中岛侧的
     *   淡出从 120 改到 70ms 完全没感觉（糊根本不是中岛侧来的）。
     *
     *   现在改成**驼峰**：中段（两套排版差异最大时）最糊，落地前**必须回到 0** ——
     *   交棒那几帧冻住的是一张**干净**的图。
     */
    const val BLUR_PEAK_K = 0.22f

    /**
     * 峰值（dp）。
     *
     * 🔴 v1.41.6：`8dp → 4dp`。8dp 在 density 3.5 下 = **28px**，而这是**全屏视图**上的
     * `RenderEffect` —— 每帧都要离屏重绘一张 1216×2640 的图，既糊得重也真的会掉帧。
     */
    const val BLUR_PEAK_DP = 4f

    /**
     * 交接后模糊淡出时长（中岛侧接续）
     *
     *  v1.41.4：`160 → 70ms`（用户："退回中岛后模糊时间太长了"）。
     *  v1.41.6：`70 → 50ms` —— 同样的反馈再来一次，说明这条尾巴还是看得见；
     *  50ms 对"接缝糊一下"已经足够，再久就是"落到中岛还在发虚"。
     *  ⚠ 这个值原来**根本没接上**（`IslandView.fadeOutMorphBlur()` 里写死 120ms），
     *  现在由 `handoffBlurFromMusicExit()` 真正传进去。 */
    const val HANDOFF_BLUR_FADE_MS = 50L

    /** 交接遮瑕的峰值（dp）—— 只有塌缩过程的一半：这是"接缝遮瑕"，不是"运动模糊" */
    const val HANDOFF_BLUR_DP = 3f

    /**
     * 落定（塌缩/展开）时长。
     *
     * @param remain 还没走完的进度比例 0..1（音乐锁屏退场时 = 当前 k）
     */
    fun fallDurationMs(remain: Float): Long =
        (FALL_BASE_MS + FALL_SPAN_MS * remain.coerceIn(0f, 1f)).toLong().coerceAtLeast(FALL_MIN_MS)

    /**
     * 入场（中岛 → 音乐锁屏）时长。
     *
     * @param remain 还没走完的进度比例 0..1（= 1 - 当前 k）
     */
    fun entryDurationMs(remain: Float): Long =
        (ENTRY_BASE_MS + ENTRY_SPAN_MS * remain.coerceIn(0f, 1f)).toLong().coerceAtLeast(200L)

    /**
     * 某一静态层在 q 处的"淡出/收缩量"，0..1。
     *
     * @param q  `1 - k`：0 = 音乐锁屏（完全显示），1 = 中岛（完全收起）
     * @param a0 开始收的 q；q ≤ a0 时返回 0
     * @param a1 收完的 q；q ≥ a1 时返回 1
     *
     * 用 smoothstep（两端导数为 0）—— 层的淡出不能有可见的"起停棱角"。
     */
    fun layerProgress(q: Float, a0: Float, a1: Float): Float {
        if (a1 <= a0) return if (q >= a1) 1f else 0f
        val t = ((q.coerceIn(0f, 1f) - a0) / (a1 - a0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 模糊遮瑕强度 0..1（乘 [BLUR_PEAK_DP] 得半径）。
     *
     * **驼峰**：`k ≥ BLUR_RISE_K` 完全不糊 → 中段升到满档（`k = BLUR_PEAK_K`）
     * → 落地前**回到 0**（`k = 0` 必须干净，理由见 [BLUR_PEAK_K]）。
     * 两段都用 smoothstep，避免可见的起停棱角。
     */
    fun blurStrength(k: Float): Float {
        val kk = k.coerceIn(0f, 1f)
        return when {
            kk >= BLUR_RISE_K -> 0f
            kk >= BLUR_PEAK_K -> smooth((BLUR_RISE_K - kk) / (BLUR_RISE_K - BLUR_PEAK_K))
            else -> smooth(kk / BLUR_PEAK_K)
        }
    }

    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
