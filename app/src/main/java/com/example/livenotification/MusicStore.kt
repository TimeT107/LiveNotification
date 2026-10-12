package com.example.livenotification

/**
 * 当前音乐播放状态的全局单例
 * - current：当前播放中（暂停超过 5 分钟视为无效）
 * - lastPlayed：最后播放过的媒体，不受 5 分钟限制
 */
object MusicStore {

    private const val PAUSE_KEEP_MS = 5 * 60 * 1000L

    @Volatile
    private var current: MusicData? = null

    // ★ 新增：最后播放过的媒体（不随时间失效）
    @Volatile
    private var lastPlayed: MusicData? = null

    private val listeners = mutableListOf<() -> Unit>()

    /** 更新音乐信息（播放或暂停时都会调用） */
    fun update(data: MusicData) {
        current = data
        lastPlayed = data          // ★ 每次更新都记录
        notifyChange()
    }

    /** 手动清除音乐状态（例如 App 被卸载或主动停止） */
    fun clear() {
        current = null
        notifyChange()
    }

    /**
     * 获取当前音乐信息
     * 如果暂停超过 5 分钟，返回 null 并自动清除
     */
    fun get(): MusicData? {
        val data = current ?: return null

        if (!data.isPlaying && data.lastPauseTime > 0) {
            val elapsed = System.currentTimeMillis() - data.lastPauseTime
            if (elapsed > PAUSE_KEEP_MS) {
                clear()
                return null
            }
        }

        return data
    }

    /**
     * ★ 新增：获取最后播放过的媒体
     * 不受 5 分钟限制，只要播放过就一直保留
     * 首页音乐卡片用它做兜底
     */
    fun getLastPlayed(): MusicData? = lastPlayed

    /** 是否有正在播放的音乐（严格判定，不含暂停中） */
    fun isPlaying(): Boolean = current?.isPlaying == true

    // ============== 监听器 ==============

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChange() {
        listeners.toList().forEach { it() }
    }
}