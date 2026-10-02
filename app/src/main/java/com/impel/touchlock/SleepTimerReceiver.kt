package com.impel.touchlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backstop for the sleep timer: fires from [android.app.AlarmManager] when the process had been
 * killed (or when the device was dozing and the in-process Handler could not run in time).
 * The system owns the alarm, so no "keep the process alive" trick is used anywhere.
 */
class SleepTimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        // Reuses the live manager when the service is running, otherwise a throwaway instance that
        // reconstructs everything it needs from SharedPreferences.
        val manager = SleepTimerManager.instance
            ?: SleepTimerManager(context.applicationContext)
        manager.onAlarmFired()
    }

    companion object {
        const val ACTION_FIRE = "com.impel.touchlock.action.TIMER_FIRE"
    }
}
