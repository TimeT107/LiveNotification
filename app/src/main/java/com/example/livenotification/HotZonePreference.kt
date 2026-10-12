package com.example.livenotification

import android.content.Context

/**
 * 热区快捷控件的开关偏好
 * 默认开启
 */
object HotZonePreference {

    private const val PREF_NAME = "hot_zone_pref"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}