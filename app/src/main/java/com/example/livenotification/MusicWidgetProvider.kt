package com.example.livenotification

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews


class MusicWidgetProvider : AppWidgetProvider() {

    companion object {

        private const val COVER_MAX_SIZE = 500

        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, MusicWidgetProvider::class.java)
                )
                ids.forEach { id ->
                    updateWidget(context, manager, id)
                }
            } catch (_: Exception) {
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_music)

            // ============ 数据 ============
            val entry = MediaSessionStore.getCurrent()
            val fallback = MusicStore.get()

            val title = entry?.title ?: fallback?.title ?: "暂无播放"
            val artist = entry?.artist ?: fallback?.artist ?: "点击播放音乐"
            val albumArt = entry?.albumArt ?: fallback?.albumArt

            // ============ 文本 ============
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)

            // ============ 封面 ============
            if (albumArt != null) {
                try {
                    views.setImageViewBitmap(
                        R.id.widget_cover,
                        scaleBitmap(albumArt)
                    )
                } catch (_: Exception) {
                    views.setImageViewResource(
                        R.id.widget_cover,
                        android.R.drawable.ic_media_play
                    )
                }
            } else {
                views.setImageViewResource(
                    R.id.widget_cover,
                    android.R.drawable.ic_media_play
                )
            }

            // ============ 点击整卡 → 打开媒体控件 ============
            val intent = Intent(context, MediaActionReceiver::class.java).apply {
                action = MediaActionReceiver.ACTION_WIDGET_OPEN
            }
            val pending = PendingIntent.getBroadcast(
                context,
                103,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_music_root, pending)

            manager.updateAppWidget(widgetId, views)
        }

        private fun scaleBitmap(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            if (w <= COVER_MAX_SIZE && h <= COVER_MAX_SIZE) return src

            val ratio = COVER_MAX_SIZE.toFloat() / maxOf(w, h)
            val newW = (w * ratio).toInt().coerceAtLeast(1)
            val newH = (h * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, newW, newH, true)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }
}