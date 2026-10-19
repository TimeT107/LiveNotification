# 气泡通知代码同步说明（单向：原工程 → 本项目）

> 建立时间：2026-10-19 · 执行人：DSH 合并会话
> 用户明确要求：**BubbleNotifyDemo 原工程只准读、不准写**（它在另一个会话里作为独立
> 软件继续开发）。需要同步时永远是**单向复制**过来。

## 来源快照

| 项 | 值 |
|---|---|
| 来源工程 | `C:\Users\WarmyTee\AndroidStudioProjects\BubbleNotifyDemo`（非 git 仓库） |
| 版本 | versionName **1.2** / versionCode 3 |
| 快照时间 | 2026-10-19 11:32（源工程 build.gradle.kts mtime 11:18） |
| 已出包对照 | `E:\WorkSpace\BubbleNotifyDemo-1.2-release.apk` |
| 落位 | `app/src/main/java/com/example/livenotification/bubble/` |

功能：把白名单应用（默认微信/QQ）的通知接成**系统原生气泡**（Android Bubbles），
支持会话历史聚合、真回复（RemoteInput）、拦截原通知、横幅去重、后台常驻、小窗打开应用。

## 复制了哪些、没复制哪些

**复制并改包名（7 个逻辑文件 → `com.example.livenotification.bubble`）**

`AppCompatMode.kt` · `BubbleGeometry.kt` · `BubbleSettings.kt` ·
`KeepAliveService.kt` · `NotifyListenerService.kt` · `RootHealer.kt` · `WindowBubble.kt`

**没有复制（被 Compose 版取代）**

| 原文件 | 处理 |
|---|---|
| `MainActivity.kt` + `res/layout/activity_main.xml` | 由 `BubbleSettingsScreen.kt`（宿主 Compose）取代 |
| `res/layout/activity_bubble.xml` | 由 `bubble/BubbleActivity.kt` 内的 Compose `BubbleChatScreen` 取代 |
| `res/values/themes.xml` / `values-night/` | 由 `res/values/bubble_themes.xml` 的 `Theme.BubbleNotify` 取代 |
| `res/values/colors.xml` | 只有 `black`/`white`，与宿主同名冲突且无用 → 丢弃 |
| `res/values/strings.xml` | 整体搬到 `res/values/bubble_strings.xml`，删掉与宿主冲突的 `app_name` |
| launcher 图标 / mipmap / backup 规则 | 宿主自带，丢弃 |

## 机械改写（同步时必须重做）

| # | 改写 | 原因 |
|---|---|---|
| 1 | 包名 `com.example.bubblenotifydemo` → `com.example.livenotification.bubble` | 子包并入 |
| 2 | `BubbleActivity.ACTION_BUBBLE_UPDATED` 字面量 → `com.example.livenotification.action.BUBBLE_UPDATED` | 应用内广播，两端都引用常量 |
| 3 | `R.drawable.ic_notification` → `R.drawable.bubble_ic_notification` | 宿主已有 `ic_notification`（同名不同图），避免覆盖 |
| 4 | `KeepAliveService` 里 `MainActivity::class.java` → `com.example.livenotification.MainActivity::class.java` | 常驻通知点击回到主软件主页 |
| 5 | `BubbleActivity` 改为 Compose（`ComponentActivity` + `LiveNotificationDemoTheme` + `BubbleChatScreen`） | UI 与主软件完全同步 |
| 6 | 设置页改为 Compose（`BubbleSettingsScreen.kt`，设计语言同 `SettingsScreen.kt`） | 同上 |

## 资源 / 清单（本项目侧新增，不在原工程里）

- `res/drawable/ic_bubble.xml`、`res/drawable/bubble_ic_notification.xml`
- `res/values/bubble_strings.xml`（原 strings 去掉 `app_name`）
- `res/values/bubble_themes.xml` → `Theme.BubbleNotify`（透明窗口 + Material3 DayNight）
- `AndroidManifest.xml`：
  - `<queries>`（ACTION_MAIN / CATEGORY_LAUNCHER）——否则白名单枚举不到应用
  - `.bubble.BubbleActivity`（allowEmbedded / resizeable / documentLaunchMode / exported）
  - `.bubble.KeepAliveService`（foregroundServiceType=specialUse，subtype=notification_bubble_proxy）
  - `.bubble.NotifyListenerService`（BIND_NOTIFICATION_LISTENER_SERVICE + listener intent-filter）
- 主页入口：`HomeScreen` 最下方"气泡通知"卡片 → `MainNavigation` 整页覆盖 `BubbleSettingsScreen`

## 重新同步步骤

1. 确认原工程改完（记录 versionName 与时间作为同步点）。
2. 复制上面那 7 个逻辑文件到 `bubble/`，重做改写表 1~4。
3. 若原工程改了 **设置项**：同步到 `BubbleSettingsScreen.kt`（开关/按钮/状态文案）。
4. 若原工程改了 **气泡面板**：同步到 `bubble/BubbleActivity.kt` 的 `BubbleChatScreen`。
5. 若原工程新增字符串/图标：追加到 `bubble_strings.xml` / `bubble_ic_*.xml`（注意宿主同名资源）。
6. 编译 + 单测：`gradlew.bat :app:testDebugUnitTest :app:assembleRelease`
   （宿主单测基线：岛 65 项 + 宿主 1 项 = 66 项全绿；气泡模块本身无单测）

## 上机前置

- 系统设置里给本应用开启「通知访问权限」（设置 → 应用 → 特殊应用权限 → 通知访问权限）
- 系统里允许本应用的气泡（设置 → 通知 → 本应用 → 允许气泡）；很多 ROM 默认关闭
- 想让"小窗打开/横屏应用 letterbox"生效需要 root（用 `am compat`，见 `AppCompatMode.kt`）
