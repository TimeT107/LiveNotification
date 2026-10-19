package com.example.livenotification.island.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.example.livenotification.island.hook.IslandLogger
import java.io.InputStream

/**
 * 一条可渲染的媒体快照。
 */
data class MediaSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val updatedAt: Long
) {
    /** 用于判断是否需要刷新 UI，跳过 Bitmap 比较（用 hashCode 代替） */
    fun sameAs(other: MediaSnapshot?): Boolean =
        other != null &&
            packageName == other.packageName &&
            title == other.title &&
            artist == other.artist &&
            playing == other.playing &&
            (albumArt?.hashCode() ?: 0) == (other.albumArt?.hashCode() ?: 0)
}

/**
 * 媒体来源。
 *
 * 为什么自己读 MediaSessionManager，而不是 hook SystemUI 的 MediaDataManager：
 *   1. SystemUI 自己持有 android.permission.MEDIA_CONTENT_CONTROL（已在反编译的
 *      AndroidManifest 里确认），所以 getActiveSessions 在 SystemUI 进程里能直接调用，
 *      不需要用户额外授权
 *   2. 不依赖任何 SystemUI 内部类/方法签名，ROM 升级不会失效
 *   3. 播放控制直接走 MediaController.TransportControls，原生可靠
 *
 * 刷新策略：由外部按需调用 [poll]，锁屏不可见时调用 [stop] 完全停掉，
 * 不产生任何后台开销。
 */
