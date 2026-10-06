package com.example.livenotification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    private lateinit var storage: LiveNotificationStorage

    // 保存 listener 引用，便于 onDestroy 时移除
    private var expressListener: (() -> Unit)? = null

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

        storage = LiveNotificationStorage(this)

        // 确保 ExpressStore 已初始化
        ExpressStore.init(this)

        // ============ 恢复列车行程 ============
        if (NotificationStore.current == null && storage.isActive()) {
            storage.load()?.let { recovered ->
                NotificationStore.current = recovered
                live.show(recovered)
            }
        }

        // ============ 首次刷新快递通知 ============
        expressManager.show(ExpressStore.getAll())

        // ============ 监听快递数据变化 ============
        val listener: () -> Unit = {
            expressManager.show(ExpressStore.getAll())
        }
        expressListener = listener
        ExpressStore.addListener(listener)

        // ============ 既无列车又无快递 → 直接退出 ============
        if (NotificationStore.current == null && ExpressStore.count() == 0) {
            stopSelf()
            return
        }

        // ============ 定时循环 ============
        scope.launch {
            while (isActive) {

                // ---- 列车通知刷新 ----
                val data = NotificationStore.current
                if (data != null) {
                    val progress = CountdownManager.calcProgress(
                        data.createTime,
                        data.departureTime,
                        data.arrivalTime
                    )
                    val refreshed = data.copy(progress = progress)
                    live.update(refreshed)

                    // 到站收尾（但不 stopSelf，可能还有快递）
                    if (System.currentTimeMillis() >= data.arrivalTime && progress >= 100) {
                        NotificationStore.current = null
                        storage.clear()
                        live.stop()
                    }
                }

                // ---- 快递通知刷新（和列车同步节奏） ----
                if (ExpressStore.count() > 0) {
                    expressManager.show(ExpressStore.getAll())
                }

                // ---- 检查是否该退出 ----
                if (NotificationStore.current == null && ExpressStore.count() == 0) {
                    stopSelf()
                    break
                }

                delay(30_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        // 移除快递监听
        expressListener?.let { ExpressStore.removeListener(it) }
        expressListener = null

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