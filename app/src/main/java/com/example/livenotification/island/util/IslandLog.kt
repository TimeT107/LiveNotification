package com.example.livenotification.island.util

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志出口。
 *
 * 因为模块要「盲测」（手机不一定能连 adb），所以默认同时输出到三处：
 *  - logcat（tag: LockScreenIsland）
 *  - LSPosed 日志（XposedBridge.log）
 *  - 文件 /data/user_de/0/com.android.systemui/files/lockscreenisland.log（自动轮转）
 *
 * 日志文件用 SystemUI 自己的 files 目录，因为 hook 就跑在 SystemUI 进程里，
 * 免权限；用户可以从模块设置界面看到路径，或在文件管理器里直接翻。
 */
object IslandLog {

    const val TAG = "LockScreenIsland"

    private const val DIR_NAME = "lockscreenisland"
    private const val FILE_NAME = "lockscreenisland.log"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var verbose: Boolean = true

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var ready = false

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** SystemUI 进程在 hook 阶段调用，用来指定日志目录 */
    fun init(dir: File?, verbose: Boolean) {
        logDir = dir
        this.verbose = verbose
        ready = true
        if (dir != null) {
            runCatching { if (!dir.exists()) dir.mkdirs() }
        }
    }

    fun setVerbose(v: Boolean) {
        verbose = v
    }

    fun i(message: String) = write("I", message, always = false)

    fun w(message: String) = write("W", message, always = true)

    fun e(message: String) = write("E", message, always = true)

    fun e(message: String, t: Throwable) =
        write("E", "$message\n${Log.getStackTraceString(t)}", always = true)

    /** 关键节点，即使关了详细日志也会写 */
    fun mark(message: String) = write("★", message, always = true)

    private fun write(level: String, message: String, always: Boolean) {
        if (!always && !verbose) return
        val line = "${timeFormat.format(Date())} $level $message"

        // 注意：logcat 只认 V/D/I/W/E/F 这几级，所以标签用中文/符号的话
        // 必须显式映射成标准级别，否则日志会被丢掉。
        runCatching { Log.println(levelToPriority(level), TAG, message) }
        runCatching { XposedBridge.log("$TAG: $message") }
        appendToFile(line)
    }

    private fun levelToPriority(level: String): Int = when (level) {
        "E" -> Log.ERROR
        "W" -> Log.WARN
        else -> Log.INFO
    }

    private fun appendToFile(line: String) {
        val dir = logDir ?: return
        runCatching {
            val file = File(dir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                val bak = File(dir, "$FILE_NAME.bak")
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    fun logFilePath(): String? = logDir?.let { File(it, FILE_NAME).absolutePath }

    /**
     * 写一份状态文件到模块自己的外部可读目录。
     *
     * 用途：hook 跑在 SystemUI 进程，设置界面跑在模块进程，两者 prefs 不互通。
     * 写入模块自己的 /data/data/<pkg>/files/ 后，设置界面就能读到 —— 这是
     * 验证「模块到底有没有注入成功」最可靠的一条通道（广播可能被拦截，
     * prefs 跨 UID 不可写，文件则一定可读）。
     *
     * SystemUI 以 system uid 运行，写别的应用私有目录是允许的；
     * 写入时显式放开其他用户读权限，确保模块进程能读到。
     */
    fun writeStatusFile(moduleFilesDir: File, content: String): Boolean {
        return runCatching {
            if (!moduleFilesDir.exists()) moduleFilesDir.mkdirs()
            val f = File(moduleFilesDir, STATUS_FILE_NAME)
            f.writeText(content, Charsets.UTF_8)
            f.setReadable(true, false)
            true
        }.getOrDefault(false)
    }

    fun statusFileName(): String = STATUS_FILE_NAME

    fun dirName(): String = DIR_NAME

    private const val STATUS_FILE_NAME = "hook_status.txt"
}
