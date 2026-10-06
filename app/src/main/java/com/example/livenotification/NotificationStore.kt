package com.example.livenotification

object NotificationStore {
    @Volatile
    var current: LiveData? = null
}