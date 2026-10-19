package com.example.livenotification.island.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 本进程内的配置读写入口（设置界面用）。
 *
 * ============ 关于跨进程读取 ============
 *
 * 这里**只用 MODE_PRIVATE**，不再尝试 `createPackageContext` +
 * `MODE_WORLD_READABLE`：后者从 Android 7 起就被系统禁用，
 * 保留它只会让"配置到底能不能跨进程读到"变成不确定的事。
 *
 * SystemUI 进程读配置走的是**确定性**通道，优先级如下：
 *   1. 设置界面广播推送的完整快照（IslandConfig.ACTION_CONFIG_SNAPSHOT）
 *   2. 设置界面写在外部存储的配置文件（IslandConfig.SNAPSHOT_RELATIVE_PATHS）
 *   3. 内置默认值
 *
 * 见 IslandController.applyConfigSnapshot / readConfig。
 */
object ConfigStore {

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var cachedName: String? = null

    fun get(context: Context): SharedPreferences? {
        val name = IslandConfig.PREFS_NAME
        cached?.let { if (cachedName == name) return it }

        synchronized(this) {
            cached?.let { if (cachedName == name) return it }
            return try {
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                cached = sp
                cachedName = name
                sp
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun pref(context: Context): SharedPreferences? = get(context)

    // ---------------- 读取（带默认值，永不抛异常） ----------------

    fun getBoolean(context: Context, key: String, def: Boolean): Boolean =
        try {
            get(context)?.getBoolean(key, def) ?: def
        } catch (_: Throwable) {
            def
        }

    fun getInt(context: Context, key: String, def: Int): Int =
        try {
            get(context)?.getInt(key, def) ?: def
        } catch (_: Throwable) {
            def
        }

    fun getString(context: Context, key: String, def: String?): String? =
        try {
            get(context)?.getString(key, def) ?: def
        } catch (_: Throwable) {
            def
        }

    // ---------------- 写入 ----------------

    fun putBoolean(context: Context, key: String, value: Boolean) {
        try {
            get(context)?.edit()?.putBoolean(key, value)?.apply()
        } catch (_: Throwable) {
        }
    }

    fun putInt(context: Context, key: String, value: Int) {
        try {
            get(context)?.edit()?.putInt(key, value)?.apply()
        } catch (_: Throwable) {
        }
    }

    fun putString(context: Context, key: String, value: String) {
        try {
            get(context)?.edit()?.putString(key, value)?.apply()
        } catch (_: Throwable) {
        }
    }
}
