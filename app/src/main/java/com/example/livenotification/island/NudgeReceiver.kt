package com.example.livenotification.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.livenotification.island.config.ConfigStore
import com.example.livenotification.island.config.IslandConfig

/**
 * 接收 SystemUI 里「长按拖动 / 双指缩放」产生的布局参数并落盘。
 *
 * 为什么需要单独一个常驻接收器：
 * SystemUI 进程和模块进程 UID 不同，SystemUI 写不了模块的 SharedPreferences，
 * 所以拖动结果通过广播发回来，由模块进程写盘。
 * 这个接收器必须在清单里声明（不能只在 Activity 里动态注册），
 * 否则用户没打开设置界面时拖动就丢了。
 *
 * 两个 extra 都是可选的，按存在的 key 分别更新 —— 这样拖动和缩放
 * 可以复用同一个广播，互不干扰。
 */
class NudgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            IslandConfig.ACTION_NUDGE_CHANGED -> {
                if (intent.hasExtra(IslandConfig.EXTRA_VERTICAL_BIAS)) {
                    ConfigStore.putInt(
                        ctx,
                        IslandConfig.KEY_VERTICAL_BIAS,
                        IslandConfig.clampPercent(
                            intent.getIntExtra(
                                IslandConfig.EXTRA_VERTICAL_BIAS,
                                IslandConfig.DEF_VERTICAL_BIAS
                            )
                        )
                    )
                }
                if (intent.hasExtra(IslandConfig.EXTRA_WIDTH_PERCENT)) {
                    ConfigStore.putInt(
                        ctx,
                        IslandConfig.KEY_WIDTH_PERCENT,
                        IslandConfig.clampPercent(
                            intent.getIntExtra(
                                IslandConfig.EXTRA_WIDTH_PERCENT,
                                IslandConfig.DEF_WIDTH_PERCENT
                            )
                        )
                    )
                }
            }

            /**
             * 熔断紧急停用：把总开关关掉。
             *
             * 这是用户在锁屏卡死边缘的唯一退路 —— 必须落盘，
             * 否则下次 SystemUI 重启还会挂载、还会卡死。
             */
            IslandConfig.ACTION_EMERGENCY_DISABLE -> {
                ConfigStore.putBoolean(ctx, IslandConfig.KEY_ENABLED, false)
                ConfigStore.putBoolean(ctx, IslandConfig.KEY_DEBUG_ALWAYS_SHOW, false)
            }
        }
    }
}
