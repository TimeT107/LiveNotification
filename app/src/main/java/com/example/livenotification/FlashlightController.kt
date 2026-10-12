package com.example.livenotification

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log


object FlashlightController {

    private const val TAG = "FlashlightCtrl"

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var maxStrengthLevel: Int = 1

    private var callbackRegistered = false

    /**
     * ★ 用户手动设置的亮度等级缓存
     * 用途：turnOnTorchWithStrengthLevel 触发 onTorchModeChanged 时，
     *      系统的 getTorchStrengthLevel() 可能返回错误值（默认最大值），
     *      用缓存值覆盖，避免"拖完又跳回最大"的问题。
     */
    @Volatile
    private var userSetLevel: Int = 0

    val maxLevel: Int
        get() = maxStrengthLevel

    val supportsBrightness: Boolean
        get() = maxStrengthLevel > 1

    // ==================== 初始化 ====================

    fun init(context: Context) {
        if (cameraManager != null) return

        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE)
                    as? CameraManager ?: return
            cameraManager = cm

            val ids = cm.cameraIdList
            for (id in ids) {
                val characteristics = cm.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) ?: false

                if (flashAvailable) {
                    torchCameraId = id

                    if (Build.VERSION.SDK_INT >= 33) {
                        val max = characteristics.get(
                            CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL
                        )
                        if (max != null && max > 0) {
                            maxStrengthLevel = max
                        }
                    }
                    break
                }
            }

            Log.d(TAG, "init: cameraId=$torchCameraId, maxLevel=$maxStrengthLevel")

        } catch (e: Exception) {
            Log.e(TAG, "init 失败", e)
        }
    }

    // ==================== 状态回调 ====================

    fun registerCallback(context: Context) {
        if (callbackRegistered) return
        val cm = cameraManager ?: return

        try {
            cm.registerTorchCallback(torchCallback, null)
            callbackRegistered = true
            Log.d(TAG, "TorchCallback 已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册回调失败", e)
        }
    }

    fun unregisterCallback() {
        if (!callbackRegistered) return
        val cm = cameraManager ?: return

        try {
            cm.unregisterTorchCallback(torchCallback)
            callbackRegistered = false
        } catch (e: Exception) {
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {

        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchCameraId) return

            val level = if (enabled) {
                // ★ 优先用用户设置值；没设置过才读系统
                if (userSetLevel > 0) {
                    userSetLevel
                } else {
                    currentStrengthLevel().also {
                        userSetLevel = it
                    }
                }
            } else {
                // 关闭时清空缓存
                userSetLevel = 0
                0
            }

            FlashlightStore.update(
                FlashlightData(
                    isOn = enabled,
                    currentLevel = level,
                    maxLevel = maxStrengthLevel
                )
            )
            Log.d(TAG, "状态变化: enabled=$enabled, level=$level")
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != torchCameraId) return

            userSetLevel = 0
            FlashlightStore.update(
                FlashlightData(
                    isOn = false,
                    currentLevel = 0,
                    maxLevel = maxStrengthLevel
                )
            )
            Log.d(TAG, "手电筒不可用")
        }

        override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
            if (cameraId != torchCameraId) return

            // ★ 系统报告了新的等级（来自我们或系统设置），更新缓存
            userSetLevel = newStrengthLevel

            val current = FlashlightStore.current ?: return
            if (!current.isOn) return

            FlashlightStore.update(
                current.copy(currentLevel = newStrengthLevel)
            )
            Log.d(TAG, "亮度变化: $newStrengthLevel")
        }
    }

    // ==================== 控制 ====================

    fun setTorch(on: Boolean) {
        val cm = cameraManager ?: return
        val id = torchCameraId ?: return

        try {
            if (!on) {
                userSetLevel = 0
            }
            cm.setTorchMode(id, on)
        } catch (e: Exception) {
            Log.e(TAG, "setTorch 失败", e)
        }
    }

    fun setBrightness(level: Int) {
        if (Build.VERSION.SDK_INT < 33) return
        if (!supportsBrightness) return

        val cm = cameraManager ?: return
        val id = torchCameraId ?: return

        val clamped = level.coerceIn(1, maxStrengthLevel)

        // ★ 先更新缓存，再调用系统 API
        userSetLevel = clamped

        try {
            cm.turnOnTorchWithStrengthLevel(id, clamped)
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness 失败", e)
        }
    }

    private fun currentStrengthLevel(): Int {
        if (Build.VERSION.SDK_INT < 33) return 1
        if (!supportsBrightness) return 1

        val cm = cameraManager ?: return 1
        val id = torchCameraId ?: return 1

        return try {
            cm.getTorchStrengthLevel(id)
        } catch (e: Exception) {
            1
        }
    }
}