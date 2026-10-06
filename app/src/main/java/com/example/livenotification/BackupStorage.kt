package com.example.livenotification

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * 外部备份存储管理
 * - 用 SAF 让用户选一个目录（比如 Documents/LiveNotificationBackup）
 * - 把权限持久化到 SharedPreferences
 * - 之后所有读写都往那个目录写 live_notification_backup.json
 */
class BackupStorage(context: Context) {

    private val appContext = context.applicationContext
    private val pref = appContext.getSharedPreferences(
        "backup_pref",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val FILE_NAME = "live_notification_backup.json"
        private const val FORMAT_VERSION = 1
    }

    // ==================== 目录 Uri 管理 ====================

    /**
     * 用户在文件选择器选完目录后调用
     * 关键：takePersistableUriPermission 让权限在 App 重启后依然有效
     */
    fun saveTreeUri(uri: Uri): Boolean {
        return try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            pref.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getTreeUri(): Uri? {
        val str = pref.getString(KEY_TREE_URI, null) ?: return null
        return try {
            Uri.parse(str)
        } catch (e: Exception) {
            null
        }
    }

    /** 是否已经选过目录 */
    fun hasBackupDir(): Boolean = getTreeUri() != null

    /** 显示给用户的目录名（末尾一段） */
    fun getBackupDirLabel(): String {
        val uri = getTreeUri() ?: return "(未选择)"
        val doc = DocumentFile.fromTreeUri(appContext, uri) ?: return "(无效)"
        return doc.name ?: "(未知)"
    }

    // ==================== 单文件读写 ====================

    private fun getOrCreateFile(): DocumentFile? {
        val treeUri = getTreeUri() ?: return null
        val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        return dir.findFile(FILE_NAME)
            ?: dir.createFile("application/json", FILE_NAME)
    }

    private fun writeText(text: String): Boolean {
        return try {
            val file = getOrCreateFile() ?: return false
            appContext.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readText(): String? {
        return try {
            val treeUri = getTreeUri() ?: return null
            val dir = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
            val file = dir.findFile(FILE_NAME) ?: return null
            appContext.contentResolver.openInputStream(file.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 列表级导入导出 ====================

    /** 把整个记录列表导出到 JSON 文件 */
    fun exportRecords(records: List<LiveNotificationRecord>): Boolean {
        return try {
            val arr = JSONArray()
            records.forEach { arr.put(LiveNotificationBackup.recordToJson(it)) }

            val root = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("exportTime", System.currentTimeMillis())
                put("records", arr)
            }

            writeText(root.toString(2))   // 缩进 2 空格，人可读
        } catch (e: Exception) {
            false
        }
    }

    /** 从 JSON 文件读回记录列表；文件不存在或解析失败返回 null */
    fun importRecords(): List<LiveNotificationRecord>? {
        val text = readText() ?: return null
        return try {
            val root = JSONObject(text)
            val arr = root.optJSONArray("records") ?: return null

            val result = mutableListOf<LiveNotificationRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                LiveNotificationBackup.recordFromJson(obj)?.let { result.add(it) }
            }
            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 DAO 读取全部记录并导出到外部 JSON
     * 在 IO 线程执行，避免阻塞 UI
     */
    suspend fun syncFromDao(dao: LiveNotificationDao): Boolean {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (!hasBackupDir()) return@withContext false
                val records = dao.getAll()
                exportRecords(records)
            } catch (e: Exception) {
                false
            }
        }
    }

}