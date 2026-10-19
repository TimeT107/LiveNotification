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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The earbuds card drawn as a system overlay.
 *
 * A ComposeView is hosted in a TYPE_APPLICATION_OVERLAY window, following the pattern
 * already used by MediaControlOverlay. The noise-control row talks to the earbuds over
 * the independent RFCOMM link, so the card is useful without opening any app.
 */
class EarbudsCard {

    private companion object {
        const val TAG = "EarbudsCard"
        const val AUTO_DISMISS_MS = 20000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)

    private var window: WindowManager? = null
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private val dismissTask = Runnable { dismiss() }

    /** Compose state for the mode shown by the open card; driven by [notifyModeChanged]. */
    private val modeState = mutableIntStateOf(BudsClient.currentMode)

    @Volatile
    var lastStatus: String = ""
        private set

    /** Called when the mode changes anywhere, so an open card updates without re-showing. */
    fun notifyModeChanged(mode: Int) {
        modeState.intValue = mode
    }

    fun show(context: Context, deviceName: String) {
        dismiss()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val lifecycleOwner = OverlayLifecycleOwner()
            val compose = ComposeView(context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    // Monet: dynamic colour from the wallpaper, falling back to the app
                    // theme on devices without it.
                    MaterialTheme(colorScheme = monetScheme(context)) {
                        Surface(color = Color.Transparent) {
                            // Slide up from the bottom edge when it appears.
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = remember { MutableTransitionState(false) }
                                    .apply { targetState = true },
                                enter = slideInVertically(
                                    initialOffsetY = { it },
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                ),
                            ) {
                                EarbudsCardContent(
                                    deviceName = deviceName,
                                    // Seeded from the tracked mode, then kept live by
                                    // notifyModeChanged so an on-device switch is reflected
                                    // without the card being shown again.
                                    initialMode = modeState.intValue,
                                    externalMode = modeState,
                                    onDismiss = { dismiss() },
                                    onMode = { mode ->
                                        scope.launch {
                                            val r = if (BudsClient.isConnected) {
                                                BudsClient.setModeFast(mode)
                                            } else {
                                                withContextIo { BudsClient.connect() }
                                                    .let {
                                                        if (BudsClient.isConnected)
                                                            BudsClient.setModeFast(mode) else it
                                                    }
                                            }
                                            lastStatus = r
                                            Log.d(TAG, "mode $mode -> $r")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Rise from the bottom edge, like the system's own device cards.
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(context, 12)
            }

            wm.addView(compose, params)
            window = wm
            view = compose
            owner = lifecycleOwner
            Log.d(TAG, "卡片已显示: $deviceName")

            handler.removeCallbacks(dismissTask)
            handler.postDelayed(dismissTask, AUTO_DISMISS_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "显示卡片失败: ${t.message}")
            dismiss()
        }
    }

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

    private suspend fun <T> withContextIo(block: () -> T): T =
        withContext(Dispatchers.IO) { block() }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()

/**
 * Monet dynamic colour: the palette is derived from the user's wallpaper, so the card
 * matches the system rather than carrying its own brand colours.
 */
@Composable
private fun monetScheme(context: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    } else {
        if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    }

/**
 * Fast-Pair-style card.
 *
 * Mirrors the structure of the system Fast Pair card: large title, leading product
 * artwork, a secondary line, and one prominent full-width action. The three noise modes
 * share that action row so the card is usable without opening the app.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EarbudsCardContent(
    deviceName: String,
    initialMode: Int,
    externalMode: MutableIntState,
    onDismiss: () -> Unit,
    onMode: (Int) -> Unit,
) {
    // Local selection drives the immediate visual feedback; externalMode carries changes
    // made on the earbuds themselves.
    var selected by remember { mutableIntStateOf(initialMode) }

    androidx.compose.runtime.LaunchedEffect(externalMode.intValue) {
        if (externalMode.intValue >= 0) selected = externalMode.intValue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            // Light container, like the system card, so it reads as a distinct sheet
            // rather than blending into whatever is behind it.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.padding(24.dp)) {

            // Leading artwork + title, the way Fast Pair lays it out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "已连接",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // One full-width action, as in the system card. The selected mode is the
            // filled one; the rest stay tonal so the state is obvious at a glance.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val modes = listOf(
                    BudsClient.MODE_ANC to "降噪",
                    BudsClient.MODE_PASS to "通透",
                    BudsClient.MODE_OFF to "关闭",
                )
                modes.forEach { (mode, label) ->
                    val isOn = selected == mode
                    Button(
                        onClick = {
                            selected = mode
                            onMode(mode)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = if (isOn) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
