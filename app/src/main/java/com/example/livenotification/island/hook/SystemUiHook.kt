package com.example.livenotification.island.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SystemUI 侧的全部 hook。
 *
 * 目标 ROM：PixelOS 17 (Android 17 / compileSdk 37) —— 包名 com.android.systemui
 * 反编译确认过的类名（见 E:\ROM\work\sysui_full）：
 *   - com.android.systemui.keyguard.ui.view.KeyguardRootView
 *       extends androidx.constraintlayout.widget.ConstraintLayout
 *       构造签名 <init>(Landroid/content/Context;Landroid/util/AttributeSet;)V
 *   - com.android.systemui.keyguard.KeyguardViewConfigurator
 *   - com.android.systemui.plugins.statusbar.StatusBarStateController
 *   - com.android.systemui.statusbar.StatusBarStateControllerImpl
 *   - com.android.systemui.statusbar.policy.KeyguardStateControllerImpl
 *
 * 所有 hook 都做了「找不到就跳过 + 记日志」的兜底，
 * 任何一个 hook 失败都不会让 SystemUI 起不来。
 */
class SystemUiHook(
    private val classLoader: ClassLoader,
    private val processName: String?
) {

    private val log = IslandLogger("SystemUiHook")

    /**
     * hook 安装结果清单。
     *
     * 这是「盲测」的关键诊断信息：用户往往只能把设置界面截图发过来，
     * 有了这张清单，就能一眼看出是「hook 没命中目标方法」还是
     * 「命中了但没找到锁屏根布局」——这两类问题的修法完全不同。
     */
    private val installedHooks = LinkedHashMap<String, String>()

    private fun recordHook(name: String, ok: Boolean, detail: String = "") {
        val mark = if (ok) "OK" else "FAIL"
        installedHooks[name] = if (detail.isEmpty()) mark else "$mark($detail)"
    }

    private fun hookReport(): String =
        installedHooks.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * 是否 SystemUI 主进程。
     *
     * SystemUI 会派生若干子进程，它们同样会加载这个模块并触发我们的 hook。
     * 只在主进程挂载岛，避免画重影。
     * processName 为 null 时（老框架拿不到）保守放行，只靠幂等保护。
     */
    private val isMainProcess: Boolean =
        processName == null || processName == MAIN_PROCESS || !processName.contains(':')

    @Volatile
    private var controller: IslandController? = null

    @Volatile
    private var context: Context? = null

    private var configReceiver: BroadcastReceiver? = null

    fun install() {
        log.mark("开始安装 SystemUI hook (SDK=${Build.VERSION.SDK_INT}, 进程=${processName ?: "?"})")

        // 1) 找到 SystemUI 的 Context（用于读配置、写日志、收广播）
        hookApplicationCreate()

        // 2) 核心：把岛挂到锁屏根布局
        //    历史教训：hookAllMethods 只遍历当前类声明的方法，而
        //    KeyguardRootView 只声明了构造函数，所以必须走继承链 / 全局 View 路径。
        hookKeyguardRootView()

        // 2b) 兜底：从 NotificationShadeWindowView 按 ID 找
        hookNotificationShadeWindowView()

        // 3) 状态来源
        hookStatusBarStateController()
        hookKeyguardStateController()
        hookScreenOn()

        // 3b) 锁屏之上的覆盖层：微件界面 / 下拉通知栏 / 密码界面
        //     这三者都会盖在锁屏内容上，岛必须让路（用户实测报的 bug）
        hookGlanceableHubController()

        // 4) 汇总清单（用户截图就能看到哪些 hook 没命中）
        log.mark("hook 安装汇总：${hookReport()}")
    }

    // ------------------------------------------------------------------
    // Context
    // ------------------------------------------------------------------

    private fun hookApplicationCreate() {
        try {
            val appClass = XposedHelpers.findClass("android.app.Application", classLoader)
            XposedBridge.hookAllMethods(
                appClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 这是 SystemUI 的启动路径，本模块绝不允许从这里抛出去。
                        // onContextReady / installConfigReceiver 内部各自也有保护，
                        // 但双层更保险 —— 崩溃的是整个系统界面，代价太高。
                        try {
                            val app = param.thisObject as? Context ?: return
                            onContextReady(app)
                            installConfigReceiver(app)
                        } catch (t: Throwable) {
                            log.e("Application.onCreate 回调异常，已吞掉", t)
                        }
                    }
                }
            )
            log.i("已挂 Application.onCreate（等待 Context）")
        } catch (t: Throwable) {
            log.e("hook Application.onCreate 失败", t)
        }
    }

    private fun onContextReady(ctx: Context) {
        if (context != null) return
        context = ctx
        try {
            // 日志目录：优先用 SystemUI 自己的 files 目录
            val dir = File(ctx.filesDir, "lockscreenisland")
            val verbose = com.example.livenotification.island.config.ConfigStore.getBoolean(
                ctx,
                com.example.livenotification.island.config.IslandConfig.KEY_DEBUG_LOG,
                com.example.livenotification.island.config.IslandConfig.DEF_DEBUG_LOG
            )
            com.example.livenotification.island.util.IslandLog.init(dir, verbose)
            log.verbose = verbose
            log.mark("日志就绪：${com.example.livenotification.island.util.IslandLog.logFilePath()}")

            controller = IslandController(ctx, log)
            // 覆盖层（微件/通知栏/密码界面）门禁：必须在 controller 建好之后接线
            wireOverlayStateProvider()

            // 关键：把设置界面写下的配置快照读进来。
            // 这条路径不依赖 prefs 跨进程、不依赖 xposedsharedprefs，
            // 所以 SystemUI 重启后也能拿到用户设置。
            val loaded = loadConfigSnapshotFromFile()
            reportStatus(
                if (loaded) {
                    "已注入 SystemUI（锁屏根布局已挂载），配置文件已加载"
                } else {
                    "已注入 SystemUI，但未找到配置文件（将使用默认值）"
                }
            )

            // 如果锁屏根布局在我们拿到 Context 之前就挂载了，这里补挂
            drainPendingRootView()
        } catch (t: Throwable) {
            log.e("初始化失败", t)
        }
    }

    /**
     * 读设置界面写下的配置快照。
     *
     * 路径：/sdcard/Android/data/com.example.livenotification.island/files/config.txt
     * SystemUI 以 system uid 运行，读这个外部私有目录是允许的
     * （Android 11+ 的 Android/data 限制针对普通应用，system 不受限）。
     *
     * @return 是否成功读到内容
     */
    private fun loadConfigSnapshotFromFile(): Boolean {
        val ctrl = controller ?: return false
        return try {
            val base = android.os.Environment.getExternalStorageDirectory()
            var lastTried: String? = null

            for (relative in com.example.livenotification.island.config.IslandConfig.SNAPSHOT_RELATIVE_PATHS) {
                val file = File(base, relative)
                lastTried = file.absolutePath
                if (!file.exists()) continue
                val text = file.readText(Charsets.UTF_8)
                if (ctrl.applyConfigSnapshot(text)) {
                    log.mark("已从 ${file.absolutePath} 加载配置快照")
                    return true
                }
                log.w("快照存在但解析为空：${file.absolutePath}")
            }

            log.w("所有候选路径都没有可用快照，最后尝试的是 $lastTried（将使用默认值）")
            false
        } catch (t: Throwable) {
            log.e("读取配置快照失败", t)
            false
        }
    }

    // ------------------------------------------------------------------
    // 核心注入点
    // ------------------------------------------------------------------

    /**
     * 在类继承链上找到真正**声明**了匹配方法（名字 + 参数个数）的那个类。
     *
     * ============ 为什么必须这么做 ============
     *
     * 已从 Xposed API 源码（api-82-sources.jar 的 XposedHelpers.java）确认：
     *   findMethodsByExactParameters 用的是 `clazz.getDeclaredMethods()`
     * 也就是说 `XposedBridge.hookAllMethods(clazz, name, cb)`
     * **只遍历当前类自己声明的方法，不会向上找父类**。
     *
     * 而反编译显示 `KeyguardRootView` 只声明了构造函数：
     *   .class public final Lcom/android/systemui/keyguard/ui/view/KeyguardRootView;
     *   .super Landroidx/constraintlayout/widget/ConstraintLayout;
     *   .method public constructor <init>(...)V   ← 只有这一个
     *
     * 所以 `hookAllMethods(KeyguardRootView, "onAttachedToWindow")` 是**空的**，
     * 一个回调都不会触发 —— 主注入路径从一开始就是失效的。
     *
     * @param arity 参数个数；-1 表示不限
     */
    private fun findDeclaringClass(
        start: Class<*>,
        methodName: String,
        arity: Int = -1
    ): Class<*>? {
        var clazz: Class<*>? = start
        while (clazz != null && clazz != Any::class.java) {
            val declared = runCatching {
                clazz!!.declaredMethods.any {
                    it.name == methodName && (arity < 0 || it.parameterCount == arity)
                }
            }.getOrDefault(false)
            if (declared) return clazz
            clazz = clazz.superclass
        }
        return null
    }

    private fun hookClassMethod(
        clazz: Class<*>,
        methodName: String,
        recordAs: String,
        arity: Int = -1,
        onlyIfSelfDeclares: Boolean = false,
        callback: (XC_MethodHook.MethodHookParam) -> Unit
    ): Boolean {
        // 有些方法只在「本类自己重写了」时才值得挂：否则会把父类实现一起挂上，
        // 造成大量无关回调。这类场景用 onlyIfSelfDeclares 限制。
        if (onlyIfSelfDeclares) {
            val selfDeclares = runCatching {
                clazz.declaredMethods.any {
                    it.name == methodName && (arity < 0 || it.parameterCount == arity)
                }
            }.getOrDefault(false)
            if (!selfDeclares) {
                recordHook(recordAs, false, "本类未重写")
                log.i("$recordAs：${clazz.simpleName} 未重写 $methodName，跳过（避免误挂父类实现）")
                return false
            }
        }

        val declaring = findDeclaringClass(clazz, methodName, arity)
        if (declaring == null) {
            recordHook(recordAs, false, "继承链上无此方法")
            log.w("$recordAs：${clazz.name} 及其父类都没有 $methodName/$arity")
            return false
        }
        return try {
            XposedBridge.hookAllMethods(
                declaring,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            callback(param)
                        } catch (t: Throwable) {
                            log.e("$recordAs 回调异常", t)
                        }
                    }
                }
            )
            recordHook(recordAs, true, declaring.simpleName)
            log.i("已挂 $recordAs（声明类=${declaring.name}）")
            true
        } catch (t: Throwable) {
            recordHook(recordAs, false, t.javaClass.simpleName)
            log.e("挂 $recordAs 失败", t)
            false
        }
    }

    private fun hookKeyguardRootView() {
        val className = "com.android.systemui.keyguard.ui.view.KeyguardRootView"
        val clazz = try {
            XposedHelpers.findClass(className, classLoader).also {
                log.i("找到 $className -> ${it.superclass?.name}")
            }
        } catch (t: Throwable) {
            recordHook("KeyguardRootView 查找", false, t.javaClass.simpleName)
            log.e("找不到 $className", t)
            null
        }

        // 走继承链挂载：解决 hookAllMethods 不遍历父类的问题
        if (clazz != null) {
            hookClassMethod(clazz, "onAttachedToWindow", "KeyguardRootView.onAttachedToWindow") {
                tryAttach(it.thisObject, "onAttachedToWindow")
            }
            hookClassMethod(clazz, "onFinishInflate", "KeyguardRootView.onFinishInflate") {
                tryAttach(it.thisObject, "onFinishInflate")
            }
        }

        // 最可靠的路径：直接挂框架的 View.onAttachedToWindow()，用类型/ID 判定。
        // 完全不依赖 SystemUI 的类结构，也不受 hookAllMethods 语义影响。
        hookFrameworkViewAttach(clazz)

        // 缓存的 KeyguardRootView Class 对象，供快速类型判定使用
        keyguardRootViewClass = clazz
    }

    /** 供 View.onAttachedToWindow 快速判定的目标类型（可能为 null） */
    @Volatile
    private var keyguardRootViewClass: Class<*>? = null

    /**
     * 挂 `android.view.View.onAttachedToWindow()`。
     *
     * 这是本模块**最强壮**的注入路径：
     *  - `View` 自己声明了 onAttachedToWindow（已对 API 37 源码确认，第 22908 行
     *    `protected void onAttachedToWindow()`），所以 hookAllMethods 一定挂得上
     *  - 不依赖 SystemUI 任何类的继承结构
     *  - 判定条件两条任一满足即命中：类型匹配 KeyguardRootView，或 id == keyguard_root_view
     *
     * 代价：每个 View attach 都会进一次回调。所以判定顺序刻意做了优化 ——
     * 先比 id（一次 int 比较，绝大多数 View 在此被排除），再退化到类型判断。
     */
    private fun hookFrameworkViewAttach(keyguardRootViewClazz: Class<*>?) {
        try {
            val viewClass = XposedHelpers.findClass("android.view.View", classLoader)
            var hits = 0

            XposedBridge.hookAllMethods(
                viewClass,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 第一次触发就记一笔：这是"hook 到底有没有生效"的
                            // 决定性证据。若日志里连这行都没有，说明这个 hook
                            // 根本没挂上，问题在安装阶段而不在判定阶段。
                            if (!viewHookProven) {
                                viewHookProven = true
                                recordHook("View.onAttachedToWindow 已触发", true)
                                log.mark("全局 View hook 首次触发（证明 hook 生效）")
                            }

                            val obj = param.thisObject as? android.view.View ?: return
                            if (!isKeyguardRootView(obj, keyguardRootViewClazz)) return

                            hits++
                            if (hits == 1) {
                                log.mark(
                                    "★ 全局 View hook 命中锁屏根布局：" +
                                        "id=0x${Integer.toHexString(obj.id)} " +
                                        "class=${obj.javaClass.name} " +
                                        "size=${obj.width}x${obj.height}"
                                )
                                // 视图此刻可能还没测量完（宽高为 0），
                                // 靠后续 applyConstraints / onLayoutChange 补齐
                                if (obj.width == 0 || obj.height == 0) {
                                    log.i("命中时尺寸为 0，视图尚未测量，等待 onLayoutChange")
                                }
                            }
                            tryAttach(obj, "View.onAttachedToWindow")
                        } catch (t: Throwable) {
                            log.e("View.onAttachedToWindow 判定异常", t)
                        }
                    }
                }
            )
            recordHook("View.onAttachedToWindow(全局兜底)", true)
            log.i("已挂 android.view.View.onAttachedToWindow（最强注入路径）")
        } catch (t: Throwable) {
            recordHook("View.onAttachedToWindow(全局兜底)", false, t.javaClass.simpleName)
            log.e("挂 View.onAttachedToWindow 失败", t)
        }
    }

    /** 全局 View hook 是否已实际触发过（用于自证 hook 生效） */
    @Volatile
    private var viewHookProven = false

    /**
     * 判定一个 View 是不是锁屏根布局。
     * 先比 id（最便宜），再比类型（兼容 id 被改的情况）。
     */
    private fun isKeyguardRootView(view: android.view.View, clazz: Class<*>?): Boolean {
        if (view.id == KEYGUARD_ROOT_VIEW_ID) return true
        val c = clazz ?: return false
        return c.isInstance(view)
    }

    private fun tryAttach(rootView: Any?, source: String) {
        if (rootView !is android.view.View) return

        // ============ 最廉价的静默门禁必须放在最前面 ============
        //
        // 真机日志证据：`onFinishInflate` 的 hook 沿继承链上溯到了
        // `android.view.View`，因此回调对 SystemUI 里**每一个 View** 都会触发。
        // 如果在这里先打日志再判定，就会刷出每秒上千行的
        // 「触发，开始注入（view=android.widget.TextView）」——
        // 真机上这个洪泛把真正的诊断信息完全淹没了。
        //
        // 只认 id：一次 int 比较，命中不了就静默返回，什么都不记。
        if (rootView.id != KEYGUARD_ROOT_VIEW_ID) return

        // 时序竞态：锁屏根布局可能在 Application.onCreate 之前就挂载，
        // 那时 controller（依赖 Context）还没建好。如果直接丢弃，这次机会就
        // 永久失去（View 只会 attach 一次）。所以先暂存，等 Context 就绪再补挂。
        val ctrl = controller
        if (ctrl == null) {
            pendingRootView = java.lang.ref.WeakReference(rootView)
            pendingRootViewSource = source
            log.w("$source 命中锁屏根布局，但 controller 未就绪 —— 已暂存待补挂")
            return
        }

        // 主进程门禁：SystemUI 还有若干子进程（如 :aod、多用户实例），
        // 它们也会加载这个类并触发 attach，重复注入会画重影。
        if (!isMainProcess) {
            log.i("$source 命中，但当前不是 SystemUI 主进程，跳过注入")
            return
        }
        if (ctrl.isAttached()) {
            log.i("$source 命中，岛已存在，刷新布局")
            ctrl.onConfigChanged(source)
            return
        }
        log.mark("$source 命中锁屏根布局，开始注入（view=${rootView.javaClass.name}）")

        // attach 里会引用 androidx.constraintlayout 的类。module 的 ClassLoader
        // 未必能解析到它，一旦抛 NoClassDefFoundError 会顺着 hook 链冒到 SystemUI
        // 的 onAttachedToWindow 里 —— 那可能把系统界面搞崩。所以这里必须兜住。
        val ok = try {
            ctrl.attach(rootView)
        } catch (t: Throwable) {
            log.e("$source 注入抛异常，已吞掉以免影响 SystemUI", t)
            reportStatus("注入异常（$source）：${t.javaClass.simpleName} ${t.message}")
            false
        }

        if (ok) {
            reportStatus("锁屏岛已注入（$source）")
            // 蓝图随后还会 apply 几次 ConstraintSet，多补几次约束
            rootView.postDelayed({ ctrl.applyConstraints("post-attach-120ms") }, 120)
            rootView.postDelayed({ ctrl.applyConstraints("post-attach-600ms") }, 600)
            rootView.postDelayed({
                ctrl.applyConstraints("post-attach-1500ms")
                (rootView as? androidx.constraintlayout.widget.ConstraintLayout)
                    ?.let { ctrl.logInjectionProbe(it) }
            }, 1500)
        } else {
            reportStatus("注入失败（$source），请查看日志")
        }
    }

    /**
     * 兜底路径：从 NotificationShadeWindowView 里按 ID 找 keyguard_root_view。
     *
     * 有两个坑必须处理：
     *  1. `onAttachedToWindow` 触发时子 View **还没 inflate**，直接 findViewById
     *     必然返回 null —— 所以必须 post 到下一轮消息队列再找。
     *  2. 只找一次不够：布局可能稍后才建好，所以加一个有限次数的重试。
     * 这条路径不依赖任何类名，是本模块最后的安全网。
     */
    private fun hookNotificationShadeWindowView() {
        val className = "com.android.systemui.shade.NotificationShadeWindowView"
        try {
            val clazz = XposedHelpers.findClass(className, classLoader)

            val attempt = object : Runnable {
                override fun run() {
                    val ctrl = controller ?: return
                    if (ctrl.isAttached()) return
                    val self = shadeViewRef?.get() ?: return
                    val keyguardRoot: android.view.View? =
                        self.findViewById<android.view.View>(KEYGUARD_ROOT_VIEW_ID)
                    if (keyguardRoot != null) {
                        log.i("兜底路径命中：通过 legacy_window_root 找到 keyguard_root_view")
                        tryAttach(keyguardRoot, "shade-fallback")
                    }
                }
            }

            XposedBridge.hookAllMethods(
                clazz,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val self = param.thisObject as? android.view.ViewGroup ?: return
                        shadeViewRef = java.lang.ref.WeakReference(self)
                        if (controller?.isAttached() == true) return
                        // 子 View 尚未 inflate，延后到消息队列里再找，并做有限次重试
                        self.post(attempt)
                        for (delay in longArrayOf(300L, 900L, 2000L, 4000L)) {
                            self.postDelayed(attempt, delay)
                        }
                    }
                }
            )
            log.i("已挂 $className.onAttachedToWindow（兜底路径，带重试）")
            recordHook("NotificationShadeWindowView.onAttachedToWindow(兜底)", true)
        } catch (t: Throwable) {
            recordHook("NotificationShadeWindowView.onAttachedToWindow(兜底)", false, "不可用")
            log.e("兜底路径挂载失败", t)
        }
    }

    /** 兜底路径要跨多次重试访问同一个根视图 */
    private var shadeViewRef: java.lang.ref.WeakReference<android.view.ViewGroup>? = null

    /** 先于 Context 就绪而挂载的锁屏根布局，等 controller 建好后补挂 */
    private var pendingRootView: java.lang.ref.WeakReference<android.view.View>? = null
    private var pendingRootViewSource: String = ""

    /**
     * 补挂之前暂存的锁屏根布局。
     * Context 就绪后调用；若此刻视图仍存活且已挂到窗口上，就正式注入。
     */
    private fun drainPendingRootView() {
        val ref = pendingRootView ?: return
        val view = ref.get()
        pendingRootView = null
        if (view == null) {
            log.i("暂存的锁屏根布局已被回收，等待下一次 onAttachedToWindow")
            return
        }
        log.mark("补挂暂存的锁屏根布局（来源=$pendingRootViewSource）")
        tryAttach(view, "pending:$pendingRootViewSource")
    }

    // ------------------------------------------------------------------
    // 锁屏之上的覆盖层（微件 / 通知栏 / 密码界面）
    // ------------------------------------------------------------------

    /** 捕获到的 GlanceableHubContainerController 实例 */
    @Volatile
    private var glanceableHub: Any? = null

    private var fHubShowing: java.lang.reflect.Field? = null
    private var fShadeShowing: java.lang.reflect.Field? = null
    private var fBouncerShowing: java.lang.reflect.Field? = null

    /**
     * 捕获 GlanceableHubContainerController 实例，用来判断锁屏之上有没有覆盖层。
     *
     * ============ 为什么找它（真机 bug） ============
     *
     * 岛挂在 NotificationShadeWindowView 上，而这个容器在下面三种界面同样可见，
     * 于是岛会跟着显示（用户实测报的 bug）：
     *   · 锁屏微件界面（Glanceable Hub）
     *   · 锁屏上下拉的通知栏
     *   · 输入密码/图案界面（Bouncer）
     *
     * 好在 GlanceableHubContainerController 正好有三个 **public 字段**，
     * 一个 hook 就能把三种状态全拿到，不用分别去挂三个不同的类：
     *     hubShowing        ← 微件界面
     *     shadeShowing      ← 通知栏
     *     anyBouncerShowing ← 密码/图案输入界面
     *
     * 这里只捕获实例并缓存 Field；真正的读取交给 IslandController
     * （它每次判定可见性时读一次，代价是 3 次 getBoolean）。
     */
    private fun hookGlanceableHubController() {
        val className = "com.android.systemui.shade.GlanceableHubContainerController"
        val clazz = try {
            XposedHelpers.findClass(className, classLoader)
        } catch (t: Throwable) {
            recordHook("GlanceableHubContainerController 查找", false, t.javaClass.simpleName)
            log.w(
                "找不到 $className —— 微件/通知栏/密码界面的可见性门禁将不可用" +
                    "（岛可能在这些界面上仍然显示）"
            )
            return
        }

        // 字段是 public，缓存下来避免每次反射查表
        fHubShowing = runCatching { clazz.getField("hubShowing") }.getOrNull()
        fShadeShowing = runCatching { clazz.getField("shadeShowing") }.getOrNull()
        fBouncerShowing = runCatching { clazz.getField("anyBouncerShowing") }.getOrNull()
        if (fHubShowing == null || fShadeShowing == null || fBouncerShowing == null) {
            log.w(
                "GlanceableHubContainerController 字段缺失" +
                    "(hub=${fHubShowing != null} shade=${fShadeShowing != null} " +
                    "bouncer=${fBouncerShowing != null}) —— 门禁可能不完整"
            )
        }

        try {
            XposedBridge.hookAllConstructors(
                clazz,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            glanceableHub = param.thisObject
                            log.mark("已捕获 GlanceableHubContainerController（覆盖层门禁就绪）")
                        }
                    }
                }
            )
            recordHook("GlanceableHub.<init>", true, "构造")
            log.i("已挂 GlanceableHubContainerController 构造函数")
        } catch (t: Throwable) {
            recordHook("GlanceableHub.<init>", false, t.javaClass.simpleName)
            log.e("挂 GlanceableHubContainerController 构造失败", t)
        }
    }

    /**
     * 把覆盖层状态提供者交给控制器。
     *
     * 在 controller 建好之后调用（install() 时 controller 还是 null，
     * 因为 Context 要等 Application.onCreate）。
     */
    private fun wireOverlayStateProvider() {
        val ctrl = controller ?: return
        ctrl.overlayStateProvider = {
            val inst = glanceableHub
            if (inst == null) {
                null
            } else {
                IslandController.OverlayState(
                    hubShowing = readBool(fHubShowing, inst),
                    shadeShowing = readBool(fShadeShowing, inst),
                    bouncerShowing = readBool(fBouncerShowing, inst)
                )
            }
        }
        log.i("覆盖层状态提供者已接入控制器（微件/通知栏/密码界面门禁生效）")
    }

    private fun readBool(f: java.lang.reflect.Field?, target: Any): Boolean = try {
        f?.getBoolean(target) ?: false
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------
    // 状态来源
    // ------------------------------------------------------------------

    /**
     * StatusBarStateControllerImpl：
     *   - setState(IZ)     -> 0=SHADE 1=KEYGUARD 2=SHADE_LOCKED
     *   - setDozeAmountInternal(F) / isDozing() 用 mIsDozing 字段
     *   - isPulsing()/isDozing() 是轻量查询，直接读字段更稳
     */
    private fun hookStatusBarStateController() {
        val className = "com.android.systemui.statusbar.StatusBarStateControllerImpl"
        val clazz = try {
            XposedHelpers.findClass(className, classLoader)
        } catch (t: Throwable) {
            recordHook("StatusBarStateControllerImpl 查找", false, t.javaClass.simpleName)
            log.e("找不到 $className", t)
            return
        }

        hookClassMethod(
            clazz, "setState", "StatusBarStateControllerImpl.setState", arity = 2
        ) {
            val state = it.args.getOrNull(0) as? Int ?: return@hookClassMethod
            controller?.statusBarState = state
        }

        hookClassMethod(
            clazz, "setDozeAmountInternal",
            "StatusBarStateControllerImpl.setDozeAmountInternal", arity = 1
        ) {
            val amount = it.args.getOrNull(0) as? Float ?: return@hookClassMethod
            val ctrl = controller ?: return@hookClassMethod
            // 把 doze 渐变值本身交给控制器：它是**跟随屏幕亮度的真实信号**
            // （0=亮、1=完全进入 AOD），比"按时间猜"可靠得多。
            // 用户反馈"300ms 时太早、600ms 时太晚"，正是因为固定延时无法匹配
            // 每次熄屏动画的时长；用这个值就能精确对齐屏幕变暗。
            ctrl.dozeAmount = amount
            val dozing = amount > 0.5f ||
                (XposedHelpers.getBooleanField(it.thisObject, "mIsDozing"))
            ctrl.dozing = dozing
            // 渐变值每次变化都要重算可见性 —— 否则跨过隐藏阈值那一刻没人通知我们
            runCatching { ctrl.onVisibilityInputChanged() }
        }

        hookClassMethod(
            clazz, "updateUpcomingState", "StatusBarStateControllerImpl.updateUpcomingState"
        ) {
            runCatching {
                controller?.dozing = XposedHelpers.getBooleanField(it.thisObject, "mIsDozing")
            }
        }
    }

    /**
     * KeyguardStateControllerImpl：
     *   mShowing / mOccluded 字段就是「锁屏是否显示 / 是否被遮挡」。
     *   update(Z) 与 notifyKeyguardState(ZZ) 是状态变更的收敛点。
     */
    private fun hookKeyguardStateController() {
        val className = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
        val clazz = try {
            XposedHelpers.findClass(className, classLoader)
        } catch (t: Throwable) {
            recordHook("KeyguardStateControllerImpl 查找", false, t.javaClass.simpleName)
            log.e("找不到 $className", t)
            return
        }

        // 用 onlyIfSelfDeclares：这几个方法如果本类没重写，挂到父类实现上会
        // 产生大量无关回调（甚至挂到 Object 上），所以宁可判定为"未命中"。
        // 注意 update(Z) 由 hookScreenOn 单独处理，这里不重复挂。
        for (name in listOf("notifyKeyguardState", "notifyKeyguardGoingAway")) {
            hookClassMethod(
                clazz, name, "KeyguardStateControllerImpl.$name",
                onlyIfSelfDeclares = true
            ) {
                readKeyguardFields(it.thisObject, name)
            }
        }
    }

    private fun readKeyguardFields(obj: Any?, source: String) {
        val ctrl = controller ?: return
        runCatching {
            val showing = XposedHelpers.getBooleanField(obj, "mShowing")
            val occluded = runCatching {
                XposedHelpers.getBooleanField(obj, "mOccluded")
            }.getOrDefault(false)
            if (log.verbose) {
                log.i("$source: keyguard showing=$showing occluded=$occluded")
            }
            ctrl.keyguardShowing = showing
            ctrl.keyguardOccluded = occluded
        }.onFailure { log.i("读取 keyguard 字段失败($source)：${it.message}") }
    }

    /**
     * KeyguardStateControllerImpl.update(Z) —— 参数就是 screenOn，
     * 这是唯一能可靠拿到「屏幕是否点亮」的公开收敛点，用来在息屏时停掉轮询。
     */
    private fun hookScreenOn() {
        val className = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
        val clazz = try {
            XposedHelpers.findClass(className, classLoader)
        } catch (t: Throwable) {
            recordHook("KeyguardStateControllerImpl.update(screenOn)", false, t.javaClass.simpleName)
            log.e("找不到 $className", t)
            return
        }

        hookClassMethod(
            clazz, "update", "KeyguardStateControllerImpl.update(screenOn)",
            arity = 1, onlyIfSelfDeclares = true
        ) {
            val screenOn = it.args.getOrNull(0) as? Boolean
            if (screenOn != null) controller?.screenOn = screenOn
            readKeyguardFields(it.thisObject, "update(screenOn=$screenOn)")
        }
    }

    // ------------------------------------------------------------------
    // 配置热更新
    // ------------------------------------------------------------------

    private fun installConfigReceiver(ctx: Context) {
        if (configReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    com.example.livenotification.island.config.IslandConfig.ACTION_CONFIG_CHANGED -> {
                        // 重新读一遍日志开关
                        val verbose = com.example.livenotification.island.config.ConfigStore.getBoolean(
                            ctx,
                            com.example.livenotification.island.config.IslandConfig.KEY_DEBUG_LOG,
                            com.example.livenotification.island.config.IslandConfig.DEF_DEBUG_LOG
                        )
                        com.example.livenotification.island.util.IslandLog.setVerbose(verbose)
                        log.verbose = verbose

                        // 设置界面每次改动都会发这个广播：既重新读文件快照，
                        // 也把最新状态回报给设置界面（相当于一次 ping）
                        loadConfigSnapshotFromFile()
                        controller?.onConfigChanged("broadcast")

                        val attached = controller?.isAttached() == true
                        reportStatus(
                            if (attached) {
                                "已注入 SystemUI（锁屏根布局已挂载）"
                            } else {
                                "hook 已加载，但锁屏布局尚未挂载（请锁屏一次）"
                            }
                        )
                    }

                    com.example.livenotification.island.config.IslandConfig.ACTION_CONFIG_SNAPSHOT -> {
                        // 完整快照直推：不依赖任何文件/prefs 通道，最可靠
                        val text = intent.getStringExtra(
                            com.example.livenotification.island.config.IslandConfig.EXTRA_SNAPSHOT
                        )
                        controller?.applyConfigSnapshot(text)
                        val verbose = com.example.livenotification.island.config.ConfigSnapshot.toBoolean(
                            com.example.livenotification.island.config.ConfigSnapshot
                                .decode(text)["debug_log"],
                            com.example.livenotification.island.config.IslandConfig.DEF_DEBUG_LOG
                        )
                        com.example.livenotification.island.util.IslandLog.setVerbose(verbose)
                        log.verbose = verbose
                        log.mark("已应用推送的配置快照")
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(com.example.livenotification.island.config.IslandConfig.ACTION_CONFIG_CHANGED)
                addAction(com.example.livenotification.island.config.IslandConfig.ACTION_CONFIG_SNAPSHOT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            configReceiver = receiver
            log.i("已注册配置变化广播接收器")
        }.onFailure { log.e("注册广播失败", it) }
    }

    // ------------------------------------------------------------------
    // 自检回报
    //
    // 两条通道，互为备份：
    //  1. 写文件到模块自己的 /data/data/<模块包名>/files/hook_status.txt
    //     —— 最可靠，设置界面直接读文件，不受广播/prefs 跨 UID 限制影响
    //  2. 广播 ACTION_STATUS_REPORT —— 设置界面开着时能实时刷新
    // ------------------------------------------------------------------

    private fun reportStatus(status: String) {
        val ctx = context ?: return
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        log.mark("状态回报：$status")

        // 1) 文件通道
        runCatching {
            val moduleFiles = File(
                "/data/data/$MODULE_PACKAGE/files"
            )
            val ok = com.example.livenotification.island.util.IslandLog.writeStatusFile(
                moduleFiles,
                buildString {
                    appendLine("status=$status")
                    appendLine("at=$ts")
                    appendLine("log=${com.example.livenotification.island.util.IslandLog.logFilePath()}")
                    appendLine("process=${processName ?: "?"}")
                    appendLine("sdk=${Build.VERSION.SDK_INT}")
                    appendLine("hookreport=${hookReport()}")
                }
            )
            if (!ok) log.i("状态文件写入失败，仅依赖广播")
        }.onFailure { log.i("状态文件写入异常：${it.message}") }

        // 2) 广播通道
        runCatching {
            val intent = Intent(
                com.example.livenotification.island.config.IslandConfig.ACTION_STATUS_REPORT
            ).apply {
                setPackage(MODULE_PACKAGE)
                // 模块可能处于 stopped 状态（安装后 / 被强制停止过），
                // 不加这个 flag 广播会被静默丢弃，设置界面就一直显示"未注入"。
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra(com.example.livenotification.island.config.IslandConfig.EXTRA_STATUS, status)
                putExtra(com.example.livenotification.island.config.IslandConfig.EXTRA_AT, ts)
                putExtra(
                    com.example.livenotification.island.config.IslandConfig.EXTRA_LOG_PATH,
                    com.example.livenotification.island.util.IslandLog.logFilePath()
                )
            }
            ctx.sendBroadcast(intent)
        }.onFailure { log.i("状态广播发送失败（可忽略）：${it.message}") }
    }

    companion object {
        /** com.android.systemui.R.id.keyguard_root_view = 0x7f0a0460（反编译确认） */
        private const val KEYGUARD_ROOT_VIEW_ID = 0x7f0a0460

        /** SystemUI 主进程名 */
        private const val MAIN_PROCESS = "com.android.systemui"

        /** 模块自身的包名 */
        private const val MODULE_PACKAGE =
            com.example.livenotification.island.config.IslandConfig.MODULE_PACKAGE
    }
}
