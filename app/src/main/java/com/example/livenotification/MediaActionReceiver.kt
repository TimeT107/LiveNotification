package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.util.Log


class MediaActionReceiver : BroadcastReceiver() {

    companion object {
        // ==== 通知按钮 ====
        const val ACTION_PLAY = "com.example.livenotification.PLAY"
        const val ACTION_PAUSE = "com.example.livenotification.PAUSE"
        const val ACTION_EXPAND_QS = "com.example.livenotification.EXPAND_QS"

        // ==== 微件按钮 ====
        const val ACTION_WIDGET_PREV = "com.example.livenotification.WIDGET_PREV"
        const val ACTION_WIDGET_TOGGLE = "com.example.livenotification.WIDGET_TOGGLE"
        const val ACTION_WIDGET_NEXT = "com.example.livenotification.WIDGET_NEXT"
        const val ACTION_WIDGET_OPEN = "com.example.livenotification.WIDGET_OPEN"

        const val ACTION_WIDGET_TRIP_OPEN = "com.example.livenotification.WIDGET_TRIP_OPEN"

        @Volatile
        private var _legacyController: MediaController? = null

        var currentController: MediaController?
            get() = MediaSessionStore.getCurrentController() ?: _legacyController
            set(value) {
                _legacyController = value
            }
    }

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {

            // ==== 通知按钮 ====
            ACTION_PLAY -> currentController?.transportControls?.play()

            ACTION_PAUSE -> currentController?.transportControls?.pause()

            ACTION_EXPAND_QS -> {
                try {
                    Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "cmd statusbar expand-settings")
                    ).waitFor()
                } catch (e: Exception) {
                    Log.e("MediaAction", "下拉 QS 面板失败", e)
                }
            }

            // ==== 微件按钮 ====
            ACTION_WIDGET_PREV -> {
                currentController?.transportControls?.skipToPrevious()
                MusicWidgetProvider.updateAll(context)
            }

            ACTION_WIDGET_NEXT -> {
                currentController?.transportControls?.skipToNext()
                MusicWidgetProvider.updateAll(context)
            }

            ACTION_WIDGET_TOGGLE -> {
                val controller = currentController
                val isPlaying = controller?.playbackState?.state ==
                        android.media.session.PlaybackState.STATE_PLAYING

                if (isPlaying) {
                    controller?.transportControls?.pause()
                } else {
                    controller?.transportControls?.play()
                }
                MusicWidgetProvider.updateAll(context)
            }

            ACTION_WIDGET_OPEN -> {
                try {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra(MainActivity.EXTRA_OPEN_MEDIA_CONTROL, true)
                    }
                    context.startActivity(openIntent)
                } catch (e: Exception) {
                    Log.e("MediaAction", "打开媒体控件失败", e)
                }
            }

            ACTION_WIDGET_TRIP_OPEN -> {
                try {
                    val openIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                    context.startActivity(openIntent)
                } catch (e: Exception) {
                    Log.e("MediaAction", "打开首页失败", e)
                }
            }
        }
    }
}