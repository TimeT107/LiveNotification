package com.example.livenotification

import android.graphics.Bitmap
import android.media.session.MediaSession

data class MusicData(
    val title: String,
    val artist: String,
    val albumArt: Bitmap?,
    val packageName: String,
    val isPlaying: Boolean,
    val sessionToken: MediaSession.Token? = null,
    val lastPauseTime: Long = 0L
)