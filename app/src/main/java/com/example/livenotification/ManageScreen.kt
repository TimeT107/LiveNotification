package com.example.livenotification

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight


@Composable
fun ManageScreen(
    dao: LiveNotificationDao,
    storage: LiveNotificationStorage,
    backupStorage: BackupStorage,
    subPage: String?,
    onSubPageChange: (String?) -> Unit,
    onReplayOnboarding: () -> Unit = {},
) {

    // 拦截返回：按层级返回
    BackHandler(enabled = subPage != null) {
        onSubPageChange(
            when (subPage) {
                "trip_train", "trip_flight" -> "trip"
                "about" -> "settings"
                else -> null
            }
        )
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            (
                    fadeIn(tween(210, delayMillis = 90)) +
                            scaleIn(
                                initialScale = 0.92f,
                                animationSpec = tween(210, delayMillis = 90)
                            )
                    ).togetherWith(
                    fadeOut(tween(90)) +
                            scaleOut(targetScale = 0.92f, animationSpec = tween(90))
                )
        },
        label = "manage_transition"
    ) { page ->

        when (page) {

            null -> {
                ManageMainScreen(
                    dao = dao,
                    onNavigate = { onSubPageChange(it) }
                )
            }

            "express" -> {
                ExpressScreen(
                    onBack = { onSubPageChange(null) }
                )
            }

            "history" -> {
                HistoryScreen(
                    dao = dao,
                    backupStorage = backupStorage,
                    onBack = { onSubPageChange(null) }
                )
            }

            "trip" -> {
                TripTypeSelectScreen(
                    onBack = { onSubPageChange(null) },
                    onSelectTrain = { onSubPageChange("trip_train") },
                    onSelectFlight = { onSubPageChange("trip_flight") }
                )
            }

            "trip_train" -> {
                TripEditScreen(
                    dao = dao,
                    storage = storage,
                    onBack = { onSubPageChange("trip") }
                )
            }

            "trip_flight" -> {
                FlightTripEditScreen(
                    dao = dao,
                    storage = storage,
                    onBack = { onSubPageChange("trip") }
                )
            }

            "settings" -> {
                SettingsScreen(
                    onBack = { onSubPageChange(null) },
                    onReplayOnboarding = onReplayOnboarding,
                    onOpenAbout = { onSubPageChange("about") },
                )
            }

            "about" -> {
                AboutScreen(
                    onBack = { onSubPageChange("settings") }
                )
            }
        }
    }
}


@Composable
private fun ManageMainScreen(
    dao: LiveNotificationDao,
    onNavigate: (String) -> Unit
) {

    val context = LocalContext.current

    var isNlsEnabled by remember {
        mutableStateOf(checkNotificationListenerEnabled(context))
    }

    // ★ 统计状态
    var expressCount by remember { mutableIntStateOf(0) }
    var trainCount by remember { mutableIntStateOf(0) }
    var flightCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            // 通知使用权状态
            isNlsEnabled = checkNotificationListenerEnabled(context)

            // 统计数据
            try {
                val records = dao.getAll()

                expressCount = records.count {
                    it.title.startsWith("快递 · ") && it.status == "已取件"
                }
                trainCount = records.count {
                    !it.title.startsWith("快递 · ") &&
                            !it.title.startsWith("航班 · ") &&
                            it.status != "进行中"
                }
                flightCount = records.count {
                    it.title.startsWith("航班 · ") && it.status != "进行中"
                }
            } catch (_: Exception) {
            }

            delay(2000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "管理",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onNavigate("settings") }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ★ 统计卡片
        StatisticsCard(
            expressCount = expressCount,
            trainCount = trainCount,
            flightCount = flightCount
        )

        Spacer(Modifier.height(20.dp))

        ManageCard(
            icon = Icons.Filled.LocalShipping,
            title = "快递管理",
            subtitle = "添加、编辑、删除快递",
            onClick = { onNavigate("express") }
        )

        Spacer(Modifier.height(12.dp))

        ManageCard(
            icon = Icons.Filled.History,
            title = "历史记录",
            subtitle = "查看、编辑、恢复历史通知",
            onClick = { onNavigate("history") }
        )

        Spacer(Modifier.height(12.dp))

        ManageCard(
            icon = Icons.Filled.Train,
            title = "行程管理",
            subtitle = "创建和管理行程通知",
            onClick = { onNavigate("trip") }
        )

        Spacer(Modifier.height(12.dp))

        MusicListenerCard(
            isEnabled = isNlsEnabled,
            onClick = {
                if (isNlsEnabled) {
                    Toast.makeText(
                        context,
                        "通知使用权已开启",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        )
    }
}


// ============================================================
// 统计卡片
// ============================================================
@Composable
private fun StatisticsCard(
    expressCount: Int,
    trainCount: Int,
    flightCount: Int
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // ============ 上半：已取快递 ============
            StatItem(
                icon = Icons.Filled.Inventory2,
                count = expressCount,
                label = "已取快递",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )

            // ============ 下半：火车 / 飞机 ============
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                StatItem(
                    icon = Icons.Filled.Train,
                    count = trainCount,
                    label = "火车出行",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                )

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                )

                StatItem(
                    icon = Icons.Filled.Flight,
                    count = flightCount,
                    label = "飞机出行",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                )
            }
        }
    }
}

/**
 * 单个统计项
 * [图标]                    [大数字]
 *                           [标签]
 * 数字压住标签（数字在上层）
 */
@Composable
private fun StatItem(
    icon: ImageVector,
    count: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧图标
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )

        Spacer(Modifier.weight(1f))

        // 右侧：用 Box 叠加，数字在上层
        Box(
            modifier = Modifier.wrapContentSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            // 1. 标签（先画，在下层）
            Text(
                text = label,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd)
            )

            // 2. 数字（后画，在上层，压住标签的上半部分）
            Text(
                text = "$count",
                fontSize = 48.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = (-6).dp)   // 数字向上抬一点，露出标签下半部分
            )
        }
    }
}
@Composable
private fun MusicListenerCard(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (isEnabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "音乐监听",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isEnabled) "已开启" else "点击前往系统设置授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = if (isEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    )
            )
        }
    }
}


private fun checkNotificationListenerEnabled(
    context: android.content.Context
): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.contains(context.packageName)
}


@Composable
private fun ManageCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}