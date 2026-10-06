package com.example.livenotification

import android.content.Context
import android.widget.Toast
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


@Composable
fun ExpressScreen(
    onBack: () -> Unit
) {

    val context = LocalContext.current

    // 从 ExpressStore 读取列表
    var items by remember { mutableStateOf(ExpressStore.getAll()) }

    // 数据变化时刷新
    DisposableEffect(Unit) {
        val listener = { items = ExpressStore.getAll() }
        ExpressStore.addListener(listener)
        onDispose { ExpressStore.removeListener(listener) }
    }

    // 编辑/新增对话框状态
    var editingData by remember { mutableStateOf<ExpressData?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {

        Button(onClick = onBack) {
            Text("返回")
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "快递取件",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "共 ${items.size} 件待取",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { showAddDialog = true }
            ) {
                Text("添加快递")
            }

            Button(
                modifier = Modifier.weight(1f),
                enabled = items.isNotEmpty(),
                onClick = {
                    ExpressStore.clear()
                    refreshExpressNotification(context)
                    Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("全部清空")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (items.isEmpty()) {
            Text(
                text = "还没有快递记录\n点上方「添加快递」开始",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn {
                items(items, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {

                            Text(
                                text = item.location,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(text = "取件码：${item.code}")

                            Spacer(Modifier.height(4.dp))

                            Text(
                                text = "添加时间：" + formatTime(item.createTime),
                                style = MaterialTheme.typography.bodySmall
                            )

                            Spacer(Modifier.height(10.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { editingData = item }
                                ) {
                                    Text("编辑")
                                }

                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        ExpressStore.remove(item.id)
                                        refreshExpressNotification(context)
                                        Toast.makeText(
                                            context,
                                            "已删除",
                                            Toast.LENGTH_SHORT
                                        ).show()
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
    }

    // ==================== 新增对话框 ====================
    if (showAddDialog) {
        ExpressEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onSave = { location, code ->
                ExpressStore.add(ExpressStore.create(location, code))
                refreshExpressNotification(context)
                showAddDialog = false
            }
        )
    }

    // ==================== 编辑对话框 ====================
    editingData?.let { record ->
        ExpressEditDialog(
            initial = record,
            onDismiss = { editingData = null },
            onSave = { location, code ->
                ExpressStore.update(record.copy(location = location, code = code))
                refreshExpressNotification(context)
                editingData = null
            }
        )
    }
}


/**
 * 统一刷新快递通知
 * 数据变化后调用，保证 Service 没在跑时通知也能及时更新
 */
private fun refreshExpressNotification(context: Context) {
    ExpressNotificationManager(context).show(ExpressStore.getAll())
}


/**
 * 快递信息编辑/新增对话框
 * initial = null 表示新增；否则表示编辑
 */
@Composable
private fun ExpressEditDialog(
    initial: ExpressData?,
    onDismiss: () -> Unit,
    onSave: (location: String, code: String) -> Unit
) {

    var location by remember {
        mutableStateOf(initial?.location ?: "")
    }
    var code by remember {
        mutableStateOf(initial?.code ?: "")
    }

    val title = if (initial == null) "添加快递" else "编辑快递"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("取件地点") },
                    placeholder = { Text("例：菜鸟驿站（小区东门）") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("取件码") },
                    placeholder = { Text("例：123-4-5678") },
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
                        enabled = location.isNotBlank() && code.isNotBlank(),
                        onClick = {
                            onSave(location.trim(), code.trim())
                        }
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}