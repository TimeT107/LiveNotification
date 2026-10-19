package com.example.livenotification.island.hook

import com.example.livenotification.island.util.IslandLog

/**
 * 控制器专用日志包装：额外带一个「模块名」前缀与运行时开关，
 * 避免每处都拼字符串。
 */
class IslandLogger(private val tag: String) {

    @Volatile
    var verbose: Boolean = true

    fun i(message: String) {
        if (verbose) IslandLog.i("[$tag] $message")
    }

    fun w(message: String) = IslandLog.w("[$tag] $message")

    fun e(message: String) = IslandLog.e("[$tag] $message")

    fun e(message: String, t: Throwable) = IslandLog.e("[$tag] $message", t)

    /** 关键节点：无论详细日志是否开启都会写 */
    fun mark(message: String) = IslandLog.mark("[$tag] $message")
}
