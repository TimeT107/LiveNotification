package com.example.livenotification

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.lang.reflect.Method

/**
 * Independent OnePlus Buds Ace client — talks to the earbuds without the 欢律 app.
 *
 * Established by instrumenting the companion app (HCI capture + socket-output sniffing):
 *
 *   - The app writes raw AA frames straight into BluetoothOutputStream; RFCOMM framing
 *     (DLCI, length, FCS) is handled by the Bluetooth stack and the earbud firmware.
 *     So this client only has to write the AA bytes itself.
 *
 *   - Transport is BR/EDR RFCOMM server channel 15.
 *
 *   - Noise mode command (variant A, the "set" path):
 *         aa 0b 00 00 04 02 <seq> 04 00 03 01 01 <mode>
 *     mode: 0x01 = ANC, 0x02 = transparency, 0x04 = off
 *
 *   - Variant B, the "report/update" path seen from the app's socket:
 *         aa 0a 00 00 04 04 <seq> 03 00 01 01 <mode>
 *
 * UNRESOLVED: the `seq` field is not a simple incrementing counter (observed values jump
 * and repeat), so its contract with the earbuds is still unknown. This client therefore
 * exposes a settable seq so the value can be searched experimentally.
 */
object BudsClient {

    private const val TAG = "BudsClient"

    /** Earbuds address; a bonded device is used when this one is not found. */
    const val DEFAULT_MAC = "08:12:87:14:1A:AE"

    /** RFCOMM server channel used by the vendor protocol. */
    const val CHANNEL = 15

    /**
     * Noise-mode values used on the wire.
     *
     * These were confirmed by listening to the earbuds, not inferred: the numbering is
     * NOT the logical order (off is 0x02 and transparency is 0x04, not the other way
     * round), which is easy to get wrong from the protocol shape alone.
     */
    const val MODE_ANC = 0x01
    const val MODE_PASS = 0x04
    const val MODE_OFF = 0x02

    fun modeName(mode: Int): String = when (mode) {
        MODE_ANC -> "降噪"
        MODE_PASS -> "通透"
        MODE_OFF -> "关闭"
        else -> "未知"
    }

    /** Last exchange, kept for display in the UI. */
    @Volatile
    var lastLog: String = ""
        private set

