package com.example.livenotification.bubble

import com.example.livenotification.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 后台常驻服务。
 *
 * 通知监听服务（NotifyListenerService）是被系统绑定的，一旦进程被回收，监听就断了，
 * 表现是"气泡功能时好时坏"。这里起一个前台服务把进程优先级顶到前台，
 * 相当于国产 ROM 上那种"后台挂机"保障。
 *
 * 只做保活，不处理任何通知逻辑。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "BubbleNotify"
        private const val CHANNEL_ID = "keep_alive"
        private const val NOTIFICATION_ID = 0x4B1

        /** 每 60 秒巡检一次监听服务是否还连着 */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        fun start(context: android.content.Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.w(TAG, "启动常驻服务失败", it) }
        }

        fun stop(context: android.content.Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /** 巡检监听服务是否还连着：活着但断开时也拉一把 */
    private val watchdog = object : Runnable {
        override fun run() {
            NotifyListenerService.ensureConnected(this@KeepAliveService)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground 失败", it) }
        RootHealer.probeAsync()
        handler.removeCallbacks(watchdog)
        // 启动时先补一次，别等第一个 60 秒
        handler.post(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        // 被系统杀掉后自动拉起
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        // IMPORTANCE_MIN：不响、不震、不打扰，只是让系统知道这是个前台服务
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keep_alive_channel),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.example.livenotification.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.bubble_ic_notification)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(getString(R.string.keep_alive_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
