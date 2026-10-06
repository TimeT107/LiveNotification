package com.example.livenotification

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity
data class LiveNotificationRecord(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val title: String,

    val content: String,

    val createTime: Long,

    val endTime: Long,

    val status: String,

    // 完整 LiveData 的 JSON 快照，用于恢复/编辑/导出
    val payloadJson: String = ""
)