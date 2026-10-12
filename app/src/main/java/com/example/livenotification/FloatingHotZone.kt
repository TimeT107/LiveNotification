package com.example.livenotification

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner


object FloatingHotZone {

    // ============ 热区 ============
    private var hotZoneView: View? = null

    private const val HOT_ZONE_HEIGHT_DP = 16
    private const val HOT_ZONE_WIDTH_DP = 140
    private const val HOT_ZONE_X_OFFSET_DP = 60

    // ============ 媒体弹出层 ============
    private var popupContainer: FrameLayout? = null
    private var popupMask: View? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var popupAnimator: ValueAnimator? = null
    private var popupLifecycleOwner: OverlayLifecycleOwner? = null
    private var isPopupShowing = false
    private var isClosing = false

    private const val POPUP_SIDE_MARGIN_DP = 16
    private const val POPUP_HEIGHT_DP = 260
    private const val ANIM_DURATION = 280L

    // ============ 音频面板 ============
    private var audioPanelContainer: FrameLayout? = null
    private var audioPanelMask: View? = null
    private var audioPanelParams: WindowManager.LayoutParams? = null
    private var audioPanelAnimator: ValueAnimator? = null
    private var audioPanelLifecycleOwner: OverlayLifecycleOwner? = null
    private var isAudioPanelShowing = false

    private const val AUDIO_PANEL_HEIGHT_DP = 480
    private const val AUDIO_PANEL_ANIM_DURATION = 300L

    // ============ 手电筒亮度面板 ============
    private var flashPanelContainer: FrameLayout? = null
    private var flashPanelMask: View? = null
    private var flashPanelParams: WindowManager.LayoutParams? = null
    private var flashPanelAnimator: ValueAnimator? = null
    private var flashPanelLifecycleOwner: OverlayLifecycleOwner? = null
    private var isFlashPanelShowing = false

    private const val FLASH_PANEL_HEIGHT_DP = 320
    private const val FLASH_PANEL_ANIM_DURATION = 300L

    // ==================== 热区控制 ====================

    val isShowing: Boolean
        get() = hotZoneView != null

    fun show(context: Context) {
        if (hotZoneView != null) return

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                    as? WindowManager ?: return

            val view = View(context).apply {
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    if (!isPopupShowing && !isClosing) {
                        showPopup(context)
                    }
                }
            }

            val statusBarHeight = getStatusBarHeight(context)

            val params = WindowManager.LayoutParams(
                dpToPx(context, HOT_ZONE_WIDTH_DP),
                dpToPx(context, HOT_ZONE_HEIGHT_DP),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(context, HOT_ZONE_X_OFFSET_DP)
                y = statusBarHeight
            }

