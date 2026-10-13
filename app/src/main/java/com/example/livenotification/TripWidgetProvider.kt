package com.example.livenotification

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews


class TripWidgetProvider : AppWidgetProvider() {

    companion object {

        /** 刷新所有行程微件 */
        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, TripWidgetProvider::class.java)
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
            val views = RemoteViews(context.packageName, R.layout.widget_trip)

            // ============ 选数据：优先火车，其次航班 ============
            val data: LiveData? = NotificationStore.currentTrain
                ?: NotificationStore.currentFlight

            if (data == null) {
                // 无行程：显示占位
                views.setImageViewResource(
                    R.id.widget_trip_icon,
                    R.drawable.train_chip
                )
                views.setTextViewText(R.id.widget_trip_title, "暂无行程")
                views.setTextViewText(R.id.widget_trip_status, "")
                views.setTextViewText(
                    R.id.widget_trip_stations,
                    "创建行程后自动显示"
                )
                views.setProgressBar(R.id.widget_trip_progress, 100, 0, false)
                views.setTextViewText(R.id.widget_trip_percent, "0%")
                views.setTextColor(R.id.widget_trip_status, Color.GRAY)

                bindClick(context, views, 200, MediaActionReceiver.ACTION_WIDGET_TRIP_OPEN)
                manager.updateAppWidget(widgetId, views)
                return
            }

            val isFlight = data.type == "flight"

            // ============ 图标 ============
            views.setImageViewResource(
                R.id.widget_trip_icon,
                if (isFlight) R.drawable.flight_chip else R.drawable.train_chip
            )

            // ============ 车次标题 ============
            views.setTextViewText(
                R.id.widget_trip_title,
                if (isFlight) "✈️ ${data.title}" else data.title
            )

            // ============ 状态 ============
            val now = System.currentTimeMillis()
            val (statusText, statusColor) = when {
                now < data.departureTime -> "等待出行" to Color.parseColor("#4A90E2")
                now < data.arrivalTime -> "已出发" to Color.parseColor("#4CAF50")
                else -> "已到达" to Color.GRAY
            }

            views.setTextViewText(R.id.widget_trip_status, statusText)
            views.setTextColor(R.id.widget_trip_status, statusColor)

            // ============ 站点行 ============
            val stations = "${data.startStation} ${data.startTime} → " +
                    "${data.endStation} ${data.endTime}"
            views.setTextViewText(R.id.widget_trip_stations, stations)

            // ============ 进度 ============
            val progress = CountdownManager.calcProgress(
                data.createTime,
                data.departureTime,
                data.arrivalTime
            )
            views.setProgressBar(
                R.id.widget_trip_progress,
                100,
                progress,
                false
            )
            views.setTextViewText(R.id.widget_trip_percent, "$progress%")

            // ============ 点击整卡 → 打开首页 ============
            bindClick(context, views, 200, MediaActionReceiver.ACTION_WIDGET_TRIP_OPEN)

            manager.updateAppWidget(widgetId, views)
        }

        private fun bindClick(
            context: Context,
            views: RemoteViews,
            requestCode: Int,
            action: String
        ) {
            val intent = Intent(context, MediaActionReceiver::class.java).apply {
                this.action = action
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_trip_root, pending)
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