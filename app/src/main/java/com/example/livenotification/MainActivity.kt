package com.example.livenotification


import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        // 初始化快递存储（如果还没初始化）
        ExpressStore.init(this)

        requestPermissions(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            100
        )


        val live =
            LiveNotificationManager(this)


        val storage =
            LiveNotificationStorage(this)


        val database =
            Room.databaseBuilder(
                applicationContext,
                LiveDatabase::class.java,
                "live_history"
            )
                .addMigrations(LiveDatabase.MIGRATION_1_2)
                .build()


        val dao =
            database.dao()


        live.createChannel()


        setContent {


            var showHistory by remember {
                mutableStateOf(false)
            }

            var showExpress by remember {
                mutableStateOf(false)
            }


            var title by remember {
                mutableStateOf(
                    "G1234次"
                )
            }


            var departureDate by remember {
                mutableStateOf(
                    "2026-09-28"
                )
            }


            var arriveDate by remember {
                mutableStateOf(
                    "2026-09-28"
                )
            }


            var subtitle by remember {
                mutableStateOf(
                    "正在检票"
                )
            }


            var status by remember {
                mutableStateOf(
                    "正在检票"
                )
            }


            var startStation by remember {
                mutableStateOf(
                    "上海虹桥"
                )
            }


            var startTime by remember {
                mutableStateOf(
                    "07:19"
                )
            }


            var endStation by remember {
                mutableStateOf(
                    "郑州东"
                )
            }


            var arriveTime by remember {
                mutableStateOf(
                    "12:57"
                )
            }


            var bottomText by remember {
                mutableStateOf(
                    "由铁路12306提供服务"
                )
            }

            var seat by remember {
                mutableStateOf(
                    "08车12D"
                )
            }


            Scaffold { innerPadding ->

                when {
                    // 历史记录
                    showHistory -> {
                        HistoryScreen(
                            dao = dao,
                            backupStorage = BackupStorage(this@MainActivity),
                            onBack = { showHistory = false }
                        )
                    }

                    // 快递管理
                    showExpress -> {
                        ExpressScreen(
                            onBack = { showExpress = false }
                        )
                    }

                    // 主界面
                    else -> {
                        Column(
                            modifier =
                                Modifier
                                    .padding(innerPadding)
                                    .padding(20.dp)
                                    .verticalScroll(
                                        rememberScrollState()
                                    )
                        ) {

                            TextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("车次") }
                            )

                            TextField(
                                value = departureDate,
                                onValueChange = { departureDate = it },
                                label = { Text("出发日期 yyyy-MM-dd") }
                            )

                            TextField(
                                value = startStation,
                                onValueChange = { startStation = it },
                                label = { Text("出发地点") }
                            )

                            TextField(
                                value = startTime,
                                onValueChange = { startTime = it },
                                label = { Text("出发时间 HH:mm") }
                            )

                            TextField(
                                value = endStation,
                                onValueChange = { endStation = it },
                                label = { Text("到达地点") }
                            )

                            TextField(
                                value = arriveDate,
                                onValueChange = { arriveDate = it },
                                label = { Text("到达日期 yyyy-MM-dd") }
                            )

                            TextField(
                                value = arriveTime,
                                onValueChange = { arriveTime = it },
                                label = { Text("到达时间 HH:mm") }
                            )

                            TextField(
                                value = subtitle,
                                onValueChange = { subtitle = it },
                                label = { Text("副标题") }
                            )

                            TextField(
                                value = status,
                                onValueChange = { status = it },
                                label = { Text("状态") }
                            )

                            TextField(
                                value = seat,
                                onValueChange = { seat = it },
                                label = { Text("座位号") }
                            )

                            TextField(
                                value = bottomText,
                                onValueChange = { bottomText = it },
                                label = { Text("底部信息") }
                            )


                            Spacer(Modifier.height(16.dp))


                            Button(
                                onClick = {

                                    val departureTime =
                                        DateTimeUtils.parseDateTime(
                                            departureDate,
                                            startTime
                                        )

                                    val arrivalTime =
                                        DateTimeUtils.parseDateTime(
                                            arriveDate,
                                            arriveTime
                                        )

                                    val expireTime = arrivalTime

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
                                        arrivalTime = arrivalTime
                                    )

                                    NotificationStore.current = liveData

                                    storage.save(liveData)

                                    live.show(liveData)

                                    startForegroundService(
                                        Intent(
                                            this@MainActivity,
                                            LiveService::class.java
                                        )
                                    )

                                    lifecycleScope.launch {
                                        dao.insert(
                                            LiveNotificationRecord(
                                                title = title,
                                                content = subtitle,
                                                createTime = System.currentTimeMillis(),
                                                endTime = expireTime,
                                                status = "进行中"
                                            )
                                        )
                                    }
                                }
                            ) {
                                Text("创建实时通知")
                            }


                            Spacer(Modifier.height(10.dp))


                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { showHistory = true }
                                ) {
                                    Text("历史记录")
                                }

                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = { showExpress = true }
                                ) {
                                    Text("快递管理")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}