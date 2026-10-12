package com.example.livenotification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 监听系统媒体通知，提取当前播放的歌曲信息
 * 需要用户手动开启"通知使用权"
 */
class MusicNotificationListener : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        // ★ 首次刷新多 session 列表
        MediaSessionStore.refresh(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        // ★ ① 快递识别
        ExpressNotificationHandler.handle(this, sbn)

        // ★ ② 验证码识别
        VerificationNotificationHandler.handle(this, sbn)

        // ③ 以下为音乐处理
        if (sbn.notification.category != Notification.CATEGORY_TRANSPORT) return

        // ★ ① 快递识别（处理所有通知，无需 category 过滤）
        ExpressNotificationHandler.handle(this, sbn)

        // ★ ② 以下为音乐通知处理
        // 只处理媒体通知
        if (sbn.notification.category != Notification.CATEGORY_TRANSPORT) return

        // 过滤掉自己的通知，防止自监听
        if (sbn.packageName == packageName) return

        try {
            val componentName = ComponentName(this, MusicNotificationListener::class.java)
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            val controller = controllers.find { it.packageName == sbn.packageName }

            if (controller != null) {
                extractFromSession(controller, sbn.notification)
            } else {
                extractFromNotification(sbn.notification)
            }
        } catch (e: Exception) {
            Log.e("MusicListener", "解析音乐通知失败", e)
        }

        // 刷新多 session 列表
        MediaSessionStore.refresh(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) {
            MusicStore.clear()
            // ★ 刷新多 session 列表
            MediaSessionStore.refresh(this)
        }
    }

    private fun extractFromSession(
        controller: MediaController,
        notification: Notification
    ) {

        val metadata = controller.metadata ?: return
        val playbackState = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        var albumArt: Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        // fallback：MediaSession 没有就从通知的 EXTRA_LARGE_ICON 拿
        if (albumArt == null) {
            albumArt = notification.extras.getParcelable<Bitmap>(
                Notification.EXTRA_LARGE_ICON
            )
        }

        val isPlaying = playbackState?.state ==
                android.media.session.PlaybackState.STATE_PLAYING

        // 保存 controller 供 MediaActionReceiver 用
        MediaActionReceiver.currentController = controller

        pushToStore(
            title = title,
            artist = artist,
            albumArt = albumArt,
            packageName = controller.packageName,
            isPlaying = isPlaying,
            sessionToken = controller.sessionToken
        )
    }

    private fun extractFromNotification(notification: Notification) {

        val extras = notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val largeIcon = extras.getParcelable<Bitmap>(
            Notification.EXTRA_LARGE_ICON
        )

        pushToStore(
            title = title,
            artist = text,
            albumArt = largeIcon,
            packageName = "",
            isPlaying = true,
            sessionToken = null
        )
    }

    private fun pushToStore(
        title: String,
        artist: String,
        albumArt: Bitmap?,
        packageName: String,
        isPlaying: Boolean,
        sessionToken: android.media.session.MediaSession.Token?
    ) {

        val previous = MusicStore.get()

        val isSameSong = previous?.title == title && previous.artist == artist

        if (isSameSong && previous?.isPlaying == isPlaying) {
            return
        }

        val lastPauseTime = if (!isPlaying && previous?.isPlaying == true) {
            System.currentTimeMillis()
        } else if (!isPlaying && previous != null) {
            previous.lastPauseTime
        } else {
            0L
        }

        MusicStore.update(
            MusicData(
                title = title,
                artist = artist,
                albumArt = albumArt,
                packageName = packageName,
                isPlaying = isPlaying,
                sessionToken = sessionToken,
                lastPauseTime = lastPauseTime
            )
        )

        try {
            val svcIntent = android.content.Intent(this, LiveService::class.java)
            startForegroundService(svcIntent)
        } catch (e: Exception) {
            Log.e("MusicListener", "启动 LiveService 失败", e)
        }

        Log.d("MusicListener", "音乐更新: $title - $artist (playing=$isPlaying)")
    }
}