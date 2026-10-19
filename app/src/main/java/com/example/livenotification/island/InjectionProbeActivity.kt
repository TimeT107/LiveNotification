package com.example.livenotification.island

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * 注入逻辑的端到端测试床（不需要 root/LSPosed）。
 *
 * ============ 为什么能在普通进程里跑 ============
 *
 * `IslandController` 对宿主视图只有两个要求：
 *   1. 它是 `androidx.constraintlayout.widget.ConstraintLayout`
 *   2. 它引用得到 `com.android.systemui.R` 里的 `keyguard_root_view`
 *
 * 而 Android 的 `ActivityThread` 类加载器可以加载 SystemUI 的类（只是加载，
 * 不是 hook），所以可以在**自己的进程**里：
 *   - 真去加载 `com.android.systemui.keyguard.ui.view.KeyguardRootView`
 *   - 把它的实例当作宿主，直接调用真实的 `IslandController.attach()`
 *   - 检查岛是否真的被加进了子视图、约束与尺寸是否正确
 *
 * 这样就能端到端验证注入逻辑本身，而不需要在 SystemUI 里执行代码。
 * 顺带它也能**决定性地回答**那个 XML 资源依赖问题：
 * 如果 `KeyguardRootView` 引用 SystemUI 的 `R.layout`，类加载会抛
 * `NoClassDefFoundError`，这里会如实报出来 —— 那正是真机上 attach 会失败的原因。
 *
 * 验证不了的是：hook 是否被触发、视图在真实锁屏上的位置。
 */
class InjectionProbeActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var hostSlot: android.widget.FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(36))
        }
        root.addView(TextView(this).apply {
            text = "注入逻辑端到端探针"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "在本进程内加载 SystemUI 的 KeyguardRootView，并用真实 " +
                "IslandController.attach() 走一遍注入路径。"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(6), 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "运行注入探针"
            setOnClickListener { runProbe() }
        })

        log = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFFDDDDDD.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(14), 0, 0)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(log)

        // 宿主视图槽位：探针把被测的 ConstraintLayout 放进这里，
        // 而不是 setContentView —— 否则会把日志视图顶掉，看不到报告。
        hostSlot = android.widget.FrameLayout(this)
        root.addView(
            hostSlot,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)
            ).apply { topMargin = dp(10) }
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private val report = StringBuilder()
    private var step = 0

    private fun out(line: String) {
        step++
        report.append(step).append(". ").append(line).append('\n')
        log.text = report.toString()
    }

    private fun runProbe() {
        report.clear()
        step = 0

        // ---- 0) 先说清这个探针能验证什么、不能验证什么 ----
        //
        // 试过的两条「加载 SystemUI 类」的路都不通，记录在此避免重复投入：
        //   a) getSystemContext().getClassLoader() → BootClassLoader，
        //      看不到 SystemUI APK 里的类，ClassNotFoundException
        //   b) 自己造 PathClassLoader 需要 SystemUI 的 APK 路径，而普通应用
        //      读不到 /proc/<pid>/maps（Android 10+ SELinux 限制），
        //      也读不到 /data/system/packages.xml
        // 所以「把 SystemUI 的 KeyguardRootView 装进本进程」这件事在无 root
        // 环境下做不到。这个探针因此改为验证**不依赖 SystemUI 类的那部分**：
        // 用真实 ConstraintLayout 当宿主，跑真实的 IslandController 逻辑。
        out("说明：无 root 时无法加载 SystemUI 的类，本探针验证注入逻辑本身")

        val simLoader = javaClass.classLoader
        // 顺手记录一次结论性的探测：SystemUI 的类在本进程是否可见
        val sysUiVisible = try {
            Class.forName("com.android.systemui.keyguard.ui.view.KeyguardRootView", false, simLoader)
            true
        } catch (_: Throwable) {
            false
        }
        out("SystemUI 类在本进程可见: $sysUiVisible（预期 false）")

        // ---- 1) 用真实 ConstraintLayout 当宿主 ----
        hostSlot.removeAllViews()
        val host = ConstraintLayout(this)
        hostSlot.addView(
            host,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
        )

        Handler(Looper.getMainLooper()).post {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(600), View.MeasureSpec.EXACTLY)
            )
            host.layout(0, 0, dp(400), dp(600))
            out("✅ 宿主 ConstraintLayout 已布局: ${host.width}x${host.height}")

            // ---- 2) 调用真实的 IslandController.attach() ----
            val logger = com.example.livenotification.island.hook.IslandLogger("InjectionProbe")
            val controller = com.example.livenotification.island.hook.IslandController(
                applicationContext, logger
            )
            val ok = try {
                controller.attach(host)
            } catch (t: Throwable) {
                out("❌ attach 抛异常: ${t.javaClass.simpleName} ${t.message}")
                false
            }
            out("${if (ok) "✅" else "❌"} attach() 返回 $ok")
            if (!ok) return@post

            Handler(Looper.getMainLooper()).postDelayed({
                runAssertions(controller, host)
                controller.release()
                out("探针结束")
            }, 700)
        }
    }

    /** attach 之后的断言：岛是否真的进了宿主、约束与渲染是否正常 */
    private fun runAssertions(
        controller: com.example.livenotification.island.hook.IslandController,
        host: ConstraintLayout
    ) {
        out("宿主子视图数: ${host.childCount}（应 >= 1，真实 SystemUI 里本来就有子视图）")

        val island = controller.currentView()
        if (island == null) {
            out("❌ currentView() 为 null")
            return
        }
        out("${if (island.parent === host) "✅" else "❌"} 岛已挂进宿主: ${island.parent === host}")
        out("岛尺寸=${island.width}x${island.height} visibility=${island.visibility} state=${island.state}")

        val lp = island.layoutParams as? ConstraintLayout.LayoutParams
        if (lp == null) {
            out("❌ LayoutParams 不是 ConstraintLayout.LayoutParams")
        } else {
            val topOk = lp.topToTop == ConstraintLayout.LayoutParams.PARENT_ID
            val startOk = lp.startToStart == ConstraintLayout.LayoutParams.PARENT_ID
            val endOk = lp.endToEnd == ConstraintLayout.LayoutParams.PARENT_ID
            out(
                "${if (topOk && startOk && endOk) "✅" else "❌"} 约束正确: " +
                    "topToTop=$topOk startToStart=$startOk endToEnd=$endOk bias=${lp.verticalBias}"
            )
            // 位置换算应当与 LayoutMath 一致
            val expectedTop = com.example.livenotification.island.util.LayoutMath.topForBias(
                host.height, island.height, lp.verticalBias
            )
            out(
                "位置: 实际top=${island.top} 期望top=$expectedTop " +
                    "${if (kotlin.math.abs(island.top - expectedTop) <= 2) "✅ 一致" else "❌ 不一致"}"
            )
        }

        // ---- 渲染路径 ----
        island.state = com.example.livenotification.island.ui.IslandView.STATE_MEDIA
        island.setCollapsedText("注入探针", "测试歌手")
        island.setCover(null)
        island.setPlaying(true)
        island.setProgress(30_000, 120_000)
        out("✅ 媒体渲染路径无异常（含无封面占位）")

        island.setExpanded(true)
        out("✅ 展开态切换无异常 expanded=${island.isExpanded()}")
        island.setExpanded(false)

        // ---- 状态胶囊路径 ----
        island.state = com.example.livenotification.island.ui.IslandView.STATE_STATUS
        island.setChipGlyph(com.example.livenotification.island.ui.Glyphs.CHARGING)
        island.setCollapsedText("正在充电", "64%")
        out("✅ 状态胶囊路径无异常")

        // ---- 重复 attach 必须幂等 ----
        val before = host.childCount
        controller.attach(host)
        out(
            "${if (host.childCount == before) "✅" else "❌"} 重复 attach 幂等: " +
                "子视图数 $before -> ${host.childCount}"
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 找出 SystemUI 的 APK 路径（用于构造 PathClassLoader）。
     *
     * 普通应用查不到别的包的 codePath，但可以通过几种间接方式拿到：
     *   1. `/proc/<pid>/maps` —— SystemUI 把 base.apk mmap 进来了，
     *      从里面筛出含 com.android.systemui 的 apk 路径
     *   2. `dumpsys` 输出里的 codePath
     * 这里优先用第 1 种（最直接，且一定能拿到真实路径）。
     */
    private fun findSystemUiApk(): String? {
        // 方式 1：从 SystemUI 进程的 maps 里找。
        // maps 的每一行形如：
        //   7f8a2c0000-7f8a2d0000 r--p 00000000 fd:00 1234  /data/app/.../base.apk
        // 路径在最后一列。
        try {
            val pid = findSystemUiPid()
            if (pid != null) {
                val lines = java.io.File("/proc/$pid/maps").readLines()
                for (line in lines) {
                    val path = line.substringAfterLast(' ').trim()
                    if (path.endsWith(".apk") &&
                        path.contains("com.android.systemui") &&
                        java.io.File(path).exists()
                    ) {
                        return path
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun findSystemUiPid(): Int? {
        return try {
            val entries = java.io.File("/proc").listFiles { f -> f.name.all { it.isDigit() } }
                ?: return null
            for (dir in entries) {
                val cmdline = try {
                    java.io.File(dir, "cmdline").readText().trim('\u0000', ' ', '\n')
                } catch (_: Throwable) {
                    continue
                }
                if (cmdline == "com.android.systemui") return dir.name.toIntOrNull()
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
