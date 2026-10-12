package com.example.livenotification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.material.icons.filled.AddPhotoAlternate


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripEditScreen(
    dao: LiveNotificationDao,
    storage: LiveNotificationStorage,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val live = remember { LiveNotificationManager(context) }

    // ============ 表单状态 ============
    var title by remember { mutableStateOf("G1234次") }
    var departureDate by remember { mutableStateOf(todayString()) }
    var arriveDate by remember { mutableStateOf(todayString()) }
    var subtitle by remember { mutableStateOf("正在检票") }
    var status by remember { mutableStateOf("正在检票") }
    var startStation by remember { mutableStateOf("上海虹桥") }
    var startTime by remember { mutableStateOf("07:19") }
    var endStation by remember { mutableStateOf("郑州东") }
    var arriveTime by remember { mutableStateOf("12:57") }
    var bottomText by remember { mutableStateOf("由铁路12306提供服务") }
    var seat by remember { mutableStateOf("08车12D") }
    var gate by remember { mutableStateOf("") }

    // ============ 选择器开关 ============
    var showDepartureDatePicker by remember { mutableStateOf(false) }
    var showArriveDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showArriveTimePicker by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ★ OCR 图片选择器（必须放在表单状态之后，才能引用它们）
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        try {
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(
                    context.contentResolver, uri
                )
                ImageDecoder.decodeBitmap(source)
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(
                    context.contentResolver, uri
                )
            }
            Log.d("OcrTest", "已选择图片: ${bitmap.width}x${bitmap.height}")

            // 调用 OCR → 解析 → 填入表单
            OcrTestHelper.recognize(bitmap) { text ->
                Log.d("OcrTest", "===== OCR 结果开始 =====")
                Log.d("OcrTest", text)
                Log.d("OcrTest", "===== OCR 结果结束 =====")

                val parsed = OcrTripParser.parse(text)

                Log.d("OcrTrip", "解析结果: " +
                        "车次=${parsed.trainNo}, " +
                        "出发站=${parsed.departureStation}, " +
                        "到达站=${parsed.arrivalStation}, " +
                        "出发时间=${parsed.departureTime}, " +
                        "到达时间=${parsed.arrivalTime}, " +
                        "座位=${parsed.seat}, " +
                        "检票口=${parsed.gate}")

                // ★ 填入表单（只在解析到值时才覆盖）
                if (parsed.trainNo.isNotEmpty()) title = parsed.trainNo
                if (parsed.departureStation.isNotEmpty()) startStation = parsed.departureStation
                if (parsed.arrivalStation.isNotEmpty()) endStation = parsed.arrivalStation
                if (parsed.departureTime.isNotEmpty()) startTime = parsed.departureTime
                if (parsed.arrivalTime.isNotEmpty()) arriveTime = parsed.arrivalTime
                if (parsed.seat.isNotEmpty()) seat = parsed.seat
                if (parsed.gate.isNotEmpty()) gate = parsed.gate
                // ★ 日期
                if (parsed.departureDate.isNotEmpty()) departureDate = parsed.departureDate
                if (parsed.arrivalDate.isNotEmpty()) arriveDate = parsed.arrivalDate

                Toast.makeText(
                    context,
                    "已识别 ${parsed.trainNo}：${parsed.departureStation} → ${parsed.arrivalStation}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("OcrTest", "读取图片失败", e)
        }
    }

    // ============ 日期选择器 ============
    if (showDepartureDatePicker) {
        DatePickerDialogWrapper(
            initialDate = departureDate,
            onDismiss = { showDepartureDatePicker = false },
            onConfirm = { newDate ->
                departureDate = newDate
                if (dateToMillis(arriveDate) < dateToMillis(newDate)) {
                    arriveDate = newDate
                }
                showDepartureDatePicker = false
            }
        )
    }

    if (showArriveDatePicker) {
        DatePickerDialogWrapper(
            initialDate = arriveDate,
            onDismiss = { showArriveDatePicker = false },
            onConfirm = { newDate ->
                arriveDate = newDate
                showArriveDatePicker = false
            }
        )
    }

    // ============ 时间选择器 ============
    if (showStartTimePicker) {
        TimePickerDialogWrapper(
            initialTime = startTime,
            onDismiss = { showStartTimePicker = false },
            onConfirm = { newTime ->
                startTime = newTime
                showStartTimePicker = false
            }
        )
    }

    if (showArriveTimePicker) {
        TimePickerDialogWrapper(
            initialTime = arriveTime,
            onDismiss = { showArriveTimePicker = false },
            onConfirm = { newTime ->
                arriveTime = newTime
                showArriveTimePicker = false
            }
        )
    }

    // ============ 错误提示对话框 ============
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("好")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Button(onClick = onBack) {
            Text("返回")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "行程管理",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(12.dp))

        // ★ 从截图导入
        // ★ 从截图导入
        OutlinedButton(
            onClick = {
                imagePicker.launch("image/*")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("从 12306 截图导入")
        }

        Spacer(Modifier.height(16.dp))

        // ============ 车次 ============
        AppTextField(
            value = title,
            onValueChange = { title = it },
            label = "车次",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 出发地点 ============
        AppTextField(
            value = startStation,
            onValueChange = { startStation = it },
            label = "出发地点",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        SelectorRow(
            label = "出发日期",
            value = departureDate,
            icon = Icons.Filled.CalendarMonth,
            onClick = { showDepartureDatePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        SelectorRow(
            label = "出发时间",
            value = startTime,
            icon = Icons.Filled.Schedule,
            onClick = { showStartTimePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = endStation,
            onValueChange = { endStation = it },
            label = "到达地点",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        SelectorRow(
            label = "到达日期",
            value = arriveDate,
            icon = Icons.Filled.CalendarMonth,
            onClick = { showArriveDatePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        SelectorRow(
            label = "到达时间",
            value = arriveTime,
            icon = Icons.Filled.Schedule,
            onClick = { showArriveTimePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = subtitle,
            onValueChange = { subtitle = it },
            label = "副标题",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = status,
            onValueChange = { status = it },
            label = "状态",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = seat,
            onValueChange = { seat = it },
            label = "座位号",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = gate,
            onValueChange = { gate = it },
            label = "检票口",
            placeholder = "例：3A / 12B",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        AppTextField(
            value = bottomText,
            onValueChange = { bottomText = it },
            label = "底部信息",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // ============ 创建按钮 ============
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val departureTime = DateTimeUtils.parseDateTime(departureDate, startTime)
                val arrivalTime = DateTimeUtils.parseDateTime(arriveDate, arriveTime)

                if (arrivalTime <= departureTime) {
                    errorMessage = "还不能回到未来喵"
                    return@Button
                }

                val liveData = LiveData(
                    title = title,
                    departureDate = departureDate,
                    subtitle = subtitle,
                    status = status,
                    startStation = startStation,
                    startTime = startTime,
                    endStation = endStation,
                    endTime = arriveTime,
                    bottomText = bottomText,
                    progress = 0,
                    createTime = System.currentTimeMillis(),
                    departureTime = departureTime,
                    remainText = "计算中",
                    seat = seat,
                    gate = gate,
                    type = "train",
                    arrivalTime = arrivalTime
                )

                NotificationStore.currentTrain = liveData
                storage.saveTrain(liveData)
                live.show(liveData)

                context.startForegroundService(
                    android.content.Intent(context, LiveService::class.java)
                )

                scope.launch {
                    dao.insert(
                        LiveNotificationRecord(
                            title = title,
                            content = subtitle,
                            createTime = System.currentTimeMillis(),
                            endTime = arrivalTime,
                            status = "进行中"
                        )
                    )
                }
            }
        ) {
            Text("创建实时通知")
        }

        Spacer(Modifier.height(20.dp))
    }
}


// ============================================================
// 只读选择行
// ============================================================
@Composable
internal fun SelectorRow(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


// ============================================================
// 日期选择器对话框
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerDialogWrapper(
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialMillis = remember {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(initialDate)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        onConfirm(sdf.format(Date(millis)))
                    } ?: onDismiss()
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    ) {
        DatePicker(state = state)
    }
}


// ============================================================
// 时间选择器对话框
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialogWrapper(
    initialTime: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val (hour, minute) = remember {
        try {
            val parts = initialTime.split(":")
            parts[0].toInt() to parts[1].toInt()
        } catch (e: Exception) {
            7 to 19
        }
    }

    val state = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = true
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "选择时间",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TimePicker(state = state)
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            val h = state.hour.toString().padStart(2, '0')
                            val m = state.minute.toString().padStart(2, '0')
                            onConfirm("$h:$m")
                        }
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}


// ============================================================
// 工具函数
// ============================================================
internal fun todayString(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date())
}

internal fun dateToMillis(dateStr: String): Long {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}