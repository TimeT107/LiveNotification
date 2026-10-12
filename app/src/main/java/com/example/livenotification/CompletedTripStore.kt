package com.example.livenotification

/**
 * 保存最近完成的行程
 * 到站后由 LiveService 写入，HomeScreen 读取
 * 保留 24 小时后自动失效
 */
object CompletedTripStore {

    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var completed: LiveData? = null

    @Volatile
    private var completedAt: Long = 0L

    fun markCompleted(data: LiveData) {
        completed = data
        completedAt = System.currentTimeMillis()
    }

    /** 获取已完成行程（超过 24 小时自动清空） */
    fun get(): LiveData? {
        val data = completed ?: return null
        if (System.currentTimeMillis() - completedAt > KEEP_MS) {
            clear()
            return null
        }
        return data
    }

    /** 是否为"已完成"状态 */
    fun isCompleted(): Boolean = get() != null

    fun clear() {
        completed = null
        completedAt = 0L
    }
}