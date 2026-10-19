package com.example.livenotification

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat


class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_MEDIA_CONTROL = "openMediaControl"
    }

    // Compose 外部状态：是否打开媒体控件覆盖层
    private val openMediaControl = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ExpressStore.init(this)

        requestPermissions(
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            ),
            100
        )

        LiveNotificationManager(this).createChannel()
        // ★ 请求悬浮窗权限
        if (!FloatingHotZone.canDrawOverlays(this)) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
            }
        }

        try {
            startForegroundService(Intent(this, LiveService::class.java))
        } catch (_: Exception) { }

        // ★ 冷启动：如果是从通知点击启动的，自动打开覆盖层
        if (intent?.getBooleanExtra(EXTRA_OPEN_MEDIA_CONTROL, false) == true) {
            openMediaControl.value = true
            intent.removeExtra(EXTRA_OPEN_MEDIA_CONTROL)
        }

        val database = Room.databaseBuilder(
            applicationContext,
            LiveDatabase::class.java,
            "live_history"
        )
            .addMigrations(LiveDatabase.MIGRATION_1_2)
            .build()

        val dao = database.dao()
        val storage = LiveNotificationStorage(this)
        val backupStorage = BackupStorage(this)

        setContent {
            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()

            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme()
                else lightColorScheme()
            }

            // ★ 状态栏 / 导航栏图标颜色跟随主题
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
            }

            MaterialTheme(colorScheme = colorScheme) {

                Box(modifier = Modifier.fillMaxSize()) {

                    // First run shows the guide before anything else.
                    var showOnboarding by remember {
                        mutableStateOf(!OnboardingPreference.isDone(context))
                    }

                    if (showOnboarding) {
                        OnboardingScreen(onFinish = { showOnboarding = false })
                    } else {
                        MainNavigation(
                            dao = dao,
                            storage = storage,
                            backupStorage = backupStorage,
                            onOpenMediaControl = { openMediaControl.value = true },
                            onReplayOnboarding = { showOnboarding = true },
                        )

                        if (openMediaControl.value) {
                            MediaControlOverlay(
                                onDismiss = { openMediaControl.value = false }
                            )
                        }
                    }
                }
            }
        }
    }

    // ★ 热启动：App 已在前台/后台时点通知，走这里
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_MEDIA_CONTROL, false) == true) {
            openMediaControl.value = true
            intent.removeExtra(EXTRA_OPEN_MEDIA_CONTROL)
        }
    }
}