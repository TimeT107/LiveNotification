package com.example.livenotification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 快递数据的全局单例
 * - 内存缓存 + SharedPreferences 持久化
 * - 数据变化时通知监听者（UI / Service）
 */
object ExpressStore {

    private const val PREF_NAME = "express_store"
    private const val KEY_LIST = "express_list"

    private var appContext: Context? = null
    private val items = mutableListOf<ExpressData>()
    private val listeners = mutableListOf<() -> Unit>()

    /** 在 MainActivity 或 Service 的 onCreate 里调用一次 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    // ============== 查询 ==============
    fun getAll(): List<ExpressData> = items.toList()

    fun count(): Int = items.size

    // ============== 修改 ==============
    fun add(data: ExpressData) {
        items.add(data)
        save()
        notifyChange()
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        save()
        notifyChange()
    }

    fun update(data: ExpressData) {
        val index = items.indexOfFirst { it.id == data.id }
        if (index >= 0) {
            items[index] = data
            save()
            notifyChange()
        }
    }

    fun clear() {
        items.clear()
        save()
        notifyChange()
    }

    /** 便捷构造：给 UI 层用，自动生成 id 和 createTime */
    fun create(location: String, code: String): ExpressData {
        return ExpressData(
            id = UUID.randomUUID().toString(),
            location = location,
            code = code,
            createTime = System.currentTimeMillis()
        )
    }

    // ============== 监听器 ==============
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChange() {
        listeners.toList().forEach { it() }
    }

    // ============== 持久化 ==============
    private fun save() {
        val ctx = appContext ?: return

        val arr = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("location", item.location)
                put("code", item.code)
                put("createTime", item.createTime)
            }
            arr.put(obj)
        }

        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }

    private fun load() {
        val ctx = appContext ?: return

        val json = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null) ?: return

        try {
            val arr = JSONArray(json)
            items.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                items.add(
                    ExpressData(
                        id = obj.optString("id"),
                        location = obj.optString("location"),
                        code = obj.optString("code"),
                        createTime = obj.optLong("createTime")
                    )
                )
            }
        } catch (e: Exception) {
            // 解析失败当空处理，不崩
        }
    }
}