package com.example.livenotification

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.annotation.DrawableRes

/**
 * 音频输出设备管理（数据层）
 * - 读取可用输出设备
 * - 猜测当前活跃设备
 * - 读写音量
 * - 设备类型 → 中文名 / 图标
 */
object AudioDeviceManager {

    private fun getAudioManager(context: Context): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ==================== 设备 ====================

    /** 所有可用的输出设备 */
    /** 只显示这些类型的输出设备（耳机 / 扬声器 / 听筒 / 助听器） */
    private val ALLOWED_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,      // 本机扬声器
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,     // 听筒
        AudioDeviceInfo.TYPE_WIRED_HEADSET,        // 有线耳机（带麦）
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,     // 有线耳机
        AudioDeviceInfo.TYPE_USB_HEADSET,          // USB 耳机
        AudioDeviceInfo.TYPE_USB_DEVICE,           // USB 音频设备
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,       // 蓝牙音频（耳机/音箱）
        AudioDeviceInfo.TYPE_BLE_HEADSET,          // 蓝牙 LE 耳机
        AudioDeviceInfo.TYPE_BLE_SPEAKER,          // 蓝牙 LE 音箱
        AudioDeviceInfo.TYPE_HEARING_AID           // 助听器
    )

    /** 所有可用的输出设备（已过滤） */
    fun getOutputDevices(context: Context): List<AudioDeviceInfo> {
        return try {
            getAudioManager(context)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in ALLOWED_OUTPUT_TYPES }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 猜测"当前活跃"的输出设备
     * Android 没有直接 API，用优先级启发式判断：
     * 蓝牙 > USB > 有线 > 扬声器
     */
    fun getActiveDevice(context: Context): AudioDeviceInfo? {
        val devices = getOutputDevices(context)

        return devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        } ?: devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
    }

    /** 设备显示名（优先用 productName，为空时用类型中文名） */
    fun getDeviceName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.trim()
        return if (!productName.isNullOrBlank()) {
            productName
        } else {
            getDeviceTypeName(device.type)
        }
    }

    /** 设备类型 → 中文名 */
    fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本机扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙音频"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 耳机"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "蓝牙 LE 音箱"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "蓝牙 LE 广播"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
            AudioDeviceInfo.TYPE_HEARING_AID -> "助听器"
            else -> "音频设备"
        }
    }

    /** 设备类型 → 图标资源（用系统内置图标，避免引入新资源） */
    @DrawableRes
    fun getDeviceIconRes(type: Int): Int {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST ->
                android.R.drawable.stat_sys_data_bluetooth

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                android.R.drawable.ic_lock_silent_mode_off

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET ->
                android.R.drawable.ic_menu_upload

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                android.R.drawable.ic_lock_silent_mode_off

            else ->
                android.R.drawable.ic_lock_silent_mode_off
        }
    }

    // ==================== 音量 ====================

    /** 当前媒体音量（0 ~ max） */
    fun getStreamVolume(context: Context): Int {
        return try {
            getAudioManager(context).getStreamVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            0
        }
    }

    /** 媒体音量最大值 */
    fun getStreamMaxVolume(context: Context): Int {
        return try {
            getAudioManager(context).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
            15
        }
    }

    /** 设置媒体音量 */
    fun setStreamVolume(context: Context, volume: Int) {
        try {
            val am = getAudioManager(context)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val clamped = volume.coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        } catch (_: Exception) {
        }
    }
}