class MediaRepository(
    private val context: Context,
    private val log: IslandLogger
) {

    private val sessionManager: MediaSessionManager? by lazy {
        try {
            context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        } catch (t: Throwable) {
            log.e("获取 MediaSessionManager 失败", t)
            null
        }
    }

    private var controller: MediaController? = null
    private var artCacheKey: String? = null
    private var artCache: Bitmap? = null

    /**
     * 上一次选中的会话标识。
     *
     * ============ 为什么不比较 MediaController 对象身份（真机性能 bug） ============
     *
     * 原来是 `if (chosen != controller)` —— 但 `getActiveSessions()` 每次返回的是
     * **新的 MediaController 包装对象**，身份比较恒为真，于是：
     *   · 每秒清空封面缓存并**重新解码 600×600 位图**（无谓的 CPU 与内存抖动）
     *   · 每秒打两行日志（实测约 26MB/天的日志增长）
     *
     * 真机日志就是这个症状：
     *   选中媒体会话：com.apple.android.music
     *   封面来源=Bitmap 600x600
     * 两条每秒各出现一次。
     *
     * 改用 `sessionToken`（会话的稳定标识）判断是否真的换了会话。
     */
    private var lastSessionToken: android.media.session.MediaSession.Token? = null

    @Volatile
    var lastSnapshot: MediaSnapshot? = null
        private set

    /** 当前选中的会话包名，用于日志 */
    val currentPackage: String? get() = controller?.packageName

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 拉一次当前媒体状态。
     * @return 最新快照；没有正在播放/存在的媒体时返回 null
     */
    fun poll(): MediaSnapshot? {
        val mgr = sessionManager ?: return null
        val sessions = try {
            mgr.getActiveSessions(null)
        } catch (t: Throwable) {
            log.e("getActiveSessions 失败", t)
            null
        } ?: return null

        if (sessions.isEmpty()) {
            controller = null
            lastSnapshot = null
            return null
        }

        val chosen = chooseSession(sessions)
        if (chosen == null) {
            controller = null
            lastSessionToken = null
            lastSnapshot = null
            return null
        }

        // 按 sessionToken 判断是否真的换了会话（不是对象身份）
        val token = try {
            chosen.sessionToken
        } catch (t: Throwable) {
            null
        }
        if (token != lastSessionToken) {
            controller = chosen
            lastSessionToken = token
            artCacheKey = null
            artCache = null
            log.mark("选中媒体会话：${chosen.packageName}")
        } else {
            // 同一会话：只更新引用，绝不动封面缓存
            controller = chosen
        }

        return try {
            buildSnapshot(chosen)
        } catch (t: Throwable) {
            log.e("构建媒体快照失败", t)
            null
        }
    }

    /**
     * 选择要显示的会话：
     *  1. 正在播放 / 缓冲的优先
     *  2. 其次有 metadata 的（暂停中）
     *  3. 多个都满足时，优先非 SystemUI 自己（避免选到音量面板之类）
     */
    private fun chooseSession(sessions: List<MediaController>): MediaController? {
        val scored = sessions.map { c ->
            val st = c.playbackState
            val md = c.metadata
            val playing = st?.state == PlaybackState.STATE_PLAYING ||
                st?.state == PlaybackState.STATE_BUFFERING ||
                st?.state == PlaybackState.STATE_FAST_FORWARDING ||
                st?.state == PlaybackState.STATE_REWINDING
            val hasMeta = md != null &&
                !md.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            val score = when {
                playing && hasMeta -> 3
                playing -> 2
                hasMeta -> 1
                else -> 0
            }
            c to score
        }
        val best = scored.filter { it.second > 0 }.maxByOrNull { it.second } ?: return null
        return best.first
    }

    private fun buildSnapshot(c: MediaController): MediaSnapshot {
        val md = c.metadata
        val st = c.playbackState

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: c.packageName
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""

        val playing = st?.state == PlaybackState.STATE_PLAYING ||
            st?.state == PlaybackState.STATE_BUFFERING

        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L
        var position = st?.position ?: 0L
        // 播放中用时间差外推，避免进度条卡住
        if (playing && st.lastPositionUpdateTime > 0) {
            val elapsed = System.currentTimeMillis() - st.lastPositionUpdateTime
            if (elapsed in 0..5000) position += elapsed
        }
        if (duration in 1..position) position %= duration

        val actions = st?.actions ?: 0L
        val hasNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        val hasPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L

        val art = loadAlbumArt(c, md, artist)

        return MediaSnapshot(
            packageName = c.packageName,
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            albumArt = art,
            playing = playing,
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration,
            hasNext = hasNext,
            hasPrevious = hasPrev,
            updatedAt = System.currentTimeMillis()
        ).also { lastSnapshot = it }
    }

    /**
     * 封面加载。
     *
     * 优先级：
     *  1. METADATA_KEY_ALBUM_ART / ART —— 直接是 Bitmap，零成本
     *  2. 封面 URI —— 部分 App 会直接给 content:// 或 http://
     *
     * 刻意避开 METADATA_KEY_ALBUM_ART_URI，因为它是 FileProvider 的 file://
     * URI，跨进程读不到（这正是 Android 官方推荐用 MediaSession 传 Bitmap 的原因）。
     * 需要时后续可以通过 MediaSession 的 ICON_URI 走 MediaSessionManager 再取。
     */
    private fun loadAlbumArt(
        c: MediaController,
        md: MediaMetadata?,
        artist: String
    ): Bitmap? {
        if (md == null) return artCache

        // ============ 缓存 key 必须用「曲目稳定标识」，不能用 Bitmap 身份（真机 bug） ============
        //
        // 原来 key 里含 `direct.hashCode()`。但 getBitmap() 每次轮询都从
        // Bundle 反序列化出一个**新的 Bitmap 实例**，identity hash 每次都不同，
        // 于是缓存永远不命中：
        //   · 每秒打一行「封面来源=Bitmap 600x600」（实测约 26MB/天日志）
        //   · 每秒做一次无意义的位图替换与内存抖动
        //
        // 改用曲目的元数据字段做 key —— 同一首歌这些字段稳定，
        // 换歌或换专辑才会变，正好对应"封面该不该重新取"。
        val trackKey = buildString {
            append(c.packageName).append('|')
            append(md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)).append('|')
            append(md.getString(MediaMetadata.METADATA_KEY_TITLE)).append('|')
            append(md.getString(MediaMetadata.METADATA_KEY_ALBUM)).append('|')
            append(artist)
        }

        val direct = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            if (trackKey != artCacheKey) {
                artCacheKey = trackKey
                artCache = direct
                log.i("封面来源=Bitmap ${direct.width}x${direct.height}（曲目变化，重新取）")
            }
            return artCache
        }

        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (uri.isNullOrBlank()) return artCache
        if (!uri.startsWith("content://") && !uri.startsWith("http")) return artCache

        val key = "$trackKey|uri|$uri"
        if (key == artCacheKey) return artCache

        return try {
            val stream: InputStream = context.contentResolver.openInputStream(
                android.net.Uri.parse(uri)
            ) ?: return artCache
            stream.use {
                val bmp = BitmapFactory.decodeStream(it)
                if (bmp != null) {
                    artCacheKey = key
                    artCache = bmp
                    log.i("封面来源=URI ${bmp.width}x${bmp.height}（曲目变化，重新取）")
                }
                bmp ?: artCache
            }
        } catch (t: Throwable) {
            log.i("封面 URI 读取失败（可忽略）：${t.message}")
            artCache
        }
    }

    // ------------------------------------------------------------------
    // 控制
    // ------------------------------------------------------------------

    fun play() = safeTransport("play") { it.play() }

    fun pause() = safeTransport("pause") { it.pause() }

    fun toggle() {
        val snap = lastSnapshot
        if (snap?.playing == true) pause() else play()
    }

    fun next() = safeTransport("next") { it.skipToNext() }

    fun previous() = safeTransport("previous") { it.skipToPrevious() }

    fun seekTo(ms: Long) = safeTransport("seekTo") { it.seekTo(ms) }

    private inline fun safeTransport(name: String, block: (MediaController.TransportControls) -> Unit) {
        val c = controller
        if (c == null) {
            log.w("$name 失败：没有活跃媒体会话")
            return
        }
        try {
            block(c.transportControls)
            log.i("媒体控制：$name")
        } catch (t: Throwable) {
            log.e("$name 失败", t)
        }
    }

    /** 拿当前选中控制器的 session token，便于调试输出 */
    fun describeController(): String {
        val c = controller ?: return "无"
        val cn: ComponentName = c.packageName.let { ComponentName(it, "MediaSession") }
        return "${c.packageName} (${cn.flattenToShortString()})"
    }
}
