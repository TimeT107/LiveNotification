package com.example.livenotification

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Earbuds control.
 *
 * Normal mode talks to the earbuds directly over RFCOMM and exposes exactly three noise
 * modes. Expert mode (enabled in Settings) swaps in the low-level harness that was used to
 * reverse-engineer the protocol.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BudsControlScreen() {
    val context = LocalContext.current

    if (BudsControlSettings.readExpertMode(context)) {
        ExpertHarnessScreen(onExit = { BudsControlSettings.writeExpertMode(context, false) })
    } else {
        NormalScreen()
    }
}

// ============================================================
// 正常模式：三个档位
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NormalScreen() {
    val scope = rememberCoroutineScope()
    // Seeded from the tracked mode and kept live, so a switch made on the earbuds shows up
    // here too.
    var selected by remember { mutableIntStateOf(BudsClient.currentMode) }
    var status by remember { mutableStateOf("") }

    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener: (Int) -> Unit = { mode -> selected = mode }
        BudsClient.addModeListener(listener)
        onDispose { BudsClient.removeModeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        LargeFlexibleTopAppBar(
            title = { Text("耳机控制", fontWeight = FontWeight.SemiBold) },
            subtitle = { Text("OnePlus Buds Ace 降噪") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusCard()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "降噪模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(16.dp))

                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(
                            BudsClient.MODE_ANC to "降噪",
                            BudsClient.MODE_PASS to "通透",
                            BudsClient.MODE_OFF to "关闭",
                        )
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = selected == mode,
                                onClick = {
                                    selected = mode
                                    scope.launch {
                                        // Connect on demand; the card path normally has the
                                        // link warmed up already, so this is usually instant.
                                        status = withContext(Dispatchers.IO) {
                                            if (!BudsClient.isConnected) {
                                                val r = BudsClient.connect()
                                                if (!BudsClient.isConnected) return@withContext r
                                            }
                                            BudsClient.setModeFast(mode)
                                        }
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                icon = {},
                                label = { Text(label, fontWeight = FontWeight.Medium) },
                            )
                        }
                    }

                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // A change made with the earbuds' own touch controls cannot be observed on this
            // link, so let the user tell the app what the earbuds are in.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "在耳机上切换过？",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "耳机的触摸切换不会通知手机，点一下实际档位即可同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            BudsClient.MODE_ANC to "降噪",
                            BudsClient.MODE_PASS to "通透",
                            BudsClient.MODE_OFF to "关闭",
                        ).forEach { (mode, label) ->
                            OutlinedButton(
                                onClick = {
                                    selected = mode
                                    BudsClient.syncMode(mode)
                                    status = "已同步为 ${BudsClient.modeName(mode)}"
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "耳机连接时会自动弹出控制卡片，也可以在这里直接切换。" +
                        "切换时左上角会短暂显示当前档位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "直连控制",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "直接与耳机通信，无需任何厂商 App。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))
            LinearWavyProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
            )
        }
    }
}

