package com.example.livenotification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightTripEditScreen(
    dao: LiveNotificationDao,
    storage: LiveNotificationStorage,
    onBack: () -> Unit
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val live = remember { LiveNotificationManager(context) }

    // ============ 表单状态 ============
    var flightNo by remember { mutableStateOf("CA1234") }
    var airline by remember { mutableStateOf("中国国航") }
    var departureDate by remember { mutableStateOf(todayString()) }
    var arriveDate by remember { mutableStateOf(todayString()) }
    var subtitle by remember { mutableStateOf("值机中") }
    var status by remember { mutableStateOf("值机中") }
    var startAirport by remember { mutableStateOf("北京首都") }
    var startTime by remember { mutableStateOf("08:30") }
    var endAirport by remember { mutableStateOf("上海虹桥") }
    var arriveTime by remember { mutableStateOf("10:45") }
    var bottomText by remember { mutableStateOf("由航空公司提供服务") }
    var seat by remember { mutableStateOf("32A") }
    var terminal by remember { mutableStateOf("T2") }
    var boardingGate by remember { mutableStateOf("A12") }
    var checkinCounter by remember { mutableStateOf("C01-C10") }

    // ============ 选择器开关 ============
    var showDepartureDatePicker by remember { mutableStateOf(false) }
    var showArriveDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showArriveTimePicker by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

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

    // ============ 错误提示 ============
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
            text = "航班管理",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(16.dp))

        // ============ 航班号 ============
        AppTextField(
            value = flightNo,
            onValueChange = { flightNo = it },
            label = "航班号",
            placeholder = "例：CA1234",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 航空公司 ============
        AppTextField(
            value = airline,
            onValueChange = { airline = it },
            label = "航空公司",
            placeholder = "例：中国国航",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 出发机场 ============
        AppTextField(
            value = startAirport,
            onValueChange = { startAirport = it },
            label = "出发机场",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // 出发日期
        SelectorRow(
            label = "出发日期",
            value = departureDate,
            icon = Icons.Filled.CalendarMonth,
            onClick = { showDepartureDatePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        // 出发时间
        SelectorRow(
            label = "出发时间",
            value = startTime,
            icon = Icons.Filled.Schedule,
            onClick = { showStartTimePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        // ============ 到达机场 ============
        AppTextField(
            value = endAirport,
            onValueChange = { endAirport = it },
            label = "到达机场",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // 到达日期
        SelectorRow(
            label = "到达日期",
            value = arriveDate,
            icon = Icons.Filled.CalendarMonth,
            onClick = { showArriveDatePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        // 到达时间
        SelectorRow(
            label = "到达时间",
            value = arriveTime,
            icon = Icons.Filled.Schedule,
            onClick = { showArriveTimePicker = true }
        )

        Spacer(Modifier.height(8.dp))

        // ============ 航站楼 ============
        AppTextField(
            value = terminal,
            onValueChange = { terminal = it },
            label = "航站楼",
            placeholder = "例：T1 / T2 / T3",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 登机口 ============
        AppTextField(
            value = boardingGate,
            onValueChange = { boardingGate = it },
            label = "登机口",
            placeholder = "例：A12 / B07",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 值机柜台 ============
        AppTextField(
            value = checkinCounter,
            onValueChange = { checkinCounter = it },
            label = "值机柜台",
            placeholder = "例：C01-C10",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 座位号 ============
        AppTextField(
            value = seat,
            onValueChange = { seat = it },
            label = "座位号",
            placeholder = "例：32A",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 副标题 ============
        AppTextField(
            value = subtitle,
            onValueChange = { subtitle = it },
            label = "副标题",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 状态 ============
        AppTextField(
            value = status,
            onValueChange = { status = it },
            label = "状态",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ============ 底部信息 ============
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

                val departureTime = DateTimeUtils.parseDateTime(
                    departureDate, startTime
                )
                val arrivalTime = DateTimeUtils.parseDateTime(
                    arriveDate, arriveTime
                )

                if (arrivalTime <= departureTime) {
                    errorMessage = "还不能回到未来喵"
                    return@Button
                }

                val liveData = LiveData(
                    title = flightNo,
                    departureDate = departureDate,
                    subtitle = subtitle,
                    status = status,
                    startStation = startAirport,
                    startTime = startTime,
                    endStation = endAirport,
                    endTime = arriveTime,
                    bottomText = bottomText,
                    progress = 0,
                    createTime = System.currentTimeMillis(),
                    departureTime = departureTime,
                    remainText = "计算中",
                    seat = seat,
                    gate = "",
                    type = "flight",
                    airline = airline,
                    terminal = terminal,
                    boardingGate = boardingGate,
                    checkinCounter = checkinCounter,
                    arrivalTime = arrivalTime
                )

                NotificationStore.currentFlight = liveData
                storage.saveFlight(liveData)
                live.show(liveData)

                context.startForegroundService(
                    android.content.Intent(context, LiveService::class.java)
                )

                scope.launch {
                    dao.insert(
                        LiveNotificationRecord(
                            title = "航班 · $flightNo",
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