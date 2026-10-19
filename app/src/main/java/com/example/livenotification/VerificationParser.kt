package com.example.livenotification

/**
 * Extracts a verification code from SMS or notification text.
 *
 * Deliberately conservative: a wrong code is worse than a missing one, because the user
 * copies it and the attempt fails. The previous version fell back to "the first 4–8 digit
 * number anywhere in the text", which happily returned a hotline (10086), an order number
 * (20240115) or a date whenever the real code was alphanumeric.
 *
 * Rules, in order:
 *   1. the text must mention a code keyword
 *   2. the text must not look like a parcel notice (those carry pickup codes)
 *   3. a code is only accepted next to an explicit prefix (`验证码 123456`), where it must
 *      start with a digit and be a plausible length
 *   4. otherwise, a bare number is accepted only when it is the CLEARED marker (last digits
 *      before a probable-expiry marker) and a code keyword is present
 *
 * Anything else returns null.
 */
object VerificationParser {

    /** Keywords that indicate a verification code rather than something else. */
    private val triggerKeywords = listOf(
        "验证码",
        "校验码",
        "动态码",
        "动态密码",
        "安全码",
        "验证代码",
        "短信密码",
    )

    /** Parcel wording; those codes belong to the express feature, not here. */
    private val excludeKeywords = listOf(
        "取件码",
        "取件",
        "快递",
        "包裹",
        "驿站",
        "菜鸟",
    )

    /** Wording that usually follows the real code, e.g. "验证码 123456，5分钟内有效". */
    private val expiryMarkers = listOf(
        "分钟内有效",
        "分钟内失效",
        "有效",
        "失效",
        "请勿泄露",
        "不要告诉",
        "泄露",
    )

    /**
     * A code written right after a prefix. Must begin with a digit, which rules out the
     * trailing English word in texts like "验证码已发送 Verify Now".
     */
    private val codeWithPrefix = Regex(
        """(?:验证码|校验码|动态码|动态密码|安全码|验证代码|短信密码)\s*(?:是|为|:|：)?\s*(\d{4,8})\b"""
    )

    /** Alphanumeric codes only when explicitly introduced. */
    private val alnumWithPrefix = Regex(
        """(?:验证码|校验码|动态码|动态密码|安全码|验证代码|短信密码)\s*(?:是|为|:|：)\s*([A-Za-z0-9]{4,8})\b"""
    )

    /**
     * Lengths accepted for a bare number. Five digits is excluded on purpose: the common
     * Chinese service hotlines (10086, 10010, 95588, 12306) are five digits.
     */
    private val allowedBareLengths = setOf(4, 6, 7, 8)

    private val bareDigits = Regex("""\b\d{4,8}\b""")

    fun parse(text: String): String? {

        if (text.isBlank()) return null

        // 1. must mention a code
        if (triggerKeywords.none { text.contains(it) }) return null

        // 2. must not be a parcel notice
        if (excludeKeywords.any { text.contains(it) }) return null

        // 3. explicit prefix, digits preferred
        codeWithPrefix.find(text)?.let { m ->
            val code = m.groupValues[1]
            if (code.length in allowedBareLengths) return code
        }

        // 3b. explicit prefix with a separator, alphanumeric allowed
        alnumWithPrefix.find(text)?.let { m ->
            val code = m.groupValues[1]
            if (code.length in 4..8) return code
        }

        // 4. bare number, only when the text also carries an expiry/privacy marker, which is
        //    what separates a real code from an order number or a phone number.
        if (expiryMarkers.any { text.contains(it) }) {
            for (m in bareDigits.findAll(text)) {
                val code = m.value
                if (code.length !in allowedBareLengths) continue
                // Skip when the digits are part of a longer run (phone numbers, tracking ids).
                val end = m.range.last + 1
                if (end < text.length && text[end].isDigit()) continue
                return code
            }
        }

        return null
    }
}
