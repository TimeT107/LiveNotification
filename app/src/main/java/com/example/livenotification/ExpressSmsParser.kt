package com.example.livenotification

object ExpressSmsParser {

    private val codeWithDash = Regex("""\d{1,2}-\d{1,2}-\d{3,4}""")
    private val codePlain = Regex("""取件码[：:\s]*(\d{6,8})""")

    private val locationKeywords = listOf(
        "菜鸟驿站",
        "菜鸟",
        "丰巢",
        "快递柜",
        "快递超市",
        "驿站",
        "京东",
        "顺丰",
        "中通",
        "圆通",
        "申通",
        "韵达",
        "极兔",
        "邮政"
    )

    private val expressIndicators = listOf(
        "取件码",
        "取件",
        "快递",
        "包裹",
        "已到",
        "驿站",
        "快递柜"
    )

    fun parse(smsBody: String): ExpressData? {

        if (expressIndicators.none { smsBody.contains(it) }) {
            return null
        }

        val code = codeWithDash.find(smsBody)?.value
            ?: codePlain.find(smsBody)?.groupValues?.getOrNull(1)
            ?: return null

        val location = extractLocation(smsBody)

        return ExpressStore.create(location, code)
    }

    private fun extractLocation(text: String): String {

        // 策略 1：匹配"到XXX驿站/快递柜"这种模式，提取具体地点
        // 例：到小区东门菜鸟驿站 → 小区东门菜鸟驿站
        //     到丰巢快递柜 → 丰巢快递柜
        val destPattern = Regex(
            """(?:到|在|存放于|投放至|已至)([^，。,.\n【】\[\]]{2,25}?(?:驿站|快递柜|超市|代收点))"""
        )
        destPattern.find(text)?.let {
            return it.groupValues[1].trim()
        }

        // 策略 2：兜底，直接用关键词本身
        for (keyword in locationKeywords) {
            if (text.contains(keyword)) return keyword
        }

        return "未知地点"
    }
}