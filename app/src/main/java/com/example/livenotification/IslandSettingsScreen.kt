package com.example.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.livenotification.island.PreviewActivity
import com.example.livenotification.island.config.ConfigSnapshot
import com.example.livenotification.island.config.ConfigStore
import com.example.livenotification.island.config.IslandConfig
import com.example.livenotification.island.util.LayoutMath
import kotlin.math.roundToInt

/**
 * 锁屏岛设置页（Compose 版）。
 *
 * 取代原先的 [com.example.livenotification.island.SettingsActivity]（那套是纯代码搭 Android View，
 * 自带 Material3 静态主题，和主软件风格割裂）。现在它就是一个普通的 Compose 页面：
 * Scaffold + TopAppBar + surfaceContainerHigh 卡片 + Switch/Slider，和主软件 SettingsScreen
 * 同一套设计语言，颜色走 MainActivity 的 dynamic color，深浅色自动跟随。
 *
 * 配置写入路径与旧版完全一致（缺一不可，否则"改了不生效"）：
 *   1. ConfigStore（本进程 prefs）
 *   2. 写共享快照文件（Android/data|media/<包名>/config.txt）
 *   3. 广播完整快照 ACTION_CONFIG_SNAPSHOT + ACTION_CONFIG_CHANGED 给 SystemUI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IslandSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val host = remember(context) { IslandSettingsHost(context) }
    var status by remember { mutableStateOf<String?>(null) }
    // 恢复默认后 +1，让下面所有 remember(revision) 的控件重新从 prefs 读一遍
    var revision by remember { mutableIntStateOf(0) }

    BackHandler(enabled = true, onBack = onBack)

    LaunchedEffect(Unit) {
        status = host.readStatus()
        // 主动问一次 SystemUI：它会回报注入状态 + hook 清单
        host.notifySystemUi()
    }

    DisposableEffect(host) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != IslandConfig.ACTION_STATUS_REPORT) return
                val s = intent.getStringExtra(IslandConfig.EXTRA_STATUS).orEmpty()
                val at = intent.getStringExtra(IslandConfig.EXTRA_AT).orEmpty()
                status = if (s.isBlank()) null else if (at.isBlank()) s else "$s\n$at"
            }
        }
        val filter = IntentFilter(IslandConfig.ACTION_STATUS_REPORT)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    var enabled by remember(revision) {
        mutableStateOf(host.bool(IslandConfig.KEY_ENABLED, IslandConfig.DEF_ENABLED))
    }
    var verticalBias by remember(revision) {
        mutableIntStateOf(host.int(IslandConfig.KEY_VERTICAL_BIAS, IslandConfig.DEF_VERTICAL_BIAS))
    }
    var dragEnabled by remember(revision) {
        mutableStateOf(host.bool(IslandConfig.KEY_DRAG_ENABLED, IslandConfig.DEF_DRAG_ENABLED))
    }
    var widthPercent by remember(revision) {
        mutableIntStateOf(host.int(IslandConfig.KEY_WIDTH_PERCENT, IslandConfig.DEF_WIDTH_PERCENT))
    }
    var heightDp by remember(revision) {
        mutableIntStateOf(host.int(IslandConfig.KEY_HEIGHT_DP, IslandConfig.DEF_HEIGHT_DP))
    }
    var expandedHeightDp by remember(revision) {
        mutableIntStateOf(
            host.int(IslandConfig.KEY_EXPANDED_HEIGHT_DP, IslandConfig.DEF_EXPANDED_HEIGHT_DP)
        )
    }
    var showOnAod by remember(revision) {
        mutableStateOf(host.bool(IslandConfig.KEY_SHOW_ON_AOD, IslandConfig.DEF_SHOW_ON_AOD))
    }
    var debugAlwaysShow by remember(revision) {
        mutableStateOf(
            host.bool(IslandConfig.KEY_DEBUG_ALWAYS_SHOW, IslandConfig.DEF_DEBUG_ALWAYS_SHOW)
        )
    }
    var debugLog by remember(revision) {
        mutableStateOf(host.bool(IslandConfig.KEY_DEBUG_LOG, IslandConfig.DEF_DEBUG_LOG))
    }
    var musicStyle by remember(revision) {
        mutableIntStateOf(host.int(IslandConfig.KEY_MUSIC_STYLE, IslandConfig.DEF_MUSIC_STYLE))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("锁屏岛", fontWeight = FontWeight.SemiBold) },
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
            IslandSectionTitle(stringResource(R.string.section_debug))
            IslandCard {
                IslandInfoRow(
                    label = stringResource(R.string.status_module),
                    value = BuildConfig.VERSION_NAME,
                )
                IslandInfoRow(
                    label = stringResource(R.string.status_hooked),
                    value = if (status == null) {
                        stringResource(R.string.status_hooked_no)
                    } else {
                        status!!.lineSequence().firstOrNull().orEmpty()
                    },
                    valueColor = if (status == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                val detail = status?.lineSequence()?.drop(1)?.joinToString("\n").orEmpty()
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row {
                    TextButton(onClick = { host.restartSystemUi() }) {
                        Text(stringResource(R.string.btn_restart_sysui))
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(context, PreviewActivity::class.java))
                        }
                    ) {
                        Text(stringResource(R.string.btn_preview))
                    }
                }
            }

            // ---------------- 外观与位置 ----------------
            IslandSectionTitle(stringResource(R.string.section_layout))
            IslandCard {
                IslandToggleRow(
                    title = stringResource(R.string.pref_enable),
                    subtitle = stringResource(R.string.pref_enable_desc),
                    checked = enabled,
                ) {
                    enabled = it
                    host.setBool(IslandConfig.KEY_ENABLED, it)
                    host.notifySystemUi()
                }

                IslandDivider()

                IslandSliderRow(
                    title = stringResource(R.string.pref_vertical_bias),
                    value = verticalBias,
                    min = 20,
                    max = 100,
                    unit = "%",
                    subtitle = host.verticalBiasHint(),
                ) {
                    verticalBias = it
                    host.setInt(IslandConfig.KEY_VERTICAL_BIAS, it)
                }

                IslandDivider()

                IslandToggleRow(
                    title = stringResource(R.string.pref_drag_enabled),
                    subtitle = stringResource(R.string.pref_drag_enabled_desc),
                    checked = dragEnabled,
                ) {
                    dragEnabled = it
                    host.setBool(IslandConfig.KEY_DRAG_ENABLED, it)
                    host.notifySystemUi()
                }

                IslandDivider()

                IslandSliderRow(
                    title = stringResource(R.string.pref_width_percent),
                    value = widthPercent,
                    min = 40,
                    max = 100,
                    unit = "%",
                    subtitle = stringResource(R.string.pref_width_percent_desc),
                ) {
                    widthPercent = it
                    host.setInt(IslandConfig.KEY_WIDTH_PERCENT, it)
                }

                IslandDivider()

                IslandSliderRow(
                    title = stringResource(R.string.pref_height),
                    value = heightDp,
                    min = 40,
                    max = 96,
                    unit = "dp",
                    subtitle = stringResource(R.string.pref_height_desc),
                ) {
                    heightDp = it
                    host.setInt(IslandConfig.KEY_HEIGHT_DP, it)
                }

                IslandDivider()

                IslandSliderRow(
                    title = stringResource(R.string.pref_expanded_height),
                    value = expandedHeightDp,
                    min = 72,
                    max = 200,
                    unit = "dp",
                    subtitle = stringResource(R.string.pref_expanded_height_desc),
                ) {
                    expandedHeightDp = it
                    host.setInt(IslandConfig.KEY_EXPANDED_HEIGHT_DP, it)
                }

                IslandDivider()

                IslandToggleRow(
                    title = stringResource(R.string.pref_show_on_aod),
                    subtitle = "",
                    checked = showOnAod,
                ) {
                    showOnAod = it
                    host.setBool(IslandConfig.KEY_SHOW_ON_AOD, it)
                    host.notifySystemUi()
                }
            }

            // ---------------- 音乐锁屏样式 ----------------
            IslandSectionTitle("音乐锁屏")
            IslandCard {
                Text(
                    "音乐锁屏样式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = musicStyle == IslandConfig.MUSIC_STYLE_CURRENT,
                        onClick = {
                            musicStyle = IslandConfig.MUSIC_STYLE_CURRENT
                            host.setInt(IslandConfig.KEY_MUSIC_STYLE, IslandConfig.MUSIC_STYLE_CURRENT)
                            host.notifySystemUi()
                        },
                        label = { Text("铺满封面") },
                    )
                    FilterChip(
                        selected = musicStyle == IslandConfig.MUSIC_STYLE_ONEUI,
                        onClick = {
                            musicStyle = IslandConfig.MUSIC_STYLE_ONEUI
                            host.setInt(IslandConfig.KEY_MUSIC_STYLE, IslandConfig.MUSIC_STYLE_ONEUI)
                            host.notifySystemUi()
                        },
                        label = { Text("卡片式") },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "卡片式 = 独立圆角封面 + 只框歌名与控件（参考 One UI）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 调试 ----------------
            IslandSectionTitle("调试")
            IslandCard {
                IslandToggleRow(
                    title = "调试模式：始终显示",
                    subtitle = "打开后即使没有媒体/状态也会显示胶囊，用来确认注入是否成功",
                    checked = debugAlwaysShow,
                ) {
                    debugAlwaysShow = it
                    host.setBool(IslandConfig.KEY_DEBUG_ALWAYS_SHOW, it)
                    host.notifySystemUi()
                }

                IslandDivider()

                IslandToggleRow(
                    title = stringResource(R.string.pref_debug_log),
                    subtitle = stringResource(R.string.pref_debug_log_desc),
                    checked = debugLog,
                ) {
                    debugLog = it
                    host.setBool(IslandConfig.KEY_DEBUG_LOG, it)
                    host.notifySystemUi()
                }

                IslandDivider()

                TextButton(onClick = {
                    host.resetToDefaults()
                    revision++
                }) {
                    Text(stringResource(R.string.pref_reset))
                }
            }

            // ---------------- 使用说明 ----------------
            IslandSectionTitle(stringResource(R.string.help_title))
            IslandCard {
                Text(
                    stringResource(R.string.help_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ============================================================
// 小组件（与主软件 SettingsScreen 同一套视觉）
// ============================================================

@Composable
private fun IslandSectionTitle(text: String) {
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
private fun IslandCard(content: @Composable () -> Unit) {
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
private fun IslandDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun IslandToggleRow(
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
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IslandSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String,
    subtitle: String,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$value$unit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IslandInfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
        )
    }
}

// ============================================================
// 非 UI 逻辑（照搬旧 SettingsActivity，行为保持一致）
// ============================================================

private class IslandSettingsHost(private val context: Context) {

    fun bool(key: String, def: Boolean): Boolean = ConfigStore.getBoolean(context, key, def)

    fun int(key: String, def: Int): Int = ConfigStore.getInt(context, key, def)

    fun setBool(key: String, value: Boolean) = ConfigStore.putBoolean(context, key, value)

    fun setInt(key: String, value: Int) = ConfigStore.putInt(context, key, value)

    /** 写快照文件 + 推送快照广播 + 老通道广播。三条通道缺一不可。 */
    fun notifySystemUi() {
        runCatching {
            val text = writeConfigSnapshot()
            if (text != null) {
                runCatching {
                    context.sendBroadcast(
                        Intent(IslandConfig.ACTION_CONFIG_SNAPSHOT).apply {
                            setPackage("com.android.systemui")
                            putExtra(IslandConfig.EXTRA_SNAPSHOT, text)
                        }
                    )
                }
            }
            context.sendBroadcast(
                Intent(IslandConfig.ACTION_CONFIG_CHANGED).apply {
                    setPackage("com.android.systemui")
                }
            )
        }
    }

    /**
     * 把当前设置写成快照文件。
     * 以 [ConfigSnapshot.ALL_KEYS] 为唯一来源（[ConfigSnapshot.buildValues]），
     * 绝不手抄 key 列表 —— 手抄漏键会让打开一次设置界面就抹掉某个配置（真机事故）。
     */
    private fun writeConfigSnapshot(): String? {
        return try {
            val stored = HashMap<String, Any?>()
            runCatching { ConfigStore.get(context)?.all?.forEach { (k, v) -> stored[k] = v } }
            val values = ConfigSnapshot.buildValues(stored, IslandConfig.SNAPSHOT_DEFAULTS)
            val text = ConfigSnapshot.encode(values)

            val written = mutableListOf<String>()
            for (relative in IslandConfig.SNAPSHOT_RELATIVE_PATHS) {
                try {
                    val base = Environment.getExternalStorageDirectory()
                    val file = java.io.File(base, relative)
                    val dir = file.parentFile
                    if (dir != null && !dir.exists()) dir.mkdirs()
                    file.writeText(text, Charsets.UTF_8)
                    written.add(file.absolutePath)
                } catch (_: Throwable) {
                    // 单条路径失败无所谓，继续试下一个
                }
            }

            // 内部存储兜底：外部存储完全不可用时至少留一份
            if (written.isEmpty()) {
                runCatching {
                    java.io.File(context.filesDir, IslandConfig.SNAPSHOT_FILE_NAME)
                        .writeText(text, Charsets.UTF_8)
                }
            }
            text
        } catch (_: Throwable) {
            null
        }
    }

    fun restartSystemUi() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f com.android.systemui"))
        } catch (_: Throwable) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f com.android.systemui"))
            } catch (_: Throwable) {
            }
        }
    }

    fun resetToDefaults() {
        val sp = ConfigStore.pref(context) ?: return
        sp.edit()
            .putBoolean(IslandConfig.KEY_ENABLED, IslandConfig.DEF_ENABLED)
            .putInt(IslandConfig.KEY_VERTICAL_BIAS, IslandConfig.DEF_VERTICAL_BIAS)
            .putInt(IslandConfig.KEY_WIDTH_PERCENT, IslandConfig.DEF_WIDTH_PERCENT)
            .putInt(IslandConfig.KEY_MAX_WIDTH_DP, IslandConfig.DEF_MAX_WIDTH_DP)
            .putInt(IslandConfig.KEY_HEIGHT_DP, IslandConfig.DEF_HEIGHT_DP)
            .putInt(IslandConfig.KEY_EXPANDED_HEIGHT_DP, IslandConfig.DEF_EXPANDED_HEIGHT_DP)
            .putInt(IslandConfig.KEY_CORNER_DP, IslandConfig.DEF_CORNER_DP)
            .putBoolean(IslandConfig.KEY_SHOW_ON_AOD, IslandConfig.DEF_SHOW_ON_AOD)
            .putBoolean(IslandConfig.KEY_DEBUG_ALWAYS_SHOW, IslandConfig.DEF_DEBUG_ALWAYS_SHOW)
            .putBoolean(IslandConfig.KEY_DEBUG_LOG, IslandConfig.DEF_DEBUG_LOG)
            .putInt(IslandConfig.KEY_MUSIC_STYLE, IslandConfig.DEF_MUSIC_STYLE)
            .apply()
        notifySystemUi()
    }

    /** 优先读 hook 写的状态文件，其次返回 null（界面会显示"未注入"）。 */
    fun readStatus(): String? = readHookStatusFile()

    private fun readHookStatusFile(): String? {
        return try {
            val f = java.io.File(context.filesDir, "hook_status.txt")
            if (!f.exists()) return null
            val text = f.readText(Charsets.UTF_8)
            val map = text.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }
                .toMap()
            val status = map["status"] ?: return null
            if (status.isBlank()) return null

            val hookReport = map["hookreport"].orEmpty()
            val failures = hookReport.split(';')
                .map { it.trim() }
                .filter { it.contains("=FAIL") }

            buildString {
                append(status)
                append('\n')
                append(map["at"].orEmpty())
                if (hookReport.isNotEmpty()) {
                    append("\nHook 清单：")
                    append(if (failures.isEmpty()) "全部命中" else "${failures.size} 项未命中")
                    for (fail in failures) {
                        append("\n  ⚠ ")
                        append(fail)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把「垂直位置」百分比翻译成实际像素区间，让用户知道拖到多少才是
     * 「时钟下方、快捷方式之间」，而不是盲拖。
     */
    fun verticalBiasHint(): String {
        return try {
            val dm = context.resources.displayMetrics
            val screenH = dm.heightPixels
            val density = dm.density
            fun dp(v: Int) = (v * density).toInt()

            val heightDp = ConfigStore.getInt(
                context, IslandConfig.KEY_HEIGHT_DP, IslandConfig.DEF_HEIGHT_DP
            )
            val islandH = dp(heightDp)
            val shortcutsTop = screenH - dp(188)
            val statusBar = dp(32)
            val clockTop = dp(18 + 48) + statusBar / 2
            val clockBottom = clockTop + dp(112)

            val minPct = LayoutMath.percentForTop(screenH, islandH, clockBottom)
            val recommendedPct = LayoutMath.percentForTop(screenH, islandH, clockBottom + dp(16))
            val maxPct = LayoutMath.percentForTop(screenH, islandH, shortcutsTop - islandH)

            context.getString(R.string.pref_vertical_bias_desc) +
                "\n屏幕 ${dm.widthPixels}×${screenH}px · 岛高 ${heightDp}dp 基准" +
                "\n可用区间约 $minPct% ~ $maxPct%（对应时钟下方到快捷方式上方）" +
                "\n推荐 $recommendedPct%" +
                "\n也可以直接在锁屏上长按岛拖动，松手自动保存"
        } catch (_: Throwable) {
            context.getString(R.string.pref_vertical_bias_desc)
        }
    }
}
