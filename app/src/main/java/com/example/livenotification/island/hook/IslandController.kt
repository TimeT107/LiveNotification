package com.example.livenotification.island.hook

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import com.example.livenotification.island.config.ConfigStore
import com.example.livenotification.island.config.IslandConfig
import com.example.livenotification.island.media.MediaRepository
import com.example.livenotification.island.media.MediaSnapshot
import com.example.livenotification.island.media.SystemStatusRepository
import com.example.livenotification.island.ui.IslandView
import com.example.livenotification.island.ui.NotifItem
import com.example.livenotification.island.util.IslandLog
import com.example.livenotification.island.util.LayoutMath
import kotlin.math.roundToInt

/**
 * 锁屏岛的挂载、布局、数据刷新总控。
 *
 * ============ 布局策略（本模块能不能活下来的核心） ============
 *
 * SystemUI 的锁屏布局是「蓝图（Blueprint）」驱动的：KeyguardRootView 是一个
 * ConstraintLayout，SystemUI 会反复对整棵树执行 ConstraintSet.apply。
 * 所以绝不能读邻居坐标再写死位置，那样下一次 apply 就被冲掉。
 *
 * 正确做法：只给岛设置**依赖父容器的稳定约束**
 * （start/end -> parent、top/bottom -> parent，位置由垂直 bias 决定），
 * onPreDraw 时只做一次签名比对，仅在 LayoutParams 被外部重建时才重修。
 *
 * ============ 跨 ClassLoader：绝不用类型转换（真机实测结论） ============
 *
 * 真机日志：
 *   ★ View.onAttachedToWindow 命中锁屏根布局：
 *       id=0x7f0a0460 class=com.android.systemui.keyguard.ui.view.KeyguardRootView
 *   E attach 失败：锁屏根布局无法转成 ConstraintLayout（类加载器隔离）
 *
 * id 与类名都完全正确，但 `as? ConstraintLayout` 失败。根因是**类加载器隔离**：
 * 模块 ClassLoader 解析出的 androidx.constraintlayout.widget.ConstraintLayout
 * 与 SystemUI 实例的实际类不是同一个 Class 对象，所以 is/as / 祖先遍历全部失效。
 *
 * 因此本项目约定：
 *   - 宿主一律声明为 ViewGroup（框架类，双方共享）
 *   - ConstraintLayout.LayoutParams 用反射构造，约束字段用反射按名读写
 *   - 判定类型一律按**类名字符串**，不用 is/as
 *
 * ============ 数据策略 ============
 *
 * 岛只在锁屏可见时刷新，不可见时轮询完全停掉，零后台开销。
 * 优先级：媒体播放 > 系统状态（闹钟/充电/耳机） > 空闲。
 * 取数据的三次跨进程调用全部在后台线程，不阻塞锁屏渲染。
 */