// ============================================================
// 专家模式：底层调试台
// ============================================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpertHarnessScreen(onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(BudsClient.isConnected) }
    var sendInit by remember { mutableStateOf(true) }
    var useVariantA by remember { mutableStateOf(false) }
    var seqText by remember { mutableStateOf("25") }
    var log by remember { mutableStateOf("") }
    var dumping by remember { mutableStateOf(BudsClient.dumpEnabled) }
    val ctx = LocalContext.current
    val dumpPath = remember {
        val f = java.io.File(ctx.getExternalFilesDir(null), "aa_dump.txt")
        BudsClient.setDumpTarget(f)
        f.absolutePath
    }

    Column(modifier = Modifier.fillMaxSize()) {

        LargeFlexibleTopAppBar(
            title = { Text("独立直连", fontWeight = FontWeight.SemiBold) },
            subtitle = { Text("调试台 · 可选帧型 / 序号 / 握手") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "耳机厂商通道只接受一个客户端，若装了厂商 App 需先停掉它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            busy = true
                            log = "连接中…"
                            scope.launch {
                                log = withContext(Dispatchers.IO) { BudsClient.connect() }
                                connected = BudsClient.isConnected
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                busy -> "处理中…"
                                connected -> "已连接（点此重连）"
                                else -> "建立长连接"
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "重放握手序列",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(checked = sendInit, onCheckedChange = { sendInit = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "用旧帧型 (04 02，实为状态帧)",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(checked = useVariantA, onCheckedChange = { useVariantA = it })
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "发送",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = seqText,
                        onValueChange = { seqText = it.filter { c -> c.isDigit() } },
                        label = { Text("seq 序号 (0-255)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(10.dp))
                    val previewSeq = seqText.toIntOrNull() ?: 0x19
                    val preview = if (useVariantA)
                        BudsClient.modeSetFrame(BudsClient.MODE_ANC, previewSeq)
                    else
                        BudsClient.modeReportFrame(BudsClient.MODE_ANC, previewSeq)
                    Text(
                        "待发帧（应 12 字节，形如 aa 0a 00 00 04 04 .. 03 00 01 01 <mode>）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        BudsClient.hex(preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(
                            BudsClient.MODE_ANC to "降噪",
                            BudsClient.MODE_PASS to "通透",
                            BudsClient.MODE_OFF to "关闭",
                        )
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = false,
                                enabled = !busy,
                                onClick = {
                                    if (BudsClient.isConnected) {
                                        val r = BudsClient.setModeFast(
                                            mode = mode,
                                            useVariantA = useVariantA,
                                            seqOverride = seqText.toIntOrNull(),
                                        )
                                        connected = BudsClient.isConnected
                                        log = r + "\n" + BudsClient.lastLog
                                    } else {
                                        busy = true
                                        log = "未连接，执行完整连接…"
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) {
                                                BudsClient.setMode(
                                                    mode = mode,
                                                    sendInit = sendInit,
                                                    useVariantA = useVariantA,
                                                    seq = seqText.toIntOrNull() ?: 0x19,
                                                    killCompanionFirst = false,
                                                )
                                            }
                                            connected = BudsClient.isConnected
                                            log = r
                                            busy = false
                                        }
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                icon = {},
                                label = { Text(label) },
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true
                            log = "探测中…"
                            scope.launch {
                                log = withContext(Dispatchers.IO) {
                                    BudsClient.probe(killCompanionFirst = false)
                                }
                                connected = BudsClient.isConnected
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (busy) "进行中…" else "仅连接探测（不发指令）")
                    }
                }
            }

            if (log.isNotEmpty()) {
                val clipboard = LocalClipboardManager.current
                val ctx = LocalContext.current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                clipboard.setText(AnnotatedString(log))
                                Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show()
                            }
                        )
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "日志 · 长按复制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val send = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, log)
                            }
                            ctx.startActivity(
                                android.content.Intent.createChooser(send, "分享日志")
                            )
                        }) { Text("分享") }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(log))
                            Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show()
                        }) { Text("复制") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }

            // Diagnostic only: record the mode the earbuds are ACTUALLY in, as heard by the
            // user. Building a frame-to-mode mapping needs this ground truth.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "标定：我听到的当前档位是",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "长按耳机切一次，然后点下面与「实际听到」相符的按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            BudsClient.MODE_ANC to "降噪",
                            BudsClient.MODE_PASS to "通透",
                            BudsClient.MODE_OFF to "关闭",
                        ).forEach { (mode, label) ->
                            OutlinedButton(
                                onClick = { BudsClient.logGroundTruth(mode) },
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    // Raw capture: every byte in and out is appended to a file so the
                    // frame layout can be analysed offline instead of guessed at.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "原始帧录制",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                dumpPath ?: "文件不可用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Switch(
                            checked = dumping,
                            onCheckedChange = { on ->
                                dumping = on
                                BudsClient.dumpEnabled = on
                                if (on) {
                                    runCatching {
                                        java.io.File(dumpPath ?: "").writeText("")
                                    }
                                    Toast.makeText(ctx, "开始录制", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "已停止，文件已保存", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("退出专家模式") }

            Spacer(Modifier.height(32.dp))
        }
    }
}
