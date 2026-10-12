package com.example.livenotification

/**
 * 从短信/通知文本里提取验证码
 */
object VerificationParser {

    // 触发关键词
    private val triggerKeywords = listOf(
        "验证码",
        "校验码",
        "动态码",
        "动态密码",
        "安全码"
    )

    // 排除词（避免和快递取件码冲突）
    private val excludeKeywords = listOf(
        "取件码",
        "取件",
        "快递",
        "包裹"
    )

    // 验证码正则：
    // 优先匹配 "验证码[:：\s]*XXXXXX" 这种
    // 支持 4~8 位纯数字，或 4~8 位字母数字混合
    private val codeWithPrefix = Regex(
        """(?:验证码|校验码|动态码|动态密码|安全码)[:：\s]*([A-Za-z0-9]{4,8})"""
    )

    // 兜底：文本里独立的 4~8 位数字（无前缀时使用）
    private val bareDigits = Regex("""\b\d{4,8}\b""")

    /**
     * 尝试解析验证码
     * @param text 短信正文或通知文本
     * @return 提取到的验证码；不符合条件返回 null
     */
    fun parse(text: String): String? {

        if (text.isBlank()) return null

        // 1. 必须含触发关键词
        if (triggerKeywords.none { text.contains(it) }) return null

        // 2. 不能含排除词
        if (excludeKeywords.any { text.contains(it) }) return null

        // 3. 优先用带前缀的正则
        codeWithPrefix.find(text)?.let {
            return it.groupValues[1]
        }

        // 4. 兜底：裸数字
        bareDigits.find(text)?.let {
            return it.value
        }

        return null
    }
}