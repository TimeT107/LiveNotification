package com.example.livenotification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log


class VerificationNotificationManager(
    private val context: Context
) {

    private val manager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "verification"
        private const val NOTIFICATION_ID = 1021

        private val ICON_SMALL = android.R.drawable.ic_lock_lock
    }

    /** 系统莫奈主色 */
    private fun monetColor(): Int {
        return try {
            context.getColor(android.R.color.system_accent1_500)
        } catch (_: Exception) {
            0xFFFF6B6B.toInt()
        }
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "验证码",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "短信/App 验证码提醒"
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("NewApi")
    fun show(data: VerificationData) {

        val accent = monetColor()

        // ============ 复制按钮 ============
        val copyIntent = Intent(context, VerificationActionReceiver::class.java).apply {
            action = VerificationActionReceiver.ACTION_COPY
            putExtra(VerificationActionReceiver.EXTRA_CODE, data.code)
        }
        val copyPending = PendingIntent.getBroadcast(
            context,
            0,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val copyIcon = Icon.createWithResource(
            context,
            android.R.drawable.ic_menu_edit
        ).apply {
            // ★ 染莫奈色
            setTint(accent)
        }

        // ============ 已读按钮 ============
        val readIntent = Intent(context, VerificationActionReceiver::class.java).apply {
            action = VerificationActionReceiver.ACTION_MARK_READ
        }
        val readPending = PendingIntent.getBroadcast(
            context,
            1,
            readIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val readIcon = Icon.createWithResource(
            context,
            android.R.drawable.ic_menu_close_clear_cancel
        ).apply {
            setTint(accent)
        }

        // ============ 卡片点击 → 打开发送方 App ============
        val openAppIntent: Intent? = try {
            context.packageManager
                .getLaunchIntentForPackage(data.senderPackage)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
        } catch (_: Exception) {
            null
        }

        val contentPending = if (openAppIntent != null) {
            PendingIntent.getActivity(
                context,
                2,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        // ============ 大图标（发送方 App 图标） ============
        val largeIconBitmap: Bitmap? = try {
            val drawable = context.packageManager
                .getApplicationIcon(data.senderPackage)
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(48)
                    val h = drawable.intrinsicHeight.coerceAtLeast(48)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                }
            }
        } catch (_: Exception) {
            null
        }

        // ============ 通知 ============
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON_SMALL)
            .setContentTitle("新验证码")
            .setContentText(data.code)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_REMINDER)

            // ★ 通知强调色也用莫奈色
            .setColor(accent)

            .addAction(
                Notification.Action.Builder(copyIcon, "复制", copyPending).build()
            )
            .addAction(
                Notification.Action.Builder(readIcon, "已读", readPending).build()
            )

            .apply {
                if (largeIconBitmap != null) {
                    setLargeIcon(Icon.createWithBitmap(largeIconBitmap))
                }
                if (contentPending != null) {
                    setContentIntent(contentPending)
                }
            }

            .setWhen(System.currentTimeMillis())
            .setUsesChronometer(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText("验证码")
        }

        manager.notify(NOTIFICATION_ID, builder.build())
        Log.d("VerifNotif", "显示验证码通知: ${data.code}")
    }

    fun stop() {
        manager.cancel(NOTIFICATION_ID)
    }
}