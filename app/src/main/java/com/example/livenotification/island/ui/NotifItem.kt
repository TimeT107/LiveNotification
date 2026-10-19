package com.example.livenotification.island.ui

import android.graphics.drawable.Drawable

/**
 * 音乐锁屏"通知小岛"里的一条通知。
 *
 * ============ 为什么是纯数据 ============
 *
 * 数据由 `IslandController` 在 SystemUI 进程里**反射**从通知行上取
 * （`ExpandableNotificationRow.getEntry() → entry.getSbn() → StatusBarNotification`）——
 * 模块**不能** `is/as` SystemUI 的类（会 ClassCastException，见 AGENTS §3.1）。
 * 而 `StatusBarNotification` / `Notification` 是**框架类**（boot classpath，
 * 与 SystemUI 共享同一个 Class），所以那两层可以安全地直接转型。
 *
 * UI 侧只认这个数据类：图标 + 标题，一行显示。
 */
class NotifItem(
    /** 通知的小图标（已 loadDrawable）。取不到时为 null，UI 用兜底音符图标 */
    val icon: Drawable?,
    /** 通知标题（取不到时用应用名兜底） */
    val title: String
)
