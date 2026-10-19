package com.example.livenotification

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
fun HomeScreen(
    dao: LiveNotificationDao,
    onOpenMediaControl: () -> Unit,
    onLongPressTrip: () -> Unit,
    onLongPressFlight: () -> Unit,
    onLongPressExpress: () -> Unit,
    onOpenBudsControl: () -> Unit = {},
    // 打开锁屏岛设置（Compose 页面，风格与主软件完全一致）
    onOpenIslandSettings: () -> Unit = {},
    // 打开气泡通知设置（Compose 页面，风格与主软件完全一致）
    onOpenBubbleSettings: () -> Unit = {}
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var musicEntries by remember {
        mutableStateOf(MediaSessionStore.entries)
    }
    var fallbackMusic by remember {
        mutableStateOf(MusicStore.get() ?: MusicStore.getLastPlayed())
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    var tripTrainData by remember { mutableStateOf<LiveData?>(null) }
    var tripFlightData by remember { mutableStateOf<LiveData?>(null) }
    var tripCompleted by remember { mutableStateOf(false) }
    var expressList by remember { mutableStateOf(ExpressStore.getAll()) }

    val emptyText = remember {
        if ((0..1).random() == 0) "大功告成" else "全部完成"
    }

    LaunchedEffect(Unit) {
        while (true) {
            musicEntries = MediaSessionStore.entries

            val base = MusicStore.get() ?: MusicStore.getLastPlayed()
            val controller = MediaActionReceiver.currentController

            fallbackMusic = if (base != null && controller != null) {
                val meta = controller.metadata
                val art = meta?.getBitmap(
                    android.media.MediaMetadata.METADATA_KEY_ALBUM_ART
                ) ?: meta?.getBitmap(
                    android.media.MediaMetadata.METADATA_KEY_ART
                ) ?: base.albumArt

                val title = meta?.getString(
                    android.media.MediaMetadata.METADATA_KEY_TITLE
                )?.takeIf { it.isNotBlank() } ?: base.title

                val artist = meta?.getString(
                    android.media.MediaMetadata.METADATA_KEY_ARTIST
                )?.takeIf { it.isNotBlank() } ?: base.artist

                base.copy(title = title, artist = artist, albumArt = art)
            } else {
                base
            }

            controller?.let { c ->
                val pb = c.playbackState
                position = pb?.position ?: 0L
                duration = c.metadata?.getLong(
                    android.media.MediaMetadata.METADATA_KEY_DURATION
                ) ?: 0L
            }

            val train = NotificationStore.currentTrain
            tripTrainData = if (train != null) {
                val liveProgress = CountdownManager.calcProgress(
                    train.createTime, train.departureTime, train.arrivalTime
                )
                train.copy(progress = liveProgress)
            } else {
                CompletedTripStore.get()
            }

            val flight = NotificationStore.currentFlight
            tripFlightData = if (flight != null) {
                val liveProgress = CountdownManager.calcProgress(
                    flight.createTime, flight.departureTime, flight.arrivalTime
                )
                flight.copy(progress = liveProgress)
            } else {
                null
            }

            tripCompleted = train == null && CompletedTripStore.get() != null

            expressList = ExpressStore.getAll()

            delay(500L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Spacer(Modifier.height(8.dp))

        Text(
            text = "首页",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(16.dp))

        // ============ 音乐卡片 ============
        val hasMulti = musicEntries.size > 1
        val hasSingle = musicEntries.size == 1 || fallbackMusic != null

        if (hasMulti) {
            HomeMusicPager(
                entries = musicEntries,
                onClick = { onOpenMediaControl() },
                onGeometryChanged = { topLeft, width, height ->
                    MusicCardGeometry.topLeft = topLeft
                    MusicCardGeometry.width = width
                    MusicCardGeometry.height = height
                }
            )
            Spacer(Modifier.height(12.dp))
        } else if (hasSingle) {
            val single = musicEntries.firstOrNull()

            val displayMusic = if (single != null) {
                MusicData(
                    title = single.title,
                    artist = single.artist,
                    albumArt = single.albumArt,
                    packageName = single.packageName,
                    isPlaying = single.isPlaying,
                    sessionToken = null,
                    lastPauseTime = 0L
                )
            } else {
                fallbackMusic!!
            }

            HomeMusicCard(
                music = displayMusic,
                position = position,
                duration = duration,
                onClick = { onOpenMediaControl() },
                onGeometryChanged = { topLeft, width, height ->
                    MusicCardGeometry.topLeft = topLeft
                    MusicCardGeometry.width = width
                    MusicCardGeometry.height = height
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        // ============ 火车卡片 ============
        if (tripTrainData != null) {
            TripCard(
                data = tripTrainData!!,
                isCompleted = tripCompleted,
                onLongPress = onLongPressTrip
            )
        } else {
            EmptyCard(
                label = "火车行程",
                emptyText = emptyText,
                onLongPress = onLongPressTrip
            )
        }

        Spacer(Modifier.height(12.dp))

        // ============ 航班卡片 ============
        if (tripFlightData != null) {
            TripCard(
                data = tripFlightData!!,
                isCompleted = false,
                onLongPress = onLongPressFlight
            )
        } else {
            EmptyCard(
                label = "航班",
                emptyText = emptyText,
                onLongPress = onLongPressFlight
            )
        }

        Spacer(Modifier.height(12.dp))

        // ============ 快递卡片 ============
        if (expressList.isNotEmpty()) {
            ExpressHomeCard(
                items = expressList,
                onCopy = { code ->
                    copyToClipboard(context, code)
                    Toast.makeText(context, "已复制取件码", Toast.LENGTH_SHORT).show()
                },
                onPickedUp = { data ->
                    ExpressStore.remove(data.id)
                    refreshExpressNotification(context)

                    scope.launch {
                        dao.insert(
                            LiveNotificationRecord(
                                title = "快递 · ${data.location}",
                                content = "取件码：${data.code}",
                                createTime = System.currentTimeMillis(),
                                endTime = System.currentTimeMillis(),
                                status = "已取件",
                                payloadJson = ""
                            )
                        )
                    }

                    Toast.makeText(context, "已标记取件", Toast.LENGTH_SHORT).show()
                },
                onDelete = { data ->
                    ExpressStore.remove(data.id)
                    refreshExpressNotification(context)
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                },
                onLongPress = onLongPressExpress
            )
        } else {
            EmptyCard(
                label = "快递",
                emptyText = emptyText,
                onLongPress = onLongPressExpress
            )
        }

        Spacer(Modifier.height(12.dp))

        // ============ 耳机降噪控制入口 ============
        BudsControlEntryCard(onClick = onOpenBudsControl)

        // ============ 锁屏岛设置入口（合并自 LockScreenIsland 模块）============
        Spacer(Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenIslandSettings() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "锁屏岛",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "样式 / 位置与大小 / 调试日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ============ 气泡通知入口（合并自 BubbleNotifyDemo）============
        Spacer(Modifier.height(16.dp))

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenBubbleSettings() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "气泡通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "微信 / QQ 消息接成原生气泡",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}


// ============================================================
// 耳机控制入口卡片
// ============================================================
@Composable
private fun BudsControlEntryCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val forcedMode = remember {
        mutableIntStateOf(BudsControlSettings.readForcedMode(context))
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    "耳机降噪",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "预设：${BudsControlSettings.modeName(forcedMode.intValue)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ============================================================
// 音乐 Pager
// ============================================================
@Composable
private fun HomeMusicPager(
    entries: List<MediaSessionStore.Entry>,
    onClick: () -> Unit,
    onGeometryChanged: (Offset, Dp, Dp) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    val pagerState = rememberPagerState(
        initialPage = MediaSessionStore.currentIndex
            .coerceIn(0, (entries.size - 1).coerceAtLeast(0)),
        pageCount = { entries.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        MediaSessionStore.selectIndex(pagerState.currentPage)
    }

    val coverEndW = screenWidthDp
    val coverEndH = minOf(
        (screenWidthDp / 0.70f).value,
        (screenHeightDp * 0.55f).value
    ).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .onGloballyPositioned { coords ->
                val posInRoot = coords.positionInRoot()
                val widthDp = with(density) { coords.size.width.toDp() }
                val heightDp = with(density) { coords.size.height.toDp() }
                onGeometryChanged(posInRoot, widthDp, heightDp)
            }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->

            val entry = entries.getOrNull(page) ?: return@HorizontalPager

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    Box(
                        modifier = Modifier
                            .width(coverEndW)
                            .height(coverEndH)
                            .align(Alignment.Center)
                    ) {
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
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

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
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Bottom
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

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(entries.size) { index ->
                val selected = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .size(if (selected) 7.dp else 5.dp)
                        .background(
                            color = Color.White.copy(
                                alpha = if (selected) 0.9f else 0.4f
                            ),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}


// ============================================================
// 单条音乐卡片
// ============================================================
@Composable
private fun HomeMusicCard(
    music: MusicData,
    position: Long,
    duration: Long,
    onClick: () -> Unit,
    onGeometryChanged: (Offset, Dp, Dp) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp

    val coverEndW = screenWidthDp
    val coverEndH = minOf(
        (screenWidthDp / 0.70f).value,
        (screenHeightDp * 0.55f).value
    ).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .onGloballyPositioned { coords ->
                val posInRoot = coords.positionInRoot()
                val widthDp = with(density) { coords.size.width.toDp() }
                val heightDp = with(density) { coords.size.height.toDp() }
                onGeometryChanged(posInRoot, widthDp, heightDp)
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            Box(
                modifier = Modifier
                    .width(coverEndW)
                    .height(coverEndH)
                    .align(Alignment.Center)
            ) {
                music.albumArt?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }

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
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = music.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = music.artist,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))

            if (duration > 0) {
                LinearProgressIndicator(
                    progress = {
                        (position.toFloat() / duration).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}


// ============================================================
// 行程卡片
// ============================================================
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TripCard(
    data: LiveData,
    isCompleted: Boolean,
    onLongPress: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val isFlight = data.type == "flight"
    val now = System.currentTimeMillis()

    val (statusText, statusColor) = when {
        isCompleted -> "已到达" to MaterialTheme.colorScheme.onSurfaceVariant
        now < data.departureTime -> "等待出行" to Color(0xFF4A90E2)
        now < data.arrivalTime -> "已出发" to Color(0xFF4CAF50)
        else -> "已到达" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val countdown = CountdownManager.getCountdownText(
        data.departureTime,
        data.arrivalTime
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongPress
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isFlight) "✈️ ${data.title}" else data.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = data.startStation,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = data.startTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = data.endStation,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = data.endTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearWavyProgressIndicator(
                    progress = { data.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    gapSize = 0.dp,
                    amplitude = { if (data.progress in 1..99) 0.7f else 0f },
                    wavelength = 24.dp,
                    waveSpeed = 12.dp
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "${data.progress}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess
                    else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(16.dp))

                TicketDivider()

                Spacer(Modifier.height(16.dp))

                if (isFlight && data.airline.isNotBlank()) {
                    TicketColumn(
                        modifier = Modifier.fillMaxWidth(),
                        label = "航空公司",
                        value = data.airline
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    TicketColumn(
                        modifier = Modifier.weight(1f),
                        label = "出发日期",
                        value = data.departureDate
                    )
                    TicketColumn(
                        modifier = Modifier.weight(1f),
                        label = "到达日期",
                        value = extractDate(data.arrivalTime)
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TicketColumn(
                        modifier = Modifier.weight(1f),
                        label = "出发时间",
                        value = data.startTime
                    )
                    TicketColumn(
                        modifier = Modifier.weight(1f),
                        label = "到达时间",
                        value = data.endTime
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (isFlight) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "航站楼",
                            value = data.terminal.ifBlank { "—" }
                        )
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "登机口",
                            value = data.boardingGate.ifBlank { "—" }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "值机柜台",
                            value = data.checkinCounter.ifBlank { "—" }
                        )
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "座位",
                            value = data.seat.ifBlank { "—" }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    TicketColumn(
                        modifier = Modifier.fillMaxWidth(),
                        label = "剩余",
                        value = countdown,
                        valueColor = statusColor
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "检票口",
                            value = data.gate.ifBlank { "—" }
                        )
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "座位",
                            value = data.seat.ifBlank { "—" }
                        )
                        TicketColumn(
                            modifier = Modifier.weight(1f),
                            label = "剩余",
                            value = countdown,
                            valueColor = statusColor
                        )
                    }
                }

                if (data.bottomText.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    TicketColumn(
                        modifier = Modifier.fillMaxWidth(),
                        label = "备注",
                        value = data.bottomText
                    )
                }
            }
        }
    }
}


// ============================================================
// 车票虚线
// ============================================================
@Composable
private fun TicketDivider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        val dashWidth = 8f
        val gapWidth = 6f
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = Color.Gray.copy(alpha = 0.5f),
                start = Offset(x, 0f),
                end = Offset((x + dashWidth).coerceAtMost(size.width), 0f),
                strokeWidth = size.height
            )
            x += dashWidth + gapWidth
        }
    }
}


// ============================================================
// 车票信息列
// ============================================================
@Composable
private fun TicketColumn(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (valueColor != Color.Unspecified) valueColor
            else MaterialTheme.colorScheme.onSurface
        )
    }
}


// ============================================================
// 从时间戳提取日期
// ============================================================
private fun extractDate(millis: Long): String {
    if (millis <= 0L) return "—"
    val sdf = java.text.SimpleDateFormat(
        "yyyy-MM-dd",
        java.util.Locale.getDefault()
    )
    return sdf.format(java.util.Date(millis))
}


// ============================================================
// 快递卡片
// ============================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpressHomeCard(
    items: List<ExpressData>,
    onCopy: (code: String) -> Unit,
    onPickedUp: (ExpressData) -> Unit,
    onDelete: (ExpressData) -> Unit,
    onLongPress: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongPress
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "待取快递",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = items.firstOrNull()?.let {
                            "${it.location} 等 ${items.size} 件"
                        } ?: "共 ${items.size} 件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "${items.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess
                    else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(16.dp))

                TicketDivider()

                Spacer(Modifier.height(16.dp))

                items.forEachIndexed { index, item ->
                    Column(modifier = Modifier.fillMaxWidth()) {

                        Text(
                            text = "${index + 1}. ${item.location}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = "取件码：${item.code}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onCopy(item.code) },
                                contentPadding = PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Text("复制", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onPickedUp(item) },
                                contentPadding = PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                )
                            ) {
                                Text("已取件", fontSize = 13.sp)
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { onDelete(item) },
                                contentPadding = PaddingValues(
                                    horizontal = 8.dp,
                                    vertical = 4.dp
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("删除", fontSize = 13.sp)
                            }
                        }

                        if (index < items.lastIndex) {
                            Spacer(Modifier.height(12.dp))
                            TicketDivider()
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}


// ============================================================
// 空状态卡片
// ============================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmptyCard(
    label: String,
    emptyText: String,
    onLongPress: (() -> Unit)? = null
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(
                        onClick = { },
                        onLongClick = onLongPress
                    )
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = emptyText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "无$label",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}


// ============================================================
// 工具函数
// ============================================================
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("取件码", text))
}

private fun refreshExpressNotification(context: Context) {
    ExpressNotificationManager(context).show(ExpressStore.getAll())
}