    private fun note(line: String) {
        Log.d(TAG, line)
        lastLog = (lastLog + line + "\n").takeLast(6000)
        if (dumpEnabled) {
            try {
                dumpFile?.appendText("[${System.currentTimeMillis()}] $line\n")
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------ raw dump

    /** When on, every byte we send or receive is appended to a file for offline analysis. */
    @Volatile
    var dumpEnabled: Boolean = false

    private var dumpFile: java.io.File? = null

    fun setDumpTarget(file: java.io.File) {
        dumpFile = file
    }

    /** Append a raw received frame to the dump, independent of any interpretation. */
    private fun dumpRx(frame: String) {
        if (!dumpEnabled) return
        try {
            dumpFile?.appendText("[${System.currentTimeMillis()}] RX $frame\n")
        } catch (_: Throwable) {
        }
    }

    /**
     * Snapshot of what is already waiting on the socket, taken right after connecting.
     *
     * If nothing is pending here, then every later "received" frame is plausibly an echo of
     * our own write rather than an unsolicited earbud message.
     */
    fun dumpPendingBytes() {
        if (!dumpEnabled) return
        try {
            val s = socket ?: return
            val ins = s.inputStream
            val avail = ins.available()
            dumpRx("CONNECT-SNAPSHOT available=$avail")
            if (avail > 0) {
                val b = ByteArray(avail)
                val n = ins.read(b)
                dumpRx("CONNECT-PENDING($n) ${hex(b.copyOf(n))}")
            }
        } catch (t: Throwable) {
            dumpRx("CONNECT-SNAPSHOT failed: ${t.message}")
        }
    }

    /** Append what we sent to the dump. */
    private fun dumpTx(frame: String) {
        if (!dumpEnabled) return
        try {
            dumpFile?.appendText("[${System.currentTimeMillis()}] TX $frame\n")
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ framing

    /**
     * Build an AA frame.
     *
     * Layout, verified against every captured frame:
     *     aa | len | 00 00 | b2 | b3 | seq | cmd | args...
     * with `len == total size - 2`.
     *
     * The two zero bytes after `len` are fixed framing, not payload. Omitting them
     * shifts every following field, makes `len` wrong, and the earbuds silently drop the
     * frame — which is exactly what happened before this was corrected.
     */
    fun frame(b2: Int, b3: Int, seq: Int, cmd: Int, args: ByteArray): ByteArray {
        val size = 8 + args.size
        val out = ByteArray(size)
        out[0] = 0xAA.toByte()
        out[1] = (size - 2).toByte()
        out[2] = 0x00
        out[3] = 0x00
        out[4] = b2.toByte()
        out[5] = b3.toByte()
        out[6] = seq.toByte()
        out[7] = cmd.toByte()
        System.arraycopy(args, 0, out, 8, args.size)
        return out
    }

    /**
     * Default noise-mode command — the frame the companion app actually writes.
     *
     * Layout captured directly from the app's BluetoothOutputStream:
     *     aa 0a 00 00 04 04 <seq> 03 00 01 01 <mode>
     * mode: 0x01 ANC / 0x02 transparency / 0x04 off
     */
    fun modeReportFrame(mode: Int, seq: Int): ByteArray =
        frame(0x04, 0x04, seq, 0x03, byteArrayOf(0x00, 0x01, 0x01, mode.toByte()))

    /**
     * Alternative layout derived from the earbuds' own report frames.
     *
     * This shape appears in the earbuds' telemetry (status/capability rather than a
     * control write), so it is kept only as an experiment; the app-backed frame above is
     * the one known to come from a real mode change.
     */
    fun modeSetFrame(mode: Int, seq: Int): ByteArray =
        frame(0x04, 0x02, seq, 0x04, byteArrayOf(0x00, 0x03, 0x01, 0x01, mode.toByte()))

    /**
     * The initialization sequence the companion app sends after connecting, captured from
     * its socket output. `seq` values are the app's own, kept verbatim.
     */
    private val INIT: List<ByteArray> = listOf(
        "aa 07 00 00 00 01 00 00 00",
        "aa 09 00 00 02 01 01 02 00 ff ff",
        "aa 07 00 00 00 02 02 00 00",
        "aa 07 00 00 06 01 03 00 00",
        "aa 07 00 00 0b 01 04 00 00",
        "aa 07 00 00 03 01 05 00 00",
        "aa 09 00 00 01 01 06 02 00 00 02",
        "aa 07 00 00 05 01 07 00 00",
        "aa 07 00 00 07 01 08 00 00",
        "aa 0a 00 00 08 01 09 03 00 02 03 01",
        "aa 09 00 00 0c 01 0a 02 00 02 01",
        "aa 09 00 00 0c 01 0b 02 00 01 01",
        "aa 09 00 00 0c 01 0c 02 00 03 01",
        "aa 09 00 00 0c 01 0d 02 00 04 01",
        "aa 07 00 00 0f 01 0e 00 00",
        "aa 0b 00 00 0d 01 0f 04 00 03 05 06 1d",
        "aa 07 00 00 21 01 10 00 00",
        "aa 07 00 00 05 01 11 00 00",
        "aa 0a 00 00 05 02 12 03 00 02 01 02",
        "aa 07 00 00 09 01 13 00 00",
        "aa 07 00 00 22 01 14 00 00",
        "aa 07 00 00 05 01 15 00 00",
        "aa 07 00 00 22 01 16 00 00",
        "aa 07 00 00 24 01 17 00 00",
        "aa 09 00 00 0c 01 18 02 00 03 01",
    ).map { hexToBytes(it) }

    fun hexToBytes(s: String): ByteArray {
        val parts = s.trim().split(Regex("\\s+"))
        return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
    }

    fun hex(b: ByteArray): String =
        b.joinToString(" ") { "%02x".format(it) }

    /**
     * Pull AA frames out of a raw socket read.
     *
     * Reads can carry leading bytes that are not part of an AA frame — either RFCOMM
     * address/control/length bytes the stack exposes, or chunk boundaries. Frames are
     * therefore located by scanning for 0xAA and validating the declared length before
     * accepting a frame.
     */
    fun extractAaFrames(buf: ByteArray, len: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < len) {
            if (buf[i] != 0xAA.toByte()) {
                i++
                continue
            }
            if (i + 2 > len) break
            val declared = buf[i + 1].toInt() and 0xFF
            val end = i + 2 + declared
            if (end > len) break
            out.add(hex(buf.copyOfRange(i, end)))
            i = end
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Kill the companion app so it releases the RFCOMM channel.
     *
     * The earbud firmware accepts a single client on server channel 15, so the app must be
     * stopped before this client can connect. Android auto-reconnects bonded devices, so
     * the kill and the connect have to happen back to back.
     *
     * KernelSU grants root per-uid; the first call may need the user to approve
     * LiveNotification in the manager.
     */
    fun killCompanionApp(): Boolean {
        val cmds = listOf(
            arrayOf("su", "-c", "am force-stop com.heytap.headset"),
            arrayOf("su", "0", "am", "force-stop", "com.heytap.headset"),
        )
        for (cmd in cmds) {
            try {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val ok = p.waitFor() == 0
                note("killCompanionApp ${cmd.joinToString(" ")} -> exit=${p.exitValue()} $out")
                if (ok) return true
            } catch (t: Throwable) {
                note("killCompanionApp 失败(${cmd.joinToString(" ")}): ${t.message}")
            }
        }
        return false
    }

    /** True when the companion app currently has a live process. */
    fun companionRunning(): Boolean = try {
        val p = ProcessBuilder("sh", "-c", "pidof com.heytap.headset")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ persistent link

    private val lock = Any()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var out: OutputStream? = null

    /** Sequence echoed back by the earbuds during the handshake; reused for commands. */
    @Volatile
    var deviceSeq: Int = 0x19
        private set

    /**
     * Mode the earbuds are believed to be in. Updated by our own writes and by the
     * state-frame watcher, so the UI and the card stay in step with the earbuds — including
     * when the mode is changed with the earbuds' own touch controls.
     */
    @Volatile
    var currentMode: Int = -1
        private set

    /** Observers fired whenever [currentMode] actually changes. */
    private val modeListeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()

    /**
     * When we last wrote a command.
     *
     * The earbuds echo frames for a while after each write, and an echo of our own mode
     * command is indistinguishable by shape from a real state report — accepting both is
     * what produced a bogus "关闭" immediately after every switch. A report is therefore
     * only trusted when the link has been quiet for a moment.
     */
    @Volatile
    private var lastWriteAtMs = System.currentTimeMillis()

    private const val ECHO_WINDOW_MS = 1400L

    /** True when received frames are still likely echoes of a recent write. */
    fun inEchoWindow(): Boolean =
        System.currentTimeMillis() - lastWriteAtMs < ECHO_WINDOW_MS

    fun addModeListener(l: (Int) -> Unit) {
        modeListeners.add(l)
    }

    fun removeModeListener(l: (Int) -> Unit) {
        modeListeners.remove(l)
    }

    private fun setCurrentModeInternal(mode: Int) {
        if (mode == currentMode) return
        currentMode = mode
        for (l in modeListeners) {
            try {
                l(mode)
            } catch (_: Throwable) {
            }
        }
    }

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Open the link once and leave it open. Every later [setModeFast] then costs a single
     * 12-byte write (milliseconds) instead of a full reconnect (~2.5 s).
     *
     * `killCompanionFirst` only matters while a vendor app is installed; with the
     * companion app uninstalled this client owns the channel outright.
     */
    @SuppressLint("MissingPermission")
    fun connect(killCompanionFirst: Boolean = false): String {
        synchronized(lock) {
            if (isConnected) return "已连接"
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return "本机没有蓝牙适配器"
            if (!adapter.isEnabled) return "蓝牙未开启"

            if (killCompanionFirst) {
                note("强制停止欢律释放通道…")
                note(if (killCompanionApp()) "欢律已停止" else "停止欢律失败（root 未授权）")
                Thread.sleep(500)
            }

            val dev = device(adapter) ?: return "找不到耳机设备"
            return try {
                val s = openSocket(dev)
                note("连接 ${dev.address} …")
                try {
                    adapter.cancelDiscovery()
                } catch (_: Throwable) {
                }
                s.connect()
                socket = s
                out = s.outputStream
                note("已连接")

                // Handshake, then pick up the sequence the earbuds acknowledge.
                for ((i, f) in INIT.withIndex()) {
                    out?.write(f)
                    Thread.sleep(20)
                }
                Thread.sleep(150)
                drainInto(responseBuffer())
                note("握手完成，deviceSeq=$deviceSeq")
                // Evidence check: is anything unsolicited waiting on the wire right after
                // the handshake, or does traffic only ever follow our own writes?
                try {
                    val avail = out?.let { socket?.inputStream?.available() } ?: -1
                    android.util.Log.d(
                        "BudsEchoDiag",
                        "握手后可用字节=$avail (若长时间为 0，则链路只是回声我们的写入)"
                    )
                } catch (_: Throwable) {
                }
                "已连接 (deviceSeq=$deviceSeq)"
            } catch (t: Throwable) {
                close()
                "连接失败：${t.message}"
            }
        }
    }

    fun close() {
        synchronized(lock) {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
            socket = null
            out = null
        }
    }

    /** Send the mode command on the existing link. Returns the reply frames seen. */
    fun setModeFast(mode: Int, useVariantA: Boolean = false, seqOverride: Int? = null): String {
        synchronized(lock) {
            val o = out ?: return "未连接"
            if (socket?.isConnected != true) {
                close()
                return "连接已断开，请重连"
            }
            val seq = seqOverride ?: deviceSeq
            val cmd = if (useVariantA) modeSetFrame(mode, seq) else modeReportFrame(mode, seq)
            return try {
                o.write(cmd)
                o.flush()
                lastWriteAtMs = System.currentTimeMillis()
                note("已发 ${modeName(mode)} seq=$seq :: ${hex(cmd)}")
                dumpTx(hex(cmd))
                setCurrentModeInternal(mode)
                Thread.sleep(150)
                val reply = drainInto(responseBuffer())
                if (reply.isNotEmpty()) note("应答 $reply")
                "已切换：${modeName(mode)}"
            } catch (t: Throwable) {
                close()
                "发送失败：${t.message}"
            }
        }
    }

    /**
     * Drain every pending frame and log it verbatim.
     *
     * Used to locate the current-mode byte in the earbuds' own state reports, which is what
     * lets the UI follow changes made with the earbuds' touch controls.
     */
    fun pollStateFrames(): List<String> {
        synchronized(lock) {
            val s = socket ?: return emptyList()
            if (!s.isConnected) return emptyList()
            val sb = responseBuffer()
            drainInto(sb)
            return sb.toString().trim().lines().filter { it.isNotBlank() }
        }
    }

    /**
     * Manual sync: record the mode the earbuds are actually in.
     *
     * Needed because a mode change made with the earbuds' own touch controls is not reported
     * on this link, so the app cannot observe it. Returns true when it changed anything.
     */
    fun syncMode(mode: Int): Boolean {
        val changed = mode != currentMode
        setCurrentModeInternal(mode)
        note("手动同步档位 -> ${modeName(mode)}")
        return changed
    }

    /** Update the tracked mode; called from the state-frame watcher. */
    fun noteModeFromDevice(mode: Int) = setCurrentModeInternal(mode)

    /**
     * Diagnostic only: record what the earbuds are actually in, as observed by the user.
     *
     * Used to build a frame-to-mode mapping when the reported byte cannot be interpreted
     * from the protocol alone. Does not affect the tracked mode.
     */
    fun logGroundTruth(actualMode: Int) {
        note("GROUND-TRUTH 用户确认当前档位 = ${modeName(actualMode)}")
    }

    private fun responseBuffer() = StringBuilder()

    /** Read whatever is already pending and return the AA frames plus the observed seqs. */
    private fun drainInto(sb: StringBuilder): String {
        val s = socket ?: return ""
        return try {
            val ins = s.inputStream
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + 250
            while (System.currentTimeMillis() < deadline && ins.available() > 0) {
                val n = ins.read(buf)
                if (n <= 0) break
                // Physical-layer dump: the bytes actually delivered by the socket,
                // including any RFCOMM address/control/length prefix. This is what tells
                // echoes of our own writes apart from genuine earbud traffic.
                dumpRx("RAW($n) ${hex(buf.copyOf(n))}")
                for (f in extractAaFrames(buf, n)) {
                    sb.append(f).append('\n')
                    dumpRx(f)
                    android.util.Log.d("BudsClient", "RX $f")
                    val b = hexToBytes(f)
                    if (b.size > 6 && b[7].toInt() == 0x0F) {
                        deviceSeq = b[6].toInt() and 0xFF
                    }
                    // Everything delivered here is an echo of what we wrote, so the mode it
                    // carries is the one we requested. No filtering needed: the echo is the
                    // only source of truth available on this link.
                    BudsModeFrame.parse(b)?.let { setCurrentModeInternal(it) }
                }
            }
            sb.toString().trim()
        } catch (t: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------------ socket

    private fun device(adapter: BluetoothAdapter): BluetoothDevice? {
        // Prefer the bonded device that owns the known address.
        val bonded = try {
            adapter.bondedDevices
        } catch (t: Throwable) {
            null
        }
        bonded?.firstOrNull { it.address.equals(DEFAULT_MAC, ignoreCase = true) }?.let {
            note("使用已配对设备 ${it.address} (${it.name})")
            return it
        }
        // Fall back to any bonded device whose name looks like the earbuds.
        bonded?.firstOrNull {
            (it.name ?: "").contains("Buds", ignoreCase = true) ||
                (it.name ?: "").contains("OnePlus", ignoreCase = true)
        }?.let {
            note("按名称匹配到 ${it.address} (${it.name})")
            return it
        }
        return try {
            adapter.getRemoteDevice(DEFAULT_MAC).also { note("按 MAC 构造 $DEFAULT_MAC") }
        } catch (t: Throwable) {
            note("找不到设备: ${t.message}")
            null
        }
    }

    /**
     * Open an RFCOMM socket on the vendor channel. The channel-specific factory is hidden,
     * so it is reached reflectively; the SDP-based call is kept as a fallback.
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        try {
            val m: Method = device.javaClass.getMethod(
                "createInsecureRfcommSocket", Int::class.javaPrimitiveType
            )
            @Suppress("DEPRECATION")
            val s = m.invoke(device, CHANNEL) as BluetoothSocket
            note("createInsecureRfcommSocket($CHANNEL) 成功")
            return s
        } catch (t: Throwable) {
            note("反射建 socket 失败(${t.message})，回退 SDP")
        }
        @Suppress("DEPRECATION")
        val uuid = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        return device.createInsecureRfcommSocketToServiceRecord(uuid)
    }

    /**
     * Connect, optionally replay the captured init sequence, then send the mode command.
     *
     * @param mode       one of MODE_ANC / MODE_PASS / MODE_OFF
     * @param sendInit   replay the 25-frame init sequence first
     * @param useVariantA true = the "set" frame, false = the "report" frame
     * @param seq        sequence value to place in the mode frame
     */
    @SuppressLint("MissingPermission")
    suspend fun setMode(
        mode: Int,
        sendInit: Boolean = true,
        useVariantA: Boolean = true,
        seq: Int = 0x19,
        killCompanionFirst: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        lastLog = ""
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext "本机没有蓝牙适配器".also { note(it) }
        if (!adapter.isEnabled) return@withContext "蓝牙未开启".also { note(it) }

        if (killCompanionFirst) {
            note("欢律进程存在: ${companionRunning()}")
            note("正在强制停止欢律以释放通道…")
            note(if (killCompanionApp()) "欢律已停止" else "停止欢律失败（可能未授予 root）")
            Thread.sleep(600)
        }

        val dev = device(adapter) ?: return@withContext "找不到耳机设备".also { note(it) }
        var socket: BluetoothSocket? = null
        try {
            socket = openSocket(dev)
            note("正在连接 ${dev.address} ...")
            try {
                adapter.cancelDiscovery()
            } catch (_: Throwable) {
            }
            socket.connect()
            note("已连接")

            val out: OutputStream = socket.outputStream

            if (sendInit) {
                note("发送初始化序列 (${INIT.size} 帧)")
                for ((i, f) in INIT.withIndex()) {
                    out.write(f)
                    note("  init[$i] ${hex(f)}")
                    Thread.sleep(25)
                }
                Thread.sleep(120)
            }

            val cmd = if (useVariantA) modeSetFrame(mode, seq) else modeReportFrame(mode, seq)
            note("发送模式命令 (${modeName(mode)}, seq=$seq) ${hex(cmd)}")
            out.write(cmd)
            out.flush()

            // Drain whatever the earbuds send back for a moment.
            Thread.sleep(400)
            val reply = StringBuilder()
            try {
                val ins = socket.inputStream
                val buf = ByteArray(256)
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline && ins.available() > 0) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    reply.append(hex(buf.copyOf(n))).append('\n')
                }
            } catch (t: Throwable) {
                note("读取应答失败: ${t.message}")
            }
            if (reply.isNotEmpty()) note("应答:\n$reply")

            "已发送：${modeName(mode)} (seq=$seq)\n$lastLog"
        } catch (t: Throwable) {
            note("失败: ${t.javaClass.simpleName}: ${t.message}")
            "连接/发送失败：${t.message}\n$lastLog"
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** Read-only probe: connect and dump whatever the earbuds volunteer. */
    @SuppressLint("MissingPermission")
    suspend fun probe(killCompanionFirst: Boolean = true): String = withContext(Dispatchers.IO) {
        lastLog = ""
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext "本机没有蓝牙适配器"
        if (killCompanionFirst) {
            note("欢律进程存在: ${companionRunning()}")
            note("正在强制停止欢律以释放通道…")
            note(if (killCompanionApp()) "欢律已停止" else "停止欢律失败（可能未授予 root）")
            Thread.sleep(600)
        }
        val dev = device(adapter) ?: return@withContext "找不到耳机设备"
        var socket: BluetoothSocket? = null
        try {
            socket = openSocket(dev)
            note("正在连接 ${dev.address} ...")
            try {
                adapter.cancelDiscovery()
            } catch (_: Throwable) {
            }
            socket.connect()
            note("已连接，监听 2 秒")
            val ins = socket.inputStream
            val buf = ByteArray(512)
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) {
                if (ins.available() > 0) {
                    val n = ins.read(buf)
                    if (n > 0) {
                        val frames = extractAaFrames(buf, n)
                        if (frames.isEmpty()) {
                            note("RX(raw ${n}B) ${hex(buf.copyOf(n))}")
                        } else {
                            for (f in frames) note("RX $f")
                        }
                    }
                } else {
                    Thread.sleep(50)
                }
            }
            lastLog.ifEmpty { "连接成功，但 2 秒内没有收到数据" }
        } catch (t: Throwable) {
            "连接失败：${t.message}\n$lastLog"
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }
}
