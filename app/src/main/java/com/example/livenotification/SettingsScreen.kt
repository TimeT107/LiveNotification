package com.example.livenotification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings: permission status/requests plus the expert-mode switch.
 *
 * Expert mode swaps the earbuds screen for the low-level direct-connect harness, which is
 * what was used while reverse-engineering the AA protocol. It is off by default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReplayOnboarding: () -> Unit = {},
) {
    val context = LocalContext.current

    // Re-evaluated on every recomposition so grants show up immediately after returning
    // from a system dialog.
    var refreshTick by remember { mutableStateOf(0) }
    val expertMode = BudsControlSettings.readExpertMode(context)
    var expert by remember(refreshTick) { mutableStateOf(expertMode) }
    var chipEnabled by remember(refreshTick) {
        mutableStateOf(BudsControlSettings.readChipEnabled(context))
    }
    var verification by remember(refreshTick) {
        mutableStateOf(FeaturePreferences.isVerificationEnabled(context))
    }
    var flashlight by remember(refreshTick) {
        mutableStateOf(FeaturePreferences.isFlashlightEnabled(context))
    }
    val permissionItems = remember { buildPermissionItems() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
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

            SectionTitle("权限")
            // Shared with the first-run guide so the two can never drift apart.
            PermissionList(items = permissionItems, onChanged = { refreshTick++ })

            SectionTitle("功能开关")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ToggleSetting(
                        title = "验证码识别",
                        subtitle = "从通知和短信里抓取验证码。功能还不完善，默认关闭。",
                        checked = verification,
                        onChange = {
                            verification = it
                            FeaturePreferences.setVerificationEnabled(context, it)
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    ToggleSetting(
                        title = "手电筒 Chip",
                        subtitle = "手电筒状态实时通知。功能还不完善，默认关闭。",
                        checked = flashlight,
                        onChange = {
                            flashlight = it
                            FeaturePreferences.setFlashlightEnabled(context, it)
                        },
                    )
                }
            }

            SectionTitle("耳机控制")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ToggleSetting(
                        title = "档位提示胶囊",
                        subtitle = "耳机上切换档位时，左上角短暂显示当前档位。" +
                                "使用悬浮窗绘制，不会产生通知。",
                        checked = chipEnabled,
                        onChange = {
                            chipEnabled = it
                            BudsControlSettings.writeChipEnabled(context, it)
                        },
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    ToggleSetting(
                        title = "独立直连（试验）",
                        subtitle = "打开后，耳机控制页会切换到调试界面：" +
                                "可选帧型、手填序号、重放握手、查看收发日志。",
                        checked = expert,
                        onChange = {
                            expert = it
                            BudsControlSettings.writeExpertMode(context, it)
                        },
                    )
                }
            }

            SectionTitle("关于")
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("控制方式", "直连耳机 RFCOMM 通道 15")
                    InfoRow("支持设备", "OnePlus Buds Ace")
                    InfoRow("协议", "AA 帧（逆向所得）")

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    TextButton(
                        onClick = { onReplayOnboarding() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重新查看引导")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ToggleSetting(
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
