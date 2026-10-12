package com.example.livenotification

import android.content.Context

/**
 * 手电筒 chip 功能的开关偏好
 * 默认关闭（用户需主动开启）
 */
object FlashlightPreference {

    private const val PREF_NAME = "flashlight_pref"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}