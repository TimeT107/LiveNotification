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

    private val applied = HashSet<String>()

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
}
