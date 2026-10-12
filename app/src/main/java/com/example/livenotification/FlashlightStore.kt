package com.example.livenotification

/**
 * 手电筒全局单例
 * - 由 FlashlightController 的状态回调写入
 * - 由 LiveService / UI 读取
 */
object FlashlightStore {

    @Volatile
    var current: FlashlightData? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    /** 更新手电筒状态 */
    fun update(data: FlashlightData?) {
        current = data
        notifyChange()
    }

    /** 手电筒当前是否开启 */
    fun isOn(): Boolean = current?.isOn == true

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