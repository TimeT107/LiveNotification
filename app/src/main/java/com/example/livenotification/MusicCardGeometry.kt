package com.example.livenotification

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 记录首页音乐卡片在屏幕上的几何信息
 * - 由 HomeScreen 写入
 * - 由 MediaControlOverlay 读取，作为展开动画的起点
 */
object MusicCardGeometry {

    /** 卡片左上角在屏幕上的坐标 */
    var topLeft: Offset by mutableStateOf(Offset.Zero)

    /** 卡片宽度 */
    var width: Dp by mutableStateOf(0.dp)

    /** 卡片高度 */
    var height: Dp by mutableStateOf(0.dp)

    /** 是否已经有有效数据（避免首次打开时用 0 值） */
    val isValid: Boolean
        get() = width.value > 0f && height.value > 0f
}