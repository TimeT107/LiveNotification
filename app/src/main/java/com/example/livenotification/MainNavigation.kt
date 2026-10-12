package com.example.livenotification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
    onOpenMediaControl: () -> Unit
) {

    var selectedTab by remember { mutableIntStateOf(0) }

    // ★ 管理页的子页面状态，提升到这里，供首页长按跳转
    var manageSubPage by remember { mutableStateOf<String?>(null) }

    // ★ 从首页长按跳转到管理页的某个子页面
    fun navigateToManageSubPage(page: String) {
        manageSubPage = page
        selectedTab = 1
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
                    onLongPressExpress = { navigateToManageSubPage("express") }
                )
                1 -> ManageScreen(
                    dao = dao,
                    storage = storage,
                    backupStorage = backupStorage,
                    subPage = manageSubPage,
                    onSubPageChange = { manageSubPage = it }
                )
            }
        }
    }
}