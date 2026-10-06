package com.example.livenotification


import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


@Composable
fun HistoryScreen(
    dao: LiveNotificationDao,
    backupStorage: BackupStorage,
    onBack: () -> Unit
) {

    val context = LocalContext.current

    val dirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val ok = backupStorage.saveTreeUri(uri)
            if (ok) {
                Toast.makeText(context, "备份目录已设置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存目录权限失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var records by remember {
        mutableStateOf(emptyList<LiveNotificationRecord>())
    }

    val scope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }

    // 正在编辑的记录；null 表示对话框关闭
    var editingRecord by remember { mutableStateOf<LiveNotificationRecord?>(null) }

    LaunchedEffect(Unit) {
        records = dao.getAll()
        backupStorage.syncFromDao(dao)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {

        Button(onClick = { onBack() }) {
            Text("返回")
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "实时通知历史",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(10.dp))

        Button(onClick = { dirPicker.launch(null) }) {
            Text(
                if (backupStorage.hasBackupDir()) {
                    "备份目录：${backupStorage.getBackupDirLabel()}"
                } else {
                    "选择备份目录"
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = backupStorage.hasBackupDir(),
                onClick = {
                    scope.launch {
                        val ok = backupStorage.syncFromDao(dao)
                        Toast.makeText(
                            context,
                            if (ok) "已导出到外部备份" else "导出失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            ) {
                Text("立即导出")
            }

            Button(
                modifier = Modifier.weight(1f),
                enabled = backupStorage.hasBackupDir(),
                onClick = { showImportDialog = true }
            ) {
                Text("从外部导入")
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn {

            items(records) { item ->

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {

                    Column(modifier = Modifier.padding(16.dp)) {

                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(text = item.content)

                        Spacer(Modifier.height(8.dp))

                        Text(text = "创建时间：" + formatTime(item.createTime))
                        Text(text = "状态：" + item.status)

                        Spacer(Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    editingRecord = item
                                }
                            ) {
                                Text("编辑")
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                enabled = item.payloadJson.isNotBlank(),
                                onClick = {
                                    activateRecord(context, item)
                                }
                            ) {
                                Text("激活")
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        dao.delete(item)
                                        records = dao.getAll()
                                        backupStorage.syncFromDao(dao)
                                    }
                                }
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== 导入对话框 ====================
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入方式") },
            text = {
                Text("合并：保留现有记录，只添加新的\n覆盖：清空现有记录，全部替换")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        scope.launch {
                            val imported = backupStorage.importRecords()
                            if (imported == null) {
                                Toast.makeText(
                                    context,
                                    "读取失败：文件不存在或格式错误",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            val safe = imported.map { it.copy(id = 0) }
                            dao.insertAll(safe)
                            records = dao.getAll()
                            backupStorage.syncFromDao(dao)
                            Toast.makeText(
                                context,
                                "已合并 ${safe.size} 条记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("合并") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        scope.launch {
                            val imported = backupStorage.importRecords()
                            if (imported == null) {
                                Toast.makeText(
                                    context,
                                    "读取失败：文件不存在或格式错误",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                            dao.deleteAll()
                            val safe = imported.map { it.copy(id = 0) }
                            dao.insertAll(safe)
                            records = dao.getAll()
                            backupStorage.syncFromDao(dao)
                            Toast.makeText(
                                context,
                                "已覆盖为 ${safe.size} 条记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("覆盖") }
            }
        )
    }

    // ==================== 编辑对话框 ====================
    editingRecord?.let { record ->
        EditRecordDialog(
            record = record,
            onDismiss = { editingRecord = null },
            onSave = { updated ->
                scope.launch {
                    dao.update(updated)
                    records = dao.getAll()
                    backupStorage.syncFromDao(dao)
                    editingRecord = null
                }
            }
        )
    }
}


// ==================== 编辑对话框 ====================
@Composable
private fun EditRecordDialog(
    record: LiveNotificationRecord,
    onDismiss: () -> Unit,
    onSave: (LiveNotificationRecord) -> Unit
) {

    // 从 payloadJson 恢复初始值；如果无效就用 record 的字段兜底
    val initialData: LiveData = remember(record) {
        LiveNotificationBackup.fromJsonString(record.payloadJson)
            ?: LiveData(
                title = record.title,
                subtitle = record.content,
                status = record.status,
                startStation = "",
                startTime = "00:00",
                endStation = "",
                endTime = "00:00",
                bottomText = "",
                progress = 0,
                createTime = record.createTime,
                departureTime = record.createTime,
                arrivalTime = record.endTime,
                remainText = "计算中",
                departureDate = "",
                seat = ""
            )
    }

    var title by remember { mutableStateOf(initialData.title) }
    var startStation by remember { mutableStateOf(initialData.startStation) }
    var endStation by remember { mutableStateOf(initialData.endStation) }
    var startTime by remember { mutableStateOf(initialData.startTime) }
    var endTime by remember { mutableStateOf(initialData.endTime) }
    var dDate by remember {
        mutableStateOf(extractDate(initialData.departureTime))
    }
    var aDate by remember {
        mutableStateOf(extractDate(initialData.arrivalTime))
    }
    var seat by remember { mutableStateOf(initialData.seat) }
    var bottomText by remember { mutableStateOf(initialData.bottomText) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                Text(
                    text = "编辑历史记录",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("车次") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = startStation,
                    onValueChange = { startStation = it },
                    label = { Text("出发站") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = dDate,
                    onValueChange = { dDate = it },
                    label = { Text("出发日期 yyyy-MM-dd") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text("出发时间 HH:mm") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = endStation,
                    onValueChange = { endStation = it },
                    label = { Text("到达站") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = aDate,
                    onValueChange = { aDate = it },
                    label = { Text("到达日期 yyyy-MM-dd") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = endTime,
                    onValueChange = { endTime = it },
                    label = { Text("到达时间 HH:mm") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = seat,
                    onValueChange = { seat = it },
                    label = { Text("座位") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = bottomText,
                    onValueChange = { bottomText = it },
                    label = { Text("底部信息") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss
                    ) {
                        Text("取消")
                    }

                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val newDepartureTime =
                                DateTimeUtils.parseDateTime(dDate, startTime)
                            val newArrivalTime =
                                DateTimeUtils.parseDateTime(aDate, endTime)

                            val newData = initialData.copy(
                                title = title,
                                startStation = startStation,
                                startTime = startTime,
                                endStation = endStation,
                                endTime = endTime,
                                departureTime = newDepartureTime,
                                arrivalTime = newArrivalTime,
                                departureDate = dDate,
                                seat = seat,
                                bottomText = bottomText
                            )

                            val updated = record.copy(
                                title = title,
                                endTime = newArrivalTime,
                                payloadJson =
                                    LiveNotificationBackup.toJsonString(newData)
                            )
                            onSave(updated)
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}


// ==================== 激活历史记录为实时通知 ====================
private fun activateRecord(
    context: Context,
    record: LiveNotificationRecord
) {

    val data = LiveNotificationBackup.fromJsonString(record.payloadJson)
    if (data == null) {
        Toast.makeText(
            context,
            "无法激活：该记录缺少完整信息",
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    // 如果到达时间已过，无法激活
    if (data.arrivalTime <= System.currentTimeMillis()) {
        Toast.makeText(
            context,
            "该行程已结束，无法激活",
            Toast.LENGTH_SHORT
        ).show()
        return
    }

    // 写入内存共享状态，并启动 Service
    NotificationStore.current = data
    context.startForegroundService(
        Intent(context, LiveService::class.java)
    )

    Toast.makeText(
        context,
        "已激活为实时通知",
        Toast.LENGTH_SHORT
    ).show()
}


// ==================== 时间工具 ====================
fun formatTime(time: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(time))
}

private fun extractDate(millis: Long): String {
    if (millis <= 0L) return "2026-01-01"
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(millis))
}