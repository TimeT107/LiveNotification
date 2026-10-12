package com.example.livenotification

import android.content.Context

class LiveNotificationStorage(context: Context) {

    private val pref = context.getSharedPreferences(
        "live_notification",
        Context.MODE_PRIVATE
    )

    companion object {
        // 公共 key（不加前缀，避免混淆）
        private const val PREFIX_TRAIN = "train_"
        private const val PREFIX_FLIGHT = "flight_"

        // 字段名
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
        private const val KEY_GATE = "gate"
        private const val KEY_TYPE = "type"
        private const val KEY_AIRLINE = "airline"
        private const val KEY_TERMINAL = "terminal"
        private const val KEY_BOARDING_GATE = "boardingGate"
        private const val KEY_CHECKIN_COUNTER = "checkinCounter"
    }

    // ==================== 保存 ====================

    /** 根据 data.type 自动决定存哪个槽位 */
    fun save(data: LiveData) {
        val prefix = if (data.type == "flight") PREFIX_FLIGHT else PREFIX_TRAIN
        saveWithPrefix(prefix, data)
    }

    /** 显式保存为火车 */
    fun saveTrain(data: LiveData) {
        saveWithPrefix(PREFIX_TRAIN, data.copy(type = "train"))
    }

    /** 显式保存为航班 */
    fun saveFlight(data: LiveData) {
        saveWithPrefix(PREFIX_FLIGHT, data.copy(type = "flight"))
    }

    private fun saveWithPrefix(prefix: String, data: LiveData) {
        pref.edit()
            .putString(prefix + KEY_TITLE, data.title)
            .putString(prefix + KEY_SUBTITLE, data.subtitle)
            .putString(prefix + KEY_STATUS, data.status)
            .putString(prefix + KEY_START_STATION, data.startStation)
            .putString(prefix + KEY_START_TIME, data.startTime)
            .putString(prefix + KEY_END_STATION, data.endStation)
            .putString(prefix + KEY_END_TIME, data.endTime)
            .putString(prefix + KEY_BOTTOM_TEXT, data.bottomText)
            .putLong(prefix + KEY_CREATE_TIME, data.createTime)
            .putLong(prefix + KEY_DEPARTURE_TIME, data.departureTime)
            .putLong(prefix + KEY_ARRIVAL_TIME, data.arrivalTime)
            .putString(prefix + KEY_DEPARTURE_DATE, data.departureDate)
            .putString(prefix + KEY_SEAT, data.seat)
            .putString(prefix + KEY_GATE, data.gate)
            .putString(prefix + KEY_TYPE, data.type)
            .putString(prefix + KEY_AIRLINE, data.airline)
            .putString(prefix + KEY_TERMINAL, data.terminal)
            .putString(prefix + KEY_BOARDING_GATE, data.boardingGate)
            .putString(prefix + KEY_CHECKIN_COUNTER, data.checkinCounter)
            .apply()
    }

    // ==================== 读取 ====================

    /** 兼容：读火车行程 */
    fun load(): LiveData? = loadTrain()

    fun loadTrain(): LiveData? = loadWithPrefix(PREFIX_TRAIN, "train")

    fun loadFlight(): LiveData? = loadWithPrefix(PREFIX_FLIGHT, "flight")

    private fun loadWithPrefix(prefix: String, defaultType: String): LiveData? {
        val title = pref.getString(prefix + KEY_TITLE, null) ?: return null
        val createTime = pref.getLong(prefix + KEY_CREATE_TIME, 0L)
        val departureTime = pref.getLong(prefix + KEY_DEPARTURE_TIME, 0L)
        val arrivalTime = pref.getLong(prefix + KEY_ARRIVAL_TIME, 0L)

        if (departureTime <= 0L || arrivalTime <= departureTime) return null

        return LiveData(
            title = title,
            subtitle = pref.getString(prefix + KEY_SUBTITLE, "") ?: "",
            status = pref.getString(prefix + KEY_STATUS, "") ?: "",
            startStation = pref.getString(prefix + KEY_START_STATION, "") ?: "",
            startTime = pref.getString(prefix + KEY_START_TIME, "") ?: "",
            endStation = pref.getString(prefix + KEY_END_STATION, "") ?: "",
            endTime = pref.getString(prefix + KEY_END_TIME, "") ?: "",
            bottomText = pref.getString(prefix + KEY_BOTTOM_TEXT, "") ?: "",
            progress = 0,
            createTime = createTime,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            remainText = "计算中",
            departureDate = pref.getString(prefix + KEY_DEPARTURE_DATE, "") ?: "",
            seat = pref.getString(prefix + KEY_SEAT, "") ?: "",
            gate = pref.getString(prefix + KEY_GATE, "") ?: "",
            type = pref.getString(prefix + KEY_TYPE, defaultType) ?: defaultType,
            airline = pref.getString(prefix + KEY_AIRLINE, "") ?: "",
            terminal = pref.getString(prefix + KEY_TERMINAL, "") ?: "",
            boardingGate = pref.getString(prefix + KEY_BOARDING_GATE, "") ?: "",
            checkinCounter = pref.getString(prefix + KEY_CHECKIN_COUNTER, "") ?: ""
        )
    }

