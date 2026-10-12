package com.example.livenotification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


/**
 * 手电筒亮度调节面板
 * 带 M3 Slider（可拖动）
 */
@Composable
fun FlashlightBrightnessPanel(
    onDismiss: () -> Unit
) {

    val context = LocalContext.current

    val maxLevel = remember { FlashlightController.maxLevel.coerceAtLeast(1) }
    val supportsBrightness = remember { FlashlightController.supportsBrightness }

    // 当前进度（1.0 ~ maxLevel）
    var sliderValue by remember {
        mutableFloatStateOf(
            (FlashlightStore.current?.currentLevel ?: 1)
                .coerceIn(1, maxLevel)
                .toFloat()
        )
    }

    // 拖动状态
    var isDragging by remember { mutableStateOf(false) }

    // 每 500ms 同步外部状态（首次延迟 300ms 让系统状态稳定）
    LaunchedEffect(Unit) {
        delay(300L)

        while (true) {
            if (!isDragging) {
                FlashlightStore.current?.let { data ->
                    val target = data.currentLevel
                        .coerceIn(1, maxLevel)
                        .toFloat()
                    // 只在差异明显时才更新，避免微小抖动
                    if (kotlin.math.abs(target - sliderValue) >= 0.5f) {
                        sliderValue = target
                    }
                }
            }
            delay(500L)
        }
    }

    val accent = MaterialTheme.colorScheme.primary

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

                // 拖拽条
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

                Spacer(Modifier.height(20.dp))

                // 标题（图标 + 文字）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.FlashlightOn,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "手电筒亮度",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 当前等级
                Text(
                    text = if (supportsBrightness) {
                        "亮度 ${sliderValue.toInt()} / $maxLevel"
                    } else {
                        "设备不支持亮度调节"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(28.dp))

                if (supportsBrightness) {
                    // ============ Slider ============
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            isDragging = true
                            sliderValue = newValue
                            // ★ 实时生效
                            FlashlightController.setBrightness(
                                newValue.toInt()
                            )
                        },
                        onValueChangeFinished = {
                            isDragging = false
                        },
                        valueRange = 1f..maxLevel.toFloat(),
                        steps = if (maxLevel > 1) maxLevel - 2 else 0,
                        colors = SliderDefaults.colors(
                            thumbColor = accent,
                            activeTrackColor = accent,
                            inactiveTrackColor = MaterialTheme.colorScheme
                                .onSurfaceVariant.copy(alpha = 0.24f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    // 刻度标记（1 ~ maxLevel）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (i in 1..maxLevel) {
                            Text(
                                text = "$i",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = "该设备不支持调节亮度，只能开关。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.weight(1f))

                // ============ 关闭按钮 ============
                OutlinedButton(
                    onClick = {
                        FlashlightController.setTorch(false)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("关闭手电筒", fontSize = 15.sp)
                }

                Spacer(Modifier.height(8.dp))

                // ============ 完成按钮 ============
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