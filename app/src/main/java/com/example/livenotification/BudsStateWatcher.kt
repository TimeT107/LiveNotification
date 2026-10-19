package com.example.livenotification

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Periodically drains the earbud link.
 *
 * The link only ever delivers echoes of our own writes, so this exists purely to keep the
 * socket from accumulating bytes and to keep a readable trace in logcat. Mode detection
 * happens in [BudsModeFrame] as frames are drained, not here.
 */
object BudsStateWatcher {

    private const val TAG = "BudsStateWatcher"
    private const val INTERVAL_MS = 600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var lastLogged = ""

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            Log.d(TAG, "已启动")
            while (true) {
                try {
                    if (BudsClient.isConnected) {
                        for (f in BudsClient.pollStateFrames()) {
                            if (f != lastLogged) {
                                lastLogged = f
                                Log.d(TAG, "FRAME $f")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "轮询失败: ${t.message}")
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
