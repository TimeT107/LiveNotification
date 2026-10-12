package com.example.livenotification

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPopupContent(
    onDismiss: () -> Unit,
    onOpenFullControl: () -> Unit,
    onOpenAudioPanel: () -> Unit
) {

    val context = LocalContext.current

    var entry by remember { mutableStateOf(MediaSessionStore.getCurrent()) }
    var fallback by remember { mutableStateOf(MusicStore.get()) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isStarred by remember { mutableStateOf(false) }
    var isShuffled by remember { mutableStateOf(false) }

    // ★ 拖动状态
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    // ★ 音频输出设备名
    var activeDeviceName by remember { mutableStateOf("音频设备") }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val dev = AudioDeviceManager.getActiveDevice(context)
                activeDeviceName = if (dev != null) {
                    AudioDeviceManager.getDeviceName(dev)
                } else {
                    "音频设备"
                }
            } catch (_: Exception) {
            }
            delay(2000L)
        }
    }

    // 数据刷新
    LaunchedEffect(Unit) {
        while (true) {
            entry = MediaSessionStore.getCurrent()
            fallback = MusicStore.get()

            val controller = entry?.controller
                ?: MediaActionReceiver.currentController

            controller?.let { c ->
                val pb = c.playbackState
                // ★ 拖动时跳过自动位置刷新
                if (!isDragging) {
                    position = pb?.position ?: 0L
                }
                duration = c.metadata?.getLong(
                    android.media.MediaMetadata.METADATA_KEY_DURATION
                ) ?: 0L
                isPlaying = pb?.state ==
                        android.media.session.PlaybackState.STATE_PLAYING
            }
            delay(500L)
        }
    }

    val title = entry?.title ?: fallback?.title ?: "未知歌曲"
    val artist = entry?.artist ?: fallback?.artist ?: ""
    val albumArt = entry?.albumArt ?: fallback?.albumArt
    val packageName = entry?.packageName ?: fallback?.packageName ?: ""

    val albumColorInt = remember(albumArt) {
        ColorExtractor.getAlbumColor(albumArt)
    }
    val accent = Color(albumColorInt)

    val controller = MediaSessionStore.getCurrentController()
        ?: MediaActionReceiver.currentController

    val displayPosition = if (isDragging) dragPosition else position

    // ============ 媒体卡片 ============
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(28.dp))
    ) {

        // 背景
        albumArt?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } ?: Box(
            Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
        )

        // 暗色遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        )

        // 整卡片点击 → 打开完整控件
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenFullControl
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            // ============ 顶部 ============
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppSourcePill(
                    context = context,
                    packageName = packageName,
                    accent = accent
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                        .clickable { onOpenAudioPanel() }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeDeviceName,
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ============ 歌名 / 歌手 ============
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 100.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ============ 播放按钮（暂停=圆形 / 播放=圆角矩形） ============
            val cornerRadius by animateDpAsState(
                // 圆形：半径 = 72/2 = 36dp
                // 圆角矩形：24dp
                targetValue = if (isPlaying) 24.dp else 36.dp,
                animationSpec = tween(durationMillis = 250),
                label = "playButtonShape"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(72.dp)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(accent)
                    .clickable {
                        if (isPlaying) {
                            controller?.transportControls?.pause()
                        } else {
                            controller?.transportControls?.play()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause
                    else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp)
                )
            }

            // ============ 底部控制行 ============
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { controller?.transportControls?.skipToPrevious() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "上一首",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // ★ 波浪进度条 + 拖动层
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .padding(horizontal = 8.dp)
                        .pointerInput(duration, controller) {
                            if (duration <= 0 || controller == null) return@pointerInput

                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()

                                isDragging = true
                                val total = size.width.toFloat()
                                if (total > 0) {
                                    val ratio = (down.position.x / total)
                                        .coerceIn(0f, 1f)
                                    dragPosition = (ratio * duration).toLong()
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                        ?: break

                                    if (change.changedToUp()) {
                                        change.consume()
                                        break
                                    }

                                    val r = (change.position.x / total)
                                        .coerceIn(0f, 1f)
                                    dragPosition = (r * duration).toLong()
                                    change.consume()
                                }

                                // 松手 → seek
                                val target = dragPosition
                                position = target
                                controller.transportControls.seekTo(target)
                                isDragging = false
                            }
                        }
                ) {
                    LinearWavyProgressIndicator(
                        progress = {
                            if (duration > 0) {
                                (displayPosition.toFloat() / duration)
                                    .coerceIn(0f, 1f)
                            } else 0f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .align(Alignment.Center),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        gapSize = 0.dp,
                        // ★ 拖动时波浪归零
                        amplitude = {
                            if (isPlaying && !isDragging) 0.7f else 0f
                        },
                        wavelength = 24.dp,
                        waveSpeed = 12.dp
                    )
                }

                IconButton(
                    onClick = { controller?.transportControls?.skipToNext() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = { isStarred = !isStarred },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isStarred) Icons.Filled.Star
                        else Icons.Filled.StarBorder,
                        contentDescription = "收藏",
                        tint = if (isStarred) accent else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { isShuffled = !isShuffled },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "随机",
                        tint = if (isShuffled) accent else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
