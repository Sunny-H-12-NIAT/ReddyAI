package com.reddysai.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class ReddysAIApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Overlay service channel — persistent, low priority so it doesn't clutter
        NotificationChannel(
            CHANNEL_OVERLAY,
            "Reddy's AI Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Reddy's AI overlay running in background"
            setShowBadge(false)
            manager.createNotificationChannel(this)
        }

        // Alert channel — high priority for payment safety confirmations
        NotificationChannel(
            CHANNEL_ALERT,
            "Payment Safety Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical alerts before any payment action"
            manager.createNotificationChannel(this)
        }
    }

    companion object {
        const val CHANNEL_OVERLAY = "reddy_overlay"
        const val CHANNEL_ALERT = "reddy_alert"

        lateinit var instance: ReddysAIApplication
            private set
    }
}
