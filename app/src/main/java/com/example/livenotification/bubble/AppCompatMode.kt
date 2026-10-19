package com.example.livenotification.bubble

import android.util.Log

/**
 * 让"竖屏应用"在横屏的宽气泡窗口里按自己的比例显示。
 *
 * 气泡窗口的宽度由系统决定（≈整屏宽），横屏时窗口必然宽而扁。竖屏应用若可自由缩放，
 * 会铺满宽窗、布局被横向撑开（错位）。
 *
 * 给应用打开 Android 的 FORCE_NON_RESIZE_APP compat 标记后，系统会把它当作
 * "不可调整大小"的应用，在多窗口里按它自己的宽高比 letterbox（两侧加黑边）显示 ——
 * 这就是"在气泡里以竖屏比例显示应用界面"。
 *
 * 需要 root（am compat 是 shell 命令）。副作用：该应用全局进入兼容模式，分屏等
 * 多窗口场景也会 letterbox。撤销命令：
 *   su -c "am compat disable 181136395 <包名>"
 */
object AppCompatMode {

    private const val TAG = "BubbleNotify"
    private const val FORCE_NON_RESIZE_APP = 181136395L

    /**
     * 反过来：让"不可调整大小"的应用在气泡里变成可调整大小。
     *
     * 起因（真机）：QQ 的 Activity 是 nonResizable，塞进气泡后系统把它按全屏处理，
     * 气泡背景变成**纯黑**（有时还会被系统转成横屏）。加这个 compat 标记后 QQ 在
     * 气泡里正常 resize，背景恢复成壁纸。撤销：su -c "am compat disable 174042936 <包名>"
     */
    private const val FORCE_RESIZE_APP = 174042936L

    private val applied = HashSet<String>()
    private val appliedResize = HashSet<String>()

    fun ensureNonResizeable(pkg: String): Boolean {
        synchronized(applied) {
            if (applied.contains(pkg)) return true
        }
        val ok = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "am compat enable " + FORCE_NON_RESIZE_APP + " " + pkg)
            )
            // am compat 失败也可能返回 0，所以看输出里有没有 "Enabled change"
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            val success = code == 0 && output.contains("Enabled change", ignoreCase = true)
            Log.i(TAG, "compat 非resize pkg=" + pkg + " code=" + code + " out=" + output)
            success
        }.getOrElse {
            Log.w(TAG, "compat 设置失败 pkg=" + pkg, it)
            false
        }
        if (ok) synchronized(applied) { applied.add(pkg) }
        return ok
    }

    /**
     * 打开系统的"强制可调整大小"（开发者选项 Force activities to be resizable 对应的
     * 全局开关 force_resizable_activities）。
     *
     * 为什么需要它：QQ 这类应用的 Activity 在清单里就声明为不可调整大小，
     * per-app 的 FORCE_RESIZE_APP compat 对它们**不生效**（实测任务仍是 nonResizable），
     * 只有这个全局开关能让它们真正 resizeable。效果：
     *   1) 气泡里不再被系统按全屏处理 → 不会出现纯黑背景；
     *   2) 横屏使用时按宽窗口排版，应用有平板/横屏布局（QQ、微信）就会以平板样式展开。
     *
     * 需要 root（settings 命令）。撤销：su -c "settings put global force_resizable_activities 0"
     */
    private const val TAG_GLOBAL = "BubbleNotify"
    private const val FORCE_RESIZABLE_KEY = "force_resizable_activities"

    @Volatile
    private var globalResizeChecked = false

    @Volatile
    private var globalResizeOk = false

    fun ensureGlobalResizeable(): Boolean {
        if (globalResizeChecked) return globalResizeOk
        val ok = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "settings put global " + FORCE_RESIZABLE_KEY + " 1")
            )
            process.waitFor()
            val check = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "settings get global " + FORCE_RESIZABLE_KEY)
            )
            val value = check.inputStream.bufferedReader().readText().trim()
            check.waitFor()
            val success = value == "1"
            Log.i(TAG_GLOBAL, "force_resizable_activities = " + value + " ok=" + success)
            success
        }.getOrElse {
            Log.w(TAG_GLOBAL, "设置 force_resizable_activities 失败", it)
            false
        }
        synchronized(this) {
            globalResizeChecked = true
            globalResizeOk = ok
        }
        return ok
    }

    /** 让不可调整大小的应用在气泡窗口里可 resize（否则气泡背景纯黑）。需要 root。 */
    fun ensureResizeable(pkg: String): Boolean {
        synchronized(appliedResize) {
            if (appliedResize.contains(pkg)) return true
        }
        val ok = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "am compat enable " + FORCE_RESIZE_APP + " " + pkg)
            )
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            val success = code == 0 && output.contains("Enabled change", ignoreCase = true)
            Log.i(TAG, "compat resizeable pkg=" + pkg + " code=" + code + " out=" + output)
            success
        }.getOrElse {
            Log.w(TAG, "compat resizeable 失败 pkg=" + pkg, it)
            false
        }
        if (ok) synchronized(appliedResize) { appliedResize.add(pkg) }
        return ok
    }
}
