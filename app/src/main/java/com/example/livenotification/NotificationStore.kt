package com.example.livenotification

/**
 * 全局单例：当前进行中的行程
 * - currentTrain：高铁/火车行程
 * - currentFlight：航班行程
 * - current：兼容旧代码，等价于 currentTrain
 */
object NotificationStore {

    @Volatile
    var currentTrain: LiveData? = null

    @Volatile
    var currentFlight: LiveData? = null

    // ★ 兼容旧代码：current 指向 currentTrain
    var current: LiveData?
        get() = currentTrain
        set(value) {
            currentTrain = value
        }

    /** 是否有任意行程在进行 */
    val hasAny: Boolean
        get() = currentTrain != null || currentFlight != null
}