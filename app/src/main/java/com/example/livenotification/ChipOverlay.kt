package com.example.livenotification

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Small status chip in the top-left corner, drawn as an overlay.
 *
 * Deliberately not the system's Live Update chip: that mechanism *is* a notification
 * (`setShortCriticalText` on a promoted ongoing notification), so it cannot show a status
 * without also posting one. An overlay gives the same look with no notification at all.
 *
 * Shared by the earbuds mode indicator and the flashlight indicator so both look and behave
 * identically.
 */
object ChipOverlay {

    private const val TAG = "ChipOverlay"
    private const val VISIBLE_MS = 2500L

    private val handler = Handler(Looper.getMainLooper())
    private var window: WindowManager? = null
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private val dismissTask = Runnable { dismiss() }

    /** Replaces any visible chip, so two rapid changes do not stack. */
    fun show(context: Context, icon: ImageVector, label: String) {
        dismiss()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = OverlayLifecycleOwner()
            val compose = ComposeView(context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    MaterialTheme(colorScheme = chipScheme(context)) {
                        Surface(color = Color.Transparent) {
                            ChipContent(icon = icon, label = label)
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(context, 12)
                y = dp(context, 12)
            }

            wm.addView(compose, params)
            window = wm
            view = compose
            owner = lifecycleOwner
            Log.d(TAG, "chip 显示: $label")

            handler.removeCallbacks(dismissTask)
            handler.postDelayed(dismissTask, VISIBLE_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "显示 chip 失败: ${t.message}")
            dismiss()
        }
    }

    /** Convenience for the earbuds mode indicator. */
    fun showBudsMode(context: Context, mode: Int) =
        show(context, Icons.Filled.Headphones, BudsClient.modeName(mode))

    fun dismiss() {
        handler.removeCallbacks(dismissTask)
        val v = view ?: return
        view = null
        try {
            window?.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        owner?.destroy()
        owner = null
        window = null
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

@Composable
private fun chipScheme(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    } else {
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    }

@Composable
private fun ChipContent(icon: ImageVector, label: String) {
    // Slide down from above the top edge, matching the card's motion language.
    AnimatedVisibility(
        visibleState = MutableTransitionState(false).apply { targetState = true },
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
