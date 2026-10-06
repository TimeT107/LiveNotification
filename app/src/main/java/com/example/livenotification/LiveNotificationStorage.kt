package com.example.livenotification

import android.content.Context

class LiveNotificationStorage(context: Context) {

    private val pref = context.getSharedPreferences(
        "live_notification",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_STATUS = "status"
        private const val KEY_START_STATION = "startStation"
        private const val KEY_START_TIME = "startTime"
        private const val KEY_END_STATION = "endStation"
        private const val KEY_END_TIME = "endTime"
        private const val KEY_BOTTOM_TEXT = "bottomText"
        private const val KEY_CREATE_TIME = "createTime"
        private const val KEY_DEPARTURE_TIME = "departureTime"
        private const val KEY_ARRIVAL_TIME = "arrivalTime"
        private const val KEY_DEPARTURE_DATE = "departureDate"
        private const val KEY_SEAT = "seat"
    }

    /** 保存一份完整的 LiveData（用于重启后恢复） */
    fun save(data: LiveData) {
        pref.edit()
            .putString(KEY_TITLE, data.title)
            .putString(KEY_SUBTITLE, data.subtitle)
            .putString(KEY_STATUS, data.status)
            .putString(KEY_START_STATION, data.startStation)
            .putString(KEY_START_TIME, data.startTime)
            .putString(KEY_END_STATION, data.endStation)
            .putString(KEY_END_TIME, data.endTime)
            .putString(KEY_BOTTOM_TEXT, data.bottomText)
            .putLong(KEY_CREATE_TIME, data.createTime)
            .putLong(KEY_DEPARTURE_TIME, data.departureTime)
            .putLong(KEY_ARRIVAL_TIME, data.arrivalTime)
            .putString(KEY_DEPARTURE_DATE, data.departureDate)
            .putString(KEY_SEAT, data.seat)
            .apply()
    }

    /** 读取上次保存的 LiveData；若没有或数据无效，返回 null */
    fun load(): LiveData? {
        val title = pref.getString(KEY_TITLE, null) ?: return null
        val createTime = pref.getLong(KEY_CREATE_TIME, 0L)
        val departureTime = pref.getLong(KEY_DEPARTURE_TIME, 0L)
        val arrivalTime = pref.getLong(KEY_ARRIVAL_TIME, 0L)

        if (departureTime <= 0L || arrivalTime <= departureTime) return null

        return LiveData(
            title = title,
            subtitle = pref.getString(KEY_SUBTITLE, "") ?: "",
            status = pref.getString(KEY_STATUS, "") ?: "",
            startStation = pref.getString(KEY_START_STATION, "") ?: "",
            startTime = pref.getString(KEY_START_TIME, "") ?: "",
            endStation = pref.getString(KEY_END_STATION, "") ?: "",
            endTime = pref.getString(KEY_END_TIME, "") ?: "",
            bottomText = pref.getString(KEY_BOTTOM_TEXT, "") ?: "",
            progress = 0,
            createTime = createTime,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            remainText = "计算中",
            departureDate = pref.getString(KEY_DEPARTURE_DATE, "") ?: "",
            seat = pref.getString(KEY_SEAT, "") ?: ""
        )
    }

    /** 是否有一个尚未到达的行程（arrivalTime 在未来） */
    fun isActive(): Boolean {
        val arrivalTime = pref.getLong(KEY_ARRIVAL_TIME, 0L)
        return arrivalTime > 0L && System.currentTimeMillis() < arrivalTime
    }

    fun clear() {
        pref.edit().clear().apply()
    }
}