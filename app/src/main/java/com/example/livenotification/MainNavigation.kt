package com.example.livenotification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier


@Composable
fun MainNavigation(
    dao: LiveNotificationDao,
    storage: LiveNotificationStorage,
    backupStorage: BackupStorage,
    onOpenMediaControl: () -> Unit,
    onReplayOnboarding: () -> Unit = {},
) {

    var selectedTab by remember { mutableIntStateOf(0) }

    // ★ 锁屏岛设置页（整页覆盖，隐藏底部导航）
    var showIslandSettings by remember { mutableStateOf(false) }

    // ★ 气泡通知设置页（整页覆盖，隐藏底部导航）
    var showBubbleSettings by remember { mutableStateOf(false) }

    // ★ 管理页的子页面状态，提升到这里，供首页长按跳转
    var manageSubPage by remember { mutableStateOf<String?>(null) }

    // ★ 从首页长按跳转到管理页的某个子页面
    fun navigateToManageSubPage(page: String) {
        manageSubPage = page
        selectedTab = 1
    }

    // ★ 锁屏岛设置：整页覆盖（不显示底部导航），Compose 主题与主软件完全一致
    if (showIslandSettings) {
        IslandSettingsScreen(onBack = { showIslandSettings = false })
        return
    }

    // ★ 气泡通知设置：同样整页覆盖
    if (showBubbleSettings) {
        BubbleSettingsScreen(onBack = { showBubbleSettings = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "首页"
                        )
                    },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "管理"
                        )
                    },
                    label = { Text("管理") }
                )
                // 耳机降噪控制（由 Xposed 模块在欢律进程内执行实际切换）
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = "耳机"
                        )
                    },
                    label = { Text("耳机") }
                )
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    dao = dao,
                    onOpenMediaControl = onOpenMediaControl,
                    onLongPressTrip = { navigateToManageSubPage("trip_train") },
                    onLongPressFlight = { navigateToManageSubPage("trip_flight") },
                    onLongPressExpress = { navigateToManageSubPage("express") },
                    onOpenBudsControl = { selectedTab = 2 },
                    onOpenIslandSettings = { showIslandSettings = true },
                    onOpenBubbleSettings = { showBubbleSettings = true }
                )
                1 -> ManageScreen(
                    dao = dao,
                    storage = storage,
                    backupStorage = backupStorage,
                    subPage = manageSubPage,
                    onSubPageChange = { manageSubPage = it },
                    onReplayOnboarding = onReplayOnboarding,
                )
                2 -> BudsControlScreen()
            }
        }
    }
}