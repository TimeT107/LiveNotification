package com.example.livenotification

/**
 * 验证码全局单例
 * - 内存持有当前验证码
 * - 2 分钟自动过期
 */
object VerificationStore {

    private const val EXPIRE_MS = 2 * 60 * 1000L

    @Volatile
    var current: VerificationData? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** 检查当前是否有效（未过期） */
    fun get(): VerificationData? {
        val data = current ?: return null
        if (System.currentTimeMillis() - data.createTime > EXPIRE_MS) {
            clear()
            return null
        }
        return data
    }

    /** 存入新验证码 */
    fun set(data: VerificationData) {
        current = data
        notifyChange()
    }

    /** 清空（已读 / 过期时调用） */
    fun clear() {
        if (current == null) return
        current = null
        notifyChange()
    }

    /** 立即判断是否过期（不触发 clear） */
    fun isExpired(): Boolean {
        val data = current ?: return true
        return System.currentTimeMillis() - data.createTime > EXPIRE_MS
    }

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