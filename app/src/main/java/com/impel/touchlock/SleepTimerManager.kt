package com.impel.touchlock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * Sleep timer with **zero** polling and two complementary triggers:
 *
 *  1. A single main-looper `postDelayed` — exact to the millisecond while the process is alive,
 *     which is the normal case because [BubbleService] is a foreground service. Costs nothing.
 *  2. A single `AlarmManager.setAndAllowWhileIdle` alarm — fires even if the process was killed or
 *     the device is dozing. `setAndAllowWhileIdle` (not `setExactAndAllowWhileIdle`) is used on
 *     purpose so the app needs no `SCHEDULE_EXACT_ALARM` permission; it is inexact by design
 *     (the system may defer it), which is exactly why it is only the backstop.
 *
 * Whichever fires first wins; [markFired] makes the path idempotent, so the screen is locked once.
 * The countdown UI is only refreshed while the menu is actually visible.
 */
class SleepTimerManager(context: Context) {

    private val appCtx = context.applicationContext
    private val alarm = appCtx.getSystemService(AlarmManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** Called whenever the timer is started, cancelled or fires. */
    var onChanged: (() -> Unit)? = null

    /** Called right after the timer fired (only while the process is alive). */
    var onFired: (() -> Unit)? = null

    private val localFire = Runnable { executeFire() }

    fun endAtElapsed(): Long = Prefs.timerEndAt(appCtx)

    fun isActive(): Boolean = endAtElapsed() > SystemClock.elapsedRealtime()

    fun remainingMs(): Long = (endAtElapsed() - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    fun remainingLabel(): String {
        val total = remainingMs() / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Wall-clock instant the timer ends, for the notification chronometer. */
    fun endAtWallClock(): Long = System.currentTimeMillis() + remainingMs()

    fun set(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 600)
        val delayMs = safeMinutes * 60_000L
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        Prefs.setTimerEndAt(appCtx, triggerAt)

        // Exact fallback #1: the live process.
        handler.removeCallbacks(localFire)
        handler.postDelayed(localFire, delayMs)

        // Backstop #2: survives process death and doze.
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent())
        } catch (_: Throwable) {
            try {
                alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent())
            } catch (_: Throwable) {
            }
        }
        onChanged?.invoke()
    }

    fun cancel() {
        handler.removeCallbacks(localFire)
        try {
            alarm.cancel(pendingIntent())
        } catch (_: Throwable) {
        }
        Prefs.setTimerEndAt(appCtx, 0L)
        onChanged?.invoke()
    }

    /** Entry point for [SleepTimerReceiver]; safe to call from a freshly created instance. */
    fun onAlarmFired() = executeFire()

    private fun executeFire() {
        if (!markFired()) return
        if (!Locker.lock(appCtx)) Notifier.lockFailed(appCtx)
    }

    /** @return true exactly once per timer, whoever got here first. */
    private fun markFired(): Boolean {
        if (Prefs.timerEndAt(appCtx) == 0L) return false
        Prefs.setTimerEndAt(appCtx, 0L)
        handler.removeCallbacks(localFire)
        try {
            alarm.cancel(pendingIntent())
        } catch (_: Throwable) {
        }
        onChanged?.invoke()
        onFired?.invoke()
        return true
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appCtx,
        REQUEST_CODE,
        Intent(appCtx, SleepTimerReceiver::class.java).setAction(SleepTimerReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val REQUEST_CODE = 4711

        /**
         * Set by [BubbleService] while it is alive so a firing timer can update the live UI.
         * Holds the application context only, and is cleared in `BubbleService.onDestroy`.
         */
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        var instance: SleepTimerManager? = null
    }
}
