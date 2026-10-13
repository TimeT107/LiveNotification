package com.example.livenotification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var live: LiveNotificationManager
    private lateinit var expressManager: ExpressNotificationManager
    private lateinit var musicManager: MusicNotificationManager
    private lateinit var verificationManager: VerificationNotificationManager
    private lateinit var flashlightManager: FlashlightNotificationManager
    private lateinit var storage: LiveNotificationStorage

    private var expressListener: (() -> Unit)? = null
    private var musicListener: (() -> Unit)? = null
    private var verificationListener: (() -> Unit)? = null
    private var flashlightListener: (() -> Unit)? = null

    // 微件刷新缓存（避免每秒重刷）
    private var lastMusicWidgetHash: Int = 0
    private var lastTripWidgetHash: Int = 0

    override fun onCreate() {
        super.onCreate()

        createServiceChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                2000,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(2000, createNotification())
        }

        live = LiveNotificationManager(this)
        live.createChannel()

        expressManager = ExpressNotificationManager(this)
        expressManager.createChannel()

        musicManager = MusicNotificationManager(this)
        musicManager.createChannel()

        verificationManager = VerificationNotificationManager(this)
        verificationManager.createChannel()

        flashlightManager = FlashlightNotificationManager(this)
        flashlightManager.createChannel()

        // 初始化手电筒控制器并注册回调
        FlashlightController.init(this)
        FlashlightController.registerCallback(this)

        storage = LiveNotificationStorage(this)

        ExpressStore.init(this)

        // ============ 恢复两个行程 ============
        if (NotificationStore.currentTrain == null && storage.isActiveTrain()) {
            storage.loadTrain()?.let { recovered ->
                NotificationStore.currentTrain = recovered
                live.show(recovered)
            }
        }

        if (NotificationStore.currentFlight == null && storage.isActiveFlight()) {
            storage.loadFlight()?.let { recovered ->
                NotificationStore.currentFlight = recovered
                live.show(recovered)
            }
        }

        // 首次刷新（内部会调用 updateHotZone）
        refreshAll()

        // 快递变化监听
        val eListener: () -> Unit = { refreshAll() }
        expressListener = eListener
        ExpressStore.addListener(eListener)

        // 音乐变化监听
        val mListener: () -> Unit = { refreshAll() }
        musicListener = mListener
        MusicStore.addListener(mListener)

        // 验证码变化监听
        val vListener: () -> Unit = { refreshAll() }
        verificationListener = vListener
        VerificationStore.addListener(vListener)

        // 手电筒状态监听
        val fListener: () -> Unit = { refreshAll() }
        flashlightListener = fListener
        FlashlightStore.addListener(fListener)

        // 什么都没有 → 直接退出
        if (isIdle()) {
            stopSelf()
            return
        }

        // ============ 主循环：每 30 秒刷新 ============
        scope.launch {
            while (isActive) {
                refreshAll()

                if (isIdle()) {
                    stopSelf()
                    break
                }

                delay(30_000L)
            }
        }

        // ============ 音乐通知：每秒刷新 ============
        scope.launch {
            while (isActive) {
                MusicStore.get()?.let { music ->
                    try {
                        musicManager.show(music)
                    } catch (e: Exception) {
                        Log.e("LiveService", "刷新音乐通知失败", e)
                    }
                }

                // ★ 刷新音乐微件（内部有去重）
                try {
                    refreshMusicWidget()
                } catch (_: Exception) {
                }

                delay(1_000L)
            }
        }

        // ============ 验证码过期检查：每秒一次 ============
        scope.launch {
            while (isActive) {
                if (VerificationStore.current != null &&
                    VerificationStore.isExpired()
                ) {
                    VerificationStore.clear()
                    Log.d("LiveService", "验证码已过期，自动清除")
                }
                delay(1_000L)
            }
        }
    }

    /**
     * 按优先级刷新：
     * 快递 → 音乐 → 航班 → 火车 → 验证码 → 手电筒
     * 系统 chip 显示"最后被 notify 的"，所以手电筒/验证码最后刷新就赢 chip
     */
    private fun refreshAll() {

        // ============ 1. 快递 ============
        if (ExpressStore.count() > 0) {
            expressManager.show(ExpressStore.getAll())
        } else {
            expressManager.stop()
        }

        // ============ 2. 音乐 ============
        val music = MusicStore.get()
        if (music != null) {
            musicManager.show(music)
        } else {
            musicManager.stop()
        }

        // ============ 3. 航班 ============
        refreshTrip(
            data = NotificationStore.currentFlight,
            isFlight = true
        )

        // ============ 4. 火车 ============
        refreshTrip(
            data = NotificationStore.currentTrain,
            isFlight = false
        )

        // ============ 5. 验证码 ============
        val verification = VerificationStore.get()
        if (verification != null) {
            verificationManager.show(verification)
        } else {
            verificationManager.stop()
        }

        // ============ 6. 手电筒 ============
        val flashPref = FlashlightPreference.isEnabled(this)
        val flashlight = FlashlightStore.current
        if (flashPref && flashlight?.isOn == true) {
            flashlightManager.show(flashlight)
        } else {
            flashlightManager.stop()
        }
        // ★ 更新热区（根据音乐状态）
        updateHotZone()

        // ★ 刷新手电筒/行程微件
        refreshTripWidget()
    }

    /**
     * 刷新行程微件（只在关键数据变化时）
     */
    private fun refreshTripWidget() {
        val data = NotificationStore.currentTrain
            ?: NotificationStore.currentFlight

        val hash = if (data == null) {
            -1
        } else {
            "${data.title}|${data.progress}|${data.startTime}|${data.endTime}"
                .hashCode()
        }

        if (hash != lastTripWidgetHash) {
            lastTripWidgetHash = hash
            TripWidgetProvider.updateAll(this)
        }
    }

    /**
     * 刷新音乐微件（只在关键数据变化时）
     */
    private fun refreshMusicWidget() {
        val entry = MediaSessionStore.getCurrent()
        val fallback = MusicStore.get()

        val title = entry?.title ?: fallback?.title ?: ""
        val artist = entry?.artist ?: fallback?.artist ?: ""
        val isPlaying = entry?.isPlaying ?: fallback?.isPlaying ?: false

        val hash = "$title|$artist|$isPlaying".hashCode()
        if (hash != lastMusicWidgetHash) {
            lastMusicWidgetHash = hash
            MusicWidgetProvider.updateAll(this)
        }
    }

    /**
     * 刷新单个行程
     */
    private fun refreshTrip(data: LiveData?, isFlight: Boolean) {

        if (data == null) {
            if (isFlight) live.stopFlight() else live.stopTrain()
            return
        }

        val progress = CountdownManager.calcProgress(
            data.createTime,
            data.departureTime,
            data.arrivalTime
        )
        val refreshed = data.copy(progress = progress)
        live.update(refreshed)

        if (System.currentTimeMillis() >= data.arrivalTime && progress >= 100) {

            if (!isFlight) {
                CompletedTripStore.markCompleted(refreshed)
                NotificationStore.currentTrain = null
                storage.clearTrain()
                live.stopTrain()
            } else {
                NotificationStore.currentFlight = null
                storage.clearFlight()
                live.stopFlight()
            }
        }
    }

    /**
     * 根据音乐状态决定热区是否显示
     */
    private fun updateHotZone() {
        val hasMusic = MusicStore.get() != null
        val hotZoneEnabled = HotZonePreference.isEnabled(this)

        if (hasMusic && hotZoneEnabled && FloatingHotZone.canDrawOverlays(this)) {
            FloatingHotZone.show(this)
        } else {
            FloatingHotZone.hide(this)
        }
    }

    /**
     * 判断是否该退出
     * ★ 手电筒控件开启时，Service 常驻（保持 TorchCallback 注册）
     */
    private fun isIdle(): Boolean {
        // 手电筒功能开启 → Service 不退出
        if (FlashlightPreference.isEnabled(this)) return false

        return NotificationStore.currentTrain == null &&
                NotificationStore.currentFlight == null &&
                ExpressStore.count() == 0 &&
                MusicStore.get() == null &&
                VerificationStore.get() == null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        expressListener?.let { ExpressStore.removeListener(it) }
        musicListener?.let { MusicStore.removeListener(it) }
        verificationListener?.let { VerificationStore.removeListener(it) }
        flashlightListener?.let { FlashlightStore.removeListener(it) }
        expressListener = null
        musicListener = null
        verificationListener = null
        flashlightListener = null

        // 注销手电筒回调
        FlashlightController.unregisterCallback()

        // 清理热区
        FloatingHotZone.hide(this)

        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 前台服务渠道 ====================

    private fun createServiceChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            "service",
            "后台服务",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("实时通知服务运行中")
            .setContentText("正在管理实时通知")
            .build()
    }
}