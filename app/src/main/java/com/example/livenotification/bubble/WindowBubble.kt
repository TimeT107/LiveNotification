package com.example.livenotification.bubble

import com.example.livenotification.R

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.core.app.Person as CorePerson
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

/**
 * 「小窗打开」：不依赖任何通知，直接给某个应用挂一个气泡，点开就是那个应用
 * 自己的界面跑在气泡窗口里（和消息气泡用的是同一套机制）。
 *
 * 关键：气泡的展开目标用**我们自己创建**的 MUTABLE PendingIntent 指向对方
 * 的 launcher Activity。系统展开时由 SystemUI 代发，BAL 检查的是我们而不是
 * 对方应用，所以不会被 "Background activity launch blocked" 拦掉。
 */
object WindowBubble {

    private const val TAG = "BubbleNotify"

    /** 把应用图标画成 Bitmap（跨包资源图标最稳的转换方式） */
    private fun appIconBitmap(context: Context, pkg: String): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val w = drawable.intrinsicWidth.coerceIn(1, 192)
        val h = drawable.intrinsicHeight.coerceIn(1, 192)
        val bitmap = createBitmap(w, h)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap
    }.onFailure { Log.w(TAG, "读取应用图标失败 pkg=" + pkg, it) }.getOrNull()

    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Log.w(TAG, "没有可启动的入口 pkg=" + pkg)
            return false
        }
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull()
        if (appInfo == null) {
            Log.w(TAG, "拿不到应用信息 pkg=" + pkg)
            return false
        }

        // 让目标应用可 resize：不可调整大小的应用会被系统按全屏处理（黑底）；
        // 横屏时也靠它让应用按平板/横屏布局展开。拿不到 root 就直接打开应用。
        val resizeOk = AppCompatMode.ensureGlobalResizeable()
        Log.i(TAG, "小窗：resize兼容=" + resizeOk + " pkg=" + pkg)
        if (!resizeOk) {
            return runCatching {
                context.startActivity(launchIntent.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrElse {
                Log.w(TAG, "直接打开失败 pkg=" + pkg, it)
                false
            }
        }

        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
        val shortcutId = "window_" + pkg.replace('.', '_').takeLast(64)
        // 对方应用的图标画成 Bitmap 再用：跨包资源 id 用 IconCompat.createWithResource
        // 会被解析到我们自己包里，拿到的图标是错的。
        val iconBitmap = appIconBitmap(context, pkg)
        val appIcon = iconBitmap?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(context, R.drawable.ic_bubble)

        // 气泡必须挂在一个长期快捷方式上（图标用对方应用的图标）
        val shortcutIcon = iconBitmap?.let { IconCompat.createWithBitmap(it) }
            ?: IconCompat.createWithResource(context, R.drawable.ic_bubble)

        val shortcutIntent = Intent(context, BubbleActivity::class.java).apply {
            // 必须设 action：ShortcutInfo.Builder 会校验，缺了直接 NPE
            action = Intent.ACTION_VIEW
            putExtra(BubbleActivity.EXTRA_PACKAGE_NAME, pkg)
            putExtra(BubbleActivity.EXTRA_TITLE, label)
            putExtra(BubbleActivity.EXTRA_SHORTCUT_ID, shortcutId)
            putExtra(BubbleActivity.EXTRA_TEXT, context.getString(R.string.window_bubble_text))
        }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(shortcutIcon)
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .setPerson(CorePerson.Builder().setName(label).setImportant(true).build())
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
            .onFailure { Log.e(TAG, "小窗快捷方式推送失败", it) }

        val bubblePendingIntent = PendingIntent.getActivity(
            context,
            shortcutId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // 横屏应用（游戏）用 16:9 的矮窗口，竖屏应用沿用默认高度
        val desiredHeight = BubbleGeometry.desiredHeightDp(context, pkg)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Notification.BubbleMetadata.Builder(bubblePendingIntent, appIcon)
        } else {
            // API 29 只有无参构造（Builder(String) 是 API 30 才加的），
            // 气泡靠通知上的 setShortcutId + 这里的 intent 关联。
            @Suppress("DEPRECATION")
            Notification.BubbleMetadata.Builder()
                .setIcon(appIcon)
                .setIntent(bubblePendingIntent)
        }
        val bubble = builder
            .setDesiredHeight(desiredHeight)
            .setAutoExpandBubble(false)
            .setSuppressNotification(false)
            .build()

        // 和消息气泡保持同一种形态（MessagingStyle + 会话标题），
        // 有些 ROM 只对"会话型"通知渲染气泡，用普通标题+正文反而出不来。
        val me = Person.Builder().setName(label).setImportant(true).build()
        val style = Notification.MessagingStyle(me)
            .setConversationTitle(label)
            .addMessage(
                context.getString(R.string.window_bubble_text),
                System.currentTimeMillis(),
                me
            )

        val notification = Notification.Builder(context, NotifyListenerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.bubble_ic_notification)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setBubbleMetadata(bubble)
            .setAutoCancel(true)
            .build()

        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(shortcutId.hashCode(), notification)
        }.onFailure { Log.e(TAG, "小窗气泡发送失败 pkg=" + pkg, it) }.isSuccess
    }
}
