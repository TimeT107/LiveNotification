package com.example.livenotification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.livenotification.bubble.BubbleSettings
import com.example.livenotification.bubble.KeepAliveService
import com.example.livenotification.bubble.NotifyListenerService
import com.example.livenotification.bubble.WindowBubble

/**
 * 「气泡通知」设置页（Compose 版）。
 *
 * 取代 BubbleNotifyDemo 原来的 MainActivity（XML + AppCompat + MaterialSwitch）。
 * 功能逐项对齐旧版：通知访问权限、气泡开关状态、白名单选择、小窗打开、6 个开关。
 * 视觉上完全套用主软件 SettingsScreen 的设计语言（Scaffold + TopAppBar +
 * surfaceContainerHigh 卡片 + Switch/TextButton），颜色走 dynamic color。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 从系统设置页返回后手动刷新状态（Compose 里没有 onResume，用按钮兜住）
    var tick by remember { mutableIntStateOf(0) }

    val listenerEnabled = remember(tick) { isNotificationListenerEnabled(context) }
    val bubbleStatus = remember(tick) { bubbleStatusText(context) }

    var keepAlive by remember(tick) { mutableStateOf(BubbleSettings.keepAlive(context)) }
    var autoExpand by remember(tick) { mutableStateOf(BubbleSettings.autoExpand(context)) }
    var headsUp by remember(tick) { mutableStateOf(BubbleSettings.headsUp(context)) }
    var dedupe by remember(tick) { mutableStateOf(BubbleSettings.dedupeHeadsUp(context)) }
    var intercept by remember(tick) { mutableStateOf(BubbleSettings.interceptSource(context)) }
    var expandSource by remember(tick) { mutableStateOf(BubbleSettings.expandSourceApp(context)) }

    var whitelistApps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var windowApps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    BackHandler(enabled = true, onBack = onBack)

    // 通知权限
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要通知权限才能显示气泡", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 进页面补一次：监听服务"活着但断开"时这里能救回来
        NotifyListenerService.ensureConnected(context)
        // 用户勾了常驻就把前台服务拉起来（幂等）
        if (BubbleSettings.keepAlive(context)) KeepAliveService.start(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("气泡通知", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------------- 状态 ----------------
            BubbleSectionTitle("状态")
            BubbleCard {
                Text(
                    if (listenerEnabled) "✅ 通知监听权限已开启" else "❌ 通知监听权限未开启",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (listenerEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (listenerEnabled) {
                        "现在用微信或 QQ 发一条消息试试"
                    } else {
                        "需要开启通知访问权限，气泡才能工作"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { openNotificationListenerSettings(context) }) {
                        Text("打开通知访问设置")
                    }
                    TextButton(onClick = { tick++ }) {
                        Text("刷新状态")
                    }
                }

                BubbleDivider()

                Text(
                    bubbleStatus,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openBubbleSettings(context) }) {
                    Text("打开气泡设置")
                }
            }

            // ---------------- 气泡行为 ----------------
            BubbleSectionTitle("气泡行为")
            BubbleCard {
                BubbleToggle(
                    title = "收到消息直接展开气泡",
                    subtitle = "新消息到达时气泡自动展开成会话面板",
                    checked = autoExpand,
                ) {
                    autoExpand = it
                    BubbleSettings.setAutoExpand(context, it)
                }
                BubbleDivider()
                BubbleToggle(
                    title = "消息来时弹内容横幅",
                    subtitle = "悬浮通知横幅显示消息内容",
                    checked = headsUp,
                ) {
                    headsUp = it
                    BubbleSettings.setHeadsUp(context, it)
                }
                BubbleDivider()
                BubbleToggle(
                    title = "源应用已弹横幅时不重复弹",
                    subtitle = "避免同一条消息弹两次横幅",
                    checked = dedupe,
                ) {
                    dedupe = it
                    BubbleSettings.setDedupeHeadsUp(context, it)
                }
                BubbleDivider()
                BubbleToggle(
                    title = "接管原应用通知（只留气泡）",
                    subtitle = "把白名单应用的通知从通知栏撤掉，交给气泡显示",
                    checked = intercept,
                ) {
                    intercept = it
                    BubbleSettings.setInterceptSource(context, it)
                }
                BubbleDivider()
                BubbleToggle(
                    title = "气泡展开显示原应用界面",
                    subtitle = "展开时直接渲染原应用（需要 root 时可能会退回本应用面板）",
                    checked = expandSource,
                ) {
                    expandSource = it
                    BubbleSettings.setExpandSourceApp(context, it)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "改动对下一条消息生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 应用与窗口 ----------------
            BubbleSectionTitle("应用与窗口")
            BubbleCard {
                val selectedCount = remember(tick) { BubbleSettings.whitelist(context).size }
                Text(
                    "当前接管 $selectedCount 个应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val apps = loadLaunchableApps(context)
                        if (apps.isEmpty()) {
                            Toast.makeText(context, "没有可选的桌面应用", Toast.LENGTH_SHORT).show()
                        } else {
                            whitelistApps = apps
                        }
                    }) { Text("选择要接管的应用") }

                    TextButton(onClick = {
                        val whitelist = BubbleSettings.whitelist(context)
                        val apps = loadLaunchableApps(context).filter { whitelist.contains(it.first) }
                        if (apps.isEmpty()) {
                            Toast.makeText(
                                context, "白名单里还没有应用，先点上面那个按钮勾选", Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            windowApps = apps
                        }
                    }) { Text("小窗打开应用") }
                }
            }

            // ---------------- 后台 ----------------
            BubbleSectionTitle("后台")
            BubbleCard {
                BubbleToggle(
                    title = "后台常驻（保持气泡存活）",
                    subtitle = "用前台服务把进程优先级顶高，避免监听服务被回收",
                    checked = keepAlive,
                ) {
                    keepAlive = it
                    BubbleSettings.setKeepAlive(context, it)
                    if (it) KeepAliveService.start(context) else KeepAliveService.stop(context)
                }
            }

            // ---------------- 说明 ----------------
            BubbleSectionTitle("说明")
            BubbleCard {
                Text(
                    "气泡通知会把白名单应用（默认微信、QQ）的新消息变成系统原生气泡。" +
                        "需要同时开启「通知访问权限」和系统对本应用的气泡允许。" +
                        "部分 ROM 默认关闭第三方应用气泡，需要在系统设置里手动打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    whitelistApps?.let { apps ->
        BubbleWhitelistDialog(
            apps = apps,
            initial = BubbleSettings.whitelist(context),
            onDismiss = { whitelistApps = null },
            onConfirm = { selected ->
                BubbleSettings.setWhitelist(context, selected)
                whitelistApps = null
                tick++
                Toast.makeText(
                    context, "已选择 ${selected.size} 个应用", Toast.LENGTH_SHORT
                ).show()
            },
        )
    }

    windowApps?.let { apps ->
        BubbleWindowDialog(
            apps = apps,
            onDismiss = { windowApps = null },
            onPick = { pkg ->
                windowApps = null
                val ok = WindowBubble.launch(context, pkg)
                if (!ok) Toast.makeText(context, "小窗打开失败", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

// ============================================================
// 小组件（与主软件 SettingsScreen 同一套视觉）
// ============================================================

@Composable
private fun BubbleSectionTitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun BubbleCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun BubbleDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun BubbleToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BubbleWhitelistDialog(
    apps: List<Pair<String, String>>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val checked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            apps.forEach { put(it.first, initial.contains(it.first)) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要接管的应用") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(apps) { (pkg, label) ->
                    val isChecked = checked[pkg] == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { checked[pkg] = !isChecked }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked[pkg] = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checked.filterValues { it }.keys) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun BubbleWindowDialog(
    apps: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("小窗打开应用") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(apps) { (pkg, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(pkg) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ============================================================
// 非 UI 逻辑（照搬旧 MainActivity）
// ============================================================

private fun bubbleStatusText(context: Context): String {
    val nm = context.getSystemService(android.app.NotificationManager::class.java)
    val appAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        nm.areBubblesEnabled()
    } else {
        @Suppress("DEPRECATION")
        nm.areBubblesAllowed()
    }
    val channelAllowed =
        nm.getNotificationChannel(NotifyListenerService.CHANNEL_ID)?.canBubble() ?: true
    return when {
        appAllowed && channelAllowed -> "✅ 气泡已允许"
        !appAllowed -> "⚠️ 系统把本应用的气泡关掉了\n\n很多 ROM 默认如此，需要手动开启，否则只会显示普通通知"
        else -> "⚠️「气泡通知」这个渠道的气泡被关掉了"
    }
}

private fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

private fun openBubbleSettings(context: Context) {
    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, NotifyListenerService.CHANNEL_ID)
    }
    val appIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(channelIntent) }
        .recoverCatching { context.startActivity(appIntent) }
        .onFailure {
            Toast.makeText(context, "没能打开设置页，请手动到 设置 → 通知 里找本应用", Toast.LENGTH_SHORT)
                .show()
        }
}

/** 有桌面入口的应用（用户能点开的那些），按名字排序 */
private fun loadLaunchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(query, 0)
    }
    return resolved
        .mapNotNull { it.activityInfo?.packageName }
        .distinct()
        .filter { it != context.packageName }
        .mapNotNull { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(pkg, 0)
                    }
                ).toString()
            }.getOrNull() ?: return@mapNotNull null
            pkg to label
        }
        .sortedBy { it.second.lowercase() }
}
