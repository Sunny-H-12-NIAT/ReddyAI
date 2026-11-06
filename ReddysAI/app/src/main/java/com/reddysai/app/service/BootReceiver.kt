package com.reddysai.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Receives BOOT_COMPLETED and restarts the overlay service automatically.
 *
 * Note for MIUI/HyperOS users: The Autostart permission MUST be granted
 * manually in Security app > Autostart > Reddy's AI, or this receiver
 * will be silently killed by the system. The setup screen reminds you.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
        if (intent.action !in validActions) return

        if (!Settings.canDrawOverlays(context)) {
            Log.w("BootReceiver", "Overlay permission not granted — skipping auto-start")
            return
        }

        Log.i("BootReceiver", "Boot received — starting OverlayService")
        val serviceIntent = Intent(context, OverlayService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
