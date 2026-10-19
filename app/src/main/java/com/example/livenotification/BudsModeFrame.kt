package com.example.livenotification

import android.util.Log

/**
 * Recovers the active noise mode from the command echo the earbuds send back.
 *
 * Background — this took several wrong turns, so the reasoning is recorded here:
 *
 * The link is effectively one-way (commands go out, the earbuds act, and nothing is
 * volunteered back). What the socket does deliver is an echo of the command we just
 * wrote. That echo still carries the mode, because our own command frame contains it.
 *
 * Echo shape (13 bytes), produced by [BudsClient.modeReportFrame]:
 *
 *     aa 0b 00 00 04 02 <seq> 04 00 03 01 01 <mode>
 *                                              ^^^^^^ index 11
 *
 * Confirmed against user-labelled ground truth:
 *     ANC  -> index 11 = 0x01
 *     PASS -> index 11 = 0x04
 *     OFF  -> index 11 = 0x02  (sent form; the earbuds never report it themselves)
 *
 * Earlier attempts read a fixed byte out of the *14-byte* status frame
 * (`aa 0d ... f1 01 01 <x> <y> 03`). That frame is identical in both modes, so it carries
 * no mode information at all — reading it produced the bogus "关闭" after every switch.
 */
object BudsModeFrame {

    private const val TAG = "BudsModeFrame"

    /** Prefix shared by the echoed mode command. */
    private val HEAD = byteArrayOf(
        0xAA.toByte(), 0x0B, 0x00, 0x00, 0x04, 0x02, // aa 0b 00 00 04 02
    )

    /** Byte positions inside the 13-byte echo that must match. */
    private val TAIL_CHECK = mapOf(
        7 to 0x04,
        8 to 0x00,
        9 to 0x03,
        10 to 0x01,
        11 to 0x01,
    )

    /** Index of the mode byte. */
    private const val MODE_INDEX = 12

    fun looksLikeEcho(b: ByteArray): Boolean {
        if (b.size != 13) return false
        for (i in HEAD.indices) if (b[i] != HEAD[i]) return false
        for ((i, v) in TAIL_CHECK) if (b[i] != v.toByte()) return false
        return true
    }

    /**
     * @return the mode carried by the echo, or null when the frame is not a mode command
     *   echo. The value here is the one we sent, so it reflects the state we asked for.
     */
    fun parse(b: ByteArray): Int? {
        if (!looksLikeEcho(b)) return null
        val raw = b[MODE_INDEX].toInt() and 0xFF
        val mode = when (raw) {
            // Sent-form values (see BudsClient.MODE_*).
            0x01 -> BudsClient.MODE_ANC
            0x04 -> BudsClient.MODE_PASS
            0x02 -> BudsClient.MODE_OFF
            else -> {
                Log.d(TAG, "未知模式值 0x%02x".format(raw))
                return null
            }
        }
        return mode
    }

    fun parseHex(hex: String): Int? =
        try {
            parse(BudsClient.hexToBytes(hex))
        } catch (t: Throwable) {
            null
        }
}
