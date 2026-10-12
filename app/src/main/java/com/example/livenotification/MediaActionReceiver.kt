package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.util.Log


class MediaActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY = "com.example.livenotification.PLAY"
        const val ACTION_PAUSE = "com.example.livenotification.PAUSE"
        const val ACTION_EXPAND_QS = "com.example.livenotification.EXPAND_QS"

        // 向后兼容：Listener 设置这个时不会丢
        @Volatile
        private var _legacyController: MediaController? = null

        /**
         * 当前 controller
         * 读：优先从 MediaSessionStore 拿（多 session 的选中项），
         *      Store 为空时 fallback 到 legacy
         * 写：写到 legacy（Listener 仍会设置它）
         */
        var currentController: MediaController?
            get() = MediaSessionStore.getCurrentController() ?: _legacyController
            set(value) {
                _legacyController = value
            }
    }

    override fun onReceive(context: Context, intent: Intent) {

        when (intent.action) {

            ACTION_PLAY -> currentController?.transportControls?.play()

            ACTION_PAUSE -> currentController?.transportControls?.pause()

            ACTION_EXPAND_QS -> {
                try {
                    Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "cmd statusbar expand-settings")
                    ).waitFor()
                    Log.d("MediaAction", "已下拉 QS 面板")
                } catch (e: Exception) {
                    Log.e("MediaAction", "下拉 QS 面板失败", e)
                }
            }
        }
    }
}