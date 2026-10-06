package com.example.livenotification

import org.json.JSONArray
import org.json.JSONObject

/**
 * LiveData ↔ JSON 的双向转换工具
 * 只处理单条 LiveData，不含列表/文件的读写逻辑
 */
object LiveNotificationBackup {

    /** LiveData → JSONObject */
    fun toJson(data: LiveData): JSONObject {
        return JSONObject().apply {
            put("title", data.title)
            put("subtitle", data.subtitle)
            put("status", data.status)
            put("startStation", data.startStation)
            put("startTime", data.startTime)
            put("endStation", data.endStation)
            put("endTime", data.endTime)
            put("bottomText", data.bottomText)
            put("progress", data.progress)
            put("createTime", data.createTime)
            put("departureTime", data.departureTime)
            put("arrivalTime", data.arrivalTime)
            put("remainText", data.remainText)
            put("departureDate", data.departureDate)
            put("seat", data.seat)
        }
    }

    /** JSONObject → LiveData；解析失败返回 null */
    fun fromJson(json: JSONObject): LiveData? {
        return try {
            LiveData(
                title = json.getString("title"),
                subtitle = json.optString("subtitle", ""),
                status = json.optString("status", ""),
                startStation = json.optString("startStation", ""),
                startTime = json.optString("startTime", ""),
                endStation = json.optString("endStation", ""),
                endTime = json.optString("endTime", ""),
                bottomText = json.optString("bottomText", ""),
                progress = json.optInt("progress", 0),
                createTime = json.optLong("createTime", 0L),
                departureTime = json.optLong("departureTime", 0L),
                arrivalTime = json.optLong("arrivalTime", 0L),
                remainText = json.optString("remainText", "计算中"),
                departureDate = json.optString("departureDate", ""),
                seat = json.optString("seat", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    // ====================== LiveData 字符串级别的便捷方法 ======================

    /** LiveData → JSON 字符串（用于存进 LiveNotificationRecord.payloadJson） */
    fun toJsonString(data: LiveData): String {
        return toJson(data).toString()
    }

    /** JSON 字符串 → LiveData；空串或非法返回 null */
    fun fromJsonString(json: String): LiveData? {
        if (json.isBlank()) return null
        return try {
            fromJson(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    // ====================== 记录级别的转换（Record ↔ 完整 JSON） ======================

    /** 单条 Record → JSON（含 payload 嵌套） */
    fun recordToJson(record: LiveNotificationRecord): JSONObject {
        val obj = JSONObject().apply {
            put("id", record.id)
            put("title", record.title)
            put("content", record.content)
            put("createTime", record.createTime)
            put("endTime", record.endTime)
            put("status", record.status)
        }
        // 如果 payloadJson 是合法 JSON，就作为嵌套对象放进去
        val payload = fromJsonString(record.payloadJson)
        if (payload != null) {
            obj.put("payload", toJson(payload))
        }
        return obj
    }

    /** 单个 JSON → Record；解析失败返回 null */
    fun recordFromJson(json: JSONObject): LiveNotificationRecord? {
        return try {
            val payloadStr = json.optJSONObject("payload")?.toString() ?: ""
            LiveNotificationRecord(
                id = json.optInt("id", 0),
                title = json.optString("title", ""),
                content = json.optString("content", ""),
                createTime = json.optLong("createTime", 0L),
                endTime = json.optLong("endTime", 0L),
                status = json.optString("status", ""),
                payloadJson = payloadStr
            )
        } catch (e: Exception) {
            null
        }
    }
}