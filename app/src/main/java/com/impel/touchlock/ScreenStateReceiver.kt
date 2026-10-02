package com.impel.touchlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Registered *dynamically* by [BubbleService] and only while a sleep timer is running or black dim
 * is active, so the app holds no manifest receiver for screen state and does no work at all when
 * idle. `ACTION_SCREEN_OFF` cannot be declared in the manifest since Android 8.
 */
class ScreenStateReceiver(private val onScreenOff: () -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_SCREEN_OFF == intent.action) onScreenOff()
    }
}
