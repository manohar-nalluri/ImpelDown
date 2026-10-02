package com.impel.touchlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Optional "start on boot". Only starts the foreground service when the user already granted the
 * overlay permission and left the feature enabled, so a reboot never produces a silent failure.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        if (!Prefs.startOnBoot(context) || !Prefs.bubbleEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        BubbleService.start(context)
    }
}
