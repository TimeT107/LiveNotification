# 部署【合并后的 LiveChip APK（含锁屏岛 LSPosed 模块）】到真机（带重载校验）
#
# 用法（执行策略受限，需用 Invoke-Expression 运行）：
#   $s = Get-Content tools\deploy.ps1 -Raw -Encoding UTF8; Invoke-Expression $s
#
# ============ 来源与边界（重要）============
# 本脚本是 E:\WorkSpace\LockScreenIsland\tools\deploy.ps1 的**副本**，为合并后的
# 单一 APK（com.example.livenotification）改写：
#   - 包名：com.pixelos.lockscreenisland -> com.example.livenotification
#   - APK ：D:\MyAndroidProject\app\build\outputs\apk\release\app-release.apk
#   - 辅助脚本：D:\MyAndroidProject\tools\lspfix\setpath.py（副本，查表包名已改）
# ⚠ 锁屏岛原项目（E:\WorkSpace\LockScreenIsland）**作为独立软件分支继续更新**，
#   **永远不要从这里回改原项目**；两边要同步时，是"原项目 -> 本项目"单向复制。
#
# ⚠ 上机前必做（只能人在 LSPosed 管理器里点）：
#   1) 启用本模块（模块列表里的 LiveChip / com.example.livenotification）
#   2) 作用域勾选「系统界面 (com.android.systemui)」
#   3) **停用或卸载旧的 com.pixelos.lockscreenisland** —— 否则两个模块同时 hook
#      SystemUI，日志会互相覆盖，行为也不可预期。
#
# ============ 为什么必须有重载校验 ============
# 血的教训：有多次部署后我基于日志做判断，实际**模块根本没重载**，
# 日志还是旧代码写的 —— 导致连续多轮错误结论。
# 所以本脚本在重启 SystemUI 后，会核对日志文件是否由**新进程**写入，
# 不满足就直接报错退出，绝不让人误以为新代码在跑。

$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\WarmyTee\AppData\Local\Android\Sdk\platform-tools\adb.exe'
# 注意：原项目那份 deploy.ps1 里的 python 路径已失效（...\dependencies\python.exe 不存在），
# 这里用的是当前有效的 bundled python。
$py = 'C:\Users\WarmyTee\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$lspDir = '/data/adb/lspd/config'
$pkg = 'com.example.livenotification'
# 日志由 hook 写在 SystemUI 自己的 files 目录里，与模块包名无关 —— 路径不变
$logFile = '/data/user_de/0/com.android.systemui/files/lockscreenisland/lockscreenisland.log'
$apk = 'D:\MyAndroidProject\app\build\outputs\apk\release\app-release.apk'
$toolDir = 'D:\MyAndroidProject\tools\lspfix'

function Step($m) { Write-Output "=== $m ===" }

Step "0. 检查旧模块是否还在（会与本模块抢 hook）"
& $adb shell pm path $pkg > $null 2>&1
$old = ((& $adb shell pm path 'com.pixelos.lockscreenisland' 2>&1) -join '')
if ($old -match 'package:') {
    Write-Output "!! 旧的 com.pixelos.lockscreenisland 仍安装着。"
    Write-Output "!! 请在 LSPosed 里【停用】它（或 adb uninstall com.pixelos.lockscreenisland），再重启 SystemUI。"
}

Step "1. 安装 APK"
$out = & $adb install -r $apk 2>&1
$verdict = ($out | Select-String 'Success|Failure') -join ''
Write-Output "安装结果: $verdict"
if ($verdict -notmatch 'Success') {
    Write-Output "安装失败，终止。"
    $out | Select-Object -First 15
    exit 1
}
$newPath = ((& $adb shell pm path $pkg 2>&1) -replace 'package:', '').Trim()
Write-Output "新路径: $newPath"

Step "2. 停守护进程并取库"
& $adb shell su -c 'killall lspd 2>/dev/null; sleep 2' 2>&1 | Out-Null

$localDb = "$toolDir\deploy.db"
New-Item -ItemType Directory -Force -Path $toolDir | Out-Null
Remove-Item $localDb -Force -ErrorAction SilentlyContinue
& $adb shell su -c "cp -f $lspDir/modules_config.db /data/local/tmp/deploy.db; chmod 666 /data/local/tmp/deploy.db" 2>&1 | Out-Null
& $adb pull /data/local/tmp/deploy.db $localDb 2>&1 | Select-String 'pulled' | Out-Null

Step "3. 更新 apk_path（保留 user_version 与 enabled）"
$env:NEWP = $newPath
$env:PKG = $pkg
& $py "$toolDir\setpath.py" $localDb 2>&1

Step "4. 推回库"
$pushDb = "$toolDir\deploy_push.db"
Copy-Item $localDb $pushDb -Force
& $adb push $pushDb /data/local/tmp/deploy_push.db 2>&1 | Select-String 'pushed' | Out-Null
& $adb shell su -c "cp -f /data/local/tmp/deploy_push.db $lspDir/modules_config.db; echo db-ok" 2>&1 | Select-Object -First 1

Step "5. 拉起 lspd（必须在 SystemUI fork 之前就绪）"
# LSPosed 是在进程 fork 时注入的：若 SystemUI 在 lspd 未就绪时启动，就不会被注入。
& $adb shell su -c 'setsid /data/adb/modules/zygisk_lsposed/daemon >/dev/null 2>&1 &' 2>&1 | Out-Null
$lspdPid = ''
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Seconds 2
    $lspdPid = ((& $adb shell su -c 'pidof lspd' 2>&1) -join '').Trim()
    if ($lspdPid) { break }
}
if (-not $lspdPid) {
    Write-Output "!! lspd 未能启动，注入不会发生。终止。"
    exit 1
}
Write-Output "lspd pid: $lspdPid"

Step "6. 清空日志（用于校验本次运行）"
& $adb shell su -c "rm -f $logFile" 2>&1 | Out-Null

Step "7. 重启 SystemUI 并等待新进程"
$oldPid = ((& $adb shell pidof com.android.systemui 2>&1) -join '').Trim()
& $adb shell su -c 'killall com.android.systemui' 2>&1 | Out-Null
$newPid = ''
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Seconds 2
    $newPid = ((& $adb shell pidof com.android.systemui 2>&1) -join '').Trim()
    if ($newPid -and $newPid -ne $oldPid) { break }
}
Write-Output "SystemUI pid: $oldPid -> $newPid"

& $adb shell svc power stayon true 2>&1 | Out-Null
& $adb shell settings put system screen_off_timeout 600000 2>&1 | Out-Null
& $adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
Start-Sleep -Seconds 8

Step "8. 校验模块是否真的重载"
# 清空过日志，所以只要文件存在且非空，就说明新进程写入了 = 模块已注入。
$logText = ((& $adb shell su -c "cat $logFile 2>/dev/null" 2>&1) -join "`n")
if (-not $logText.Trim()) {
    Write-Output "!! 日志为空 —— 模块【没有】注入到新 SystemUI 进程。"
    Write-Output "!! 后续任何日志判断都无效。请检查 LSPosed 里本模块是否启用 + 作用域是否勾了系统界面。"
    exit 2
}
$firstLine = ($logText -split "`n")[0]
Write-Output "日志首行: $firstLine"
Write-Output "✓ 模块已重载（日志由新进程写入）"

Step "9. 关键日志"
$logText -split "`n" |
    Select-String '挂载自证|已注入|布局\(|约束|浮出|可见性 ->' |
    Select-Object -First 14

Write-Output ""
Write-Output "完成。设备时间: $((& $adb shell date '+%H:%M:%S' 2>&1) -join '')"
