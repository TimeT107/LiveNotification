package com.example.livenotification.bubble

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.os.Build
import android.util.Log
import kotlin.math.roundToInt

/**
 * 气泡展开窗口的尺寸。
 *
 * 气泡窗口的**宽度由系统给**（约等于屏幕宽减边距），我们能调的只有高度
 * （BubbleMetadata.setDesiredHeight）。之前对谁都用 600dp，结果：
 *
 *  - 竖屏时窗口变成"窄而高"，横屏游戏塞进去只能上下大片留黑，画面挤在中间；
 *  - 横屏时窗口仍是 600dp 高，比例同样不对，游戏画面照样错位。
 *
 * 现在按目标应用的屏幕方向算：横屏应用用 16:9 的矮窗口（和游戏期望的比例一致），
 * 竖屏应用继续用 600dp。
 */
object BubbleGeometry {

    private const val TAG = "BubbleNotify"
    private const val PORTRAIT_HEIGHT_DP = 600
    private const val MIN_LANDSCAPE_HEIGHT_DP = 180

    private val LANDSCAPE = setOf(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
    )
    private val PORTRAIT = setOf(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
    )

    /** 气泡展开高度（dp）：横屏应用按当前屏幕宽度算 16:9，竖屏应用沿用 600 */
    fun desiredHeightDp(context: Context, pkg: String): Int {
        if (!isLandscapeApp(context, pkg)) return PORTRAIT_HEIGHT_DP
        val metrics = context.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = (widthDp * 9f / 16f).roundToInt().coerceAtLeast(MIN_LANDSCAPE_HEIGHT_DP)
        Log.i(TAG, "横屏气泡尺寸 pkg=" + pkg + " widthDp=" + widthDp + " heightDp=" + heightDp)
        return heightDp
    }

    /**
     * 当前显示是不是横屏。
     *
     * 不能用 `resources.configuration.orientation`：后台服务进程拿到的配置会滞后
     * （实测手机明明竖屏，服务里仍读到横屏，导致竖屏应用被误判成"横屏里打开的竖屏应用"
     * 而退化成面板）。直接问 DisplayManager 拿系统当前的显示旋转，实时且权威。
     */
    fun isDisplayLandscape(context: Context): Boolean {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return false
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        return display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
    }

    /**
     * 目标应用塞进当前方向的气泡窗口，会不会被拉变形？
     *
     * 气泡窗口的宽度≈屏幕宽（系统定死），高度可调但有上限：
     *  - 横屏应用 → 16:9 窗口，竖屏/横屏显示都合适；
     *  - 竖屏应用 + 竖屏显示 → 窗口窄高，合适；
     *  - 竖屏应用 + 横屏显示 → 窗口被拉成宽扁，竖屏应用会把布局横向撑开，内容错位。
     */
    fun appFitsBubbleWindow(context: Context, pkg: String): Boolean =
        isLandscapeApp(context, pkg) || !isDisplayLandscape(context)

    /** 目标应用的 launcher Activity 是不是横屏 */
    fun isLandscapeApp(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(pkg) ?: return false
        val component = launch.component ?: return false
        val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: return false
        if (info.screenOrientation in LANDSCAPE) return true
        if (info.screenOrientation in PORTRAIT) return false

        // sensor / unspecified 时用应用分类兜底：游戏基本都是横屏
        val app = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull() ?: return false
        return app.category == ApplicationInfo.CATEGORY_GAME
    }
}
