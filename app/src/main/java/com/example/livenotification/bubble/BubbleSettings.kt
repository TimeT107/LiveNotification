package com.example.livenotification.bubble

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * 气泡的可调项 + 接管白名单。
 *
 * 白名单之前是写死的微信/QQ，现在由用户自己勾选；默认仍然是微信 + QQ，
 * 保证装完就能用。
 */
object BubbleSettings {

    private const val PREFS = "bubble_settings"
    private const val KEY_AUTO_EXPAND = "auto_expand"
    private const val KEY_HEADS_UP = "heads_up"
    private const val KEY_DEDUPE_HEADS_UP = "dedupe_heads_up"
    private const val KEY_INTERCEPT_SOURCE = "intercept_source"
    private const val KEY_EXPAND_SOURCE_APP = "expand_source_app"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_WHITELIST = "whitelist"

    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val WX_PACKAGE = "com.tencent.mm"

    /** 默认接管这两个，装完即用 */
    val DEFAULT_WHITELIST = setOf(WX_PACKAGE, QQ_PACKAGE)

    const val DEFAULT_AUTO_EXPAND = false
    const val DEFAULT_HEADS_UP = true
    const val DEFAULT_DEDUPE_HEADS_UP = true
    const val DEFAULT_INTERCEPT_SOURCE = true
    const val DEFAULT_EXPAND_SOURCE_APP = true
    const val DEFAULT_KEEP_ALIVE = false

    fun autoExpand(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_EXPAND, DEFAULT_AUTO_EXPAND)

    fun setAutoExpand(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_EXPAND, value) }
    }

    fun headsUp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEADS_UP, DEFAULT_HEADS_UP)

    fun setHeadsUp(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_HEADS_UP, value) }
    }

    fun dedupeHeadsUp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEDUPE_HEADS_UP, DEFAULT_DEDUPE_HEADS_UP)

    fun setDedupeHeadsUp(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_DEDUPE_HEADS_UP, value) }
    }

    fun interceptSource(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INTERCEPT_SOURCE, DEFAULT_INTERCEPT_SOURCE)

    fun setInterceptSource(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_INTERCEPT_SOURCE, value) }
    }

    fun expandSourceApp(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXPAND_SOURCE_APP, DEFAULT_EXPAND_SOURCE_APP)

    fun setExpandSourceApp(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_EXPAND_SOURCE_APP, value) }
    }

    /** 前台服务常驻：让监听服务不会被系统回收 */
    fun keepAlive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, DEFAULT_KEEP_ALIVE)

    fun setKeepAlive(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_KEEP_ALIVE, value) }
    }

    // ------------------------------------------------------------------
    // 白名单
    // ------------------------------------------------------------------

    fun whitelist(context: Context): Set<String> {
        val saved = prefs(context).getStringSet(KEY_WHITELIST, null) ?: return DEFAULT_WHITELIST
        // 存的是个不可变副本，读出来也要复制，别让调用方改坏 SharedPreferences
        return HashSet(saved)
    }

    fun setWhitelist(context: Context, packages: Set<String>) {
        prefs(context).edit { putStringSet(KEY_WHITELIST, HashSet(packages)) }
    }

    fun isWhitelisted(context: Context, packageName: String): Boolean =
        whitelist(context).contains(packageName)

    // ------------------------------------------------------------------

    /**
     * 气泡当前是否被允许。厂商 ROM（ColorOS 等）默认关掉第三方应用气泡，
     * 这时不管怎么发通知都不会有气泡，所以主界面要能显示状态并给出入口。
     */
    fun bubblesAllowed(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val channelAllows =
            nm.getNotificationChannel(NotifyListenerService.CHANNEL_ID)?.canBubble() ?: true
        val appAllows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            nm.areBubblesEnabled()
        } else {
            @Suppress("DEPRECATION")
            nm.areBubblesAllowed()
        }
        return channelAllows && appAllows
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