class IslandController(
    private val context: Context,
    private val log: IslandLogger
) {

    private val media = MediaRepository(context, log)
    private val status = SystemStatusRepository(context, log)

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    @Volatile
    private var island: IslandView? = null

    /** 宿主锁屏根布局。刻意用 ViewGroup（框架类），见类注释的类加载器隔离说明。 */
    @Volatile
    private var root: ViewGroup? = null

    /** 用户正在拖动进度条，期间不要覆盖进度 */
    @Volatile
    private var userSeeking = false

    /** 最近一次渲染的内容签名，用于跳过无意义的更新 */
    private var renderedSignature: String? = null

    private var lastState: Int = IslandView.STATE_IDLE
    private var lastExpanded: Boolean = false

    /** 非主线程重投的防重入标志（真机上自激循环的修复） */
    @Volatile
    private var threadRerouteGuarded = false

    /** 看门狗上一次观察到的「锁屏根布局是否真的显示着」 */
    @Volatile
    private var watchdogSawShown: Boolean = false

    // ------------------------------------------------------------------
    // 状态来源（由 SystemUiHook 写入）
    // ------------------------------------------------------------------

    @Volatile
    var keyguardShowing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            log.mark("锁屏可见性变化 -> keyguardShowing=$value")
            onVisibilityInputChanged()
        }

    @Volatile
    var keyguardOccluded: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            log.i("keyguardOccluded=$value")
            onVisibilityInputChanged()
        }

    @Volatile
    var dozing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            log.i("dozing=$value")
            onVisibilityInputChanged()
        }

    /** StatusBarState：0=SHADE 1=KEYGUARD 2=SHADE_LOCKED */
    @Volatile
    var statusBarState: Int = -1
        set(value) {
            if (field == value) return
            field = value
            log.i("statusBarState=$value")
            onVisibilityInputChanged()
        }

    /** 屏幕是否点亮（来自 KeyguardStateControllerImpl.update(Z)） */
    @Volatile
    var screenOn: Boolean = true

    /**
     * 锁屏之上的「覆盖层」状态。
     *
     * ============ 为什么需要它（真机 bug） ============
     *
     * 岛挂在 NotificationShadeWindowView 上，而这个容器在下面这些场景**同样可见**，
     * 于是岛会跟着出现在这些界面上（用户实测报的 bug）：
     *   · 锁屏微件界面（Glanceable Hub）
     *   · 锁屏上下拉的通知栏
     *   · 输入密码/图案界面（Bouncer）
     *
     * 数据来源是 SystemUI 的 GlanceableHubContainerController，
     * 它正好有三个 public 字段：hubShowing / shadeShowing / anyBouncerShowing。
     * 由 SystemUiHook 捕获实例后通过 [overlayStateProvider] 提供。
     *
     * provider 为 null（hook 未命中）时按「都没有覆盖」处理，
     * 即退化成原来的行为，不会因为拿不到状态就把岛永久藏起来。
     */
    data class OverlayState(
        val hubShowing: Boolean,
        val shadeShowing: Boolean,
        val bouncerShowing: Boolean
    )

    @Volatile
    var overlayStateProvider: (() -> OverlayState?)? = null

    // ------------------------------------------------------------------
    // 后台轮询
    // ------------------------------------------------------------------

    /**
     * 后台轮询线程。
     *
     * 一轮刷新要做三次跨进程调用（getActiveSessions / getDevices / nextAlarmClock），
     * 原来全在主线程，只要某个音乐应用响应慢锁屏就会掉帧，极端情况 ANR。
     * 现在后台线程只取数据并算好「渲染计划」，视图操作全部 post 回主线程。
     */
    private val pollExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "LockScreenIsland-poll").apply { isDaemon = true }
    }

    /** 最近一次后台轮询是否拿到了可显示内容（供主线程做便宜的可见性判定） */
    @Volatile
    private var lastHadContent: Boolean = false

    private data class RenderPlan(
        val snapshot: MediaSnapshot?,
        val chip: com.example.livenotification.island.media.StatusChip?,
        val cfg: Cfg,
        val signature: String,
        val noContentVisibilityFlip: Boolean
    )

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollExecutor.execute {
                try {
                    val plan = buildRenderPlan()
                    handler.post { applyRenderPlan(plan) }
                } catch (t: Throwable) {
                    log.e("后台轮询失败", t)
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** 后台线程：取数据 + 算签名，不碰任何 View */
    private fun buildRenderPlan(): RenderPlan {
        val cfg = readConfig()
        val snapshot = media.poll()
        val chip = if (snapshot == null) status.currentChip() else null
        lastHadContent = snapshot != null || chip != null

        val signature = when {
            snapshot != null -> "media|${snapshot.packageName}|${snapshot.title}|" +
                "${snapshot.artist}|${snapshot.playing}|${snapshot.albumArt?.hashCode()}|" +
                "${snapshot.durationMs}"

            chip != null -> "status|${chip.glyph}|${chip.text}|${chip.detail}"

            else -> "idle"
        }

        val visible = island?.visibility == View.VISIBLE
        val flip = if (!cfg.debugAlwaysShow) lastHadContent != visible else !visible

        return RenderPlan(snapshot, chip, cfg, signature, flip)
    }

    /** 主线程：应用渲染计划 */
    private fun applyRenderPlan(plan: RenderPlan) {
        val view = island ?: return

        // ============ 音乐锁屏期间的硬守卫（真机破图）============
        //
        // 结构上的坑：**岛绘制在音乐锁屏之上**（我们刻意把音乐锁屏插到
        // KeyguardRootView 之前，好让时钟/快捷方式/指纹留在最上层）。
        // 所以只要有任何一刻岛和音乐锁屏同时可见，就会叠出破图
        // （真机截图：卡片形状正确，但多出半圆按钮与被压住的波浪进度条）。
        //
        // updateVisibility() 里已经有 `if (musicMode) GONE`，但它只在
        // "输入变化"时被调用；轮询、覆盖层检测、动画回调都可能在某些时序下
        // 让岛短暂恢复显示。这里每次轮询都兜一次底，并且**记日志** ——
        // 一旦这行出现，就说明确实存在那条竞态路径。
        // 条件用 musicLock != null（"音乐视图确实存在"）而不是 musicMode 标志位 ——
        // 标志位与视图生命周期万一不同步，守卫就会失效；以视图存在为准更可靠。
        if (musicLock != null && view.visibility != View.GONE) {
            log.w("音乐锁屏期间岛被意外显示，已强制隐藏（竞态证据）")
            view.visibility = View.GONE
        }
        // ============ 音乐锁屏期间**持续**压制通知（真机反馈）============
        //
        // 问题：在音乐界面时收到消息 → 熄屏（会还原通知）→ 亮屏回到音乐锁屏，
        // 那条新通知就显示在音乐锁屏上了（本该被挡住）。
        //
        // 原因：我只在"进入音乐锁屏"那一刻扫描并隐藏通知；
        // 熄屏时还原过通知容器，之后新到的通知不在记录里，也没人再压它。
        //
        // 所以每次轮询都兜一次底：记录空了就重新扫描，
        // 再按当前转场进度重设透明度 —— 新到的通知自然被压住。
        if (musicLock != null) {
            if (hiddenNotifViews.isEmpty()) {
                runCatching {
                    findShadeWindow(view)?.let { hideKeyguardNotifications(it) }
                }
            }
            applyNotificationAlpha(1f - (musicLock?.heroProgress ?: 1f))

            // 同时**持续**压制锁屏自带元素（系统时钟 / 日期 / 充电提示）。
            // 真机问题：熄屏亮屏恢复、或充电时时钟插件重建视图，都会让"一次性隐藏"失效
            // （截图里系统大时钟又出现了、充电文字盖住控制键）。
            // 与通知压制同一个模式：每次轮询重新定位并压制；缓存视图失效时会自动重找。
            runCatching { hideSystemClockForMusic() }

            // ============ 通知小岛：刷数据（节流 4s，内部再看签名是否变化）============
            // 采集要遍历通知栈 + 反射 + loadDrawable，所以不在 1s 轮询里每次做。
            maybeRefreshMusicNotifications(view)
        }

        if (musicLock != null && log.verbose) {
            log.i(
                "音乐形态采样：岛vis=" + view.visibility +
                    " 转场k=" + musicLock?.heroProgress +
                    " 音乐视图=" + (musicLock != null)
            )
        }

        val stateChanged = applyContent(view, plan.snapshot, plan.chip, plan.cfg)
        if (plan.signature != renderedSignature || stateChanged) {
            renderedSignature = plan.signature
        }

        val snap = plan.snapshot
        if (snap != null && !userSeeking) {
            view.setProgress(snap.positionMs, snap.durationMs)
            if (snap.durationMs > 0) view.setSeekBarMax(1000)
            musicLock?.setProgress(snap.positionMs, snap.durationMs)
        } else if (snap == null && morphExpanded) {
            // 媒体没了就复位到小岛形态（中岛只展示媒体内容）
            resetMorph()
        }

        logSampledState(view, snap, plan.chip)

        // ============ 覆盖层变化必须主动重算可见性（关键） ============
        //
        // 通知栏能生效是因为它顺带把 statusBarState 从 1 改成了 2，
        // 于是 onVisibilityInputChanged() 触发重算。
        //
        // 但**微件界面与密码界面不会改变任何已 hook 的输入**
        // （statusBarState 保持 KEYGUARD=1），所以没有人去调 updateVisibility()，
        // 门禁即使读到了 true 也不会生效。
        //
        // 所以在这里（每次轮询，主线程）比较覆盖层状态，一变就重算。
        // 只在真正变化时才调用，避免每秒刷日志/重排。
        val ov = currentOverlayState()
        if (ov != lastOverlayState) {
            lastOverlayState = ov
            log.i(
                "覆盖层状态变化 -> 重算可见性 " +
                    "[微件=${ov?.hubShowing} 通知栏=${ov?.shadeShowing} 密码=${ov?.bouncerShowing}]"
            )
            updateVisibility()
        }

        if (plan.noContentVisibilityFlip) {
            handler.post { updateVisibility() }
        }
    }

    /** 上一次读到的覆盖层状态，用于检测变化 */
    @Volatile
    private var lastOverlayState: OverlayState? = null

    /** 拖动进度是否已越过 0.5（用于避免每帧写日志） */
    private var lastDragCrossedHalf = false
    private var overlayStateInitialized = false

    /**
     * 读一次覆盖层状态；provider 缺失或异常时返回 null（视为无覆盖）。
     */
    private fun currentOverlayState(): OverlayState? =
        runCatching { overlayStateProvider?.invoke() }.getOrNull()

    /**
     * 可见性看门狗。
     *
     * 岛显示需要 keyguardShowing=true，而它来自 KeyguardStateControllerImpl 的 hook。
     * 但轮询只在岛可见时才启动 —— 如果那组 hook 没触发就会死锁：
     *   keyguardShowing 一直 false → 岛一直 GONE → 轮询不启动 → 永远不显示。
     * 看门狗独立于岛自身可见性，直接看「宿主是否真的显示在屏幕上」，从而放行。
     */
    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (!watching) return
            try {
                val view = island
                if (view != null && view.parent != null) {
                    val shown = view.isShown
                    if (shown != watchdogSawShown) {
                        watchdogSawShown = shown
                        log.i("看门狗：锁屏根布局 isShown=$shown（keyguardShowing=$keyguardShowing）")
                    }
                    if (shown && !keyguardShowing) {
                        log.mark(
                            "看门狗：锁屏根布局已显示但 keyguardShowing=false —— " +
                                "keyguard 状态 hook 未生效，改用视图可见性判定"
                        )
                        keyguardShowing = true
                    }
                }
            } catch (_: Throwable) {
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    @Volatile
    private var watching = false

    // ------------------------------------------------------------------
    // 监听器
    // ------------------------------------------------------------------

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        applyConstraints(reason = "onLayoutChange")
    }

    /**
     * 宿主的布局监听器：在**每一次宿主布局时**检查岛的约束是否被清掉，被清掉就补回。
     *
     * ============ 为什么必须有它（真机决定性证据） ============
     *
     * 约束回读显示：
     *   topToTop=-1 bottomToBottom=-1 startToStart=-1 endToEnd=-1
     *   verticalBias=0.5（默认值）topMargin=2052 w=1070 h=196
     *
     * 我设的约束**全被清空了** —— 锁屏蓝图（ConstraintSet.apply）会重建子视图的
     * LayoutParams，把我们的约束擦掉，只留下宽高与 margin。
     * 这才是「设对了却不生效、位置恒为 (0,0)」的真正原因。
     *
     * 之前所有尝试（改字段、topMargin、requestLayout、重挂、MethodHandle）
     * 之所以无效，是因为补上去的约束**下一次布局又被擦掉**。
     *
     * 对策：挂在宿主的 onLayoutChange 上（宿主每次布局必然回调），
     * 检查约束完整性，一旦缺失立刻补回。只在真的缺失时才写，避免自激。
     */
    // ============ 「在宿主 onLayoutChange 里补回约束」已彻底删除（真机事故） ============
    //
    // 我原本加了 hostLayoutListener：检测到约束被锁屏蓝图清空就补回。想法没错，
    // 但它在**布局过程中修改 LayoutParams**，而修改又触发布局 → 再触发监听 →
    // 再补回……形成**无界布局循环**，把 SystemUI 拖死、反复崩溃，
    // 最终触发 LSPosed 安全模式（最严重的一次事故）。
    //
    // 而且它连目的都没达到：真机日志里岛仍然是 top=0。
    // 既无效又有害，所以不是"修一修"，而是整条思路废弃。
    //
    // 根因是「和锁屏蓝图争抢同一份 LayoutParams」。现在改成把岛挂到
    // NotificationShadeWindowView（FrameLayout）上，用 leftMargin/topMargin
    // 定位，不经过任何约束求解，也就没有"被清空"这回事。
    // 任何「在布局回调里改布局」的代码都不允许再加回来。

    /**
     * 蓝图每次 apply ConstraintSet 都可能重建我们的 LayoutParams，
     * 所以 onPreDraw 时检查一次约束是否还完整；只在被破坏时才修。
     * 平时只是一次字段比较，开销可忽略。
     */
    private val preDrawListener = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            // ⚠ 绝不能在这里无条件调用 applyConstraints（真机事故）。
            //
            // 我一度改成每次 onPreDraw 都调用，结果：岛自身有动画（律动条、淡入），
            // onPreDraw 会**每帧**触发 → applyConstraints 约 60 次/3 秒 →
            // 直接触发熔断（真机日志：「3000ms 内被调用 61 次，上限 60」）。
            //
            // 现在只在两种必要时才调用：
            //  1) 还没成功应用过（lastSignature == null）—— 覆盖「宿主刚拿到尺寸」这个时机
            //  2) 有明确的重排请求（needsReapply）
            // 其余帧一律不动，代价是两次廉价比较。
            if (needsReapply || lastSignature == null) {
                needsReapply = false
                applyConstraints(reason = "preDraw")
            }
            return true
        }
    }

    @Volatile
    private var needsReapply: Boolean = false

    private var lastSignature: String? = null

    /**
     * 上一次**我们主动设置**的形态高度。
     *
     * 刻意不用 view.height 判断形态是否变化：removeView+addView 会让它短暂归零，
     * 于是每次布局回调都判定"变了"并再次重挂，形成自激循环
     * （真机日志刷出连续的「形态变化：重挂视图 (1070x196 -> 1070x196)」）。
     */
    private var lastAppliedShapeHeight: Int = -1

    /**
     * 是否已完成「首次约束应用」。
     *
     * addView 发生在 attach()，那时容器还没测量（0x0），无法算出正确的宽高与 bias。
     * 而事后又没有任何 API 能让 ConstraintLayout 重解约束
     * （ConstraintSet 因类加载器隔离必然失败）。
     *
     * 所以只在「第一次拿到有效尺寸」时重建一次 LayoutParams 并重新挂载，
     * 让父容器从一开始就按正确值解算；之后只更新参数、不再重挂，
     * 避免重挂造成视图脱离视图树（曾导致展开态 parent=null 完全不可见）。
     */
    private var initialConstraintApplied = false

    /** 是否已请求过父容器整体重新布局（只做一次，避免布局自激） */
    private var parentRelayoutRequested = false

    // ------------------------------------------------------------------
    // 挂载
    // ------------------------------------------------------------------

    /**
     * 把岛挂到锁屏根布局上。
     *
     * ⚠ 第一步必须是最廉价的「不是目标就静默退出」：onFinishInflate 的 hook
     * 沿继承链上溯到了 android.view.View，所以回调对 SystemUI 里**每一个 View**
     * 都会触发（日志里能看到 ImageView / TextView…）。不在这里筛掉就会产生
     * 每秒上千行的日志洪泛，把真正的诊断信息淹没。
     */
    fun attach(rootView: View): Boolean {
        if (rootView.id != KEYGUARD_ROOT_VIEW_ID) return false

        // ============ 幂等检查必须针对真正的宿主（真机 bug） ============
        //
        // 岛现在挂在 NotificationShadeWindowView 上，而不是传入的 KeyguardRootView。
        // 所以不能用「岛是不是 rootView 的子视图」来判断是否已挂载 ——
        // 那样永远返回 false，导致重复注入、每次都重建一个新 IslandView，
        // 新视图的 topMargin 是默认 0，于是岛停在 top=0（真机实测到的现象）。
        //
        // 正确做法：先找到 shade window，再比较岛的父亲是不是它。
        val shade = findShadeWindow(rootView)
        if (shade == null) {
            log.w(
                "跳过：找不到 NotificationShadeWindowView" +
                    "（祖先=${describeAncestors(rootView)}）"
            )
            return false
        }
        if (island?.parent === shade) return false

        // addView 必须在主线程执行。这个分支必须防重入：非主线程 post 重投后
        // 若再次进入并再次判定非主线程，就会自激循环（真机实测刷了数千行）。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (threadRerouteGuarded) {
                log.w("attach 非主线程重入，丢弃本次（避免自激循环）")
                return false
            }
            threadRerouteGuarded = true
            log.w("attach 不在主线程（${Thread.currentThread().name}），转投主线程执行一次")
            rootView.post {
                threadRerouteGuarded = false
                attach(rootView)
            }
            return true
        }
        threadRerouteGuarded = false

        // 上面已经找到并校验过 shade window，这里直接用
        val container = shade

        // 幂等：先清理旧的
        island?.let { old ->
            runCatching {
                old.removeOnLayoutChangeListener(layoutListener)
                container.removeView(old)
            }
            island = null
        }

        root = container
        val view = IslandView(context)
        island = view

        // ============ 新建视图后必须让签名失效（真机 bug） ============
        //
        // 签名记录的是「计算出的目标值」，不是「已应用到视图上的值」。
        // 而这里创建的是一个**全新的 IslandView**，它的 layoutParams 是默认值
        // （topMargin=0）。如果不清空签名，applyConstraints 会认为
        // "目标值没变"而直接短路，新视图就永远停在 top=0。
        //
        // 真机表现：left=73（正确，新建时就算对了）但 top=0（右），
        // 因为 topMargin 依赖 applyConstraints 去设，却被短路跳过了。
        lastSignature = null

        configureIsland(view)

        // ============ 创建时就把位置设对（不再依赖 applyConstraints） ============
        //
        // 真机 bug：这里原来写 topMargin = 0，指望接下来的 applyConstraints 去修正。
        // 但第二次注入（shade-fallback）发生在一次布局过程中，那时防重入守卫
        // applyingConstraints 正好为 true，applyConstraints 被直接拦下 ——
        // 于是新视图永远停在 top=0（真机日志：LP[LayoutParams left=73 top=0]）。
        //
        // 所以创建时就必须算出正确位置。宿主可能还没测量（0x0），
        // 那就退回用屏幕高度 —— 二者在这个场景下是同一个值（全屏窗口）。
        val w = islandWidthPx(container)
        val h = dp(IslandConfig.DEF_HEIGHT_DP)
        val parentH = if (container.height > 0) {
            container.height
        } else {
            context.resources.displayMetrics.heightPixels
        }
        // 位置算术走 LayoutMath（纯函数、有单元测试覆盖）
        val anchor = LayoutMath.bottomAnchorForBias(parentH, h, liveBiasPercent / 100f)
        val targetTop = LayoutMath.topForBottomAnchor(anchor, h)

        val lp = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = LayoutMath.centeredLeftMargin(container.width, w)
            topMargin = targetTop
        }
        log.i(
            "创建岛视图：w=$w h=$h leftMargin=${lp.leftMargin} topMargin=$targetTop " +
                "（底部锚点=$anchor）"
        )

        runCatching {
            container.addView(view, lp)
        }.onFailure {
            log.e("addView 失败", it)
            island = null
            return false
        }

        view.addOnLayoutChangeListener(layoutListener)
        runCatching {
            container.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }

        status.start()

        log.mark(
            "★ 锁屏岛已注入 ${container.javaClass.simpleName}（FrameLayout 锚点，不用约束）" +
                "(边长=${container.width}x${container.height})"
        )

        applyConstraints(reason = "attach")
        startWatchdog()
        verifyAttached(1500L)
        verifyAttached(4000L)
        onVisibilityInputChanged()
        return true
    }

    /**
     * 向上找到 NotificationShadeWindowView（锁屏与通知栏共同的 FrameLayout 父级）。
     *
     * 用 id 判定，不用类型转换 —— 见类注释的类加载器隔离说明。
     */
    private fun findShadeWindow(view: View): ViewGroup? {
        var p: android.view.ViewParent? = view.parent
        var depth = 0
        while (p != null && depth < 12) {
            if (p is View && p.id == NOTIFICATION_SHADE_WINDOW_ID && p is ViewGroup) {
                return p
            }
            p = p.parent
            depth++
        }
        return null
    }

    /**
     * 岛的目标宽度。
     *
     * 算法抽到 LayoutMath.islandWidth（纯函数，有单元测试覆盖）：
     * 「百分比 → maxWidth 上限 → minWidth 下限」，保证任何配置下都是合法宽度。
     */
    private fun islandWidthPx(container: ViewGroup): Int {
        val cfg = readConfig()
        val base = if (container.width > 0) {
            container.width
        } else {
            context.resources.displayMetrics.widthPixels
        }
        return com.example.livenotification.island.util.LayoutMath.islandWidth(
            containerWidth = base,
            widthPercent = liveWidthPercent,
            maxWidthPx = dp(cfg.maxWidthDp),
            minWidthPx = dp(120)
        )
    }

    /** 该视图是否已经挂着我们的岛了（幂等：只认实例身份） */
    private fun attachedTo(v: View): Boolean {
        val cur = island ?: return false
        return cur.parent === v
    }

    // ------------------------------------------------------------------
    // 小岛 ↔ 中岛 变形（morph）
    // ------------------------------------------------------------------

    @Volatile
    private var morphExpanded = false

    @Volatile
    private var morphAnimating = false

    private var morphAnimator: android.animation.ValueAnimator? = null

    /** 底部锚点（收起态的 bottom），变形时保持不动 */
    @Volatile
    private var morphBottomAnchor = 0

    // 说明：原来这里有个 toggleMorph()（点击在展开/收起之间翻转）。
    // 交互改成「点击只展开、收起必须按住向下拖」之后它没有调用点了，已删除 ——
    // 留着会让人以为点击也能收起。

    /**
     * 设置变形状态并播放「向上长高 / 向下收起」动画。
     *
     * ============ 为什么现在可以做这个动画 ============
     *
     * 岛挂在 NotificationShadeWindowView（FrameLayout）上，位置就是
     * leftMargin/topMargin 的字面值，高度就是 layoutParams.height ——
     * 没有任何约束求解、没有蓝图清空。所以这里可以在控制器里
     * （layoutParams 的唯一归属方）安全地逐帧插值。
     *
     * 之前挂在 KeyguardRootView(ConstraintLayout) 上时这是做不到的：
     * 改高度要求父容器重解约束，而那条路因类加载器隔离被彻底堵死。
     */
    // ------------------------------------------------------------------
    // 音乐锁屏（中岛 → 全屏音乐界面）
    // ------------------------------------------------------------------

    @Volatile
    private var musicLock: com.example.livenotification.island.ui.MusicLockView? = null

    /** 音乐锁屏所在的宿主（用于还原 KeyguardRootView 的层级） */
    private var musicLockContainer: android.view.ViewGroup? = null

    /** 进入时记录的中岛各元素位置，供"逆一镜到底"使用 */
    private var heroRectsCache: Map<String, android.graphics.Rect>? = null

    /** 逆动画时长（与入场一致，保证"原路返回"的观感） */
    private val HERO_EXIT_MS = 420L

    /** 当前是否处于音乐锁屏形态 */
    @Volatile
    var musicMode: Boolean = false
        private set

    // ------------------------------------------------------------------
    // 形态记忆（用户要求：音乐锁屏状态跨亮屏保持，不弹回小岛）
    // ------------------------------------------------------------------

    private val FORM_SMALL = 0
    private val FORM_MEDIUM = 1
    private val FORM_MUSIC = 2

    /**
     * 用户"期望"的形态。
     *
     * ============ 为什么需要它 ============
     *
     * 原来锁屏不活动（熄屏/解锁）时会 `resetMorph()` 把形态清成小岛；
     * 用户再次亮屏就"弹回小岛"了 —— 真机反馈要求**保持上次的形态**。
     *
     * 所以把"期望形态"与"当前可见状态"分开：
     *  · 熄屏/解锁：只隐藏，**不动**期望形态
     *  · 锁屏重新出现：按期望形态恢复（小岛 / 中岛 / 音乐锁屏）
     *  · 用户主动拖动或点击改变形态时，才更新它
     */
    @Volatile
    private var desiredForm = FORM_SMALL

    /** 亮屏恢复期间为 true：让 setMorphExpanded 直接落到最终形态，不播长高动画 */
    @Volatile
    private var instantMorph = false

    /**
     * **钉死的底部锚点**（px）。
     *
     * 用户反馈"中岛往下滑、底边不应该动"—— 根因是旧实现用 `view.top + view.height`
     * 从实时位置反推底边，而 `top` 在本项目里不可靠（真机记录过"top 始终为 0"），
     * 于是 anchor 跟着正在动画的 height 一起变 -> 底边每帧都在动。
     *
     * 现在它是一个常量（由 bias 与父容器高度算出），只有父容器高度变化时才重算。
     * 于是收缩时**只有上边框往下走**，底边纹丝不动。
     */
    private var stableBottomAnchor = -1
    private var lastAnchorParentH = -1

    /** morph 期间打印几帧自检（证明底边钉住），避免刷屏 */
    private var morphFrameLog = 0

    /** 上一次的"锁屏是否活动"，用于识别"重新亮屏"这一刻 */
    private var wasLockScreenActive = false

    // ------------------------------------------------------------------
    // 息屏去抖（用户反馈"息屏时会先消失再息屏"）
    // ------------------------------------------------------------------
    //
    // `dozing` 是**息屏动画刚开始**就置位的，此刻屏幕还亮着。
    // 原来一变就隐藏 → 用户看到"岛先消失，然后屏幕才暗下去"，很不自然。
    //
    // 加 300ms 去抖：等屏幕已经进入变暗过程再隐藏，
    // 观感上就是"跟着屏幕一起暗掉"。
    /**
     * SystemUI 的 doze 渐变值：0=完全亮、1=完全进入 AOD。
     * 由 `StatusBarStateControllerImpl.setDozeAmountInternal(F)` hook 持续写入。
     *
     * ============ 为什么用它而不是延时（真机两轮反馈）============
     *
     * 一开始我按 `dozing` 立即隐藏 → "屏幕还亮着岛就没了"（太早）；
     * 改成 300ms 去抖 → 仍然太早；改成 600ms → "息屏后显示一会才消失"（太晚）。
     * 固定延时**无法匹配每次熄屏动画的时长**，怎么调都是一头翘。
     *
     * dozeAmount 是屏幕实际变暗的进度，按它隐藏就能精确对齐：
     * 屏幕快暗完时才隐藏，既不会提前、也不会滞留到 AOD。
     */
    @Volatile
    var dozeAmount: Float = 0f

    /** 隐藏阈值：屏幕暗到这个程度就收掉（0.75 约等于动画接近结束） */
    private val DOZE_HIDE_THRESHOLD = 0.75f

    /** 由 dozeAmount 推导的"应当隐藏"状态（取代原来的定时去抖） */
    private var dozingDebounced = false
    private var lastDozingSeen = false

    private fun syncDozingDebounce() {
        val shouldHide = dozing && dozeAmount >= DOZE_HIDE_THRESHOLD
        if (shouldHide == dozingDebounced) return
        dozingDebounced = shouldHide
        // 状态翻转后需要重算一次可见性；由调用方（updateVisibility）继续往下走即可，
        // 但 dozeAmount 变化来自 hook、不一定触发重算，所以这里主动补一次。
        handler.post { updateVisibility() }
    }

    /** 按期望形态恢复（在锁屏重新出现时调用） */
    private fun restoreDesiredForm() {
        when (desiredForm) {
            FORM_MUSIC -> {
                if (musicLock == null) {
                    log.mark("亮屏恢复：回到音乐锁屏形态（立即，不走入场动画）")
                    // 立即恢复，且**不播入场动画**。
                    //
                    // 原来延后 400ms 再 showMusicLock()，这 400ms 里中岛/小岛已经
                    // 显示出来了 → 用户看到"先小岛，再跳成音乐锁屏"（真机反馈）。
                    // 直接以最终形态出现，中间没有多余状态。
                    showMusicLock(animated = false)
                }
            }

            FORM_MEDIUM -> {
                if (!morphExpanded) {
                    log.mark("亮屏恢复：回到中岛形态（立即，不播长高动画）")
                    // 直接落到中岛，不从小岛长出来（真机反馈）
                    instantMorph = true
                    setMorphExpanded(true)
                    instantMorph = false
                }
            }

            else -> Unit // 小岛就是默认状态
        }
    }

    /**
     * 点中岛 → 展开成全屏音乐锁屏。
     *
     * 形态链：小岛 --点击/上拖--> 中岛 --点击--> 音乐锁屏
     *         音乐锁屏 --按住下拖--> 中岛 --按住下拖--> 小岛
     *
     * 实现上是把音乐锁屏作为 NotificationShadeWindowView 的**最后一个子视图**
     * 加进去（FrameLayout 子视图按添加顺序绘制），因此它自然盖在中岛之上，
     * 不需要动中岛自己的布局。
     */
    private fun showMusicLock(animated: Boolean = true) {
        val view = island ?: return
        if (musicLock != null) return
        val container = findShadeWindow(view) ?: run {
            log.w("找不到宿主窗口，音乐锁屏无法显示")
            return
        }

        // 按配置选择音乐锁屏样式（0=当前，1=One UI）
        // 把实际读到的样式值打出来 —— 用于定位"配置里有 music_lock_style=1 但没生效"
        // （读到 0 = 配置没读进来；读到 1 却没换样式 = 视图构造有问题）
        val styleValue = readConfig().musicStyle
        log.mark("音乐锁屏样式 musicStyle=" + styleValue)
        val useOneUi = styleValue ==
            com.example.livenotification.island.config.IslandConfig.MUSIC_STYLE_ONEUI
        val v = com.example.livenotification.island.ui.MusicLockView(context, useOneUi)
        // 每次进场重置"冻结日志"配额，方便排查"是谁把时钟改回可见的"
        clockSetterLogLeft = 6
        if (useOneUi) log.mark("音乐锁屏使用 One UI 样式")

        // ---- "一镜到底"：先取中岛各元素的位置，再让音乐锁屏的元素从那里出发 ----
        val heroRects = runCatching { view.expandedElementRects() }.getOrNull()

        // 媒体按钮复用与岛相同的分发
        v.onMediaButton = { which ->
            // 诊断：用户反馈"媒体控制的按钮点击不了"——先确认点击有没有到达这里
            log.mark("媒体按钮点击：which=$which（1=上一首 2=播放/暂停 3=下一首）")
            when (which) {
                IslandView.BTN_PREV -> media.previous()
                IslandView.BTN_PLAY -> media.toggle()
                IslandView.BTN_NEXT -> media.next()
            }
            postImmediatePoll(120L)
        }
        v.onSeekEnd = { fraction ->
            userSeeking = false
            val snap = media.lastSnapshot
            if (snap != null && snap.durationMs > 0) {
                val ms = (snap.durationMs * fraction).toLong()
                log.i("音乐锁屏 seek 到 ${ms}ms（${(fraction * 100).toInt()}%）")
                media.seekTo(ms)
                postImmediatePoll(200L)
            }
        }
        // 转场进度 → 通知 alpha：**拖动过程中**就实时淡入淡出（用户要求）
        // 转场进度 → 通知 alpha：**拖动过程中**就实时淡入淡出（用户要求）
        // 同一个 k 也驱动**系统时钟/日期的 transform**（目标 ②）——
        // 这样时钟的缩小上移和转场是同一套进度，天然同步、不会"跳一下"。
        v.onHeroProgress = { k ->
            applyNotificationAlpha(1f - k)
            applyClockTransform(k)
        }
        v.onExitRequest = {
            log.mark("音乐锁屏：按住向下拖 → 回到中岛")
            // 用户主动退出：期望形态变成中岛（这样下次亮屏回到中岛而不是音乐锁屏）
            desiredForm = FORM_MEDIUM
            hideMusicLock()
        }

        // ============ 音乐锁屏强调色：**专辑封面**优先（用户要求）============
        //
        // 原来是壁纸的莫奈色（monetContainer）。但用户要求"背景用专辑封面的莫奈取色"，
        // 而且壁纸色和当前封面无关 —— 背景着色、进度条、发光边框都应该跟着封面走。
        // 取不到（无封面/灰阶）才回退壁纸莫奈色，再回退一个中性色。
        val coverAccent = com.example.livenotification.island.util.AlbumColor.accent(
            media.lastSnapshot?.albumArt
        )
        val fill = coverAccent
            ?: com.example.livenotification.island.ui.SystemUiResources.monetContainer(context)
            ?: 0xFF8B4D44.toInt()
        v.setAccentFill(fill)

        // 立刻填一次数据，避免出现一帧空视图
        media.lastSnapshot?.let { snap ->
            v.setTexts(snap.title, snap.artist)
            v.setArt(snap.albumArt)
            v.setPlaying(snap.playing)
            v.setProgress(snap.positionMs, snap.durationMs)
        }

        // ============ z 序：让锁屏内容绘制在音乐锁屏**之上** ============
        //
        // 用户要求"手电筒、照相机和指纹的层级在音乐锁屏之上"，时钟也要留在上面。
        // 这些都在 KeyguardRootView 里。
        //
        // ============ 为什么不能动 KeyguardRootView（真机崩溃） ============
        //
        // 我原来的做法是把 KeyguardRootView `bringChildToFront`，退出时再
        // `removeView` + `addView` 还原索引。结果 SystemUI 直接崩溃：
        //     IllegalStateException: Check failed.
        //       at AodPromotedNotificationSection.addViews
        //       at KeyguardBlueprintViewBinder...
        //       at KeyguardBlueprintViewModel$runTransition
        // 锁屏蓝图（blueprint）会重建 KeyguardRootView 的内部结构并对父子关系做校验，
        // **重新挂载它会让校验失败**。
        //
        // 正确做法：只调整**我们自己视图**的位置 —— 把音乐锁屏插到
        // KeyguardRootView **之前**，于是 KeyguardRootView 及其子视图（时钟/快捷方式/
        // 指纹）后绘制、自然盖在我们之上。SystemUI 的视图一个都不碰。
        // 通知必须在音乐视图加进来**之前**隐藏（真机反馈：亮屏恢复时通知卡片会闪一下）
        hideKeyguardNotifications(container)

        val insertIndex = runCatching {
            val kg = container.findViewById<android.view.View>(KEYGUARD_ROOT_VIEW_ID)
            if (kg != null) container.indexOfChild(kg) else container.childCount
        }.getOrDefault(container.childCount)

        container.addView(
            v,
            insertIndex.coerceIn(0, container.childCount),
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // "一镜到底"入场：各元素从中岛的位置移动到音乐锁屏的位置。
        // 亮屏恢复时 animated=false —— 直接以最终形态出现，避免"先小岛再跳"。
        // 注意：animated=false 也必须走 prepareHero()，不能只调 applyHero(1f)！
        // prepareHero 才会计算 coverStart/coverEnd 并**在结尾 applyHero(1f)**；
        // 只调 applyHero(1f) 的话 coverEnd 还是零矩形，封面被压成 1x1 像素
        // → 亮屏时先看到封面、随即消失露出壁纸（真机反馈）。
        if (animated) v.startHero(heroRects) else v.prepareHero(heroRects)
        // 存起来供"逆动画"用（中岛在音乐锁屏期间几何不变，这些矩形仍然有效）
        heroRectsCache = heroRects


        musicLock = v
        musicMode = true
        // mini 时钟：隐藏系统时钟/日期，由 MusicLockView 自绘（用户要求的"系统没有的形态"）
        runCatching { hideSystemClockForMusic() }
        // 亮屏恢复时，系统会先让通知栈可见、我们随后才压下去 ——
        // 中间那一帧用户看得到（真机反馈"会显示一下然后消失"）。
        // 所以在紧接着的几个时间点各补压一次，把那几帧也盖掉。
        for (delay in longArrayOf(60L, 150L, 320L, 600L)) {
            handler.postDelayed({
                if (musicLock != null) {
                    runCatching {
                        findShadeWindow(island ?: return@postDelayed)
                            ?.let { hideKeyguardNotifications(it) }
                    }
                    applyNotificationAlpha(0f)
                }
            }, delay)
        }
        desiredForm = FORM_MUSIC   // 记住形态：下次亮屏仍回到音乐锁屏
        // 中岛让位（音乐锁屏是全屏的，岛藏在它下面没有意义）
        view.visibility = View.GONE
        log.mark("进入音乐锁屏（全屏，一镜到底转场）")
    }

    // ------------------------------------------------------------------
    // 隐藏锁屏通知（音乐锁屏期间）
    // ------------------------------------------------------------------

    /** 被我们临时隐藏的通知容器（原可见性，退出时逐条还原） */
    private val hiddenNotifViews = ArrayList<Pair<android.view.View, Int>>()

    /**
     * 把 KeyguardRootView 里的**通知容器**设为 INVISIBLE。
     *
     * ============ 为什么只改 visibility、不重新挂载 ============
     *
     * 之前为了实现 z 序对 KeyguardRootView 做过 removeView + addView，
     * 结果触发 `AodPromotedNotificationSection.addViews` 的
     * `IllegalStateException: Check failed.` —— 锁屏蓝图会校验父子结构。
     *
     * 只改 visibility 不动结构，风险低得多；而且退出时会逐条还原。
     *
     * 按类名匹配而不是按资源 id：锁屏通知容器在不同版本里 id 会变，
     * 但类名始终带 "Notif"。找到第一个就停（不再往那棵子树里钻），
     * 避免误伤通知卡片内部的小部件。
     */
    private fun hideKeyguardNotifications(container: android.view.ViewGroup) {
        // ============ 先扫再换，不要先清再扫 ============
        //
        // 原来这里一进来就 clear()，然后才去 BFS 找宿主。隐患是：
        // 如果**某一次扫描没命中**（宿主此刻正被 SystemUI 重建 / 短暂脱离窗口），
        // 那个已经被我们设成 alpha=0 的旧宿主就**丢失了记录** ——
        // 退出时 restoreKeyguardNotifications() 遍历空表，它永远不会被还原，
        // 通知栏就一直空白（直到 SystemUI 重启）。
        //
        // 本函数在一次音乐锁屏入场里会被调用多次（showMusicLock 里有 4 次延时重压，
        // 外加每秒轮询兜底），所以"某一次没扫到"是现实存在的。
        //
        // 改成：扫到东西才替换记录；扫不到就**保留原记录**（宁可不更新，也不丢还原信息）。
        val found = ArrayList<Pair<android.view.View, Int>>()

        // 从**整个宿主窗口**开始找，而不是只从 KeyguardRootView：
        // 真机日志显示锁屏通知并不在 KeyguardRootView 里（那一轮扫描一无所获），
        // 它更可能挂在面板/通知栈那一支上。
        val queue = ArrayDeque<android.view.View>()
        queue.addLast(container)
        val candidates = StringBuilder()
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cn = v.javaClass.simpleName
            if (cn.contains("Notif", ignoreCase = true) ||
                cn.contains("StackScroll", ignoreCase = true)
            ) {
                candidates.append(cn).append("(vis=").append(v.visibility).append(") ")
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        log.i("音乐锁屏：通知相关候选容器 = ${if (candidates.isEmpty()) "无" else candidates}")

        // 命中"通知栈/容器"级别才隐藏（不碰通知卡片内部的小部件）
        queue.clear()
        queue.addLast(container)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cn = v.javaClass.simpleName
            // 只藏**通知栈**本身。
            //
            // 真机候选清单（NotificationStackScrollLayout 那一层）显示可选的宿主有：
            //   NotificationShadeWindowView / NotificationPanelView /
            //   SharedNotificationContainer / NotificationsQuickSettingsContainer /
            //   NotificationStackScrollLayout
            // 其中 NotificationPanelView 是**整个通知面板**、SharedNotificationContainer 是
            // 共享容器 —— 藏它们会连带影响下拉通知栏，属于过度操作（真机实测确认）。
            // NotificationStackScrollLayout 才是真正承载通知卡片的那一层。
            val isNotifHost = cn.equals("NotificationStackScrollLayout", ignoreCase = true)
            if (isNotifHost) {
                if (v.visibility == View.VISIBLE) {
                    // 注意：先记进**临时**表，扫完之后确认有收获才替换正式记录
                    found.add(v to v.visibility)
                    // 与 INVISIBLE 不同：这里只把 alpha 归零、保持 VISIBLE，
                    // 这样后续可以由转场进度**实时**调节 alpha（拖动中就淡入淡出）。
                    v.alpha = 0f
                    log.i("音乐锁屏：通知宿主 " + cn + " 交给转场进度控制透明度")
                }
                continue
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        if (found.isEmpty()) {
            // 扫不到就保留原记录：旧的 alpha=0 宿主仍能被还原，
            // 不会因为"这一次没看见它"而永远留在不可见状态。
            log.w(
                "音乐锁屏：本次未扫到通知宿主（保留原有 " +
                    hiddenNotifViews.size + " 条还原记录）"
            )
        } else {
            // 只在确认有收获时替换 —— 每一条都记了**原始可见性**，退出时逐条还原
            hiddenNotifViews.clear()
            hiddenNotifViews.addAll(found)
        }
    }

    /**
     * 还原被隐藏的通知容器 —— 用**淡入**，与"回到中岛"的转场联动。
     *
     * 真机反馈："中岛正式出现后通知突然出现好突兀"。
     * 原来是一行 `visibility = VISIBLE`，通知瞬间弹出来；
     * 现在先以 alpha=0 恢复可见，再在 260ms 内淡入，
     * 与音乐视图淡出（180ms）、中岛成形是同一段时间，观感上是一起发生的。
     */
    /**
     * 由转场进度驱动通知透明度（每帧调用）。
     * a=1 完全显示（中岛形态）、a=0 完全隐去（音乐锁屏形态）。
     */
    private fun applyNotificationAlpha(a: Float) {
        if (hiddenNotifViews.isEmpty()) return
        val v01 = a.coerceIn(0f, 1f)
        hiddenNotifViews.forEach { (view, _) ->
            runCatching { view.alpha = v01 }
        }
    }

    private fun restoreKeyguardNotifications() {
        hiddenNotifViews.forEach { (v, vis) ->
            runCatching {
                v.visibility = vis
                v.animate().alpha(1f).setDuration(200L).start()
            }
        }
        hiddenNotifViews.clear()
    }

    // ------------------------------------------------------------------
    // 音乐锁屏"通知小岛"的数据源（图标 + 标题，轮播由 MusicLockView 负责）
    // ------------------------------------------------------------------

    /** 从通知行上摘下来的原始信息（`Icon` 还没 loadDrawable） */
    private class RowInfo(
        val pkg: String,
        val key: String,
        val title: String,
        val icon: android.graphics.drawable.Icon?
    )

    /** 上次刷新时间（1s 轮询里节流，避免每秒 loadDrawable） */
    private var lastNotifRefreshMs = 0L

    /** 上次采集的内容签名 —— 没变就复用 List（连图标都不重新解码） */
    private var lastNotifKey = ""

    private var lastNotifItems: List<NotifItem> = emptyList()

    /**
     * 音乐锁屏通知小岛：**节流刷新**数据（主线程调用，每次轮询兜一次）。
     *
     * 采集成本不低（遍历通知栈 + 反射 + `loadDrawable`），所以：
     *  · 整体节流到 [NOTIF_REFRESH_MS]（4s）—— 轮播本身 5s 一条，4s 足够新；
     *  · 内容签名没变时复用上次的 List，连 `loadDrawable` 都不做。
     */
    private fun maybeRefreshMusicNotifications(anchor: View) {
        val v = musicLock ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNotifRefreshMs < NOTIF_REFRESH_MS) return
        lastNotifRefreshMs = now
        runCatching {
            val key = collectNotifications(anchor)
            v.setNotifications(lastNotifItems)
            if (key != lastNotifKey) {
                lastNotifKey = key
                log.i("通知小岛：采集到 ${lastNotifItems.size} 条通知")
            }
        }.onFailure { log.w("通知小岛：采集失败 -> ${it.message}") }
    }

    // ---- 诊断：只打一次，用于确认 ROM 上"通知行 → entry → sbn"的真实 API ----

    private var notifApiDumped = false

    /**
     * ROM 改名/重构是常态（本机 PixelOS 17 的通知行内部已经是
     * `NotificationOptimizedLinearLayout`/`NotificationRowIconView` 这一套新类名）。
     * 所以**不写死方法名**，而是按"无参 + 返回类型"去找 accessor：
     *   · 行 → 返回类型简单名含 "Entry" 的方法（或含 entry 的字段）
     *   · entry → 返回类型可赋给 StatusBarNotification 的方法（或同类型字段）
     * 下面这个 dump 只在第一个音乐锁屏会话里打一次，方便失败时一眼看出到底有什么可用。
     */
    private fun dumpNotifRowApi(stack: ViewGroup) {
        if (notifApiDumped) return
        notifApiDumped = true
        runCatching {
            val sb = StringBuilder()
            for (i in 0 until minOf(stack.childCount, 6)) {
                val c = stack.getChildAt(i) ?: continue
                val getters = c.javaClass.methods
                    .filter { it.parameterCount == 0 && it.returnType.simpleName.contains("Entry", true) }
                    .joinToString(",") { it.name + ":" + it.returnType.simpleName }
                val fields = c.javaClass.declaredFields
                    .filter { it.name.contains("entry", true) }
                    .joinToString(",") { it.name + ":" + it.type.simpleName }
                sb.append('#').append(i).append(' ').append(c.javaClass.simpleName)
                    .append(" vis=").append(c.visibility)
                    .append(" entryGetters=[").append(getters).append(']')
                    .append(" entryFields=[").append(fields).append("] | ")
            }
            log.i("通知小岛诊断：行 API = $sb")

            // 再顺着第一个能拿到 entry 的行，把 entry 上的 sbn accessor 也 dump 出来
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i) ?: continue
                val e = entryOf(c) ?: continue
                val sbnGet = e.javaClass.methods
                    .filter {
                        it.parameterCount == 0 && android.service.notification.StatusBarNotification::class.java
                            .isAssignableFrom(it.returnType)
                    }
                    .joinToString(",") { it.name + ":" + it.returnType.simpleName }
                log.i("通知小岛诊断：entry=${e.javaClass.simpleName} sbnGetters=[$sbnGet]")
                break
            }
        }
    }

    /** 行 → NotificationEntry（按返回类型找，不写死方法名；再退化为字段） */
    private fun entryOf(row: View): Any? {
        row.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.returnType.simpleName.contains("Entry", true)
        }?.let { m ->
            m.isAccessible = true
            runCatching { m.invoke(row) }.getOrNull()?.let { return it }
        }
        var c: Class<*>? = row.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name.contains("entry", true) }?.let { f ->
                f.isAccessible = true
                runCatching { f.get(row) }.getOrNull()?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    /** entry → StatusBarNotification（同样按返回类型找） */
    private fun sbnOf(entry: Any): android.service.notification.StatusBarNotification? {
        val sbnClass = android.service.notification.StatusBarNotification::class.java
        entry.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && sbnClass.isAssignableFrom(it.returnType)
        }?.let { m ->
            m.isAccessible = true
            runCatching { m.invoke(entry) }.getOrNull()
                ?.let { return it as? android.service.notification.StatusBarNotification }
        }
        var c: Class<*>? = entry.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { sbnClass.isAssignableFrom(it.type) }?.let { f ->
                f.isAccessible = true
                runCatching { f.get(entry) }.getOrNull()
                    ?.let { return it as? android.service.notification.StatusBarNotification }
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 从 SystemUI 的**通知行**里取"图标 + 标题"，返回内容签名。
     *
     * ============ 为什么走视图树而不是 hook ============
     *
     * `hideKeyguardNotifications` 已经把 `NotificationStackScrollLayout` 的 alpha 压到 0，
     * 但**它的子视图（通知行）依然存在**，可以直接读 ——
     * 不必去 hook `NotifCollection` / `NotificationEntryManager`（版本差异 + classloader
     * 处理，风险大得多）。
     *
     * ============ classloader 注意（AGENTS §3.1）============
     *
     * 行 / entry 是 **SystemUI 的类** → 只能反射（且见 [entryOf]：连方法名都不假设）；
     * `StatusBarNotification` / `Notification` / `Icon` 是**框架类**
     * （boot classpath，与 SystemUI 共享同一个 Class 对象）→ 可以安全直接转型。
     */
    private fun collectNotifications(anchor: View): String {
        val stack = notificationStack(anchor)
        if (stack == null) {
            lastNotifItems = emptyList()
            log.w("通知小岛：找不到 NotificationStackScrollLayout（按 0 条处理）")
            return ""
        }
        dumpNotifRowApi(stack)

        val rows = ArrayList<RowInfo>()
        val sb = StringBuilder()
        val seenKeys = HashSet<String>()
        var visited = 0
        var scanned = 0

        // ============ 遍历通知栈的**整棵子树**，而不是只看顶层子视图 ============
        //
        // 真机教训（v1.38.0 第一版）：顶层只有 4 个 `ExpandableNotificationRow`，
        // 而系统里同时有 20+ 条通知 —— 因为 SystemUI 把同一应用的通知自动分组
        // （本机分组名 `Aggregate_AlertingSection`）渲染成**一个摘要行 + 嵌在行内的子行**
        // （子行已经 inflate，只是 INVISIBLE）。只扫顶层就会"漏通知"，
        // 而用户要的是"轮播**所有**通知"。
        //
        // ⚠ 刻意**不按 visibility 过滤**：锁屏上没被展开的行正是 INVISIBLE，
        //    但它们依然是有效通知（真机上三条通知里只有一条是 VISIBLE）。
        // 深度用 [NOTIF_MAX_NODES] 兜住 —— 通知子树的节点数是几百级，别做无界遍历。
        val queue = ArrayDeque<View>()
        queue.addLast(stack)
        while (queue.isNotEmpty() && rows.size < NOTIF_MAX_ITEMS && visited < NOTIF_MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node is ViewGroup) for (i in 0 until node.childCount) queue.addLast(node.getChildAt(i))
            // 只认通知行（跳过 NotificationShelf / HeaderView / ContentView / RowIconView 等）
            if (!node.javaClass.simpleName.contains("Row", ignoreCase = true)) continue
            scanned++
            runCatching {
                val entry = entryOf(node) ?: return@runCatching
                val sbn = sbnOf(entry) ?: return@runCatching
                val n = sbn.notification ?: return@runCatching
                // 群组摘要行的标题没意义（"3 条通知"之类）→ 跳过，让它的子行进来
                if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return@runCatching

                val raw = n.extras
                    ?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
                    ?: ""
                val title = raw.ifEmpty { appLabel(sbn.packageName) }
                if (title.isEmpty()) return@runCatching

                val key = sbn.key ?: ""
                val dedupe = if (key.isNotEmpty()) key else sbn.packageName + '\u0000' + title
                if (!seenKeys.add(dedupe)) return@runCatching

                rows.add(RowInfo(sbn.packageName, key, title, n.smallIcon))
                sb.append(sbn.packageName).append('|').append(key).append('|')
                    .append(title).append('\u0001')
            }
        }

        val sig = sb.toString()
        log.i("通知小岛：遍历=$visited 行=$scanned 命中=${rows.size}（栈子=${stack.childCount}）")
        // 内容没变 → 复用上次的 List（已经 loadDrawable 过的图标不用重解码）
        if (sig == lastNotifKey) return sig

        lastNotifItems = rows.map { r -> NotifItem(r.icon?.let { loadIcon(it) }, r.title) }
        return sig
    }

    private fun loadIcon(icon: android.graphics.drawable.Icon): android.graphics.drawable.Drawable? =
        runCatching { icon.loadDrawable(context) }.getOrNull()

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    /**
     * 找到承载通知卡片的 `NotificationStackScrollLayout`。
     *
     * 优先用**已经被我们压制**的那一个（`hiddenNotifViews` 里记着它），
     * 免得每 4s 重新遍历整棵视图树；兜底才走一次 BFS。
     */
    private fun notificationStack(anchor: View): ViewGroup? {
        for ((v, _) in hiddenNotifViews) {
            if (v is ViewGroup && v.javaClass.simpleName.contains("StackScroll", true)) return v
        }
        val host = runCatching { findShadeWindow(anchor) }.getOrNull() ?: return null
        val queue = ArrayDeque<View>()
        queue.addLast(host)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v.javaClass.simpleName.equals("NotificationStackScrollLayout", ignoreCase = true)) {
                return v as? ViewGroup
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
        }
        return null
    }

    /**
     * **立刻**移除音乐锁屏：不播退场动画、不显示中岛。
     *
     * 用于"锁屏已经结束/息屏"这类场景 —— 此时它已经不该出现在屏幕上，
     * 再播 420ms 退场动画就会在桌面上留下一小段时间的残影（真机反馈）。
     */
    private fun hideMusicLockImmediately() {
        val v = musicLock ?: return
        musicLock = null
        musicMode = false
        runCatching { (v.parent as? android.view.ViewGroup)?.removeView(v) }
        // 无动画退出时 k 不会被推到 0，所以要显式把**系统时钟/日期恢复原样**
        // （否则时钟会永远停在"缩小上移"的状态）
        runCatching { resetClockTransform() }
        // mini 时钟用完，把系统时钟/日期还原（幂等）
        runCatching { restoreSystemClockVisibility() }
        // ============ 息屏时**不还原**通知（真机反馈"亮屏还是极短闪现"）============
        //
        // 之前熄屏时会（延迟）还原通知容器，于是亮屏那一刻系统的通知栈是可见的，
        // 我们随后才压下去 —— 中间总有一帧追不上（那是合成层面的时序，改 alpha 来不及）。
        //
        // 现在：只要**期望形态仍是音乐锁屏**，熄屏就保持压制、根本不还原。
        // 亮屏时通知从头到尾没可见过，自然不会有任何闪现。
        //
        // 解锁（not dozing）必须还原，否则用户回到桌面后通知栏会是空的。
        if (dozing && desiredForm == FORM_MUSIC) {
            log.mark("息屏：期望形态仍是音乐锁屏 -> 保持压制通知（不还原，避免亮屏闪现）")
        } else {
            restoreKeyguardNotifications()
        }
    }

    /** 音乐锁屏 → 回到中岛（**逆一镜到底**：各元素飞回中岛里的位置） */
    private fun hideMusicLock() {
        val v = musicLock ?: return
        musicLock = null

        // ============ 还原系统时钟/日期/充电提示（真机 bug）============
        //
        // hideMusicLockImmediately（解锁/熄屏那条路径）里有还原，
        // 但**带动画的这条路径漏了** —— 于是从音乐锁屏拖回中岛后，
        // 系统时钟和日期一直是 INVISIBLE（用户实测："没有恢复"）。
        runCatching { restoreSystemClockVisibility() }

        // ============ 虚影（真机反馈）============
        //
        // 原实现在这里立刻 updateVisibility() 让中岛显示，而音乐锁屏还要淡出 420ms。
        // 岛是后加的子视图、绘制在音乐锁屏**之上**，于是这 420ms 里
        // 中岛的卡片封面 与 音乐锁屏正在缩小的封面 **同时可见** → 叠影。
        //
        // 所以：保持 musicMode=true（岛继续 GONE）直到逆动画结束，
        // 结束时音乐锁屏的封面**正好落在中岛卡片的矩形上**，此刻再切换，
        // 视觉上完全等价，不会有叠影也不会有跳变。
        // ============ v1.41.0：退场改成"塌缩"（理念同中岛→小岛）============
        //
        // 旧实现：300·k ms 的强调减速 + 收尾 90ms **交叉淡出**。
        // 新实现：时长 `140 + 180·k`、曲线 `(0.55,0.05,0.2,1)`（都在 MusicExitMotion 里、
        // 有单测钉住），落定到**逐元素几何重合**后由 [onMusicExitLanded] 交棒。
        //
        // ⚠ 时长不再在这里算 —— 旧代码把 `300f` 抄了一份，MusicLockView 改时长时
        //   这里会白等/抢跑（AGENTS 里记着这个坑）。现在由动画自己回调。
        v.startExitCollapse(heroRectsCache) { onMusicExitLanded(v) }

        val handoffDesc = if (EXIT_CROSSFADE_MS > 0L) {
            "交叉淡出 " + EXIT_CROSSFADE_MS + "ms"
        } else {
            "瞬时 + 中岛模糊"
        }
        log.mark("退出音乐锁屏 → 塌缩回中岛（k=" + v.heroProgress + "，交棒=" + handoffDesc + "）")
    }

    /**
     * 音乐锁屏**塌缩落定后**的交棒：让中岛就位，再撤掉音乐视图。
     *
     * 顺序沿用旧实现（真机 bug：先撤音乐视图会让按钮"移走→消失→又出现"）：
     *   ① 给中岛挂满档模糊（此刻音乐视图还是糊的，视觉连续）
     *   ② 展开内容 → 同步数据 → 重新应用形态高度
     *   ③ musicMode=false + 还原通知 + 重算可见性
     *   ④ 撤掉音乐视图（瞬时；或按 [EXIT_CROSSFADE_MS] 退回旧的交叉淡出——用于对比）
     */
    private fun onMusicExitLanded(v: com.example.livenotification.island.ui.MusicLockView) {
        // 🔴 v1.41.6：交棒链要跑好几帧，期间屏幕上仍是这张**全屏**音乐视图 ——
        //   先把它的模糊摘掉，否则被冻住的是一张糊图（用户："模糊时间太长/像未响应"）。
        runCatching { v.clearExitBlur() }
        island?.let { iv ->
            runCatching {
                // 接棒的遮瑕：与中岛→小岛的"换布局后模糊淡出"同一套（~120ms）
                iv.handoffBlurFromMusicExit()
                iv.setExpanded(true)
                applyRenderPlan(buildRenderPlan())
                // 显式重应用形态高度（真机老 bug：回来时"尺寸=小岛、内容=中岛"）
                setMorphExpanded(true)
            }
        }
        musicMode = false
        restoreKeyguardNotifications()
        island?.let { updateVisibility() }

        if (EXIT_CROSSFADE_MS > 0L) {
            v.animate()
                .alpha(0f)
                .setDuration(EXIT_CROSSFADE_MS)
                .withEndAction {
                    runCatching { (v.parent as? ViewGroup)?.removeView(v) }
                }
                .start()
        } else {
            // 瞬时切：几何已逐元素重合，中岛侧还挂着模糊 —— 看不出接缝
            runCatching { (v.parent as? ViewGroup)?.removeView(v) }
        }
    }

    /** 媒体数据变化时同步到音乐锁屏（不在音乐形态时是空操作） */
    private fun syncMusicLock(snap: MediaSnapshot) {
        val v = musicLock ?: return
        v.setTexts(snap.title, snap.artist)
        v.setArt(snap.albumArt)
        v.setPlaying(snap.playing)
    }

    fun setMorphExpanded(expanded: Boolean) {
        val view = island ?: return
        val container = root ?: return

        val cfg = readConfig()
        val collapsedH = dp(cfg.heightDp)
        val expandedH = dp(cfg.expandedHeightDp)
        if (collapsedH <= 0 || expandedH <= 0) return

        // 拖动压缩的目标比例（收起高/展开高）—— 由控制器注入，IslandView 不读配置
        runCatching { view.compressToRatio = (collapsedH.toFloat() / expandedH).coerceIn(0.1f, 1f) }

        // ============ 不能只看 morphExpanded 标志位（真机老 bug，修了两轮）============
        //
        // 现象：从音乐锁屏切回中岛，**有几率尺寸变成小岛、但内容仍是中岛**；
        //      点一下（→中岛）再切回小岛才恢复。
        //
        // 本质：**高度是收起值、而内容标志是展开** —— 于是 190dp 的卡片被塞在 196px
        // 高的视图里，看起来就是"小岛尺寸 + 中岛内容"。
        //
        // 第 1 轮修法用 `morphExpanded == expanded` 判重 → 无效（标志位本来就是 true）。
        // 第 2 轮改用 `lastAppliedShapeHeight` → **仍然无效**：
        //   它记的是"我们**请求过**的高度"，一旦高度被**别的路径**改成收起值
        //   （而那个路径没更新这个记录），记录依然说"是展开高度" → 又 early-return。
        //
        // 所以第 3 轮直接读 **LayoutParams.height** —— 那是布局真正会用的值，
        // 不受"谁改的"影响；也不用 `view.height`（重挂时会短暂为 0，AGENTS.md 有记载）。
        val lpH = (view.layoutParams as? FrameLayout.LayoutParams)?.height ?: -1
        val targetH = if (expanded) expandedH else collapsedH
        val heightAlreadyRight = lpH == targetH
        if (morphExpanded == expanded && !morphAnimating && heightAlreadyRight) return

        // 形态标志没变、只是尺寸不同步 -> 直接吸附，避免"从小岛长出来"的多余动画
        if (morphExpanded == expanded) instantMorph = true

        morphExpanded = expanded
        // 记住形态（小岛/中岛）。注意：这里的恢复路径也会调用本方法，重复赋值无害。
        desiredForm = if (expanded) FORM_MEDIUM else FORM_SMALL

        // 内容切换与淡入由视图自己处理（setExpanded 只动可见性与 alpha）
        view.setExpanded(expanded && view.state == IslandView.STATE_MEDIA)

        // ============ 底部锚点必须**钉死**（用户反馈"中岛往下滑、底边不该动"）============
        //
        // 旧实现用 `view.top + view.height` 从**当前实时位置**反推底边 ——
        // 但本项目早就记录过真机现象："top 始终为 0"（见 logConstraintFields 的注释）。
        // 于是 anchor = 0 + height，而 height **正在被动画改变** ->
        // 底边每帧都在动，观感就是"整块中岛往下滑"。
        //
        // 正确做法：底边是一个**由 bias 与父容器高度决定的常量**，
        // 与当前高度、当前 top 完全无关 —— 这样收缩时只有**上边框**往下走。
        // 只在父容器高度变化时（旋转/分辨率变化）重算，其余时刻复用缓存。
        val ph = if (container.height > 0) container.height
        else context.resources.displayMetrics.heightPixels
        if (stableBottomAnchor <= 0 || lastAnchorParentH != ph) {
            stableBottomAnchor = LayoutMath.bottomAnchorForBias(ph, collapsedH, liveBiasPercent / 100f)
            lastAnchorParentH = ph
        }
        val anchor = stableBottomAnchor
        morphBottomAnchor = anchor

        val from = view.height.takeIf { it > 0 } ?: if (expanded) collapsedH else expandedH
        val to = if (expanded) expandedH else collapsedH

        morphAnimator?.cancel()

        // ============ 亮屏恢复：直接落到最终形态，不播动画 ============
        //
        // 真机反馈："在中岛界面息屏再亮屏会重新从小岛长出来"。
        // 因为熄屏时 resetMorph() 已经把视觉重置成小岛，恢复时又走了
        // 260ms 的"长高"动画 —— 用户就会看到一次多余的长高过程。
        //
        // 亮屏恢复应当是"直接就是那个形态"，所以这里跳过动画，
        // 直接把高度落到目标值（和音乐锁屏那边的 animated=false 同理）。
        if (instantMorph) {
            // 用掉就复位 —— 现在它也会被 setMorphExpanded **内部**设置
            // （形态标志没变、只是尺寸不同步时），不能让状态残留到下一次调用。
            instantMorph = false
            morphAnimating = false
            val lp = view.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                // 同样要用"确定性 top"：只写 topMargin 会被 bottom+bias 约束求解覆盖
                ClConstraints.applyDeterministicTop(lp, lp.width, to, (anchor - to).coerceAtLeast(0))
                view.layoutParams = lp
            }
            lastSignature = null
            applyConstraints(reason = "restore-instant")
            return
        }

        morphAnimating = true
        morphAnimator = android.animation.ValueAnimator.ofInt(from, to).apply {
            // 260 -> **220ms**（"松散"整改 A）：中岛↔小岛是可见范围较小的 morph，
            // 比全屏一镜到底更快收住，"果断"。
            duration = 220L
            // ============ 轻微过冲（整改 D）============
            //
            // 0.2,0,0,1 是纯减速（无过冲），观感偏"软"。
            // 这里给一点点回弹：控制点 y1 = 1.3 会让高度**略微越过**目标再落回，
            // 产生"物理感"（iOS/One UI 的卡片展开都带这一下）。
            //
            // 过冲量刻意**很小**（1.3，不是 1.6+）—— 过冲太大会在圆角与内容上露出破绽。
            // 仅用于**中岛↔小岛**这个可控范围；音乐锁屏（全屏）那条路径**不加**过冲，
            // 否则全屏内容越界会露边。
            interpolator = android.view.animation.PathInterpolator(0.34f, 1.3f, 0.64f, 1f)
            addUpdateListener { anim ->
                val h = anim.animatedValue as Int
                val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return@addUpdateListener
                // ============ 必须用"确定性 top"（用户反馈：底边不该动）============
                //
                // 只改 height + topMargin **不够**：如果 LP 上还留着
                // `bottomToBottom` 与 `verticalBias`，ConstraintLayout 会用
                // "top + bottom + bias" 三者共同求解位置 ——
                // 高度一变，底边就跟着走（= 用户看到的"整块中岛往下滑"）。
                //
                // applyDeterministicTop 会解掉 bottom 约束、清零 bias，
                // 让位置**只由 topMargin 决定**（ClHelper 里对此有真机结论记录）。
                ClConstraints.applyDeterministicTop(
                    lp, lp.width, h, (anchor - h).coerceAtLeast(0)
                )
                view.layoutParams = lp
                // 自检：底边是否真的钉在 anchor 上（真机可用日志核对）
                if (morphFrameLog < 3) {
                    morphFrameLog++
                    log.i(
                        "morph自检：h=$h top=${lp.topMargin} 底边=${lp.topMargin + h} " +
                            "anchor=$anchor 偏差=${lp.topMargin + h - anchor}"
                    )
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    morphAnimating = false
                    lastSignature = null
                    applyConstraints(reason = "morph-end")
                    // 展开后量一次各段实际宽度：用数字确认文字列够宽、
                    // 右侧按钮没有被挤出岛边界（真机曾出现按钮贴边被裁）
                    if (expanded) {
                        view.postDelayed({ view.logExpandedLayout() }, 350L)
                    }
                }
            })
            start()
        }
        log.mark(
            "变形：${if (expanded) "小岛→中岛" else "中岛→小岛"} " +
                "${from}px → ${to}px（底部锚点=$anchor）"
        )
    }

    /** 立即复位到收起形态（不做动画），用于岛被隐藏等场景 */
    private fun resetMorph() {
        morphAnimator?.cancel()
        morphAnimator = null
        morphAnimating = false
        if (morphExpanded) {
            morphExpanded = false
            island?.setExpanded(false)
            lastSignature = null
        }
    }

    /**
     * 读回岛的实际约束字段，判断「字段没设上」还是「父容器不求解」。
     *
     * 真机现状：topMargin=2052 已设、verticalBias=0.84 已设，但 top 始终为 0。
     * 必须区分这两种可能，否则无法决定下一步。
     */
    fun logConstraintFields() {
        val v = island ?: return
        val lp = v.layoutParams ?: return
        runCatching {
            val topToTop = ClConstraints.getInt(lp, "topToTop", -999)
            val bottomToBottom = ClConstraints.getInt(lp, "bottomToBottom", -999)
            val startToStart = ClConstraints.getInt(lp, "startToStart", -999)
            val endToEnd = ClConstraints.getInt(lp, "endToEnd", -999)
            val vBias = runCatching {
                lp.javaClass.getField("verticalBias").getFloat(lp)
            }.getOrDefault(-999f)
            val topMargin = (lp as? ViewGroup.MarginLayoutParams)?.topMargin ?: -999
            log.mark(
                "约束回读：class=${lp.javaClass.simpleName} " +
                    "topToTop=$topToTop bottomToBottom=$bottomToBottom " +
                    "startToStart=$startToStart endToEnd=$endToEnd " +
                    "verticalBias=$vBias topMargin=$topMargin " +
                    "w=${(lp as ViewGroup.LayoutParams).width} h=${(lp as ViewGroup.LayoutParams).height} " +
                    "实际位置=(${v.left},${v.top})-(${v.right},${v.bottom})"
            )
        }.onFailure { log.w("约束回读失败：${it.message}") }
    }

    /** 启动可见性看门狗（幂等） */
    private fun startWatchdog() {
        if (watching) return
        watching = true
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, WATCHDOG_INTERVAL_MS)
        log.i("可见性看门狗已启动（间隔 ${WATCHDOG_INTERVAL_MS}ms）")
    }

    private fun stopWatchdog() {
        watching = false
        handler.removeCallbacks(visibilityWatchdog)
    }

    /**
     * 判断这个 KeyguardRootView 是否在锁屏窗口内。
     *
     * 真机证据：ScreenDecorations 窗口里也有 KeyguardRootView 实例，
     * 只按 id 判定会命中错的实例。锁屏根布局的祖先链一定包含
     * NotificationShadeWindowView（legacy_window_root），向上找它即可。
     *
     * 用**类名**比较而非 is/as：见类注释的类加载器隔离说明。
     */
    private fun isInsideShadeWindow(view: View): Boolean {
        var p: android.view.ViewParent? = view.parent
        var depth = 0
        while (p != null && depth < 12) {
            if (p is View && p.id == NOTIFICATION_SHADE_WINDOW_ID) return true
            p = p.parent
            depth++
        }
        return false
    }

    /** 祖先链描述，用于诊断到底命中了哪个窗口 */
    private fun describeAncestors(view: View): String {
        val sb = StringBuilder()
        var p: android.view.ViewParent? = view.parent
        var depth = 0
        while (p != null && depth < 6) {
            sb.append((p as? View)?.javaClass?.simpleName ?: p.javaClass.simpleName)
            sb.append(" > ")
            p = p.parent
            depth++
        }
        return sb.toString().trimEnd(' ', '>')
    }

    /**
     * 挂载后自证。addView 成功不等于岛真的上屏，这里在几个时间点主动检查并给结论，
     * 把「成功了」和「以为成功了」区分开。
     */
    private fun verifyAttached(delayMs: Long) {
        handler.postDelayed({
            val view = island ?: return@postDelayed
            val container = root ?: return@postDelayed
            val measured = view.width > 0 && view.height > 0
            val laidOut = view.bottom > view.top && view.right > view.left
            val inTree = view.parent === container
            val shownToUser = view.isShown

            val verdict = when {
                !inTree -> "❌ 不在锁屏布局树里"
                !measured -> "❌ 未被测量（宽高为 0）"
                !laidOut -> "❌ 布局结果为空"
                !shownToUser -> "⚠ 已布局但当前不可见（可能不在锁屏上/息屏）"
                else -> "✅ 已上屏"
            }

            // 关键诊断：读出 LayoutParams 上的实际边距，判断是「没设上」还是「没被采用」
            val lpInfo = runCatching {
                val lp = view.layoutParams
                val mlp = lp as? ViewGroup.MarginLayoutParams
                "${lp?.javaClass?.simpleName} " +
                    "left=${mlp?.leftMargin} top=${mlp?.topMargin} " +
                    "w=${lp?.width} h=${lp?.height}"
            }.getOrDefault("读取失败")

            log.mark(
                "挂载自证(${delayMs}ms)：$verdict " +
                    "[inTree=$inTree measured=$measured laidOut=$laidOut shown=$shownToUser " +
                    "size=${view.width}x${view.height} " +
                    "位置=(${view.left},${view.top})-(${view.right},${view.bottom}) " +
                    "宿主=${container.width}x${container.height} " +
                    "visibility=${view.visibility} bias=$liveBiasPercent% " +
                    "LP[$lpInfo]]"
            )

            if (!measured) {
                log.i("自证未通过，重新套用约束")
                lastSignature = null
                applyConstraints(reason = "verify-retry")
            }

            // 第二次自证时把完整探针与自动标定打出来（此时宿主已完成布局，
            // 子视图坐标才有意义）。这是「把默认位置标定到真实像素」的关键输出。
            if (delayMs >= 4000L) {
                logInjectionProbe(container)
            }
        }, delayMs)
    }

    fun detach() {
        val container = root
        val view = island
        stopPolling()
        stopWatchdog()
        status.stop()
        if (container != null && view != null) {
            runCatching {
                view.removeOnLayoutChangeListener(layoutListener)
                container.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
                container.removeView(view)
            }
        }
        island = null
        log.i("已移除锁屏岛")
    }

    fun isAttached(): Boolean = island?.parent != null

    fun currentView(): IslandView? = island

    // ------------------------------------------------------------------
    // 探针
    // ------------------------------------------------------------------

    /**
     * 锁屏根布局探针。这是「盲测」的关键：把每个子 View 的位置、大小、类名
     * 打出来，据此把默认垂直位置标定到真实像素；同时自动识别时钟底部与
     * 快捷方式顶部，直接算出该用哪个百分比。
     */
    fun logInjectionProbe(container: ViewGroup) {
        if (!log.verbose) return
        runCatching {
            log.i(
                "锁屏根布局探针：宿主=${container.javaClass.name} " +
                    "size=${container.width}x${container.height} children=${container.childCount}"
            )

            var clockBottom = -1
            var shortcutsTop = Int.MAX_VALUE
            val islandH = island?.height ?: dp(IslandConfig.DEF_HEIGHT_DP)

            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i) ?: continue
                if (child === island) continue
                val loc = IntArray(2)
                child.getLocationInWindow(loc)
                log.i(
                    "  子[$i] ${child.javaClass.simpleName} " +
                        "id=0x${Integer.toHexString(child.id)} " +
                        "vis=${visName(child.visibility)} shown=${child.isShown} " +
                        "l=${child.left} t=${child.top} r=${child.right} b=${child.bottom} " +
                        "w=${child.width} h=${child.height} win=[${loc[0]},${loc[1]}]"
                )

                val simple = child.javaClass.simpleName
                if (child.isShown && child.bottom > 0) {
                    if (simple.contains("Clock") || simple.contains("clock")) {
                        clockBottom = maxOf(clockBottom, child.bottom)
                    }
                    if (simple.contains("LaunchableImageView") ||
                        simple.contains("Shortcut") ||
                        simple.contains("Affordance")
                    ) {
                        shortcutsTop = minOf(shortcutsTop, child.top)
                    }
                }
            }

            logAutoCalibration(container, islandH, clockBottom, shortcutsTop)
            logDrawOrder(container)
        }.onFailure { log.e("探针失败", it) }
    }

    /**
     * 自动标定：拿到真实的时钟底部与快捷方式顶部后，直接把「该用哪个百分比」
     * 算出来写进日志，用户不用手工换算。
     */
    private fun logAutoCalibration(
        container: ViewGroup,
        islandH: Int,
        clockBottom: Int,
        shortcutsTop: Int
    ) {
        val h = container.height
        if (h <= 0) return
        val usableBottom = if (shortcutsTop == Int.MAX_VALUE) h else shortcutsTop

        if (clockBottom < 0) {
            log.w("自动标定：未能从子视图识别出时钟，跳过（可在设置界面手动拖动校准）")
            return
        }

        val desiredTop = (clockBottom + dp(16)).coerceAtMost(usableBottom - islandH)
        val percent = com.example.livenotification.island.util.LayoutMath.percentForTop(
            h, islandH, desiredTop
        )

        log.mark(
            "★ 自动标定结果：把「垂直位置」设为 $percent% 即可让岛位于时钟下方 16dp " +
                "[时钟底部=$clockBottom 快捷方式顶部=$usableBottom " +
                "期望顶部=$desiredTop 当前顶部=${island?.top ?: -1} " +
                "岛高=$islandH 宿主高=$h 当前百分比=$liveBiasPercent]"
        )

        val belowClock = com.example.livenotification.island.util.LayoutMath.isBelowClock(
            h, islandH, liveBiasPercent / 100f, clockBottom
        )
        val aboveShortcuts = com.example.livenotification.island.util.LayoutMath.isAboveShortcuts(
            h, islandH, liveBiasPercent / 100f, usableBottom
        )
        log.mark(
            "位置校验：在时钟下方=$belowClock 在快捷方式上方=$aboveShortcuts " +
                (if (!belowClock || !aboveShortcuts) " ← 建议改用 $percent%" else "（当前已合格）")
        )
    }

    /**
     * 绘制层级诊断。NotificationShadeWindowView 是 FrameLayout 且未改写
     * dispatchDraw，所以绘制顺序 = 子 View 添加顺序。
     * 已知索引 7=KeyguardRootView（我们的宿主）、8=SharedNotificationContainer，
     * 因此锁屏上有通知时通知卡会画在岛之上。这里把完整层级打印出来作为依据。
     */
    private fun logDrawOrder(keyguardRoot: ViewGroup) {
        runCatching {
            val shadeRoot = keyguardRoot.parent as? ViewGroup
            if (shadeRoot == null) {
                log.i("绘制层级：宿主的父容器不是 ViewGroup，无法分析")
                return
            }
            val myIndex = (0 until shadeRoot.childCount).firstOrNull {
                shadeRoot.getChildAt(it) === keyguardRoot
            } ?: -1

            log.mark(
                "绘制层级诊断：上层=${shadeRoot.javaClass.simpleName} " +
                    "共 ${shadeRoot.childCount} 个子 View，宿主索引=$myIndex"
            )
            for (i in 0 until shadeRoot.childCount) {
                val child = shadeRoot.getChildAt(i) ?: continue
                val relation = when {
                    i < myIndex -> "在我之下（先画）"
                    i == myIndex -> "★ 我的宿主"
                    else -> "在我之上（后画，会盖住岛）"
                }
                log.i("  [$i] ${child.javaClass.simpleName} vis=${visName(child.visibility)} $relation")
            }
        }.onFailure { log.e("绘制层级诊断失败", it) }
    }

    private fun visName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "?$v"
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private data class Cfg(
        val enabled: Boolean,
        val widthPercent: Int,
        val maxWidthDp: Int,
        val heightDp: Int,
        val expandedHeightDp: Int,
        val cornerDp: Int,
        val verticalBias: Int,
        val showOnAod: Boolean,
        val debugAlwaysShow: Boolean,
        val dragEnabled: Boolean,
        /** 音乐锁屏样式：0=当前（铺满封面）1=One UI（独立卡片 + 粗进度条 + 纯图标控制） */
        val musicStyle: Int
    )

    /**
     * 配置读取三级优先级：
     *  1. configSnapshot —— 设置界面推来的快照，跨进程确定可靠
     *  2. ConfigStore —— 同进程或框架允许时能读到
     *  3. 内置默认值 —— 读不到也不会崩，只是回到默认外观
     */
    @Volatile
    private var configSnapshot: Map<String, String> = emptyMap()

    /**
     * 收到设置界面推来的完整配置快照。
     * 不依赖 hook 能否读文件/prefs，所以这是「改设置一定生效」的保证。
     */
    fun applyConfigSnapshot(text: String?): Boolean {
        val cs = com.example.livenotification.island.config.ConfigSnapshot
        val parsed = cs.decode(text)
        if (parsed.isEmpty()) {
            log.w("配置快照为空或无法解析，忽略")
            return false
        }
        val changed = parsed != configSnapshot
        configSnapshot = parsed
        log.mark("已接收配置快照（${parsed.size} 项，变化=$changed）: $parsed")
        if (changed) {
            biasInitialized = false
            widthInitialized = false
            lastSignature = null
            val view = island
            if (view != null) {
                view.setDragEnabled(
                    cs.toBoolean(parsed[cs.KEY_DRAG_ENABLED], IslandConfig.DEF_DRAG_ENABLED)
                )
                view.post {
                    applyConstraints(reason = "snapshot")
                    updateVisibility()
                }
            }
        }
        // 无论快照是否"变化"，都显式刷新一次可见性 ——
        // 真机出现过「快照已生效但岛仍是 GONE」的情况：
        //   keyguardShowing=true / debug_always_show=true，却没有 VISIBLE 日志。
        // 多刷一次的成本极低，能避免岛卡在不可见状态。
        island?.post { updateVisibility() }
        return true
    }

    private fun readConfig(): Cfg {
        val snap = configSnapshot
        val cs = com.example.livenotification.island.config.ConfigSnapshot

        fun bool(key: String, def: Boolean): Boolean =
            if (snap.containsKey(key)) cs.toBoolean(snap[key], def)
            else ConfigStore.getBoolean(context, key, def)

        fun int(key: String, def: Int): Int =
            if (snap.containsKey(key)) cs.toInt(snap[key], def)
            else ConfigStore.getInt(context, key, def)

        return Cfg(
            enabled = bool(IslandConfig.KEY_ENABLED, IslandConfig.DEF_ENABLED),
            widthPercent = IslandConfig.clampPercent(
                int(IslandConfig.KEY_WIDTH_PERCENT, IslandConfig.DEF_WIDTH_PERCENT)
            ),
            maxWidthDp = int(IslandConfig.KEY_MAX_WIDTH_DP, IslandConfig.DEF_MAX_WIDTH_DP),
            heightDp = int(IslandConfig.KEY_HEIGHT_DP, IslandConfig.DEF_HEIGHT_DP),
            expandedHeightDp = int(
                IslandConfig.KEY_EXPANDED_HEIGHT_DP, IslandConfig.DEF_EXPANDED_HEIGHT_DP
            ),
            cornerDp = int(IslandConfig.KEY_CORNER_DP, IslandConfig.DEF_CORNER_DP),
            verticalBias = IslandConfig.clampPercent(
                int(IslandConfig.KEY_VERTICAL_BIAS, IslandConfig.DEF_VERTICAL_BIAS)
            ),
            showOnAod = bool(IslandConfig.KEY_SHOW_ON_AOD, IslandConfig.DEF_SHOW_ON_AOD),
            debugAlwaysShow = bool(
                IslandConfig.KEY_DEBUG_ALWAYS_SHOW, IslandConfig.DEF_DEBUG_ALWAYS_SHOW
            ),
            dragEnabled = bool(IslandConfig.KEY_DRAG_ENABLED, IslandConfig.DEF_DRAG_ENABLED),
            musicStyle = int(IslandConfig.KEY_MUSIC_STYLE, IslandConfig.DEF_MUSIC_STYLE)
        )
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private var draggedPercent: Int = IslandConfig.DEF_VERTICAL_BIAS

    /** 当前生效的垂直位置百分比。拖动时它领先于磁盘配置，避免被布局回调弹回旧值。 */
    @Volatile
    private var liveBiasPercent: Int = IslandConfig.DEF_VERTICAL_BIAS

    @Volatile
    private var liveWidthPercent: Int = IslandConfig.DEF_WIDTH_PERCENT

    @Volatile
    private var biasInitialized: Boolean = false

    @Volatile
    private var widthInitialized: Boolean = false

    private var draggedWidthPercent: Int = IslandConfig.DEF_WIDTH_PERCENT

    /**
     * 重新套用约束与尺寸。幂等、轻量，可随时调用。
     */
    fun applyConstraints(reason: String) {
        val container = root ?: return
        val view = island ?: return
        // 宿主尚未测量时先跳过；宿主布局完成后 onLayoutChange 会再调用一次。
        // 因为短路判断用的是含宿主尺寸的签名，那时会重新计算并正确落位。
        if (container.width <= 0 || container.height <= 0) return

        // ============ 变形动画期间完全让位（必须在熔断计数之前） ============
        //
        // 真机事故：变形动画每帧写 layoutParams → 触发 onLayoutChange →
        // 调 applyConstraints。这个调用本身是**预期行为**（动画帧），
        // 但原来它先经过了熔断计数器，于是 260ms 的动画就顶到了 150 次上限，
        // 熔断被误触发，把岛摘掉并写了 enabled=false。
        //
        // 所以「动画期间直接返回」必须在计数之前 —— 正常动画帧不该被当成洪泛。
        if (morphAnimating) return

        // ============ 防重入（这是两次真机事故的共同根因） ============
        //
        // 本方法会被 onLayoutChange 调用，而它自己又会引起布局变化。
        // 一旦重入形成环，就会无限递归 → SystemUI 主线程卡死 → ANR →
        // 进程被杀 → 重启 → 再卡死（真机上表现为锁屏永远加载不出来）。
        //
        // 任何「布局回调 → 改布局」的路径都必须有这个守卫。
        if (applyingConstraints) {
            log.i("applyConstraints($reason) 重入被拦下（防递归）")
            return
        }
        applyingConstraints = true
        try {
            // 熔断：即使防重入失效（例如异常路径绕过了 finally），
            // 单位时间内调用次数超限也直接停掉整个岛。
            if (!tripBreakerIfFlooding()) return
            applyConstraintsInternal(reason)
        } finally {
            applyingConstraints = false
        }
    }

    @Volatile
    private var applyingConstraints = false

    // ---- 熔断器 ----

    private var applyCountInWindow = 0
    private var windowStartAt = 0L

    @Volatile
    private var breakerTripped = false

    /**
     * 熔断检查。
     *
     * ============ 为什么必须有它 ============
     *
     * 我两次把用户的 SystemUI 搞成 ANR 死循环（一次 onFinishInflate 挂到 View
     * 造成洪泛、一次强制 measure/layout 造成递归），而**用户在锁屏卡死时
     * 没法去 LSPosed 里禁用模块** —— 只能靠救砖机制。
     *
     * 所以模块必须能自己刹车：一旦发现单位时间内布局调用量异常，
     * 立刻停掉整个岛并把 enabled 写成 false，这样下次 SystemUI 重启
     * 也不会再挂载，用户就永远有退路。
     */
    private fun tripBreakerIfFlooding(): Boolean {
        if (breakerTripped) return false
        val now = System.currentTimeMillis()
        if (now - windowStartAt > BREAKER_WINDOW_MS) {
            windowStartAt = now
            applyCountInWindow = 0
        }
        applyCountInWindow++
        if (applyCountInWindow <= BREAKER_MAX_APPLIES) return true

        breakerTripped = true
        log.e(
            "★ 熔断触发：${BREAKER_WINDOW_MS}ms 内 applyConstraints 被调用 " +
                "$applyCountInWindow 次（上限 $BREAKER_MAX_APPLIES），" +
                "判定为布局死循环。立即停用整个岛并关闭开关，避免 SystemUI 卡死。"
        )
        emergencyShutdown()
        return false
    }

    /** 紧急停机：把自己从视图树摘掉、停掉轮询、并把 enabled 写成 false */
    private fun emergencyShutdown() {
        runCatching {
            stopPolling()
            stopWatchdog()
            val container = root
            val view = island
            if (container != null && view != null) {
                view.removeOnLayoutChangeListener(layoutListener)
                runCatching { container.viewTreeObserver.removeOnPreDrawListener(preDrawListener) }
                container.removeView(view)
            }
            island = null
        }.onFailure { log.e("紧急停机清理失败", it) }

        // 关键：落盘关闭开关。SystemUI 进程写不了模块 prefs（不同 UID），
        // 所以发广播让模块进程写；同时写下自己的快照，保证下次启动也不挂载。
        runCatching {
            context.sendBroadcast(
                Intent(IslandConfig.ACTION_EMERGENCY_DISABLE).apply {
                    setPackage(MODULE_PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
            )
        }.onFailure { log.i("发送紧急停用广播失败：${it.message}") }

        val offSnapshot = "enabled=false\n"
        runCatching {
            java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/$MODULE_PACKAGE/files/config.txt"
            ).writeText(offSnapshot, Charsets.UTF_8)
        }
    }

    private fun applyConstraintsInternal(reason: String) {
        val container = root ?: return
        val view = island ?: return

        runCatching {
            val cfg = readConfig()

            if (!biasInitialized) {
                liveBiasPercent = cfg.verticalBias
                draggedPercent = cfg.verticalBias
                biasInitialized = true
            }
            if (!widthInitialized) {
                liveWidthPercent = cfg.widthPercent
                draggedWidthPercent = cfg.widthPercent
                widthInitialized = true
            }
            val biasPercent = liveBiasPercent

            val targetWidth = minOf(
                (container.width * liveWidthPercent / 100f).toInt(),
                dp(cfg.maxWidthDp)
            ).coerceAtLeast(dp(120))

            val heightPx = dp(cfg.heightDp)
            val expandedPx = dp(cfg.expandedHeightDp)
            val cornerPx = if (cfg.cornerDp < 0) -1 else dp(cfg.cornerDp)

            // ============ 高度：变形状态决定（换 FrameLayout 锚点后可以安全变高） ============
            //
            // 过去「高度恒定不变」是因为岛挂在 KeyguardRootView(ConstraintLayout) 上，
            // 改高度要求父容器重解约束，而那条路被类加载器隔离堵死。
            // 现在岛挂在 FrameLayout 上，高度就是 layoutParams.height 的字面值，
            // 所以收起态/中岛态可以安全切换，变形动画在 setMorphExpanded 里做。
            //
            // 动画期间 layoutParams 由 animator 逐帧写，这里必须让位，否则互相覆盖。
            if (morphAnimating) return@runCatching

            val effectiveHeight = if (morphExpanded) expandedPx else heightPx

            // ============ 位置算术走 LayoutMath（有单元测试覆盖） ============
            //
            // 底部锚点 = 收起态底边（由用户设定的 bias 决定），变形时保持不变。
            // 目标 top = 底部锚点 − 当前高度 → 「向上长高、底边不动」。
            //
            // 这些公式抽到 LayoutMath 并被 FrameLayoutGeometryTest 用真机实测数字
            // （1216×2640、密度 3.5、锚点 2248）验证过，避免再靠真机试错发现算术错误。
            val effectiveBias = biasPercent / 100f
            val bottomAnchor = LayoutMath.bottomAnchorForBias(
                container.height, heightPx, effectiveBias
            )
            val targetTop = LayoutMath.topForBottomAnchor(bottomAnchor, effectiveHeight)

            val signature =
                "w=$targetWidth;h=$effectiveHeight;t=$targetTop;cw=${container.width};ch=${container.height}"

            // ============ 幂等短路必须放在最前面（真机教训） ============
            //
            // 本方法会被 onLayoutChange 与 onPreDraw 调用，而它自己又会改布局，
            // 于是可能形成「布局 → 回调 → 改布局 → 布局」的跨帧循环 ——
            // 那正是把 SystemUI 拖崩的那种模式。
            //
            // 关键点：短路必须发生在**任何写操作之前**，包括 applyStyle()。
            // applyStyle 会改 content/expandedContent 的内边距与子视图 LayoutParams，
            // 那些改动同样会引发新一轮布局 —— 如果把它放在短路之前，
            // 即使尺寸没变也会持续搅动布局树（实测把熔断顶到 151 次）。
            //
            // 所以：先算签名 → 没变就立即返回、一个字节都不改。
            // lastSignature 在拖动/缩放/旋转/快照/变形结束时会置空，需要时会重新应用。
            if (signature == lastSignature) return@runCatching

            view.applyStyle(heightPx, cornerPx, expandedPx)
            view.setBiasFraction(effectiveBias)
            view.setWidthPercent(liveWidthPercent)

            // ============ 位置：纯 FrameLayout 边距，不涉及任何约束 ============
            //
            // 岛现在挂在 NotificationShadeWindowView（FrameLayout）上，
            // 所以位置就是 leftMargin/topMargin 的字面值 —— 确定性、可预测、
            // 不会被任何蓝图清空，也不需要"重解约束"。
            //
            // 这正是换锚点的意义：把「和锁屏蓝图争抢 LayoutParams」这个根本冲突去掉。
            // 之前 7 种方案全部失败的共同原因就是没跳出这个冲突。
            val leftMargin = LayoutMath.centeredLeftMargin(container.width, targetWidth)
            val lp = FrameLayout.LayoutParams(targetWidth, effectiveHeight).apply {
                this.leftMargin = leftMargin
                this.topMargin = targetTop
            }
            view.layoutParams = lp

            lastAppliedShapeHeight = effectiveHeight
            lastSignature = signature
            needsReapply = false

            // ⚠ 这里**绝不能**手动 container.measure()/layout()！
            // 那会重新触发 onLayoutChange → 再进本方法 → 无限递归 →
            // SystemUI 主线程卡死 ANR（真机实测把用户手机搞成锁屏加载不出来）。
            // 也**绝不能**在宿主的 onLayoutChange 里改 LayoutParams ——
            // 那会形成无界布局循环并把 SystemUI 拖崩（更严重的一次事故）。
            view.requestLayout()

            if (log.verbose && !reason.startsWith("preDraw")) {
                log.i(
                    "布局($reason): 宿主=${container.width}x${container.height} " +
                        "岛=${targetWidth}x$effectiveHeight " +
                        "leftMargin=$leftMargin topMargin=$targetTop " +
                        "实际=(${view.left},${view.top})-(${view.right},${view.bottom})"
                )
            }
        }.onFailure { log.e("applyConstraints($reason) 失败", it) }
    }

    // 说明：原来这里有个 applyBiasPercent()，供「拖动岛调垂直位置」使用。
    // 交互变更后拖动不再调位置（只切形态），位置改由设置界面的滑块写入，
    // 所以这个私有方法失去调用点，已删除。

    /** 双指缩放时直接改宽度 */
    private fun applyWidthPercent(view: IslandView, percent: Int) {
        draggedWidthPercent = percent
        liveWidthPercent = percent
        view.setWidthPercent(percent)

        val container = root ?: return
        val cfg = readConfig()
        val targetWidth = minOf(
            (container.width * percent / 100f).toInt(),
            dp(cfg.maxWidthDp)
        ).coerceAtLeast(dp(120))

        val lp = view.layoutParams ?: return
        lp.width = targetWidth
        view.layoutParams = lp
        view.applyStyle(dp(cfg.heightDp), -1, dp(cfg.expandedHeightDp))
        lastSignature = null
    }

    /**
     * 岛的交互与渲染回调
     */
    private fun configureIsland(view: IslandView) {
        view.setDragEnabled(readConfig().dragEnabled)

        view.setOnClickListener {
            if (view.state != IslandView.STATE_MEDIA) return@setOnClickListener
            // ============ 形态链：小岛 → 中岛 → 音乐锁屏 ============
            //
            //   · 小岛：点击 **或** 按住向上拖 → 中岛
            //   · 中岛：点击 → 全屏音乐锁屏
            //   · 回退只能按住向下拖（中岛→小岛、音乐锁屏→中岛）
            if (!morphExpanded) {
                setMorphExpanded(true)
            } else {
                showMusicLock()
            }
        }

        view.setOnMediaButtonListener { which ->
            // ⚠ 诊断修正：用户反馈"媒体按钮点击不了"时，我先把探针加在了
            // **音乐锁屏**的 v.onMediaButton 上 —— 但**岛**的按钮走的是这条路径
            // （setOnMediaButtonListener），所以那时"无日志"并不能证明点击没到达。补上探针。
            log.mark("岛媒体按钮：which=$which（1=上一首 2=播放/暂停 3=下一首）")
            when (which) {
                IslandView.BTN_PREV -> media.previous()
                IslandView.BTN_PLAY -> media.toggle()
                IslandView.BTN_NEXT -> media.next()
            }
            postImmediatePoll(120L)
        }

        // ============ 中岛波浪进度条：拖动 seek（比例 0..1） ============
        //
        // 旧实现是给 SeekBar 挂 OnSeekBarChangeListener，新卡片布局里换成了
        // 自绘的 WavyProgressBar，所以改成比例回调。
        // 注意别再走"适配成 SeekBar 监听器"那条路 —— 那需要传 null 的 SeekBar，
        // 而监听器实现里 `sb!!` 会直接 NPE，异常发生在触摸分发路径上会很难看。
        view.onSeekStart = {
            userSeeking = true
        }
        view.onSeekMove = { fraction ->
            val snap = media.lastSnapshot
            if (snap != null && snap.durationMs > 0) {
                // 拖动过程中只更新显示，不提交 seek
                view.setProgress((snap.durationMs * fraction).toLong(), snap.durationMs)
            }
        }
        view.onSeekEnd = { fraction ->
            userSeeking = false
            val snap = media.lastSnapshot
            if (snap != null && snap.durationMs > 0) {
                val ms = (snap.durationMs * fraction).toLong()
                log.i("seek 到 ${ms}ms（${(fraction * 100).toInt()}%）")
                media.seekTo(ms)
                postImmediatePoll(200L)
            }
        }

        // ============ 形态手势（取代原来的拖动调位置） ============
        //
        // 设计变更：
        //   · 小岛：点击 **或** 按住向上拖 → 变中岛
        //   · 中岛：按住向下拖 → 变回小岛（点击不再收起，避免误触）
        // 位置调节改到设置界面里做，不再占用长按拖动这个手势。
        view.onMorphGesture = { expand ->
            if (expand != morphExpanded) {
                log.mark(
                    if (expand) "手势：向上拖 → 展开成中岛"
                    else "手势：向下拖 → 收回小岛"
                )
                // ============ "自然下落"的收尾（用户要求）============
                //
                // 用户："松手还是跳，我们需要做出一种自然下落的感觉，落到那个位置就不要动了。"
                //
                // 跳的来源：松手时岛的视觉已经压缩到位，但**布局高度还是 665**；
                //   若这里走 220ms 的 morph 动画，视觉会先弹回全尺寸再缩 —— 就是那一下跳。
                //
                // 所以：IslandView 先在动画里"落"到压缩位置，落到位的那一帧
                //   置 requestInstantCollapse=true 并回调这里 —— 我们用 **instantMorph
                //   瞬时**把布局换成小岛（几何已经完全重合，观感是"落到位置就不动了"）。
                if (!expand && view.consumeInstantCollapseRequest()) {
                    instantMorph = true
                }
                setMorphExpanded(expand)
            }
        }

        view.onMorphDrag = { p ->
            // ============ ⚠ 这里**绝不能**写 layoutParams（真机熔断事故）============
            //
            // 我曾在这里按拖动进度改写高度（每帧 layoutParams = lp），
            // 结果触发**布局自激循环**：3000ms 内 applyConstraints 被调用 151 次，
            // 命中熔断 -> **模块被自动关闭、岛整个消失**（用户："解锁后亮屏岛不见了"）。
            //
            // 现在压缩完全由 IslandView 用**以底边为支点的 scaleY** 完成（纯渲染变换，
            // 不碰布局），这里只做诊断记录。
            val crossed = (p >= 0.5f) != (lastDragCrossedHalf)
            if (crossed) {
                lastDragCrossedHalf = p >= 0.5f
                if (log.verbose) log.i("拖动进度 ${(p * 100).toInt()}%（过半=${lastDragCrossedHalf}）")
            }
        }

        view.onResize = { percentFloat ->
            val percent = IslandConfig.clampPercent(percentFloat.roundToInt())
            applyWidthPercent(view, percent)
            if (log.verbose) log.i("缩放中：宽度 -> ${percent}%（实际=${view.width}）")
        }
        view.onResizeFinished = {
            log.mark("用户把岛缩放到 ${draggedWidthPercent}%，正在保存")
            requestPersist()
        }
    }

    /**
     * 把拖动/缩放结果落盘。SystemUI 写不了模块自己的 prefs（不同 UID），
     * 所以发广播，由模块的常驻 NudgeReceiver 写入。
     */
    private fun requestPersist() {
        runCatching {
            val intent = Intent(IslandConfig.ACTION_NUDGE_CHANGED).apply {
                setPackage(MODULE_PACKAGE)
                // 模块被强制停止过（安装后很常见）时默认收不到广播，
                // 不加这个 flag，拖动结果会静默丢失
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(IslandConfig.EXTRA_VERTICAL_BIAS, draggedPercent)
                putExtra(IslandConfig.EXTRA_WIDTH_PERCENT, draggedWidthPercent)
            }
            context.sendBroadcast(intent)
        }.onFailure { log.i("保存拖动结果失败（可忽略）：${it.message}") }
    }

    // ------------------------------------------------------------------
    // 可见性
    // ------------------------------------------------------------------

    fun onVisibilityInputChanged() {
        val view = island ?: return
        view.post { updateVisibility() }
    }

    fun updateVisibility() {
        val view = island ?: return
        // 息屏隐藏要用去抖后的值，避免"屏幕还亮着岛就消失"（用户反馈）
        syncDozingDebounce()

        // ============ 解锁时必须**立刻**收掉音乐锁屏（真机反馈）============
        //
        // 用户实测：在音乐锁屏上解锁进入桌面时，它会退化成中岛并**在桌面上停留一小段**。
        // 原因是 keyguardShowing 在解锁动画期间还会短暂为 true，
        // 等下一次可见性重算才收掉。
        //
        // 所以这里不等重算：只要判断出"锁屏已不在"，马上隐藏，并且**不做退场动画**
        // （动画期间它就已经不该出现在桌面上了）。
        if (!keyguardShowing || keyguardOccluded || dozingDebounced) {
            if (musicLock != null) {
                // 打印**具体是哪个信号**触发的：解锁与息屏共用 keyguardShowing=false，
                // 但两者对"隐藏时机"的要求相反（解锁要立刻、息屏要跟随屏幕变暗），
                // 所以必须知道实际是哪一个先到（真机诊断）。
                val why = when {
                    dozingDebounced -> "dozing(已去抖)"
                    keyguardOccluded -> "occluded"
                    else -> "keyguardShowing=false"
                }
                log.mark("锁屏结束/息屏 -> 立刻隐藏音乐锁屏（触发信号=" + why + " 原始dozing=" + dozing + "）")
                hideMusicLockImmediately()
            }
        }
        val cfg = readConfig()

        // 读一次覆盖层状态（微件 / 通知栏 / 密码界面）。
        // 由 SystemUiHook 提供的 provider 读取 GlanceableHubContainerController 的
        // public 字段；provider 缺失或抛异常时当作 null（视为无覆盖），
        // 避免"拿不到状态就把岛永久藏起来"。
        val overlay = runCatching { overlayStateProvider?.invoke() }.getOrNull()

        // ============ 可见性判定 ============
        //
        // 刻意**不**用 screenOn 做条件：真机日志显示 KeyguardStateControllerImpl.update(Z)
        // 传进来的参数恒为 false（实测 `update(screenOn=false)` 一路都是 false），
        // 说明那个 boolean 不是屏幕状态，我当初猜错了 —— 它会把岛永久挡成 GONE。
        //
        // keyguardShowing 本身已经包含「屏幕点亮且在锁屏」的语义：
        // 屏幕熄灭时 SystemUI 会报 keyguard showing=false。
        // 息屏显示（dozing）由 showOnAod 单独控制。
        val allowed = when {
            !cfg.enabled -> false
            // ============ AOD 上不显示（用户明确要求，硬性关闭） ============
            //
            // 刻意**不读 cfg.showOnAod**：息屏显示是常亮场景，
            // 岛画在上面既费电又容易烧屏，用户要求 AOD 里不出现岛。
            // 所以这里直接判 false，配置项保留只为兼容旧配置文件。
            dozingDebounced -> false
            !keyguardShowing -> false
            keyguardOccluded -> false
            // ============ 锁屏之上的覆盖层：微件 / 通知栏 / 密码界面 ============
            //
            // 这三者都在 NotificationShadeWindowView 里、且都会盖在锁屏内容之上，
            // 而岛也挂在这个容器上，所以必须显式让路（真机 bug）。
            overlay?.hubShowing == true -> false
            overlay?.shadeShowing == true -> false
            overlay?.bouncerShowing == true -> false
            else -> true
        }

        // ============ 先决定「是否允许轮询」，再决定「是否可见」 ============
        //
        // 真机 bug（死锁）：原顺序是「先算可见性 → 可见才 startPolling」，
        // 而可见性又依赖 lastHadContent —— 那个值只能由轮询产生。
        // 于是首次进入时 lastHadContent=false → GONE → 不启动轮询 → 永远是 false，
        // 岛再也出不来。我之前一直用 debug_always_show=true 测试，把这个 bug 掩盖了。
        //
        // 现在再加一条：轮询的条件是「**锁屏处于活动状态**」，而不是「岛可见」。
        //   · 岛因覆盖层（微件/通知栏/密码界面）隐藏时，轮询必须继续，
        //     否则覆盖层一关闭就没人去重新检测 —— 岛再也回不来（同类死锁）。
        //   · 覆盖层检测本身就跑在轮询里（见 applyRenderPlan）。
        val lockScreenActive = cfg.enabled && keyguardShowing && !dozingDebounced
        // 识别"锁屏重新出现"这一刻：只有此刻才按期望形态恢复，
        // 否则每次可见性重算都会重新触发一次展开/进入音乐锁屏（会打架）。
        val becameActive = lockScreenActive && !wasLockScreenActive
        wasLockScreenActive = lockScreenActive

        if (lockScreenActive) {
            startPolling()
            if (becameActive) {
                // 换壁纸后系统会重新取色，这里重新解析一次主题/莫奈色（真机反馈"不跟着变色"）
                runCatching { view.refreshThemeColors() }
                restoreDesiredForm()
            }
        } else {
            stopPolling()
            resetMorph()
            // 锁屏不活动（含 AOD/解锁）时，音乐锁屏也必须一起收掉，
            // 否则它会以全屏不透明视图留在屏幕上
            hideMusicLock()
        }

        // 覆盖层（微件/通知栏/密码界面）出现时同样要收掉音乐锁屏 ——
        // 它比中岛大得多，留在这些界面上会非常突兀
        if (!allowed && musicLock != null) {
            hideMusicLock()
        }

        // 音乐锁屏形态下中岛必须彻底让位：
        // 只靠 showMusicLock() 里设一次 GONE 不够 —— 轮询会调用 updateVisibility()
        // 把它重新设回 VISIBLE，于是中岛卡片会透过音乐锁屏的半透明背景显形（真机截图）。
        val target = if (musicMode) {
            View.GONE
        } else if (allowed && (cfg.debugAlwaysShow || hasSomethingToShow())) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (view.visibility != target) {
            view.visibility = target
            log.mark(
                "可见性 -> ${if (target == View.VISIBLE) "VISIBLE" else "GONE"} " +
                    "(enabled=${cfg.enabled} keyguardShowing=$keyguardShowing " +
                    "occluded=" + keyguardOccluded + " dozing=" + dozing +
                    " dozingDebounced=" + dozingDebounced + " screenOn=" + screenOn + " " +
                    "state=$statusBarState debugAlwaysShow=${cfg.debugAlwaysShow} " +
                    "hasContent=$lastHadContent " +
                    "覆盖层[微件=${overlay?.hubShowing} 通知栏=${overlay?.shadeShowing} " +
                    "密码=${overlay?.bouncerShowing}])"
            )
        }

        if (target == View.VISIBLE) {
            applyConstraints(reason = "visibility")
        }
    }

    /**
     * 当前是否已有内容可显示。刻意用后台线程维护的 volatile 布尔值，
     * 而不是现场查 —— 现场查会触发跨进程调用，而本方法会被可见性判定频繁调用。
     */
    private fun hasSomethingToShow(): Boolean = lastHadContent

    // ------------------------------------------------------------------
    // 轮询控制
    // ------------------------------------------------------------------

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        log.i("开始轮询（间隔 ${POLL_INTERVAL_MS}ms，后台线程）")
    }

    /** 立刻补一轮轮询，用于「点了播放/切歌」这类需要马上反馈的场景 */
    private fun postImmediatePoll(delayMs: Long = 0L) {
        if (!polling) return
        handler.removeCallbacks(pollRunnable)
        if (delayMs <= 0L) handler.post(pollRunnable) else handler.postDelayed(pollRunnable, delayMs)
    }

    private fun stopPolling() {
        if (!polling) return
        polling = false
        handler.removeCallbacks(pollRunnable)
        log.i("停止轮询")
    }

    private var pollCount = 0

    private fun logSampledState(
        view: IslandView,
        snapshot: MediaSnapshot?,
        chip: com.example.livenotification.island.media.StatusChip?
    ) {
        pollCount++
        if (pollCount % LOG_EVERY_N_POLLS != 1) return
        if (!log.verbose) return

        val parent = view.parent as? View
        log.i(
            "轮询#$pollCount 岛=${view.width}x${view.height} top=${view.top} " +
                "vis=${view.visibility} expanded=${view.isExpanded()} state=${view.state} " +
                "宿主shown=${parent?.isShown} 媒体=" +
                (snapshot?.let { "${it.title}/${it.artist} playing=${it.playing}" } ?: "无") +
                " chip=${chip?.text ?: "无"}"
        )
    }

    private fun applyContent(
        view: IslandView,
        snapshot: MediaSnapshot?,
        chip: com.example.livenotification.island.media.StatusChip?,
        cfg: Cfg
    ): Boolean {
        var changed = false

        when {
            snapshot != null -> {
                if (view.state != IslandView.STATE_MEDIA) {
                    view.state = IslandView.STATE_MEDIA
                    changed = true
                }
                view.setChipGlyph(0)
                view.setCollapsedText(snapshot.title, snapshot.artist)
                view.setExpandedText(snapshot.title, snapshot.artist)
                view.setCover(snapshot.albumArt)
                view.setPlaying(snapshot.playing)
                syncMusicLock(snapshot)
            }

            chip != null -> {
                if (view.state != IslandView.STATE_STATUS) {
                    view.state = IslandView.STATE_STATUS
                    changed = true
                }
                view.setChipGlyph(chip.glyph)
                view.setCollapsedText(chip.text, chip.detail)
            }

            else -> {
                if (view.state != IslandView.STATE_IDLE) {
                    view.state = IslandView.STATE_IDLE
                    changed = true
                }
                view.setChipGlyph(0)
                view.setCollapsedText(
                    if (cfg.debugAlwaysShow) "锁屏岛已就绪" else "",
                    if (cfg.debugAlwaysShow) "等待媒体播放" else null
                )
            }
        }

        if (view.state != lastState || view.isExpanded() != lastExpanded) {
            lastState = view.state
            lastExpanded = view.isExpanded()
            changed = true
            applyConstraints(reason = "content-state")
        }
        return changed
    }

    /** 配置变化（旋转、改设置）后调用 */
    fun onConfigChanged(reason: String) {
        log.i("收到配置变化：$reason")
        biasInitialized = false
        widthInitialized = false
        lastSignature = null

        // ============ 音乐锁屏样式：当场重建视图（否则要退出再进才生效）============
        //
        // 用户问："选择样式后是立即生效还是怎么才能生效？"
        //
        // 事实：`music_lock_style` 是 **MusicLockView 的构造函数参数**
        // （`MusicLockView(context, useOneUi)`），所以视图一旦已经存在，
        // 改配置**不会**让它换样式 —— 原来必须退出音乐锁屏再进一次。
        //
        // 修法：发现"当前视图的样式 ≠ 配置里的样式"就**无动画地重建**它：
        //   先按现有路径立即移除（同时会把系统时钟/日期还原），
        //   再按新样式 animated=false 显示 —— 因为是无动画恢复，
        //   观感就是"当场换了一套样式"，不会有中间动画。
        runCatching {
            val v = musicLock
            if (v != null) {
                val wantOneUi = readConfig().musicStyle != 0
                if (v.oneUiStyle != wantOneUi) {
                    log.mark("音乐锁屏样式变化 -> 当场重建视图（OneUI=$wantOneUi）")
                    hideMusicLockImmediately()
                    if (desiredForm == FORM_MUSIC) {
                        showMusicLock(animated = false)
                    }
                }
            }
        }

        island?.post {
            applyConstraints(reason = "config:$reason")
            updateVisibility()
            postImmediatePoll()
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        log.i("系统配置变化：${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp")
        onConfigChanged("Configuration")
    }

    fun release() {
        runCatching { root?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener) }
        detach()
    }

    // ------------------------------------------------------------------
    // 系统时钟 / 日期的转场 transform（目标 ②）
    // ------------------------------------------------------------------

    /** 系统锁屏时钟 / 日期视图（惰性查找；SystemUI 重建后引用会失效，届时自动重找） */
    private var keyguardClockView: android.view.View? = null
    private var keyguardDateView: android.view.View? = null

    /**
     * 真正承受时钟变换的视图 = 时钟的**父容器**。
     *
     * 为什么不直接变换时钟自己：PixelOS 的时钟有"数字重叠"动画控制器，
     * 每秒刷新时会重写时钟自身的 scale/translation，把我们的值冲掉
     * （真机诊断：写入立刻回读正确，但截图时钟没变）。
     * 父容器不在那套动画的控制范围内，变换才能存活。
     */
    private var keyguardClockTransformTarget: android.view.View? = null

    /** 时钟缩到原来的多少（0.22 = 缩到 78%） */
    private val CLOCK_SHRINK = 0.22f

    /** 时钟上移多少 dp（k=1 时） */
    private val CLOCK_RISE_DP = 90

    /** 日期向右挪多少 dp（挪到时钟右侧 → "并排"） */
    private val DATE_SIDE_DP = 110

    /** 日期上移多少 dp（与缩小后的时钟竖直对齐） */
    private val DATE_RISE_DP = 40

    /**
     * 把音乐锁屏的转场进度 k 映射成系统时钟 / 日期的 transform。
     *
     * ============ 为什么用 transform 而不是改布局 ============
     *
     * 时钟和日期是 **SystemUI 自己的 View**。改布局（尺寸 / 父子 / 约束）风险极高 ——
     * 本项目已经因为 re-parent `KeyguardRootView` 崩过一次（见 AGENTS.md 禁止事项）。
     *
     * `scaleX/scaleY + translationX/Y` 是**纯绘制变换**：不触发重新布局、不碰父子结构，
     * 还原也只是设回 `1f / 0f` —— 天然可逆，**不可能"回不去"**。
     *
     * 而且它由**同一个转场进度 k** 驱动（k=0 中岛、k=1 音乐锁屏）：
     * 时钟的缩小上移和卡片/封面/文字是同一套进度，所以不会出现"两段动画各走各的"。
     * 用户对这一点极其敏感（原话："一定不能有跳一下之类的情况"）。
     *
     * k=0 时是恒等变换 → 退出音乐锁屏后时钟一定回到原样。
     *
     * ============ 效果（参考 iOS / ColorOS）============
     *   · 时钟进一步**缩小**并**上移**
     *   · 日期横向挪到时钟**右侧** → "时钟与日期并排"
     */
    /**
     * 时钟 transform 的**总开关**。当前 = **false（关闭）**。
     *
     * ============ 为什么关掉（第 18 轮真机结论）============
     *
     * 真机日志证明写入与抢写都是成功的：
     *   `写入 scale=0.78 ty=-315 → 回读 scaleX=0.78 ty=-315.0（已开启 50ms 抢写循环）`
     * 但**截图里时钟完全没变**。
     *
     * 结论：`NumberOverlapClockViewGroup` / `NumberOverlapSmallClockViewGroup` 是
     * **自绘时钟**（基类有 `onDraw` + `drawSingleDigit/drawOverlappedDigits`），
     * 它们的渲染**不受容器 transform 影响** —— 我们找到的那个视图不是真正在画数字的那个。
     * 所以"对时钟视图做 scale/translation"这条路是**死的**，不该再试。
     *
     * 正解：hook 插件里的 `getOverlappedRatio()`（大/小形态的形变参数，见 AGENTS.md 第 17 轮）。
     * 代码保留在这里是为了留住"这条路已排除"的证据与现成的抢写框架，
     * 下一轮改造成插件 hook 时可直接复用循环结构。
     */
    private val CLOCK_TRANSFORM_ENABLED = false

    /** 重写节流：与系统动画抢写，比它写得快就能"赢"下大部分帧 */
    private val CLOCK_REAPPLY_MS = 50L

    private var clockTransformActive = false
    private var clockLoopRunning = false

    @Volatile
    private var lastClockK = 0f

    /**
     * 与系统时钟动画抢写的循环。
     *
     * ============ 为什么需要"持续重写" ============
     *
     * 真机诊断证明：一次性写入**是生效的**（写入后立刻回读 `scaleX=0.78 ty=-315.0`），
     * 但几秒后截图时钟毫无变化 —— 因为 PixelOS 的时钟是**插件**（NumOverlap 自绘时钟），
     * 它自己的动画/刷新会**重写时钟的 scale/translation**，把我们的值冲掉。
     *
     * 彻底的正解是 hook 插件里的 `getOverlappedRatio()`（见 AGENTS.md，第 17 轮侦察结论），
     * 但那要先解决"插件类加载晚于 xposed_init"的时机问题，成本高。
     *
     * 这里先用**抢写**这个权宜方案：只要我们在 k>0 期间每 50ms 重写一次，
     * 系统那次重写就会被我们下一拍覆盖 —— 观感上时钟稳定保持缩小/上移。
     * 代价：极端情况下（系统恰好在两拍之间重写并完成合成）可能出现极轻微抖动。
     */
    private val clockReapplyRunnable = object : Runnable {
        override fun run() {
            if (!clockTransformActive) {
                clockLoopRunning = false
                return
            }
            writeClockTransform(lastClockK)
            handler.postDelayed(this, CLOCK_REAPPLY_MS)
        }
    }

    private fun applyClockTransform(k: Float) {
        // 已证明对自绘时钟无效（见 CLOCK_TRANSFORM_ENABLED 的说明），默认关闭。
        // 保留入口是为了让下一轮改成"hook 插件 getOverlappedRatio"时只替换实现。
        if (!CLOCK_TRANSFORM_ENABLED) return

        val kk = k.coerceIn(0f, 1f)
        lastClockK = kk

        if (kk <= 0.001f) {
            // 回到恒等：停止抢写并把时钟恢复原样
            clockTransformActive = false
            resetClockTransform()
            return
        }

        if (ensureClockViews() == null) return
        writeClockTransform(kk)

        clockTransformActive = true
        if (!clockLoopRunning) {
            clockLoopRunning = true
            handler.post(clockReapplyRunnable)
        }
    }

    /** 纯写入（不控制循环），供 applyClockTransform 与抢写循环共用 */
    private fun writeClockTransform(kk: Float) {
        val clock = keyguardClockTransformTarget ?: return
        val s = 1f - CLOCK_SHRINK * kk
        clock.scaleX = s
        clock.scaleY = s
        clock.translationY = -dp(CLOCK_RISE_DP) * kk

        keyguardDateView?.let { d ->
            d.translationX = dp(DATE_SIDE_DP) * kk
            d.translationY = -dp(DATE_RISE_DP) * kk
        }

        if (kk >= 0.99f && !clockTransformLogged) {
            clockTransformLogged = true
            log.mark(
                "时钟 transform 诊断：k=$kk 目标=${clock.javaClass.simpleName} " +
                    "写入 scale=$s ty=${-dp(CLOCK_RISE_DP)} " +
                    "→ 回读 scaleX=${clock.scaleX} ty=${clock.translationY} " +
                    "日期 tx=${keyguardDateView?.translationX}（已开启 50ms 抢写循环）"
            )
        }
    }

    private var clockTransformLogged = false

    // ------------------------------------------------------------------
    // mini 时钟：隐藏系统时钟/日期，改用自绘（用户定义的"系统没有的形态"）
    // ------------------------------------------------------------------

    /**
     * 被我们隐藏过的**时钟视图实例 → 原始可见性**。
     *
     * ⚠ 真机 bug：原来只记一个 `Int`（可见性数值），还原时再用 `keyguardClockView`
     * 去写回 —— 但隐藏期间一旦缓存失效重找过，`keyguardClockView` 已经是**新实例**，
     * 于是**当初被隐藏的旧实例永远留在 INVISIBLE**（用户实测："并没有恢复"）。
     *
     * 所以必须**以视图实例为 key**（和日期/提示的做法一致），逐条还原。
     */
    private val clockOriginalVisibilities = HashMap<android.view.View, Int>()

    /** 时钟候选的原始 alpha（与 [dateOriginalAlphas] 同理：visibility 会被系统改回来） */
    private val clockOriginalAlphas = HashMap<android.view.View, Float>()

    /** 上次 BFS 找到的时钟候选（含隐藏的两套大小布局） */
    private val keyguardClockCands = ArrayList<android.view.View>()
    private var todayOriginalVisibility: Int? = null

    /**
     * 所有"日期"类视图。
     *
     * 真机教训：只隐藏 `VariableDateView`（资源 id = `date`）**不够** ——
     * 截图里系统日期依然可见。候选清单显示还有 `DateSmartspaceView`（出现两次）。
     * 所以这里把**所有**类名含 "Date" 或 id 为 date 的视图都收进来，一起隐藏。
     */
    private val keyguardDateViews = ArrayList<android.view.View>()
    private val dateOriginalVisibilities = HashMap<android.view.View, Int>()

    /**
     * 日期 / smartspace 视图的**原始 alpha**。
     *
     * ============ 为什么还要压 alpha（v1.40.0 真机结论）============
     *
     * 只写 `visibility = INVISIBLE` **会输给系统**：本机 PixelOS 17 上，
     * 系统每秒都在重新"显示"时钟与 smartspace（天气 / 充电 / 日程内容轮换时重设可见性），
     * 于是我们写完 INVISIBLE，下一帧又被改回 VISIBLE —— 截图里就能看到
     * 封面正中的"重庆市明天的最高气温为 24℃"和露出来的系统时钟。
     *
     * 日志证据（同一次音乐锁屏里连续 4 次重扫都成立）：
     *   `仍有可见的日期/Smartspace BcSmartspaceView(vis=0 …) WeatherSmartspaceView(vis=0 …)`
     *   `NumberOverlapSmallClockViewGroup[vis=1 899x399]`（vis=1 = isShown）
     *
     * 而 **alpha 系统不会重写**（notification stack 当年就是这么解决的，见
     * `applyNotificationAlpha`）。所以两类视图都**同时**压 visibility + alpha：
     * visibility 负责"别画"，alpha 负责"系统改回来也别画"。
     */
    private val dateOriginalAlphas = HashMap<android.view.View, Float>()

    /**
     * 锁屏"提示文字"类视图（充电中 / 指纹提示等）。
     *
     * 真机问题：`74% · 正在充电` 会**盖住我们的控制键**（⏮ ▶ ⏭ 被压在下面）。
     * 音乐锁屏期间一并隐藏。
     */
    private val keyguardIndicationViews = ArrayList<android.view.View>()
    private val indicationOriginalVisibilities = HashMap<android.view.View, Int>()
    private val indicationOriginalAlphas = HashMap<android.view.View, Float>()
    private var keyguardIndicationVisibilityLogged = false

    /**
     * 进入音乐锁屏：把**系统时钟与日期**设为 `INVISIBLE`。
     *
     * ============ 为什么用 INVISIBLE 而不是 transform ============
     *
     * 用户澄清：系统**时钟**当前就是小模式，而音乐锁屏要的是 **mini** ——
     * 系统根本没有这个形态，无法复用系统机制。
     *
     * 而 transform 已被真机证伪：`NumberOverlap*` 是插件里的**自绘**时钟，
     * 渲染不受容器 transform 影响（写入回读正确 + 50ms 抢写在跑，截图仍毫无变化）。
     *
     * `visibility` 是 View 属性、插件不会重写它，而且 **INVISIBLE 仍占位**（不改布局），
     * 属于本项目里"动 SystemUI 视图"最安全的一类操作。
     * 隐藏之后由 `MusicLockView` 自绘 mini 时钟 + 日期（同一排）。
     */
    private fun hideSystemClockForMusic() {
        // 音乐锁屏期间把"系统往外显示"的写入冻结掉（幂等，只挂一次）
        installClockSetterHooks()
        // ============ 缓存有效性：**绝不能看 isShown**（真机 bug 的根源）============
        //
        // v1.39.0 用户反馈"音乐锁屏上会显示天气信息"，真因就在这里：
        //   原来是 `if (cached == null || cached.parent == null || !cached.isShown)` → 清缓存重找。
        //   可**时钟正是我们自己设成 INVISIBLE 的** → 下一轮必然 !isShown → 重找；
        //   而重找只挑"可见"的时钟（见 ensureClockViews 的挑选逻辑）→ 一个都没有
        //   → 返回 null → 本函数 `?: return` **提前返回** —— 连日期 / 充电提示的压制
        //   也一起停了。之后系统只要重刷 smartspace（天气 / 充电 / 日程内容轮换），
        //   那几行文字就留在音乐锁屏上（截图：封面正中"重庆市明天的最高气温为 24℃"、
        //   底部"85% · 正在充电"压着通知小岛）。
        //   日志特征：`压制锁屏元素` 停在某一刻，之后每轮都是"未找到时钟视图"。
        //
        // 现在三条改正：
        //  · 缓存只按"还挂在视图树上"判有效（不管可见性 —— 那是我们自己改的）；
        //  · 每 [CLOCK_RESCAN_EVERY_POLLS] 轮**强制重扫**：系统会重建 smartspace /
        //    日期实例，不重扫就永远盖不住新实例；
        //  · **找不到时钟也要继续**压日期 / 充电提示 —— 它们本来就是相互独立的三类视图。
        val cached = keyguardClockView
        val forceRescan = pollCount % CLOCK_RESCAN_EVERY_POLLS == 0
        if (cached == null || cached.parent == null || forceRescan) {
            keyguardClockView = null
            keyguardDateViews.clear()
            keyguardIndicationViews.clear()
            keyguardClockCands.clear()
            // 注意：**不清** xxxOriginalVisibilities —— 那些是"已隐藏实例"的账，
            // 必须留到还原时逐条写回（清掉就等于把它们永久留在 INVISIBLE）。
        }
        // ⚠ 允许为 null：时钟找不到时下面两类**照样要压**（v1.39.0 就是在这里提前返回挂掉的）
        val clock = ensureClockViews()

        // 隐藏**所有可见的时钟候选**（大/小两套视图组并存，当前在显示哪套不确定），
        // 并以**视图实例**记录原始可见性 —— 缓存重找后仍能正确还原。
        var clockHidden = 0
        for (c in keyguardClockCands) {
            if (c.parent == null || !c.isShown) continue
            if (!clockOriginalVisibilities.containsKey(c)) {
                clockOriginalVisibilities[c] = c.visibility
            }
            if (c.visibility != android.view.View.INVISIBLE) {
                c.visibility = android.view.View.INVISIBLE
                clockHidden++
            }
        }
        if (clockHidden == 0 && clock != null &&
            clock.visibility != android.view.View.INVISIBLE
        ) {
            // 兜底：候选为空（极少数时序）时至少隐藏当前这个
            if (!clockOriginalVisibilities.containsKey(clock)) {
                clockOriginalVisibilities[clock] = clock.visibility
            }
            clock.visibility = android.view.View.INVISIBLE
            clockHidden = 1
        }

        // 隐藏**所有**日期 / smartspace 视图（不止 VariableDateView，见 keyguardDateViews 的说明）
        //
        // ⚠ visibility 与 alpha **都要压**：系统每秒都在把 visibility 改回 VISIBLE
        //   （真机 bug：音乐锁屏上一直显示天气），alpha 它不碰。
        var hidden = 0
        var flipped = 0
        for (d in keyguardDateViews) {
            if (d.parent == null) continue
            if (!dateOriginalVisibilities.containsKey(d)) {
                dateOriginalVisibilities[d] = d.visibility
            }
            if (!dateOriginalAlphas.containsKey(d)) dateOriginalAlphas[d] = d.alpha
            if (d.visibility != android.view.View.INVISIBLE) {
                d.visibility = android.view.View.INVISIBLE
                flipped++
            }
            if (d.alpha != 0f) d.alpha = 0f
            hidden++
        }

        // 充电提示（"74% · 正在充电"）会盖住我们的控制键 —— 音乐锁屏期间一起隐藏
        for (ind in keyguardIndicationViews) {
            if (ind.parent == null) continue
            if (!indicationOriginalVisibilities.containsKey(ind)) {
                indicationOriginalVisibilities[ind] = ind.visibility
            }
            if (!indicationOriginalAlphas.containsKey(ind)) {
                indicationOriginalAlphas[ind] = ind.alpha
            }
            if (ind.visibility != android.view.View.INVISIBLE) {
                ind.visibility = android.view.View.INVISIBLE
                flipped++
            }
            if (ind.alpha != 0f) ind.alpha = 0f
        }

        // 时钟同样补一刀 alpha（截图里系统时钟又露出来过，原因与天气一致）
        var clockAlphaFixed = 0
        for (c in keyguardClockCands + listOfNotNull(clock)) {
            if (c.parent == null) continue
            if (!clockOriginalAlphas.containsKey(c)) clockOriginalAlphas[c] = c.alpha
            if (c.alpha != 0f) {
                c.alpha = 0f
                clockAlphaFixed++
            }
        }

        log.i(
            "音乐锁屏：压制锁屏元素（时钟 $clockHidden/alpha$clockAlphaFixed + " +
                "日期 $hidden(新压 $flipped) + 充电提示 ${keyguardIndicationViews.size}）"
        )
    }

    // ------------------------------------------------------------------
    // 冻结"系统把锁屏元素重新显示出来"的写入（v1.40.0）
    // ------------------------------------------------------------------

    private var clockSetterHooksInstalled = false

    /** 剩余多少条"冻结"日志要打（每进一次音乐锁屏重置，避免刷屏） */
    private var clockSetterLogLeft = 6

    /**
     * 把 `View.setAlpha / setTransitionAlpha / setVisibility` 里**"要显示"**的写入冻结掉。
     *
     * ============ 为什么必须动 hook（真机证据）============
     *
     * 只改 `visibility` 和 `alpha` **会输**：PixelOS 17 上系统每秒都把时钟改回可见，
     * 日志里 `压制锁屏元素（时钟 1/alpha1 …）` 每秒都出现"新压 1" ——
     * 就是我们压下去、系统立刻写回来，于是截图里系统时钟一直露在我们的 mini 时钟后面。
     * （天气 / smartspace 那几行用 alpha 已经压住了，因为系统只改它们的 visibility。）
     *
     * 这里在**源头**拦住"要显示"的写入：
     *  · 只在 `musicLock != null`（音乐锁屏期间）生效；
     *  · 只对**我们压制过的实例**生效（时钟候选 / 日期 / smartspace / 充电提示）；
     *  · 只吞"变可见 / alpha>0"的写入，隐藏类写入照常放行；
     *  · 退出音乐锁屏时 `restoreSystemClockVisibility()` 会把记录的原始
     *    visibility/alpha 写回去，而且冻结立刻失效 —— 系统下一次写入就能自愈。
     */
    private fun installClockSetterHooks() {
        if (clockSetterHooksInstalled) return
        clockSetterHooksInstalled = true
        installSetterHook("setAlpha", java.lang.Float.TYPE)
        installSetterHook("setTransitionAlpha", java.lang.Float.TYPE)
        installSetterHook("setVisibility", java.lang.Integer.TYPE)
    }

    private fun installSetterHook(name: String, argType: Class<*>) {
        runCatching {
            de.robv.android.xposed.XposedBridge.hookAllMethods(
                android.view.View::class.java,
                name,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 最热的路径：不在音乐锁屏就直接返回（一次 volatile 读）
                        if (musicLock == null) return
                        val v = param.thisObject as? android.view.View ?: return
                        if (!clockOriginalVisibilities.containsKey(v) &&
                            !dateOriginalVisibilities.containsKey(v) &&
                            !indicationOriginalVisibilities.containsKey(v)
                        ) return
                        val raw = param.args.getOrNull(0)
                        val wantsVisible = when (raw) {
                            is Int -> raw == android.view.View.VISIBLE
                            is Float -> raw > 0f
                            else -> false
                        }
                        // 隐藏类写入放行 —— 与我们意图一致，也让系统状态机能正常收敛
                        if (!wantsVisible) return
                        if (clockSetterLogLeft > 0) {
                            clockSetterLogLeft--
                            log.w(
                                "已冻结系统对锁屏元素的显示写入：${v.javaClass.simpleName}" +
                                    ".$name($raw)（音乐锁屏期间）"
                            )
                        }
                        param.result = null   // 吞掉：跳过原方法
                    }
                }
            )
            log.mark("已挂 setter 冻结：View.$name")
        }.onFailure { log.w("setter 冻结挂载失败 View.$name：${it.message}") }
    }

    /** 逐条还原系统时钟/日期/充电提示的可见性与 alpha（幂等，以视图实例为 key） */
    private fun restoreSystemClockVisibility() {
        // 时钟：遍历**所有被隐藏过的实例**（不能只写回当前引用，见字段说明）
        for ((c, v) in clockOriginalVisibilities) {
            runCatching { c.visibility = v }
        }
        clockOriginalVisibilities.clear()
        for ((c, a) in clockOriginalAlphas) {
            runCatching { c.alpha = a }
        }
        clockOriginalAlphas.clear()
        for ((d, v) in dateOriginalVisibilities) {
            runCatching { d.visibility = v }
        }
        dateOriginalVisibilities.clear()
        for ((d, a) in dateOriginalAlphas) {
            runCatching { d.alpha = a }
        }
        dateOriginalAlphas.clear()
        for ((ind, v) in indicationOriginalVisibilities) {
            runCatching { ind.visibility = v }
        }
        indicationOriginalVisibilities.clear()
        for ((ind, a) in indicationOriginalAlphas) {
            runCatching { ind.alpha = a }
        }
        indicationOriginalAlphas.clear()
    }

    /** 退出音乐锁屏 / 熄屏前把时钟恢复原样（幂等），并停掉抢写循环 */
    private fun resetClockTransform() {
        clockTransformActive = false
        keyguardClockView?.let {            it.scaleX = 1f
            it.scaleY = 1f
            it.translationX = 0f
            it.translationY = 0f
        }
        keyguardDateView?.let {
            it.translationX = 0f
            it.translationY = 0f
        }
    }

    /**
     * 惰性定位时钟与日期。
     *
     * 时钟：**BFS 第一个**类名含 "Clock" 且可见的视图 —— BFS 是按层序的，
     *       所以拿到的是**最外层**的时钟容器（缩放它等于缩放整块时钟+日期）。
     * 日期：`com.android.systemui:id/date`（用 `getIdentifier` 按名字查，避免写死资源 id）。
     *
     * 找不到就返回 null —— 时钟联动是**增强**，绝不能因为它失败而影响岛本身。
     */
    private fun ensureClockViews(): android.view.View? {
        val cached = keyguardClockView
        if (cached != null && cached.parent != null) return cached

        return runCatching {
            val anchor = island ?: return null
            val root = findShadeWindow(anchor) ?: return null
            val dateId = context.resources.getIdentifier("date", "id", "com.android.systemui")

            var date: android.view.View? = null
            val candidates = StringBuilder()
            // 时钟候选要**收集全部再挑**：大/小时钟是两套视图组并存
            // （NumberOverlapClockViewGroup / NumberOverlapSmallClockViewGroup），
            // 其中只有一套在显示。BFS 先撞到的很可能是**隐藏的那套**，
            // 对它做 transform 完全没有视觉效果（真机踩过：截图对比时钟没变）。
            val clockCands = ArrayList<android.view.View>()
            val queue = ArrayDeque<android.view.View>()
            queue.addLast(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 4000) {
                val v = queue.removeFirst()
                visited++
                val cn = v.javaClass.simpleName
                // ⚠ 必须排除**我们自己的视图**：`MusicLockView` 里含 "cLock"，
                // 大小写不敏感匹配 "clock" 时会误命中（真机日志：时钟=MusicLockView）。
                val ours = cn.contains("MusicLock") || cn.contains("Island") ||
                    cn.contains("GlowCard") || cn.contains("WavyProgress") ||
                    cn.contains("BlurFade") || cn.contains("RoundedIcon") ||
                    cn.contains("OrderedLinear")
                val looksLikeClock = !ours && cn.contains("Clock", ignoreCase = true)
                if (looksLikeClock) {
                    clockCands.add(v)
                    candidates.append(cn)
                        .append("[vis=").append(if (v.isShown) 1 else 0)
                        .append(' ').append(v.width).append('x').append(v.height)
                        .append("] ")
                }
                // 收集**所有**日期类视图（不止一个，见 keyguardDateViews 的说明）
                //
                // ⚠ 同时收 "Smartspace"：天气 / 充电 / 日程那几行文字就画在 smartspace 上，
                //    而它的类名**不一定含 "Date"**（真机 bug：音乐锁屏上出现
                //    "重庆市明天的最高气温为 24℃" 与 "85% · 正在充电"）。
                if (!ours && (cn.contains("Date", ignoreCase = true) ||
                        cn.contains("Smartspace", ignoreCase = true) ||
                        (dateId != 0 && v.id == dateId))
                ) {
                    if (!keyguardDateViews.contains(v)) keyguardDateViews.add(v)
                    candidates.append(cn).append("[date] ")
                }
                // 收集锁屏提示文字（充电中 / 指纹提示等），它们会盖住我们的控制键
                if (!ours && cn.contains("Indication", ignoreCase = true)) {
                    if (!keyguardIndicationViews.contains(v)) keyguardIndicationViews.add(v)
                    keyguardIndicationVisibilityLogged = true
                }
                if (date == null && dateId != 0 && v.id == dateId) date = v
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
                }
            }
            log.i("时钟联动：候选（Clock）= ${if (candidates.isEmpty()) "无" else candidates}")

            // ============ 诊断：**当前仍然可见**的日期/Smartspace ============
            // 这几行（天气 / 充电）就是它们画的；有输出就说明我们没盖住 ——
            // v1.39.0 的天气 bug 就是靠它一眼看出来的（可见 → 压制早就停了）。
            val stillShown = keyguardDateViews.filter { it.isShown }
            if (stillShown.isNotEmpty()) {
                log.w(
                    "时钟联动：仍有可见的日期/Smartspace " + stillShown.joinToString(" ") {
                        it.javaClass.simpleName + "(vis=" + it.visibility +
                            " " + it.width + "x" + it.height +
                            " id=0x" + Integer.toHexString(it.id) + ")"
                    }
                )
            }
            // 记下全部候选：压制时要**把可见的都隐藏**（不知道系统当前用哪套）
            keyguardClockCands.clear()
            keyguardClockCands.addAll(clockCands)

            // 挑"真正在显示的那个"：可见 且 面积最大。
            //
            // ⚠ 全都被我们自己压成 INVISIBLE 是**正常状态**，必须有兜底 ——
            //    否则"找不到时钟"会让调用方整段压制停摆（v1.39.0 的天气 bug）。
            //    兜底顺序：① 可见里最大的 ② 我们藏过的里最大的 ③ 任意候选里最大的
            val pick = { pool: List<android.view.View> ->
                var best = 0L
                var found: android.view.View? = null
                for (c in pool) {
                    val area = c.width.toLong() * c.height.toLong()
                    if (area > best) {
                        best = area
                        found = c
                    }
                }
                found
            }
            var clock = pick(clockCands.filter { it.isShown })
            if (clock == null) {
                clock = pick(clockCands.filter { clockOriginalVisibilities.containsKey(it) })
            }
            if (clock == null) clock = pick(clockCands)

            keyguardClockView = clock
            keyguardDateView = date

            // ============ 目标必须是时钟自己，**不能**用它的父容器 ============
            //
            // 我原本想"变换父容器以躲开系统对时钟自身的每帧重写"，
            // 但真机日志显示：`NumberOverlapSmallClockViewGroup` 的父容器就是
            // **`KeyguardRootView`**（整个锁屏根布局，它是直接子视图）。
            // 缩放它 = 缩放整个锁屏（指纹/快捷方式/通知全跟着动）—— 这是危险的错误。
            //
            // 所以仍然变换时钟自身，代价是：系统时钟的 "重叠" 动画会重写它
            // （真机诊断：写入后立刻回读正确，但之后被覆盖 -> 视觉无变化）。
            // 这条 ② 需要另找方案，见 AGENTS.md：hook 时钟的动画控制器，或在 k>0 期间持续重写。
            keyguardClockTransformTarget = clock

            if (clock == null) {
                log.w("时钟联动：未找到时钟视图（本次不做时钟变换）")
            } else {
                log.mark(
                    "时钟联动：已定位 时钟=${clock.javaClass.simpleName}" +
                        " 日期=${date?.javaClass?.simpleName ?: "未找到"}"
                )
            }
            clock
        }.getOrNull()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        /** 轮询间隔。锁屏可见时才跑，1s 足够跟手且几乎不耗电 */
        private const val POLL_INTERVAL_MS = 1000L

        /** 每多少轮打一次状态日志 */
        private const val LOG_EVERY_N_POLLS = 15

        /**
         * 音乐锁屏期间，每多少轮**强制重扫**一次锁屏元素（时钟 / 日期 / smartspace / 充电提示）。
         *
         * 系统会重建这些实例（天气、充电、日程内容轮换时），不重扫就永远盖不住新实例 ——
         * 真机 bug：压制日志停在某一刻，之后音乐锁屏上一直挂着天气与充电文字。
         * 5 轮（≈5s）一次，代价与 `hideKeyguardNotifications` 每轮的 BFS 同量级。
         */
        private const val CLOCK_RESCAN_EVERY_POLLS = 5

        /** 通知小岛数据的刷新节流（轮播本身 5s 一条，4s 足够跟上新通知） */
        private const val NOTIF_REFRESH_MS = 4000L

        /**
         * 音乐锁屏退场"交棒"方式（v1.41.0，用户要求两种都留开关做对比）。
         *
         *  · `0L`  = **推荐**：几何落定后瞬时切 + 中岛侧模糊 ~120ms 淡出
         *           （与"中岛→小岛"的换布局 + 边框模糊淡出同一套）
         *  · `90L` = 旧行为：音乐视图交叉淡出 90ms（两套排版会有一小段"溶解"）
         */
        private const val EXIT_CROSSFADE_MS = 0L

        /** 通知小岛最多轮播几条 */
        private const val NOTIF_MAX_ITEMS = 8

        /** 遍历通知栈子树时的节点数上限（通知子树是几百级，兜住别做无界遍历） */
        private const val NOTIF_MAX_NODES = 260

        /** 可见性看门狗间隔 */
        private const val WATCHDOG_INTERVAL_MS = 2000L

        /** 模块自身的包名 */
        private const val MODULE_PACKAGE = IslandConfig.MODULE_PACKAGE

        /** com.android.systemui.R.id.keyguard_root_view = 0x7f0a0460（aapt2 对原 APK 核实） */
        private const val KEYGUARD_ROOT_VIEW_ID = 0x7f0a0460

        /** com.android.systemui.R.id.legacy_window_root = 0x7f0a04aa（aapt2 核实） */
        private const val NOTIFICATION_SHADE_WINDOW_ID = 0x7f0a04aa

        // ---- 熔断器参数 ----
        /** 统计窗口 */
        private const val BREAKER_WINDOW_MS = 3000L

        /**
         * 窗口内允许的最大布局调用次数。
         *
         * 真机教训：原来设 60（=20/秒），但锁屏切换/动画期间布局回调本身就可能密集，
         * 结果正常操作也被误判为死循环（实测触发了 61 次就熔断）。
         * 真正的布局死循环是**每秒上千次**量级，所以放宽到 150（=50/秒）
         * 仍能可靠捕获异常，同时不误伤正常活动。
         */
        private const val BREAKER_MAX_APPLIES = 150

        /**
         * 浮出卡片的高度。
         *
         * 关键设计：**岛本身高度永远不变**（始终 DEF_HEIGHT_DP）。
         * 中岛是独立浮出的卡片，所以不触碰 KeyguardRootView 的约束解算 ——
         * 这正是绕开「ConstraintLayout 不重解约束」那堵墙的办法。
         */
        private const val CARD_HEIGHT_DP = 104

        /**
         * 约束补回的最小间隔。
         *
         * 锁屏蓝图会在每次布局清空我们的约束，无限制补回就是与它高频拉锯、
         * 持续占用主线程。1 秒一次足够让岛保持在正确位置。
         */
        private const val REPAIR_MIN_INTERVAL_MS = 1000L
    }
}
