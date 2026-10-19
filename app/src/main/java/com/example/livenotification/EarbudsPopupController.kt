package com.example.livenotification

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Shows the earbuds card whenever the headset's audio link comes up.
 *
 * Trigger choice: A2DP `STATE_CONNECTED`. That is the moment the earbuds are out of the
 * case and actually usable, which matches the "just took them out" feel. The raw ACL
 * event fires earlier (and for unrelated profiles), so it is deliberately not used.
 *
 * Note on the system Fast Pair popup: it cannot be reproduced here. Fast Pair is driven
 * by a BLE advertisement that the earbuds only broadcast while in pairing mode, so once
 * the device is bonded there is nothing for GMS to detect. Companion apps (including the
 * vendor's) draw their own card for exactly this reason, which is what this does.
 */
object EarbudsPopupController {

    private const val TAG = "EarbudsPopup"

    /** Default address; the bonded OnePlus earbuds are detected by name as well. */
    private const val DEFAULT_MAC = "08:12:87:14:1A:AE"

    private val card = EarbudsCard()

    @Volatile
    private var registered = false

    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return

            val state = intent.getIntExtra(
                BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
            )
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE
            )
            val name = try {
                device?.name
            } catch (t: Throwable) {
                null
            }
            Log.d(TAG, "A2DP state=$state device=${device?.address} name=$name")

            if (state != BluetoothProfile.STATE_CONNECTED) return
            if (!isOurEarbuds(device, name)) return

            // Warm up the independent link so the card's buttons act instantly, and so the
            // app works with the earbuds without any vendor app installed.
            Thread {
                try {
                    if (!BudsClient.isConnected) {
                        val r = BudsClient.connect(killCompanionFirst = false)
                        Log.d(TAG, "自动建链: $r")
                    }
                    // Follow changes made with the earbuds' own touch controls.
                    BudsStateWatcher.start()
                } catch (t: Throwable) {
                    Log.w(TAG, "自动建链失败: ${t.message}")
                }
            }.start()

            handler.post { card.show(context.applicationContext, name ?: "OnePlus Buds Ace") }
        }
    }

    private fun isOurEarbuds(device: BluetoothDevice?, name: String?): Boolean {
        val addr = device?.address
        if (addr != null && addr.equals(DEFAULT_MAC, ignoreCase = true)) return true
        val n = name ?: return false
        return n.contains("Buds", ignoreCase = true)
    }

    /**
     * Fired whenever the active mode changes, including changes made with the earbuds' own
     * touch controls. Shows the top-left chip and refreshes the card if it is open.
     */
    private val modeListener: (Int) -> Unit = { mode ->
        handler.post {
            val ctx = appContext
            if (ctx != null && BudsControlSettings.readChipEnabled(ctx)) {
                ChipOverlay.showBudsMode(ctx, mode)
            }
            card.notifyModeChanged(mode)
        }
    }

    @Volatile
    private var appContext: Context? = null

    /** True when the overlay permission is available, otherwise the card cannot be shown. */
    @SuppressLint("MissingPermission")
    fun install(context: Context) {
        if (registered) return
        appContext = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，卡片不会显示")
            return
        }
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        try {
            context.registerReceiver(receiver, filter)
            registered = true
            Log.d(TAG, "已注册 A2DP 连接监听")
        } catch (t: Throwable) {
            Log.w(TAG, "注册失败: ${t.message}")
        }

        // Follow mode changes, wherever they were made.
        BudsClient.addModeListener(modeListener)

        // If the earbuds are already connected when this starts, show the card once.
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter?.isEnabled == true) {
                val bonded = adapter.bondedDevices
                val eb = bonded?.firstOrNull {
                    it.address.equals(DEFAULT_MAC, ignoreCase = true) ||
                        (it.name ?: "").contains("Buds", ignoreCase = true)
                }
                if (eb != null && adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
                ) {
                    Log.d(TAG, "启动时耳机已连接，直接显示卡片")
                    Thread {
                        try {
                            if (!BudsClient.isConnected) {
                                Log.d(TAG, "启动建链: " + BudsClient.connect(killCompanionFirst = false))
                            }
                            BudsStateWatcher.start()
                        } catch (t: Throwable) {
                            Log.w(TAG, "启动建链失败: ${t.message}")
                        }
                    }.start()
                    handler.postDelayed(
                        { card.show(context.applicationContext, eb.name ?: "OnePlus Buds Ace") },
                        1500
                    )
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "启动时检查连接状态失败: ${t.message}")
        }
    }

    fun uninstall(context: Context) {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        registered = false
        card.dismiss()
    }
}