            windowManager.addView(view, params)
            hotZoneView = view

        } catch (e: Exception) {
            hotZoneView = null
        }
    }

    fun hide(context: Context) {
        hideFlashlightPanel(context)
        hideAudioPanel(context)
        hidePopup(context, immediate = true)

        val view = hotZoneView ?: return
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                    as? WindowManager
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        hotZoneView = null
    }

    // ==================== 媒体弹出层 ====================

    fun showPopup(context: Context) {
        if (isPopupShowing || isClosing) return
        if (!canDrawOverlays(context)) return

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                    as? WindowManager ?: return

            val statusBarHeight = getStatusBarHeight(context)
            val screenWidth = context.resources.displayMetrics.widthPixels
            val popupWidth = screenWidth - dpToPx(context, POPUP_SIDE_MARGIN_DP * 2)
            val popupHeight = dpToPx(context, POPUP_HEIGHT_DP)

            // ========== 1. 全屏透明点击层 ==========
            val mask = View(context).apply {
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    hidePopup(context, immediate = false)
                }
            }

            val maskParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            // ========== 2. ComposeView 内容 ==========
            val lifecycleOwner = OverlayLifecycleOwner()

            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                setContent {
                    // ★ 动态莫奈取色
                    val isNight = isSystemInDarkTheme()
                    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (isNight) dynamicDarkColorScheme(context)
                        else dynamicLightColorScheme(context)
                    } else {
                        if (isNight) darkColorScheme()
                        else lightColorScheme()
                    }

                    MaterialTheme(colorScheme = scheme) {
                        MediaPopupContent(
                            onDismiss = { hidePopup(context, immediate = false) },
                            onOpenFullControl = {
                                hidePopup(context, immediate = true)
                                try {
                                    val intent = android.content.Intent(
                                        context, MainActivity::class.java
                                    ).apply {
                                        addFlags(
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        )
                                        putExtra(
                                            MainActivity.EXTRA_OPEN_MEDIA_CONTROL,
                                            true
                                        )
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                }
                            },
                            onOpenAudioPanel = {
                                showAudioPanel(context)
                            }
                        )
                    }
                }
            }

            // ========== 3. 容器 ==========
            val container = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true

                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        popupHeight
                    )
                )
            }

            // 初始位置：完全在容器上方
            container.translationY = -popupHeight.toFloat()

            // ========== 4. 窗口参数 ==========
            val popupParams = WindowManager.LayoutParams(
                popupWidth,
                popupHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dpToPx(context, POPUP_SIDE_MARGIN_DP)
                y = statusBarHeight + dpToPx(context, 4)
            }

            windowManager.addView(mask, maskParams)
            windowManager.addView(container, popupParams)

            popupMask = mask
            popupContainer = container
            this.popupParams = popupParams
            popupLifecycleOwner = lifecycleOwner
            isPopupShowing = true

            // ========== 5. 滑入动画 ==========
            animateTranslation(
                container = container,
                from = -popupHeight.toFloat(),
                to = 0f,
                onEnd = { }
            )

        } catch (_: Exception) {
            hidePopup(context, immediate = true)
        }
    }

    fun hidePopup(context: Context, immediate: Boolean) {
        if (!isPopupShowing) return
        if (isClosing) return

        val container = popupContainer

        if (immediate || container == null) {
            cleanUpPopup(context)
            return
        }

        isClosing = true

        val popupHeight = dpToPx(context, POPUP_HEIGHT_DP)

        animateTranslation(
            container = container,
            from = container.translationY,
            to = -popupHeight.toFloat(),
            onEnd = {
                cleanUpPopup(context)
            }
        )
    }

    private fun animateTranslation(
        container: FrameLayout,
        from: Float,
        to: Float,
        onEnd: () -> Unit
    ) {
        popupAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                container.translationY = a.animatedValue as Float
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
        popupAnimator = animator
    }

    private fun cleanUpPopup(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                as? WindowManager

        try {
            popupAnimator?.cancel()
            popupAnimator = null

            popupContainer?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            popupMask?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }

            popupLifecycleOwner?.destroy()
        } catch (_: Exception) {
        }

        popupContainer = null
        popupMask = null
        popupParams = null
        popupLifecycleOwner = null
        isPopupShowing = false
        isClosing = false
    }

    // ==================== 音频面板 ====================

    fun showAudioPanel(context: Context) {
        if (isAudioPanelShowing) return
        if (!canDrawOverlays(context)) return

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                    as? WindowManager ?: return

            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenWidth = context.resources.displayMetrics.widthPixels
            val panelHeight = dpToPx(context, AUDIO_PANEL_HEIGHT_DP)
                .coerceAtMost((screenHeight * 0.8f).toInt())

            // 遮罩（点击关闭）
            val mask = View(context).apply {
                setBackgroundColor(0x66000000)
                setOnClickListener {
                    hideAudioPanel(context)
                }
            }

            val maskParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val lifecycleOwner = OverlayLifecycleOwner()

            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                setContent {
                    // ★ 动态莫奈取色
                    val isNight = isSystemInDarkTheme()
                    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (isNight) dynamicDarkColorScheme(context)
                        else dynamicLightColorScheme(context)
                    } else {
                        if (isNight) darkColorScheme()
                        else lightColorScheme()
                    }

                    MaterialTheme(colorScheme = scheme) {
                        AudioOutputPanel(
                            onDismiss = { hideAudioPanel(context) }
                        )
                    }
                }
            }

            val container = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true

                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        panelHeight
                    )
                )
            }

            // 初始位移：完全在屏幕下方
            container.translationY = panelHeight.toFloat()

            val panelParams = WindowManager.LayoutParams(
                screenWidth,
                panelHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(mask, maskParams)
            windowManager.addView(container, panelParams)

            audioPanelMask = mask
            audioPanelContainer = container
            audioPanelParams = panelParams
            audioPanelLifecycleOwner = lifecycleOwner
            isAudioPanelShowing = true

            animateAudioPanel(
                container = container,
                from = panelHeight.toFloat(),
                to = 0f,
                onEnd = { }
            )

        } catch (_: Exception) {
            hideAudioPanel(context)
        }
    }

    fun hideAudioPanel(context: Context) {
        if (!isAudioPanelShowing) return

        val container = audioPanelContainer
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                as? WindowManager

        if (container == null || windowManager == null) {
            cleanUpAudioPanel(context)
            return
        }

        val screenHeight = context.resources.displayMetrics.heightPixels
        val panelHeight = dpToPx(context, AUDIO_PANEL_HEIGHT_DP)
            .coerceAtMost((screenHeight * 0.8f).toInt())

        animateAudioPanel(
            container = container,
            from = container.translationY,
            to = panelHeight.toFloat(),
            onEnd = {
                cleanUpAudioPanel(context)
            }
        )
    }

    private fun animateAudioPanel(
        container: FrameLayout,
        from: Float,
        to: Float,
        onEnd: () -> Unit
    ) {
        audioPanelAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = AUDIO_PANEL_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                container.translationY = a.animatedValue as Float
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
        audioPanelAnimator = animator
    }

    private fun cleanUpAudioPanel(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                as? WindowManager

        try {
            audioPanelAnimator?.cancel()
            audioPanelAnimator = null

            audioPanelContainer?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            audioPanelMask?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }

            audioPanelLifecycleOwner?.destroy()
        } catch (_: Exception) {
        }

        audioPanelContainer = null
        audioPanelMask = null
        audioPanelParams = null
        audioPanelLifecycleOwner = null
        isAudioPanelShowing = false
    }

    // ==================== 手电筒亮度面板 ====================

    fun showFlashlightPanel(context: Context) {
        if (isFlashPanelShowing) return
        if (!canDrawOverlays(context)) return

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                    as? WindowManager ?: return

            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenWidth = context.resources.displayMetrics.widthPixels
            val panelHeight = dpToPx(context, FLASH_PANEL_HEIGHT_DP)
                .coerceAtMost((screenHeight * 0.8f).toInt())

            // 遮罩（点击关闭）
            val mask = View(context).apply {
                setBackgroundColor(0x66000000)
                setOnClickListener {
                    hideFlashlightPanel(context)
                }
            }

            val maskParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val lifecycleOwner = OverlayLifecycleOwner()

            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindow
                )
                setContent {
                    // ★ 动态莫奈取色
                    val isNight = isSystemInDarkTheme()
                    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (isNight) dynamicDarkColorScheme(context)
                        else dynamicLightColorScheme(context)
                    } else {
                        if (isNight) darkColorScheme()
                        else lightColorScheme()
                    }

                    MaterialTheme(colorScheme = scheme) {
                        FlashlightBrightnessPanel(
                            onDismiss = { hideFlashlightPanel(context) }
                        )
                    }
                }
            }

            val container = FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true

                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        panelHeight
                    )
                )
            }

            // 初始位移：完全在屏幕下方
            container.translationY = panelHeight.toFloat()

            val panelParams = WindowManager.LayoutParams(
                screenWidth,
                panelHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = 0
            }

            windowManager.addView(mask, maskParams)
            windowManager.addView(container, panelParams)

            flashPanelMask = mask
            flashPanelContainer = container
            flashPanelParams = panelParams
            flashPanelLifecycleOwner = lifecycleOwner
            isFlashPanelShowing = true

            animateFlashPanel(
                container = container,
                from = panelHeight.toFloat(),
                to = 0f,
                onEnd = { }
            )

        } catch (_: Exception) {
            hideFlashlightPanel(context)
        }
    }

    fun hideFlashlightPanel(context: Context) {
        if (!isFlashPanelShowing) return

        val container = flashPanelContainer
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                as? WindowManager

        if (container == null || windowManager == null) {
            cleanUpFlashPanel(context)
            return
        }

        val screenHeight = context.resources.displayMetrics.heightPixels
        val panelHeight = dpToPx(context, FLASH_PANEL_HEIGHT_DP)
            .coerceAtMost((screenHeight * 0.8f).toInt())

        animateFlashPanel(
            container = container,
            from = container.translationY,
            to = panelHeight.toFloat(),
            onEnd = {
                cleanUpFlashPanel(context)
            }
        )
    }

    private fun animateFlashPanel(
        container: FrameLayout,
        from: Float,
        to: Float,
        onEnd: () -> Unit
    ) {
        flashPanelAnimator?.cancel()

        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = FLASH_PANEL_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                container.translationY = a.animatedValue as Float
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
        flashPanelAnimator = animator
    }

    private fun cleanUpFlashPanel(context: Context) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE)
                as? WindowManager

        try {
            flashPanelAnimator?.cancel()
            flashPanelAnimator = null

            flashPanelContainer?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }
            flashPanelMask?.let {
                try { windowManager?.removeView(it) } catch (_: Exception) {}
            }

            flashPanelLifecycleOwner?.destroy()
        } catch (_: Exception) {
        }

        flashPanelContainer = null
        flashPanelMask = null
        flashPanelParams = null
        flashPanelLifecycleOwner = null
        isFlashPanelShowing = false
    }

    // ==================== 工具 ====================

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            dpToPx(context, 24)
        }
    }
}