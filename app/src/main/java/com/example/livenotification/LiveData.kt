package com.example.livenotification


data class LiveData(


    val title:String,


    val subtitle:String,


    val status:String,



    val startStation:String,


    val startTime:String,



    val endStation:String,


    val endTime:String,



    val bottomText:String,


    val progress: Int = 0,

// 创建通知的时间戳（用于第一阶段进度计算）
    val createTime: Long = 0L,

// 出发时间
    val departureTime: Long,

// 到达时间
    val arrivalTime: Long,

    val remainText:String,



    // 日期
    val departureDate:String,



    // 座位
    val seat:String = ""

)