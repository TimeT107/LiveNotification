package com.example.livenotification


data class LiveData(

    val title: String,

    val subtitle: String,

    val status: String,

    val startStation: String,

    val startTime: String,

    val endStation: String,

    val endTime: String,

    val bottomText: String,

    val progress: Int = 0,

    // 创建通知的时间戳（用于第一阶段进度计算）
    val createTime: Long = 0L,

    // 出发时间
    val departureTime: Long,

    // 到达时间
    val arrivalTime: Long,

    val remainText: String,

    // 日期
    val departureDate: String,

    // 座位
    val seat: String = "",

    // 检票口（火车）/ 登机口（航班复用此字段可能不合适，先分开）
    val gate: String = "",

    // ==================== 新增 ====================

    /** 行程类型："train" 高铁/火车 / "flight" 航班 */
    val type: String = "train",

    /** 航空公司（仅航班） */
    val airline: String = "",

    /** 航站楼（仅航班）：T1 / T2 / T3 */
    val terminal: String = "",

    /** 登机口（仅航班）：A12 / B07 */
    val boardingGate: String = "",

    /** 值机柜台（仅航班）：C01-C10 */
    val checkinCounter: String = ""
)