package com.example.livenotification

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


/**
 * 全局媒体 session 管理
 * - 维护所有活跃媒体 session 的列表
 * - 跟踪"当前选中"的索引
 *
 * 关键设计：列表顺序稳定
 * - 已存在的 session 保持原位
 * - 新 session 追加到末尾
 * - 消失的移除
 * 这样"播放状态变化"不会导致索引错位
 */
object MediaSessionStore {

    data class Entry(
        val packageName: String,
        val title: String,
        val artist: String,
        val albumArt: Bitmap?,
        val isPlaying: Boolean,
        val controller: MediaController
    )

    var entries: List<Entry> by mutableStateOf(emptyList())
        private set

    var currentIndex: Int by mutableIntStateOf(0)
        private set

    val hasMultiple: Boolean
        get() = entries.size > 1

    // ==================== 查询 ====================

    fun getCurrent(): Entry? = entries.getOrNull(currentIndex)

    fun getCurrentController(): MediaController? = getCurrent()?.controller

    // ==================== 修改 ====================

    fun selectIndex(index: Int) {
        if (index in entries.indices && index != currentIndex) {
            currentIndex = index
        }
    }

    fun next() {
        if (entries.size > 1) {
            currentIndex = (currentIndex + 1) % entries.size
        }
    }

    fun prev() {
        if (entries.size > 1) {
            currentIndex = (currentIndex - 1 + entries.size) % entries.size
        }
    }

    // ==================== 刷新 ====================

    /**
     * 从系统重新读取所有活跃 session
     * ★ 稳定顺序：已存在的保持原位，新的追加，消失的移除
     */
    fun refresh(context: Context) {
        try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as? MediaSessionManager ?: return

            val component = ComponentName(
                context,
                MusicNotificationListener::class.java
            )

            val controllers = try {
                manager.getActiveSessions(component)
            } catch (_: SecurityException) {
                emptyList()
            }

            val newEntries = controllers.mapNotNull { controller ->
                try {
                    buildEntry(controller)
                } catch (_: Exception) {
                    null
                }
            }

            // ★ 稳定顺序：保留旧顺序，追加新 session
            val oldPkgs = entries.map { it.packageName }

            val preserved = newEntries
                .filter { it.packageName in oldPkgs }
                .sortedBy { oldPkgs.indexOf(it.packageName) }

            val newOnes = newEntries.filter { it.packageName !in oldPkgs }

            val result = preserved + newOnes

            // 保持选中不变
            val currentPkg = entries.getOrNull(currentIndex)?.packageName
            val newIndex = result.indexOfFirst { it.packageName == currentPkg }

            entries = result

            when {
                newIndex >= 0 -> currentIndex = newIndex
                currentIndex >= result.size ->
                    currentIndex = (result.size - 1).coerceAtLeast(0)
            }

        } catch (_: Exception) {
        }
    }

    private fun buildEntry(controller: MediaController): Entry? {
        val metadata = controller.metadata ?: return null

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

        val albumArt: Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val isPlaying = controller.playbackState?.state ==
                PlaybackState.STATE_PLAYING

        return Entry(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            albumArt = albumArt,
            isPlaying = isPlaying,
            controller = controller
        )
    }
}