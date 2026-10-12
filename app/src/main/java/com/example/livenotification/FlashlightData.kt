package com.example.livenotification

data class FlashlightData(
    /** 手电筒当前是否开启 */
    val isOn: Boolean,

    /** 当前亮度等级（1 起，不含 0；关闭时为 0） */
    val currentLevel: Int,

    /** 最大亮度等级（设备支持时 > 1） */
    val maxLevel: Int
) {
    /** 设备是否支持亮度调节 */
    val supportsBrightness: Boolean
        get() = maxLevel > 1
}