    // ==================== 状态 ====================

    /** 兼容：有火车行程 */
    fun isActive(): Boolean = isActiveTrain()

    fun isActiveTrain(): Boolean {
        val t = pref.getLong(PREFIX_TRAIN + KEY_ARRIVAL_TIME, 0L)
        return t > 0L && System.currentTimeMillis() < t
    }

    fun isActiveFlight(): Boolean {
        val t = pref.getLong(PREFIX_FLIGHT + KEY_ARRIVAL_TIME, 0L)
        return t > 0L && System.currentTimeMillis() < t
    }

    // ==================== 清理 ====================

    /** 兼容：清火车 */
    fun clear() = clearTrain()

    fun clearTrain() {
        val editor = pref.edit()
        editor.remove(PREFIX_TRAIN + KEY_TITLE)
        editor.remove(PREFIX_TRAIN + KEY_SUBTITLE)
        editor.remove(PREFIX_TRAIN + KEY_STATUS)
        editor.remove(PREFIX_TRAIN + KEY_START_STATION)
        editor.remove(PREFIX_TRAIN + KEY_START_TIME)
        editor.remove(PREFIX_TRAIN + KEY_END_STATION)
        editor.remove(PREFIX_TRAIN + KEY_END_TIME)
        editor.remove(PREFIX_TRAIN + KEY_BOTTOM_TEXT)
        editor.remove(PREFIX_TRAIN + KEY_CREATE_TIME)
        editor.remove(PREFIX_TRAIN + KEY_DEPARTURE_TIME)
        editor.remove(PREFIX_TRAIN + KEY_ARRIVAL_TIME)
        editor.remove(PREFIX_TRAIN + KEY_DEPARTURE_DATE)
        editor.remove(PREFIX_TRAIN + KEY_SEAT)
        editor.remove(PREFIX_TRAIN + KEY_GATE)
        editor.remove(PREFIX_TRAIN + KEY_TYPE)
        editor.remove(PREFIX_TRAIN + KEY_AIRLINE)
        editor.remove(PREFIX_TRAIN + KEY_TERMINAL)
        editor.remove(PREFIX_TRAIN + KEY_BOARDING_GATE)
        editor.remove(PREFIX_TRAIN + KEY_CHECKIN_COUNTER)
        editor.apply()
    }

    fun clearFlight() {
        val editor = pref.edit()
        editor.remove(PREFIX_FLIGHT + KEY_TITLE)
        editor.remove(PREFIX_FLIGHT + KEY_SUBTITLE)
        editor.remove(PREFIX_FLIGHT + KEY_STATUS)
        editor.remove(PREFIX_FLIGHT + KEY_START_STATION)
        editor.remove(PREFIX_FLIGHT + KEY_START_TIME)
        editor.remove(PREFIX_FLIGHT + KEY_END_STATION)
        editor.remove(PREFIX_FLIGHT + KEY_END_TIME)
        editor.remove(PREFIX_FLIGHT + KEY_BOTTOM_TEXT)
        editor.remove(PREFIX_FLIGHT + KEY_CREATE_TIME)
        editor.remove(PREFIX_FLIGHT + KEY_DEPARTURE_TIME)
        editor.remove(PREFIX_FLIGHT + KEY_ARRIVAL_TIME)
        editor.remove(PREFIX_FLIGHT + KEY_DEPARTURE_DATE)
        editor.remove(PREFIX_FLIGHT + KEY_SEAT)
        editor.remove(PREFIX_FLIGHT + KEY_GATE)
        editor.remove(PREFIX_FLIGHT + KEY_TYPE)
        editor.remove(PREFIX_FLIGHT + KEY_AIRLINE)
        editor.remove(PREFIX_FLIGHT + KEY_TERMINAL)
        editor.remove(PREFIX_FLIGHT + KEY_BOARDING_GATE)
        editor.remove(PREFIX_FLIGHT + KEY_CHECKIN_COUNTER)
        editor.apply()
    }

    /** 全部清空 */
    fun clearAll() {
        pref.edit().clear().apply()
    }
}