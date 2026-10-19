package com.example.livenotification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * One permission row: name, why it is needed, current state, and a grant action.
 *
 * @param manifest unused for special-access permissions, which are granted via Settings.
 */
data class PermissionItem(
    val name: String,
    val reason: String,
    val manifest: String?,
    val check: (Context) -> Boolean,
    val request: (Context) -> Unit,
)

/** Full permission list used by both the first-run guide and Settings. */
fun buildPermissionItems(): List<PermissionItem> = listOf(
    PermissionItem(
        name = "通知",
        reason = "显示行程 / 快递 / 音乐的实时通知",
        manifest = Manifest.permission.POST_NOTIFICATIONS,
        check = { ctx ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        request = { ctx ->
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200
            )
        },
    ),
    PermissionItem(
        name = "蓝牙连接",
        reason = "直连耳机以控制降噪",
        manifest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else null,
        check = { ctx ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true
            else ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        },
        request = { ctx ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx as? android.app.Activity)?.requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 201
                )
            }
        },
    ),
    PermissionItem(
        name = "悬浮窗",
        reason = "耳机连接时弹出控制卡片、显示档位提示",
        manifest = null,
        check = { ctx -> Settings.canDrawOverlays(ctx) },
        request = { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    ),
    PermissionItem(
        name = "通知使用权",
        reason = "读取音乐播放信息、识别验证码",
        manifest = null,
        check = { ctx -> isNotificationListenerEnabled(ctx) },
        request = { ctx ->
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    ),
    PermissionItem(
        name = "短信",
        reason = "自动识别快递取件码",
        manifest = Manifest.permission.RECEIVE_SMS,
        check = { ctx ->
            ctx.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED
        },
        request = { ctx ->
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                ), 202
            )
        },
    ),
)

/** True when this app is an enabled notification listener. */
fun isNotificationListenerEnabled(context: Context): Boolean {
    return try {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        flat.contains(context.packageName)
    } catch (t: Throwable) {
        false
    }
}

/** Renders every permission with its live state and a grant button when missing. */
@Composable
fun PermissionList(
    items: List<PermissionItem>,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            for (item in items) {
                val granted = item.check(context)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (granted) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            item.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (granted) {
                        Text(
                            "已授权",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = {
                            item.request(context)
                            onChanged()
                        }) { Text("去授权") }
                    }
                }
            }
        }
    }
}
