package com.example.livenotification

data class VerificationData(
    /** 验证码内容（4~8 位数字或字母数字混合） */
    val code: String,

    /** 原始文本（调试用） */
    val rawText: String,

    /** 发送方 App 包名；短信时为系统短信 App */
    val senderPackage: String,

    /** 创建时间戳 */
    val createTime: Long
)