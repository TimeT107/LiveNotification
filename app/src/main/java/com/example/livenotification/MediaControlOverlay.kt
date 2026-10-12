package com.example.livenotification

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun MediaControlOverlay(
    onDismiss: () -> Unit
) {

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 首次打开刷新一遍 session 列表
    LaunchedEffect(Unit) {
        MediaSessionStore.refresh(context)
    }

    // 判断数据来源：优先 MediaSessionStore，为空时 fallback 到 MusicStore
    val entries = MediaSessionStore.entries
    val hasMedia = entries.isNotEmpty() || MusicStore.get() != null

    if (!hasMedia) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    // 配色：从当前封面取色（entries 为空时从 MusicStore）
    val currentArt = entries.getOrNull(MediaSessionStore.currentIndex)?.albumArt
        ?: MusicStore.get()?.albumArt

    val albumColorInt = remember(currentArt) {
        ColorExtractor.getAlbumColor(currentArt)
    }
    val albumColor = Color(albumColorInt)

    val baseScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme()
    }

    val colorScheme = baseScheme.copy(
        primary = albumColor,
        primaryContainer = albumColor.copy(alpha = 0.3f),
        onPrimaryContainer = Color.White
    )

    val animProgress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 400,
                easing = FastOutSlowInEasing
            )
        )
    }

    fun startClose() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 350,
                    easing = LinearOutSlowInEasing
                )
            )
            onDismiss()
        }
    }

    BackHandler(enabled = !isClosing) { startClose() }

    MaterialTheme(colorScheme = colorScheme) {
        MediaControlContent(
            onClose = { startClose() },
            isClosing = isClosing,
            animProgress = animProgress.value,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp,
            hasGeometry = MusicCardGeometry.isValid
        )
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaControlContent(
    onClose: () -> Unit,
    isClosing: Boolean,
    animProgress: Float,
    screenWidthDp: Dp,
    screenHeightDp: Dp,
    hasGeometry: Boolean
) {

    val context = LocalContext.current
    val density = LocalDensity.current

    // ============================================
    // Session 列表
    // ============================================
    val entries = MediaSessionStore.entries

    // 没有 session 时用 MusicStore 兜底（构造一条 entry）
    val displayEntries: List<MediaSessionStore.Entry> = remember(entries) {
        if (entries.isNotEmpty()) entries
        else {
            val fb = MusicStore.get()
            if (fb != null) {
                listOf(
                    MediaSessionStore.Entry(
                        packageName = fb.packageName,
                        title = fb.title,
                        artist = fb.artist,
                        albumArt = fb.albumArt,
                        isPlaying = fb.isPlaying,
                        controller = MediaActionReceiver.currentController
                            ?: return@remember emptyList()
                    )
                )
            } else emptyList()
        }
    }

    if (displayEntries.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // ============================================
    // Pager
    // ============================================
    val pagerState = rememberPagerState(
        initialPage = MediaSessionStore.currentIndex
            .coerceIn(0, displayEntries.size - 1),
        pageCount = { displayEntries.size }
    )

    // 监听滑动 → 更新 Store 选中
    LaunchedEffect(pagerState.currentPage) {
        MediaSessionStore.selectIndex(pagerState.currentPage)
    }

    val currentEntry = displayEntries.getOrNull(pagerState.currentPage)
        ?: displayEntries.first()
    val currentController: MediaController? = currentEntry.controller

    // ============================================
    // 播放状态
    // ============================================
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(currentEntry.isPlaying) }
    var isStarred by remember { mutableStateOf(false) }
    var isShuffled by remember { mutableStateOf(false) }

    // 拖动
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }

    var chipEnabled by remember {
        mutableStateOf(MusicChipPreference.isEnabled(context))
    }

    var hotZoneEnabled by remember {
        mutableStateOf(HotZonePreference.isEnabled(context))
    }

    var flashEnabled by remember {
        mutableStateOf(FlashlightPreference.isEnabled(context))
    }

    // ★ 音频输出面板
    var showAudioSheet by remember { mutableStateOf(false) }

    // ★ 当前音频输出设备名（每 2 秒刷新）
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

    val musicManager = remember { MusicNotificationManager(context) }
    val accent = MaterialTheme.colorScheme.primary

    // ============================================
    // 监听当前 controller
    // ============================================
    LaunchedEffect(currentController) {
        if (currentController == null) return@LaunchedEffect

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                position = state?.position ?: 0L
                isPlaying = state?.state == PlaybackState.STATE_PLAYING
            }
        }
        currentController.registerCallback(callback)

        // 初始值
        position = currentController.playbackState?.position ?: 0L
        duration = currentController.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        isPlaying = currentController.playbackState?.state ==
                PlaybackState.STATE_PLAYING

        try {
            while (true) {
                if (!isDragging) {
                    position = currentController.playbackState?.position ?: 0L
                }
                delay(500L)
            }
        } finally {
            currentController.unregisterCallback(callback)
        }
    }

    // ============================================
    // 动画几何
    // ============================================
    val defaultY = 100.dp
    val defaultH = 140.dp
    val defaultW = screenWidthDp - 32.dp

    val startX = if (hasGeometry) with(density) { MusicCardGeometry.topLeft.x.toDp() } else 0.dp
    val startY = if (hasGeometry) with(density) { MusicCardGeometry.topLeft.y.toDp() } else defaultY
    val startW = if (hasGeometry) MusicCardGeometry.width else defaultW
    val startH = if (hasGeometry) MusicCardGeometry.height else defaultH

    val endX = 0.dp
    val endY = 0.dp
    val endW = screenWidthDp
    val endH = minOf(
        (screenWidthDp / 0.70f).value,
        (screenHeightDp * 0.55f).value
    ).dp

    val p = animProgress

    val currentX = startX + (endX - startX) * p
    val currentY = startY + (endY - startY) * p
    val currentW = startW + (endW - startW) * p
    val currentH = startH + (endH - startH) * p
    val currentCorner = 24.dp + (0.dp - 24.dp) * p

    // 是否允许滑动（动画完成后 + 有多条）
    val canSwipe = p >= 0.99f && displayEntries.size > 1

    val displayPosition = if (isDragging) dragPosition else position

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {

        // ================================================
        // 封面区（Pager）
        // ================================================
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = canSwipe,
            modifier = Modifier
                .offset(x = currentX, y = currentY)
                .width(currentW)
                .height(currentH)
                .clip(RoundedCornerShape(currentCorner))
        ) { page ->

            val entry = displayEntries.getOrNull(page) ?: return@HorizontalPager

            Box(modifier = Modifier.fillMaxSize()) {

                // 封面
                entry.albumArt?.let { bitmap ->
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

                // 卡片遮罩
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.4f),
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .alpha(1f - p)
                )

                // 覆盖层渐变
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.45f to Color.Transparent,
                                    0.70f to Color(0xFF0F0F0F).copy(alpha = 0.35f),
                                    0.88f to Color(0xFF0F0F0F).copy(alpha = 0.85f),
                                    1.0f to Color(0xFF0F0F0F)
                                )
                            )
                        )
                        .alpha(p)
                )

                // 顶部栏
                if (p > 0.5f) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp)
                            .padding(top = 8.dp)
                            .alpha(((p - 0.5f) * 2f).coerceIn(0f, 1f)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppSourcePill(
                            context = context,
                            packageName = entry.packageName,
                            accent = accent
                        )

                        Spacer(Modifier.weight(1f))

                        // 右侧：当前音频输出设备（可点击）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(accent)
                                .clickable { showAudioSheet = true }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
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
                }

                // 歌名 / 歌手
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = entry.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.artist,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ================================================
        // 小圆点指示器
        // ================================================
        if (displayEntries.size > 1 && p > 0.5f) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = currentY + currentH - 20.dp)
                    .alpha(((p - 0.5f) * 2f).coerceIn(0f, 1f)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(displayEntries.size) { index ->
                    val selected = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(if (selected) 8.dp else 6.dp)
                            .background(
                                color = Color.White.copy(
                                    alpha = if (selected) 0.9f else 0.35f
                                ),
                                shape = CircleShape
                            )
                    )
                }
            }
        }

        // ================================================
        // 底部控制区（可滚动）
        // ================================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.45f)      // 底部固定占屏幕 45%
                .alpha(p.coerceIn(0f, 1f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())   // 内部可滚动
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 24.dp)
            ) {

                // 三个按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            currentController?.transportControls?.skipToPrevious()
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "上一首",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(accent)
                            .clickable {
                                if (isPlaying) {
                                    currentController?.transportControls?.pause()
                                } else {
                                    currentController?.transportControls?.play()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause
                            else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            currentController?.transportControls?.skipToNext()
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 可拖动进度条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .pointerInput(duration, currentController) {
                            if (duration <= 0 || currentController == null) return@pointerInput

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

                                val target = dragPosition
                                position = target
                                currentController.transportControls.seekTo(target)
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
                        color = accent,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        gapSize = 0.dp,
                        amplitude = { if (isPlaying && !isDragging) 0.7f else 0f },
                        wavelength = 24.dp,
                        waveSpeed = 12.dp
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMillis(displayPosition),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatMillis(duration),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Chip 开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "音乐芯片",
                            color = Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "在状态栏显示音乐胶囊",
                            color = Color.White.copy(alpha = if (isClosing) 0.25f else 0.5f),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = chipEnabled,
                        enabled = !isClosing,
                        onCheckedChange = { enabled ->
                            chipEnabled = enabled
                            MusicChipPreference.setEnabled(context, enabled)
                            if (enabled) {
                                MusicStore.get()?.let { musicManager.show(it) }
                            } else {
                                musicManager.stop()
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 快捷控件开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "快捷控件",
                            color = Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "点击状态栏下方区域弹出快捷媒体卡片",
                            color = Color.White.copy(alpha = if (isClosing) 0.25f else 0.5f),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = hotZoneEnabled,
                        enabled = !isClosing,
                        onCheckedChange = { enabled ->
                            hotZoneEnabled = enabled
                            HotZonePreference.setEnabled(context, enabled)

                            // 立即生效
                            if (enabled && FloatingHotZone.canDrawOverlays(context)) {
                                FloatingHotZone.show(context)
                            } else {
                                FloatingHotZone.hide(context)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ★ 手电筒控件开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "手电筒控件",
                            color = Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "手电筒开启时显示快捷通知",
                            color = Color.White.copy(alpha = if (isClosing) 0.25f else 0.5f),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = flashEnabled,
                        enabled = !isClosing,
                        onCheckedChange = { enabled ->
                            flashEnabled = enabled
                            FlashlightPreference.setEnabled(context, enabled)
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !isClosing,
                        onClick = { isStarred = !isStarred }
                    ) {
                        Icon(
                            imageVector = if (isStarred) Icons.Filled.Star
                            else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (isStarred) accent
                            else Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isStarred) "已收藏" else "收藏",
                            color = Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            fontSize = 13.sp
                        )
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !isClosing,
                        onClick = { isShuffled = !isShuffled }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = null,
                            tint = if (isShuffled) accent
                            else Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isShuffled) "随机中" else "随机",
                            color = Color.White.copy(alpha = if (isClosing) 0.4f else 1f),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = onClose,
                    enabled = !isClosing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent.copy(alpha = 0.25f),
                        contentColor = Color.White,
                        disabledContainerColor = accent.copy(alpha = 0.1f),
                        disabledContentColor = Color.White.copy(alpha = 0.4f)
                    )
                ) {
                    Text("关闭", fontSize = 15.sp)
                }

                // 底部留白，滚动到底时不会贴边
                Spacer(Modifier.height(8.dp))
            }
        }

        // ================================================
        // 音频输出面板
        // ================================================
        if (showAudioSheet) {
            AudioOutputSheet(
                onDismiss = { showAudioSheet = false }
            )
        }
    }
}


// ============================================================
// App 来源胶囊
// ============================================================
@Composable
internal fun AppSourcePill(
    context: android.content.Context,
    packageName: String,
    accent: Color
) {
    val iconBitmap: Bitmap? = remember(packageName) {
        try {
            val drawable = context.packageManager
                .getApplicationIcon(packageName)
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(48)
                    val h = drawable.intrinsicHeight.coerceAtLeast(48)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    bmp
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    val appName: String = remember(packageName) {
        try {
            val info = context.packageManager
                .getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            "音乐"
        }
    }

    val shortName = remember(appName) {
        if (appName.length > 10) appName.take(10) + "…" else appName
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
            .clickable {
                try {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                        context.startActivity(intent)
                    }
                } catch (_: Exception) {
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = shortName,
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


// ============================================================
private fun formatMillis(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSec = millis / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}