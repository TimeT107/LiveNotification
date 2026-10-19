# 锁屏岛代码同步说明（单向：原项目 → 本项目）

> 建立时间：2026-10-15 · 最近一次同步：2026-10-19 · 执行人：DSH 合并会话
> 用户明确要求：**锁屏岛原项目 `E:\WorkSpace\LockScreenIsland` 作为独立软件分支继续更新，
> 本项目只准"读"它、不准"写"它。** 需要同步时永远是**单向复制**过来，绝不回改原项目。

## 本项目里岛的副本来源

| 项 | 值 |
|---|---|
| 来源仓库 | `E:\WorkSpace\LockScreenIsland` |
| 来源提交 | `8d5cdbb` "feat(immersive): 沉浸式（样式 0）改版 … v1.42.0" |
| 来源 versionName | `1.42.0-immersive-monet` |
| 复制时间 | 2026-10-19 11:20 (Asia/Shanghai) |
| 落位 | `app/src/main/java/com/example/livenotification/island/`（**31 个主源码**）<br>`app/src/test/java/com/example/livenotification/island/`（**6 个文件 / 65 项测试**） |

本次同步带来的新内容（相对上一版 v1.24.0）：输出设备胶囊 + 自绘图标、通知小岛、设置写快照
漏键修复、音乐锁屏退场塌缩、模糊驼峰化、沉浸式（样式 0）通栏封面 + 莫奈纯色底。

## ⚠️ 本项目相对原项目的「有意偏差」（同步时必须保留）

| # | 偏差 | 说明 |
|---|---|---|
| 1 | 包名 `com.example.livenotification.island.*` | 子包并入宿主 APK |
| 2 | `IslandConfig.MODULE_PACKAGE` = `com.example.livenotification`（**不带 .island**） | 它就是宿主 App 包名，用于 `setPackage()` 与 `Android/data|media/<pkg>/config.txt` |
| 3 | 5 个广播 action 改为 `com.example.livenotification.*` | 与清单里 NudgeReceiver 的 intent-filter 一致 |
| 4 | 岛的设置页**不再用** `island/SettingsActivity.kt`（已删除） | 改为宿主 Compose 页 `IslandSettingsScreen.kt`，清单里**没有** SettingsActivity；同步时**不要**把 `SettingsActivity.kt` 复制回来 |
| 5 | 岛的资源独立成文件：`res/values/island_strings.xml` / `island_themes.xml` / `island_arrays.xml`；`app_name` → `island_app_name` | 不覆盖宿主同名资源；岛的 `ic_launcher*/mipmap/colors.xml` 丢弃 |
| 6 | 设置入口：主页最下方卡片 → `MainNavigation` 整页覆盖 `IslandSettingsScreen` | 与主软件同一套 Material3 设计语言 / dynamic color |

## 重新同步的步骤

1. 确认原项目改动已提交（拿一个明确 commit 作为同步点）。
2. 复制主源码与测试：
   `LockScreenIsland/app/src/main/java/com/pixelos/lockscreenisland/**`
   → `app/src/main/java/com/example/livenotification/island/**`（子包结构不变）
   `LockScreenIsland/app/src/test/java/com/pixelos/lockscreenisland/**`
   → `app/src/test/java/com/example/livenotification/island/**`
3. 对新复制进来的文件做下面这 6 处**机械改写**（少一处就编译不过或运行时不生效）：

| # | 改写 | 原因 |
|---|---|---|
| 1 | `com.pixelos.lockscreenisland` → `com.example.livenotification.island` | 子包改名 |
| 2 | `MODULE_PACKAGE` 的值 → `com.example.livenotification`（不带 .island） | 跨进程广播 + 快照路径 |
| 3 | 5 个广播 action 串 → `com.example.livenotification.{CONFIG_CHANGED,STATUS_REPORT,NUDGE_CHANGED,EMERGENCY_DISABLE,CONFIG_SNAPSHOT}` | 与清单一致 |
| 4 | `PreviewActivity` 里硬编码的入口类 → `com.example.livenotification.island.hook.HookEntry` | 与 `assets/xposed_init` 一致 |
| 5 | 需要 `R` / `BuildConfig` 的文件补 `import com.example.livenotification.R` / `.BuildConfig` | 搬家后不再"同包可解析" |
| 6 | 岛的新字符串/主题/数组追加到 `island_*.xml`；字面量 `app_name` 改名 | 资源名全模块唯一 |

4. **不要**复制 `SettingsActivity.kt`（见偏差 #4）。若岛那边改了设置项（新增开关/滑块），
   同步到 `IslandSettingsScreen.kt` 里即可；`IslandSettingsHost` 里的
   `notifySystemUi / writeConfigSnapshot / resetToDefaults` 抄自岛侧，注意保持一致。
5. 编译 + 单测：`gradlew.bat :app:testDebugUnitTest :app:assembleRelease`
   岛的单测基线 **65 项**（ConfigSnapshot 14 / MusicExitMotion 12 / MusicScrimStops 10 /
   ContrastMath 10 / LayoutMath 9 / FrameLayoutGeometry 5 / ImmersivePalette 5），
   加宿主 1 项 = **66 项全绿**。数字变小说明有测试没同步过来。
6. 若岛新增配置键：原项目 AGENTS.md §4 要求同时更新 `ConfigSnapshot.ALL_KEYS` **和**
   `IslandConfig.SNAPSHOT_DEFAULTS` —— 对副本同样成立（v1.39.0 的真机事故）。
7. 若岛新增/改动了设置项文案：同步更新 `res/values/island_strings.xml`。

## 部署

用本项目自己那份 `tools/deploy.ps1`（已改包名/APK 路径/失效的 python 路径），
**不要**用原项目那份（它写的是 `com.pixelos.lockscreenisland`）。
上机前必须：LSPosed 里启用 `com.example.livenotification` + 作用域勾系统界面，
并**停用旧的 `com.pixelos.lockscreenisland`**，否则两个模块同时 hook SystemUI。
