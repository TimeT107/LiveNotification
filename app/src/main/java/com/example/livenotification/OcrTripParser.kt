package com.example.livenotification


/**
 * 从 12306 截图的 OCR 结果中解析行程信息
 * 启发式解析，不保证 100% 准确，用户需在表单里确认/修改
 */
object OcrTripParser {

    data class ParsedTrip(
        val trainNo: String = "",
        val departureStation: String = "",
        val arrivalStation: String = "",
        val departureTime: String = "",   // HH:mm
        val arrivalTime: String = "",     // HH:mm
        val seat: String = "",
        val gate: String = "",
        val departureDate: String = "",   // yyyy-MM-dd
        val arrivalDate: String = ""      // yyyy-MM-dd
    )
    private val timeRegex = Regex("^\\d{1,2}:\\d{2}$")
    private val trainNoRegex = Regex("[GDCKTZ]\\d{1,4}")
    private val seatRegex = Regex("座位号[:：\\s]*(\\d+车\\d+[A-Za-z]?)")
    private val gateRegex = Regex("检票口[●○:：\\s]*([^\\n]+)")
    private val dateRegex = Regex("(\\d{1,2})月(\\d{1,2})日")

    fun parse(text: String): ParsedTrip {

        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 1. 车次
        val trainNo = trainNoRegex.find(text)?.value ?: ""

        // 2. 座位号
        val seat = seatRegex.find(text)
            ?.groupValues?.getOrNull(1)?.trim() ?: ""

        // 3. 检票口（去掉可能的"●"等符号）
        val gate = gateRegex.find(text)
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trimEnd('。', '，', ',', ' ') ?: ""

        // 4. 出发时间/出发站
        val trainLineIdx = lines.indexOfFirst {
            trainNo.isNotEmpty() && it.contains(trainNo)
        }

        var departureTime = ""
        var departureStation = ""

        if (trainLineIdx >= 0) {
            for (i in (trainLineIdx + 1) until minOf(trainLineIdx + 10, lines.size)) {
                if (timeRegex.matches(lines[i])) {
                    departureTime = lines[i]
                    break
                }
            }
            if (departureTime.isNotEmpty()) {
                val depTimeIdx = lines.indexOf(departureTime)
                if (depTimeIdx >= 0) {
                    for (i in (depTimeIdx + 1) until minOf(depTimeIdx + 8, lines.size)) {
                        val line = lines[i]
                        if (line.contains(">") || line.contains("→")) {
                            departureStation = line
                                .substringBefore(">")
                                .substringBefore("→")
                                .trim()
                                .removePrefix("→")
                                .trim()
                            break
                        }
                    }
                }
            }
        }

        // 5. 到达时间/到达站：找出发时间之后（时间值更大）的第一个 HH:MM
        var arrivalTime = ""
        var arrivalStation = ""

        val depMinutes = parseTimeToMinutes(departureTime)
        if (depMinutes >= 0) {
            for (i in lines.indices) {
                if (!timeRegex.matches(lines[i])) continue
                val m = parseTimeToMinutes(lines[i])
                if (m > depMinutes) {
                    arrivalTime = lines[i]
                    // 下一行找站名（2-6 个纯中文）
                    for (j in (i + 1) until minOf(i + 5, lines.size)) {
                        val s = lines[j]
                        if (s.length in 2..6 && s.all { it.code > 127 }) {
                            arrivalStation = s
                            break
                        }
                    }
                    if (arrivalStation.isNotEmpty()) break
                }
            }
        }

        // 6. 日期
        val parsedDate = parseDate(text)

        return ParsedTrip(
            trainNo = trainNo,
            departureStation = departureStation,
            arrivalStation = arrivalStation,
            departureTime = departureTime,
            arrivalTime = arrivalTime,
            seat = seat,
            gate = gate,
            departureDate = parsedDate,
            arrivalDate = parsedDate
        )
    }

    private fun parseTimeToMinutes(hhmm: String): Int {
        return try {
            val parts = hhmm.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 从文本里解析 "9月30日" → "2026-09-30"
     * 年份用今年；如果推算出的日期已经过去，则用明年
     */
    private fun parseDate(text: String): String {
        val match = dateRegex.find(text) ?: return ""
        val month = match.groupValues[1].toIntOrNull() ?: return ""
        val day = match.groupValues[2].toIntOrNull() ?: return ""

        val now = java.util.Calendar.getInstance()

        // 先试今年
        val thisYear = now.get(java.util.Calendar.YEAR)
        val tryThisYear = buildDateStr(thisYear, month, day)
        if (tryThisYear != null && !isPast(tryThisYear)) {
            return tryThisYear
        }

        // 今年已过 → 用明年
        val nextYear = thisYear + 1
        return buildDateStr(nextYear, month, day) ?: ""
    }

    private fun buildDateStr(year: Int, month: Int, day: Int): String? {
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.set(year, month - 1, day, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            // 校验：Calendar 自动归一化非法日期（比如 2 月 30 日），
            // 校验实际月份/日期是否匹配
            if (cal.get(java.util.Calendar.YEAR) != year ||
                cal.get(java.util.Calendar.MONTH) != month - 1 ||
                cal.get(java.util.Calendar.DAY_OF_MONTH) != day
            ) {
                return null
            }
            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault()
            )
            sdf.format(cal.time)
        } catch (e: Exception) {
            null
        }
    }

    private fun isPast(dateStr: String): Boolean {
        return try {
            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault()
            )
            val date = sdf.parse(dateStr) ?: return false
            // 日期小于"今天 0 点"就算过去
            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.time
            date.before(today)
        } catch (e: Exception) {
            false
        }
    }
}