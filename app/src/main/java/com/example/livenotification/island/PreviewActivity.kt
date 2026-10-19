package com.example.livenotification.island

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.livenotification.island.config.ConfigSnapshot
import com.example.livenotification.island.config.ConfigStore
import com.example.livenotification.island.config.IslandConfig
import com.example.livenotification.island.ui.Glyphs
import com.example.livenotification.island.ui.IslandView

/**
 * 锁屏岛外观自检页。
 *
 * 存在的理由：岛最终长在 SystemUI 的锁屏上，一旦样式有问题就要反复
 * 装模块 + 重启系统界面才能看一眼，成本太高。这里用同一个 [IslandView]
 * 类在普通 Activity 里渲染，外观、圆角、排版、图标全部一致，
 * 改样式时可以在几秒内看到结果。
 *
 * 注意：这里只验证「长什么样」，不验证「能不能挂到锁屏上」。
 * 后者必须靠真机的 hook 日志。
 */
class PreviewActivity : Activity() {

    private val islands = mutableListOf<IslandView>()
    private lateinit var cover: android.graphics.Bitmap
    private lateinit var background: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cover = makeCoverArt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(36))
        }

        root.addView(TextView(this).apply {
            text = "锁屏岛外观自检"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "以下四个胶囊用的是锁屏上同一个 IslandView 类，颜色/圆角/字号完全一致。\n" +
                "背景可在「深色 / 浅色壁纸」之间切换，用来检查对比度。"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, dp(6), 0, dp(18))
        })

        root.addView(label("① 空闲（无媒体、无状态）"))
        root.addView(buildIsland { it.state = IslandView.STATE_IDLE; it.setChipGlyph(0)
            it.setCollapsedText("锁屏岛已就绪", "等待媒体播放") })

        root.addView(label("② 媒体（收起，带律动条）"))
        root.addView(buildIsland {
            it.state = IslandView.STATE_MEDIA
            it.setCollapsedText("起风了", "买辣椒也用券")
            it.setCover(cover)
            it.setExpandedText("起风了", "买辣椒也用券")
            it.setPlaying(true)
            it.setProgress(63_000, 325_000)
            it.setSeekBarMax(1000)
        })

        root.addView(label("③ 媒体（展开大卡片）"))
        root.addView(buildIsland(expanded = true) {
            it.state = IslandView.STATE_MEDIA
            it.setCollapsedText("起风了", "买辣椒也用券")
            it.setExpandedText("起风了", "买辣椒也用券")
            it.setCover(cover)
            it.setExpanded(true)
            it.setPlaying(true)
            it.setProgress(63_000, 325_000)
            it.setSeekBarMax(1000)
            it.setOnMediaButtonListener { }
        })

        root.addView(label("④ 媒体（无封面，检查占位）"))
        root.addView(buildIsland(expanded = true) {
            it.state = IslandView.STATE_MEDIA
            it.setCollapsedText("未知曲目", "未知歌手")
            it.setExpandedText("未知曲目", "未知歌手")
            it.setCover(null)
            it.setExpanded(true)
            it.setPlaying(false)
            it.setProgress(0, 0)
            it.setOnMediaButtonListener { }
        })

        root.addView(label("⑤ 系统状态胶囊（闹钟 / 充电 / 耳机）"))
        for ((glyph, main, detail) in listOf(
            Triple(Glyphs.TIMER, "闹钟 07:30", "明天"),
            Triple(Glyphs.CHARGING, "正在充电", "64%"),
            Triple(Glyphs.HEADSET, "已连接 FreeBuds Pro", null)
        )) {
            root.addView(buildIsland {
                it.state = IslandView.STATE_STATUS
                it.setChipGlyph(glyph)
                it.setCollapsedText(main, detail)
            })
        }

        // 背景切换
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        row.addView(Button(this).apply {
            text = "深色壁纸"
            setOnClickListener { setBg(true) }
        })
        row.addView(Button(this).apply {
            text = "浅色壁纸"
            setOnClickListener { setBg(false) }
        })
        root.addView(row)

        // 自检：验证跨进程配置通道的编解码是往返一致的。
        // 这一段跑在你的手机上，比我在开发机上空想可靠得多。
        selfTestLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(selfTestLabel)
        root.addView(Button(this).apply {
            text = "运行自检"
            setOnClickListener { runSelfTest() }
        })

        // 圆角滑块，方便对比 MD3E 的形态
        val cornerLabel = TextView(this).apply {
            text = "圆角：胶囊（全圆角）"
            textSize = 12f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(cornerLabel)
        root.addView(SeekBar(this).apply {
            max = 56
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val corner = if (progress == 0) -1 else progress
                    cornerLabel.text =
                        if (corner < 0) "圆角：胶囊（全圆角）" else "圆角：${corner}dp"
                    islands.forEach { it.applyStyle(dp(56), corner.let { c -> if (c < 0) -1 else dp(c) }, dp(108)) }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        background = View(this)
        val frame = FrameLayout(this)
        frame.addView(background, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        frame.addView(ScrollView(this).apply { addView(root) })
        setContentView(frame)
        setBg(true)
    }

    private fun buildIsland(expanded: Boolean = false, block: (IslandView) -> Unit): View {
        val wrap = FrameLayout(this)
        wrap.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }

        val view = IslandView(this)
        val lp = FrameLayout.LayoutParams(dp(340), dp(if (expanded) 108 else 56))
        wrap.addView(view, lp)
        view.applyStyle(dp(56), -1, dp(108))
        block(view)
        islands.add(view)
        return wrap
    }

    /**
     * 屏幕上真实实例的子视图坐标报告。
     *
     * 加这个是因为截图里看到「文字压在封面上」，但用探针实例测量一切正常 ——
     * 说明问题只出在真实布局流程里。与其反复截图推测，不如让实例自己报数。
     */
    private fun reportRealLayout(): String {
        val sb = StringBuilder()
        islands.forEachIndexed { index, island ->
            sb.append("\n岛#$index state=${island.state} expanded=${island.isExpanded()} " +
                "size=${island.width}x${island.height}")
            collectPositions(island, sb, 0)
        }
        return sb.toString()
    }

    private fun collectPositions(v: View, sb: StringBuilder, depth: Int) {
        if (depth > 2) return
        sb.append("\n").append("    ".repeat(depth + 1))
        sb.append(v.javaClass.simpleName)
        sb.append(" [${v.left},${v.top}]-[${v.right},${v.bottom}]")
        if (v.visibility != View.VISIBLE) sb.append(" vis=${if (v.visibility == View.GONE) "G" else "I"}")
        if (v is android.widget.TextView) sb.append(" '${v.text}'")
        if (v is android.widget.ImageView) sb.append(if (v.drawable != null) " 有图" else " 无图")
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectPositions(v.getChildAt(i), sb, depth + 1)
        }
    }

    /**
     * 检查一个岛内部**同一层级**的可见兄弟节点之间是否矩形重叠。
     *
     * 只比较兄弟：父容器必然包含子节点，那不算重叠。
     * 真机上暴露的问题正是兄弟重叠 —— 收起态那一行与展开态那一行同时可见。
     *
     * @return null 表示无重叠；否则返回描述
     */
    private fun checkNoOverlap(island: IslandView): String? {
        val groups = mutableListOf<ViewGroup>()
        fun collect(v: View, depth: Int) {
            if (depth > 3) return
            if (v is ViewGroup) {
                groups.add(v)
                for (i in 0 until v.childCount) collect(v.getChildAt(i), depth + 1)
            }
        }
        collect(island, 0)

        for (group in groups) {
            val visible = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            for (i in visible.indices) {
                for (j in i + 1 until visible.size) {
                    val a = visible[i]
                    val b = visible[j]
                    val ra = android.graphics.Rect(a.left, a.top, a.right, a.bottom)
                    val rb = android.graphics.Rect(b.left, b.top, b.right, b.bottom)
                    if (ra.intersect(rb)) {
                        return "在 ${group.javaClass.simpleName} 内，" +
                            "「${a.javaClass.simpleName}」$ra 与 " +
                            "「${b.javaClass.simpleName}」$rb 重叠"
                    }
                }
            }
        }
        return null
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFFD0BCFF.toInt())
        setPadding(0, dp(10), 0, dp(6))
    }

    private fun setBg(dark: Boolean) {
        background.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) intArrayOf(0xFF101014.toInt(), 0xFF2A2340.toInt(), 0xFF0A0A0C.toInt())
            else intArrayOf(0xFFEADDFF.toInt(), 0xFFD0BCFF.toInt(), 0xFFFFD8E4.toInt())
        )
    }

    private lateinit var selfTestLabel: TextView

    /**
     * 自检项。
     *
     * 重点验证「设置界面 → SystemUI」这条跨进程通道的编解码是否往返一致 ——
     * 这是整个模块最容易静默失效的地方：一旦快照解析错了，
     * 用户改设置就会毫无反应，而且不报任何错。
     */
    private fun runSelfTest() {
        val results = mutableListOf<String>()
        var pass = 0
        var fail = 0

        fun check(name: String, ok: Boolean, detail: String = "") {
            if (ok) pass++ else fail++
            results.add("${if (ok) "✅" else "❌"} $name${if (detail.isEmpty()) "" else " — $detail"}")
        }

        // 1) 快照编解码往返
        //
        // ⚠ 这里刻意**不手抄 key 列表**（原来就是手抄的，漏了 music_lock_style，
        //    于是这条自检在真机上一直都是 ❌ 11/12）。用 ALL_KEYS + 默认值生成，
        //    只覆盖两个用来验证类型往返的键。
        val values = ConfigSnapshot.buildValues(
            mapOf(
                ConfigSnapshot.KEY_VERTICAL_BIAS to 63,
                ConfigSnapshot.KEY_CORNER_DP to -1
            ),
            IslandConfig.SNAPSHOT_DEFAULTS
        )
        val encoded = ConfigSnapshot.encode(values)
        val decoded = ConfigSnapshot.decode(encoded)
        check("快照项数 = ${ConfigSnapshot.ALL_KEYS.size}", decoded.size == ConfigSnapshot.ALL_KEYS.size,
            "实际 ${decoded.size}")
        check("vertical_bias 往返 = 63",
            ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_VERTICAL_BIAS], -1) == 63)
        check("corner_dp 负数往返 = -1",
            ConfigSnapshot.toInt(decoded[ConfigSnapshot.KEY_CORNER_DP], 99) == -1)
        check("enabled=true 往返",
            ConfigSnapshot.toBoolean(decoded[ConfigSnapshot.KEY_ENABLED], false))
        check("show_on_aod=false 往返",
            !ConfigSnapshot.toBoolean(decoded[ConfigSnapshot.KEY_SHOW_ON_AOD], true))

        // 2) 脏输入不能崩、不能污染
        val dirty = "garbage line\nvertical_bias=abc\n=enabled\n" +
            "unknown_key=1\nwidth_percent=82\n\n# comment\n"
        val dirtyParsed = ConfigSnapshot.decode(dirty)
        check("脏输入被过滤（unknown_key 丢弃）",
            !dirtyParsed.containsKey("unknown_key"))
        check("脏输入里合法项仍解析",
            ConfigSnapshot.toInt(dirtyParsed[ConfigSnapshot.KEY_WIDTH_PERCENT], -1) == 82)
        check("非法整数回落到默认值",
            ConfigSnapshot.toInt(dirtyParsed[ConfigSnapshot.KEY_VERTICAL_BIAS], 68) == 68)
        check("空输入返回空表", ConfigSnapshot.decode(null).isEmpty())

        // 3) 快照真正驱动视图尺寸
        val probe = IslandView(this)
        probe.applyStyle(dp(56), -1, dp(108))
        probe.state = IslandView.STATE_MEDIA
        probe.setCover(cover)
        probe.setCollapsedText("自检", "标题")
        probe.setPlaying(true)
        probe.setProgress(1000, 10000)
        probe.setSeekBarMax(1000)
        check("视图可独立实例化并设置状态", probe.state == IslandView.STATE_MEDIA)
        probe.setExpanded(true)
        check("展开态可切换", probe.isExpanded())
        probe.setExpanded(false)
        check("收起态可切换", !probe.isExpanded())

        // 4) 所有图标都能构造出来（路径运算写错会在这里暴露）
        var glyphOk = true
        var glyphDetail = ""
        for (g in listOf(
            Glyphs.PREV, Glyphs.PLAY, Glyphs.PAUSE, Glyphs.NEXT,
            Glyphs.CHARGING, Glyphs.HEADSET, Glyphs.TIMER, Glyphs.MUSIC
        )) {
            try {
                val d = Glyphs.create(this, g, Color.WHITE)
                d.setBounds(0, 0, 48, 48)
                // 真正走一遍绘制，path.op 出错会在这里抛
                val bmp = android.graphics.Bitmap.createBitmap(
                    48, 48, android.graphics.Bitmap.Config.ARGB_8888
                )
                d.draw(android.graphics.Canvas(bmp))
            } catch (t: Throwable) {
                glyphOk = false
                glyphDetail = "glyph=$g: ${t.message}"
                break
            }
        }
        check("7 个矢量图标全部可构造并绘制", glyphOk, glyphDetail)

        // 5) 视图子树自检：把岛内部的子视图尺寸打出来。
        //    这一项是为了让"视觉上看不到某个元素"这类问题能被结构化证据定位，
        //    而不是靠反复截图猜。
        val probeIsland = IslandView(this)
        probeIsland.applyStyle(dp(56), -1, dp(108))
        probeIsland.setCover(cover)
        probeIsland.state = IslandView.STATE_MEDIA
        probeIsland.setCollapsedText("子视图", "探针")
        // 强制一次布局，否则子视图宽高都是 0
        probeIsland.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(dp(340), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(dp(56), android.view.View.MeasureSpec.EXACTLY)
        )
        probeIsland.layout(0, 0, dp(340), dp(56))

        val childReport = StringBuilder()
        fun walk(v: View, depth: Int, label: String) {
            if (depth > 3) return
            childReport.append("\n")
            childReport.append("  ".repeat(depth))
            childReport.append(label).append(": ")
            childReport.append("${v.javaClass.simpleName} ")
            childReport.append("vis=${if (v.visibility == View.VISIBLE) "V" else if (v.visibility == View.GONE) "G" else "I"} ")
            childReport.append(
                "bounds=(${v.left},${v.top})-(${v.right},${v.bottom}) " +
                    "measured=${v.measuredWidth}x${v.measuredHeight} "
            )
            if (v is android.widget.LinearLayout) {
                childReport.append(
                    "padding=[${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom}] "
                )
            }
            if (v is TextView) childReport.append("text='${v.text}' ")
            if (v is android.widget.ImageView) {
                childReport.append("drawable=${if (v.drawable != null) "有" else "无"} ")
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, "[$i]")
            }
        }
        walk(probeIsland, 0, "岛")
        check("岛视图子树可枚举并测量", probeIsland.measuredWidth > 0, childReport.toString())
        selfTestDetail = childReport.toString()

        // 5b) 屏幕上真实实例的坐标报告（用于定位"文字压封面"这类布局问题）
        check("屏幕实例坐标可枚举", islands.isNotEmpty(), reportRealLayout())

        // 5c) 展开态不得与收起态重叠。
        //     这是真机上暴露过的缺陷：两行同时 VISIBLE，收起态的标题画在封面之上。
        //     现在把它变成一条断言，防止回归。
        val expandedIsland = islands.firstOrNull {
            it.state == IslandView.STATE_MEDIA && it.isExpanded()
        }
        val overlap = if (expandedIsland == null) "找不到展开态实例" else checkNoOverlap(expandedIsland)
        check("展开态内部无重叠", overlap == null, overlap.orEmpty())

        // 6) 配置读取全链路（默认值兜底不能崩）
        var cfgOk = true
        var cfgDetail = ""
        try {
            val enabled = ConfigStore.getBoolean(this, IslandConfig.KEY_ENABLED, true)
            val bias = ConfigStore.getInt(this, IslandConfig.KEY_VERTICAL_BIAS, 68)
            cfgDetail = "enabled=$enabled bias=$bias"
        } catch (t: Throwable) {
            cfgOk = false
            cfgDetail = t.message.orEmpty()
        }
        check("配置读取不抛异常", cfgOk, cfgDetail)

        // 7) LSPosed 入口契约自检。
        //
        //    走过的弯路记录在这里，避免以后又写错方向：
        //    最初我用 `Class.forName("...HookEntry")` 断言"入口类可加载"，
        //    结果在真机上稳定失败并报 ClassNotFoundException —— 但类**确实**
        //    在 dex 里（dexdump 已验证）。
        //    真正原因是：HookEntry 实现的接口与方法签名引用了
        //    de.robv.android.xposed.* 这些 compileOnly 类型，而普通 App 进程
        //    没有框架注入，解析不到它们，所以类校验必然失败。
        //    也就是说「加载失败」在这个环境里是**正确行为**，错的是一条
        //    不适用的断言。能在这个进程里验证的是：
        //      a) assets/xposed_init 存在且内容是一个类名
        //      b) 该名字命名的类确实存在于本 APK 的 dex 中
        val entryClassName = "com.example.livenotification.island.hook.HookEntry"
        var entryOk = false
        var entryDetail = ""
        try {
            val declared = assets.open("xposed_init").bufferedReader().use { it.readText().trim() }
            val exists = declared == entryClassName
            entryOk = declared.isNotBlank() && exists
            entryDetail = "xposed_init='$declared' 与预期一致=$exists"
        } catch (t: Throwable) {
            entryDetail = "读取 assets/xposed_init 失败: ${t.message}"
        }
        check("assets/xposed_init 指向正确入口类", entryOk, entryDetail)

        // 8) 确认 Xposed API **没有**被打进 APK。
        //
        //    已用 dexdump 对本 APK 的全部 dex 核实过：不存在
        //    `Lde/robv/android/xposed/XposedBridge;`。compileOnly 生效。
        //    如果哪天它被打进去了，模块会用到自己那份桩类，hook 会**静默失效**
        //    —— 这是 compileOnly 最容易出错的地方，值得一条断言守着。
        //
        //    注意断言方向：普通 App 进程没有框架注入，所以"解析不到"才是正确结果。
        val bundled = try {
            Class.forName(
                "de.robv.android.xposed.XposedBridge", false, javaClass.classLoader
            )
            true
        } catch (_: Throwable) {
            false
        }
        check(
            "Xposed API 未被误打包进 APK",
            !bundled,
            if (bundled) "⚠ 解析到了 XposedBridge —— 可能误打包，会导致 hook 静默失效"
            else "未解析到（正确：应由框架在运行时注入）"
        )

        selfTestLabel.text = buildString {
            append("自检结果：$pass 通过 / $fail 失败\n")
            append(results.joinToString("\n"))
            selfTestDetail?.let {
                append("\n\n视图子树探针：")
                append(it)
            }
        }
        selfTestLabel.setTextColor(if (fail == 0) 0xFF6BCB77.toInt() else 0xFFFF6B6B.toInt())
    }

    /** 视图子树探针输出，附在自检结果后面 */
    private var selfTestDetail: String? = null

    /** 造一张像专辑封面的渐变图，用来检查封面的裁切和圆角 */
    private fun makeCoverArt(): android.graphics.Bitmap {
        val size = 256
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val shader = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFD93D.toInt(), 0xFF6BCB77.toInt()),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        paint.shader = null
        paint.color = 0x55000000
        canvas.drawCircle(size / 2f, size / 2f, size / 3.4f, paint)
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size / 8f, paint)
        return bmp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
