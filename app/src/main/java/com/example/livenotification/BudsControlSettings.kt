package com.example.livenotification

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 耳机降噪控制的设置，供「主 App」和「Xposed 模块」共用。
 *
 * 为什么用外部文件而不是 SharedPreferences：
 *   hook 必须运行在 com.heytap.headset 进程里，那个进程读不到本 App 私有的
 *   SharedPreferences。外部专属目录 /sdcard/Android/data/<包名>/files/ 两个进程
 *   都能访问，因此作为唯一的交换点。
 *
 * 文件同时被设成全局可读，避免个别 ROM 上因权限位导致模块读失败。
 */
object BudsControlSettings {

    private const val TAG = "BudsControlSettings"

    private const val PREFS = "buds_control"
    private const val KEY_FORCED_MODE = "forced_mode"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_EXPERT = "expert_mode"
    private const val KEY_CHIP = "chip_enabled"

    /** -1 = 不干预，0 = 降噪，1 = 关闭，2 = 通透 */
    const val DO_NOT_FORCE = -1
    const val MODE_ANC = 0
    const val MODE_OFF = 1
    const val MODE_PASS = 2

    private const val FILE_NAME = "buds_control.json"

    fun modeName(mode: Int): String = when (mode) {
        MODE_ANC -> "降噪"
        MODE_OFF -> "关闭"
        MODE_PASS -> "通透"
        else -> "不强制"
    }

    /**
     * 交换文件。优先放到外部专属目录，取不到时退回内部目录
     * （内部目录在部分 ROM 上模块可能读不到，因此只作为兜底）。
     */
    fun settingsFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val dir = external ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun readForcedMode(context: Context): Int {
        // SharedPreferences 作为主存储，读取快、无 IO
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_FORCED_MODE)) {
            return sp.getInt(KEY_FORCED_MODE, DO_NOT_FORCE)
        }
        // 首次运行：尝试从交换文件恢复（例如重装 App 后）
        return readFromFile(context)?.first ?: DO_NOT_FORCE
    }

    fun readOverlayEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY_OVERLAY)) {
            return sp.getBoolean(KEY_OVERLAY, true)
        }
        return readFromFile(context)?.second ?: true
    }

    /**
     * Expert mode replaces the earbuds screen with the low-level direct-connect harness
     * used while reverse-engineering the protocol. Off by default.
     */
    fun readExpertMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPERT, false)

    fun writeExpertMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_EXPERT, enabled).apply()
    }

    /** Whether the top-left mode chip appears when the earbuds report a mode change. */
    fun readChipEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CHIP, true)

    fun writeChipEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CHIP, enabled).apply()
    }

    fun writeForcedMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FORCED_MODE, mode).apply()
        writeFile(context, mode, readOverlayEnabled(context))
    }

    fun writeOverlayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OVERLAY, enabled).apply()
        writeFile(context, readForcedMode(context), enabled)
    }

    private fun writeFile(context: Context, mode: Int, overlay: Boolean) {
        try {
            val file = settingsFile(context)
            file.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("forcedMode", mode)
                put("overlayEnabled", overlay)
                put("updatedAt", System.currentTimeMillis())
            }
            file.writeText(json.toString())
            // 让 Xposed 模块（另一个 uid）也能读
            runCatching {
                file.setReadable(true, false)
                file.parentFile?.setReadable(true, false)
                file.parentFile?.setExecutable(true, false)
            }
            Log.d(TAG, "写入成功: ${file.absolutePath} -> $json")
        } catch (t: Throwable) {
            Log.w(TAG, "写入交换文件失败: ${t.message}")
        }
    }

    /** @return Pair(forcedMode, overlayEnabled) */
    private fun readFromFile(context: Context): Pair<Int, Boolean>? {
        return try {
            val file = settingsFile(context)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val mode = json.optInt("forcedMode", DO_NOT_FORCE)
            val overlay = json.optBoolean("overlayEnabled", true)
            mode to overlay
        } catch (t: Throwable) {
            Log.w(TAG, "读取交换文件失败: ${t.message}")
            null
        }
    }
}
