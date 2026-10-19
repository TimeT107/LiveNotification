package com.example.livenotification.bubble

import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 用 root 强制让系统重新注册通知监听器。
 *
 * 背景：反复 force-stop / 重装、或系统升级后，NMS 会卡在
 *
 *   Not registering ...NotifyListenerService  is already bound
 *
 * 此时服务"活着"、权限列表里也启用着，但收不到任何通知，
 * requestRebind 完全无效 —— 只有 disallow + allow 让 NMS 重新注册才管用。
 * 这个操作需要 shell/system 权限，普通应用调不了，所以走 su。
 */
object RootHealer {

    private const val TAG = "BubbleNotify"

    @Volatile
    private var running = false

    /**
     * 启动时探一次 root：日志里一眼看出应用到底有没有被授权，
     * 免得自愈失效还得靠猜。
     */
    fun probeAsync() {
        Thread {
            runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = process.inputStream.bufferedReader().readText().trim()
                val code = process.waitFor()
                Log.i(TAG, "root 探测: exit=" + code + " -> " + out)
            }.onFailure { Log.w(TAG, "root 探测失败（未授权或无 su）: " + it.message) }
        }.start()
    }

    /** 异步执行，不阻塞调用线程（watchdog 跑在主线程上） */
    fun healListenerAsync(context: Context) {
        if (running) return
        running = true
        val component = ComponentName(context, NotifyListenerService::class.java)
        val flat = component.packageName + "/" + component.className
        Thread {
            try {
                val disallow = execRoot("cmd notification disallow_listener " + flat)
                Thread.sleep(800)
                val allow = execRoot("cmd notification allow_listener " + flat)
                Log.w(TAG, "root 强制重注册: disallow=" + disallow + " allow=" + allow + " (" + flat + ")")
            } catch (t: Throwable) {
                Log.e(TAG, "root 强制重注册失败（没有 root 或者被拒绝了）", t)
            } finally {
                running = false
            }
        }.start()
    }

    private fun execRoot(command: String): Int {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val code = process.waitFor()
        runCatching {
            process.inputStream.close()
            process.errorStream.close()
        }
        return code
    }
}
