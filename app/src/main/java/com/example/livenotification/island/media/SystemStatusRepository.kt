package com.example.livenotification.island.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import com.example.livenotification.island.hook.IslandLogger
import com.example.livenotification.island.ui.IslandView

/**
 * 一条系统状态提示（充电 / 耳机 / ...）。
 */
data class StatusChip(
    val glyph: Int,
    val text: String,
    val detail: String?
)

/**
 * 系统状态来源：闹钟、充电、耳机。
 *
 * 都走公开 API，不需要任何权限，也不依赖 SystemUI 内部类：
 *  - 闹钟：AlarmManager.getNextAlarmClock()。SystemUI 是特权应用且持有
 *    READ_PRIVILEGED_PHONE_STATE（反编译 AndroidManifest 第 36 行确认），
 *    所以能拿到**所有**用户设的闹钟，而不是被过滤成"只属于自己包"的。
 *  - 充电：BatteryManager（sticky）
 *  - 有线耳机：ACTION_HEADSET_PLUG（sticky）
 *  - 蓝牙/Type-C/USB 音频：AudioManager.getDevices
 *
 * 关于「秒表/计时器」：已在整个 SystemUI 里搜过 stopwatch/stage timer 相关符号，
 * 只命中 Guava 的工具类，SystemUI 根本不持有秒表状态。秒表状态只存在于
 * 时钟（DeskClock）应用内部，没有公开 API，硬读它的数据库太脆弱，故不做。
 */
class SystemStatusRepository(
    private val context: Context,
    private val log: IslandLogger
) {

    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var headsetPlugged = false

    @Volatile
    private var charging = false

    @Volatile
    private var batteryLevel = -1

    @Volatile
    private var externalAudioLabel: String? = null

    @Volatile
    private var lastAudioScanAt: Long = 0L

    @Volatile
    private var lastAlarmScanAt: Long = 0L

    @Volatile
    private var cachedAlarmChip: StatusChip? = null

    private companion object {
        const val AUDIO_SCAN_INTERVAL_MS = 3000L
        const val ALARM_SCAN_INTERVAL_MS = 5000L

        /** 只显示这个时间窗内的闹钟（12 小时），更远的以后再说 */
        const val ALARM_LOOKAHEAD_MS = 12L * 60 * 60 * 1000
    }

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_HEADSET_PLUG -> {
                        headsetPlugged = intent.getIntExtra("state", 0) == 1
                        log.i("有线耳机状态 -> $headsetPlugged")
                    }

                    Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        batteryLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    }
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(r, filter)
            }
            receiver = r
            log.i("系统状态监听已启动")
        }.onFailure { log.e("系统状态监听启动失败", it) }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * 当前应当显示的 chip；没有可显示的返回 null。
     * 优先级：闹钟 > 充电 > 耳机。
     *
     * 闹钟排最前，因为它是「有时间点的事件」，用户最需要一眼看到；
     * 耳机是最弱的信息（插着就一直显示），放最后。
     */
    fun currentChip(): StatusChip? {
        refreshExternalAudio()

        nextAlarmChip()?.let { return it }

        if (charging) {
            return StatusChip(
                glyph = com.example.livenotification.island.ui.Glyphs.CHARGING,
                text = "正在充电",
                detail = if (batteryLevel in 0..100) "$batteryLevel%" else null
            )
        }

        val audioLabel = externalAudioLabel
        if (audioLabel != null) {
            return StatusChip(
                glyph = com.example.livenotification.island.ui.Glyphs.HEADSET,
                text = audioLabel,
                detail = null
            )
        }
        return null
    }

    /**
     * 最近的闹钟。
     *
     * 只显示未来 [ALARM_LOOKAHEAD_MS] 内的闹钟，避免"明天早上的闹钟"
     * 从现在起就一直占着锁屏岛 —— 那不是用户此刻关心的信息。
     */
    private fun nextAlarmChip(): StatusChip? {
        val now = System.currentTimeMillis()
        if (now - lastAlarmScanAt < ALARM_SCAN_INTERVAL_MS) {
            return cachedAlarmChip
        }
        lastAlarmScanAt = now

        cachedAlarmChip = try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            val info = am?.nextAlarmClock
            val trigger = info?.triggerTime ?: 0L
            when {
                trigger <= now -> null
                trigger - now > ALARM_LOOKAHEAD_MS -> null
                else -> {
                    val label = formatAlarmTime(trigger)
                    StatusChip(
                        glyph = com.example.livenotification.island.ui.Glyphs.TIMER,
                        text = "闹钟 $label",
                        detail = relativeDay(now, trigger)
                    )
                }
            }
        } catch (t: Throwable) {
            log.i("读取闹钟失败（可忽略）：${t.message}")
            null
        }
        return cachedAlarmChip
    }

    private fun formatAlarmTime(triggerMs: Long): String {
        return try {
            val fmt = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
            java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                .format(java.util.Date(triggerMs))
        } catch (_: Throwable) {
            ""
        }
    }

    /** 相对天数：今天 / 明天 / X 天后 */
    private fun relativeDay(nowMs: Long, triggerMs: Long): String? {
        return try {
            val zone = java.util.TimeZone.getDefault()
            fun dayOf(ms: Long): Long = (ms + zone.getOffset(ms)) / 86_400_000L
            val diff = dayOf(triggerMs) - dayOf(nowMs)
            when {
                diff <= 0L -> null
                diff == 1L -> "明天"
                diff == 2L -> "后天"
                else -> "$diff 天后"
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun refreshExternalAudio() {
        // AudioManager.getDevices 是跨进程调用，做了 3 秒缓存，
        // 避免可见性判定频繁触发时反复 IPC。
        val now = System.currentTimeMillis()
        if (now - lastAudioScanAt < AUDIO_SCAN_INTERVAL_MS) return
        lastAudioScanAt = now

        externalAudioLabel = try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val devices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val external = devices?.firstOrNull { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true

                    else -> false
                }
            }

            when (external?.type) {
                null -> null

                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "已连接耳机"

                else -> {
                    val name = external.productName?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) "已连接蓝牙音频" else "已连接 $name"
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
