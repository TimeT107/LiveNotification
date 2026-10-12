package com.example.livenotification

import android.content.Intent
import android.media.AudioDeviceInfo
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioOutputPanel(
    onDismiss: () -> Unit
) {

    val context = LocalContext.current

    var volume by remember {
        mutableIntStateOf(AudioDeviceManager.getStreamVolume(context))
    }
    val maxVolume = remember {
        AudioDeviceManager.getStreamMaxVolume(context)
    }
    var activeDevice by remember {
        mutableStateOf(AudioDeviceManager.getActiveDevice(context))
    }
    var devices by remember {
        mutableStateOf(AudioDeviceManager.getOutputDevices(context))
    }

    // ★ 拖动状态
    var isDragging by remember { mutableStateOf(false) }
    var dragVolume by remember { mutableFloatStateOf(0f) }

    val displayVolume = if (isDragging) dragVolume else volume.toFloat()

    LaunchedEffect(Unit) {
        while (true) {
            activeDevice = AudioDeviceManager.getActiveDevice(context)
            devices = AudioDeviceManager.getOutputDevices(context)
            // 拖动时跳过自动刷新
            if (!isDragging) {
                volume = AudioDeviceManager.getStreamVolume(context)
            }
            delay(1000L)
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 20.dp)
            ) {

                // 顶部拖拽条
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                )

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {

                    Text(
                        text = "播放设备",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(20.dp))

                    // ============ 当前设备 ============
                    activeDevice?.let { dev ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(
                                    AudioDeviceManager.getDeviceIconRes(dev.type)
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )

                            Spacer(Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = AudioDeviceManager.getDeviceName(dev),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "当前设备",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } ?: Text(
                        text = "未检测到设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))

                    // ============ 音量条（波浪样式 + 可拖动） ============
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = when {
                                displayVolume == 0f -> Icons.Filled.VolumeOff
                                displayVolume < maxVolume / 2 -> Icons.Filled.VolumeDown
                                else -> Icons.Filled.VolumeUp
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )

                        Spacer(Modifier.width(12.dp))

                        // 波浪进度条 + 拖动层
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)   // 触摸区
                                .pointerInput(maxVolume) {
                                    if (maxVolume <= 0) return@pointerInput

                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()

                                        isDragging = true
                                        val total = size.width.toFloat()
                                        if (total > 0) {
                                            val ratio = (down.position.x / total)
                                                .coerceIn(0f, 1f)
                                            dragVolume = ratio * maxVolume
                                        }

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull()
                                                ?: break

                                            if (change.changedToUp()) {
                                                change.consume()
                                                break
                                            }

                                            val r = (change.position.x / total)
                                                .coerceIn(0f, 1f)
                                            dragVolume = r * maxVolume
                                            change.consume()
                                        }

                                        // 松手 → 应用音量
                                        val target = dragVolume.toInt()
                                        volume = target
                                        AudioDeviceManager.setStreamVolume(
                                            context, target
                                        )
                                        isDragging = false
                                    }
                                }
                        ) {
                            LinearWavyProgressIndicator(
                                progress = {
                                    if (maxVolume > 0) {
                                        (displayVolume / maxVolume)
                                            .coerceIn(0f, 1f)
                                    } else 0f
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)   // 比主控件细
                                    .align(Alignment.Center),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.2f),
                                gapSize = 0.dp,
                                // 拖动时波浪归零
                                amplitude = { if (isDragging) 0f else 0.7f },
                                wavelength = 24.dp,
                                waveSpeed = 12.dp
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Text(
                            text = "${displayVolume.toInt()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // ============ 设备列表 ============
                    Text(
                        text = "所有设备",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    if (devices.isEmpty()) {
                        Text(
                            text = "未检测到输出设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        devices.forEach { device ->
                            DeviceRow(
                                device = device,
                                isActive = device.id == activeDevice?.id,
                                onClick = {
                                    if (device.id == activeDevice?.id) return@DeviceRow
                                    Toast.makeText(
                                        context,
                                        "切换设备暂不支持",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("连接设备")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("完成", fontSize = 15.sp)
                }
            }
        }
    }
}


@Composable
private fun DeviceRow(
    device: AudioDeviceInfo,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(
                AudioDeviceManager.getDeviceIconRes(device.type)
            ),
            contentDescription = null,
            tint = if (isActive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AudioDeviceManager.getDeviceName(device),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = if (isActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = AudioDeviceManager.getDeviceTypeName(device.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isActive) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "当前设备",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}