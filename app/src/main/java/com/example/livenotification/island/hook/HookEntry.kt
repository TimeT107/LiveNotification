package com.example.livenotification.island.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口（见 assets/xposed_init）。
 *
 * 作用域只有一个：com.android.systemui
 * 只做一件事：把 [SystemUiHook] 装上去；所有异常都在内部吞掉并记日志，
 * 保证绝不会把 SystemUI 搞崩。
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XposedBridge.log("锁屏岛: zygote 初始化，modulePath=${startupParam?.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        if (pkg != "com.android.systemui") return

        XposedBridge.log("锁屏岛: 命中 SystemUI，processName=${lpparam.processName}")

        try {
            SystemUiHook(lpparam.classLoader, lpparam.processName).install()
        } catch (t: Throwable) {
            XposedBridge.log("锁屏岛: 安装 hook 失败 -> $t")
            XposedBridge.log(t)
        }
    }
}
