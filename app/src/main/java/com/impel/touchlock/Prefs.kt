package com.impel.touchlock

import android.content.Context
import android.content.SharedPreferences

/**
 * Every piece of persistent state lives in this one tiny SharedPreferences file.
 *
 * Writes only ever happen on a user-visible event (drag release, timer change, setting change),
 * so the app never touches disk while it is idle. The service observes changes through
 * [SharedPreferences.OnSharedPreferenceChangeListener] instead of polling.
 */
object Prefs {

    private const val FILE = "touch_lock"

    // Bubble geometry / look.
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"
    const val BUBBLE_SIZE_DP = "bubble_size_dp"
    const val BUBBLE_ALPHA = "bubble_alpha"
    const val IDLE_FADE = "idle_fade"
    const val IDLE_ALPHA = "idle_alpha"
    const val IDLE_FADE_MS = "idle_fade_ms"

    // Behaviour.
    const val BUBBLE_ENABLED = "bubble_enabled"
    const val START_ON_BOOT = "start_on_boot"
    const val LOCK_METHOD = "lock_method"
    const val DEFAULT_TIMER_MIN = "default_timer_min"
    const val DIM_ZERO_BRIGHTNESS = "dim_zero_brightness"

    // Sleep timer runtime state (survives a process restart so the alarm stays meaningful).
    const val TIMER_END_AT = "timer_end_at_elapsed"

    const val LOCK_METHOD_ADMIN = "admin"
    const val LOCK_METHOD_ACCESSIBILITY = "accessibility"

    fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- bubble ----

    fun bubbleX(ctx: Context): Int = sp(ctx).getInt(BUBBLE_X, -1)
    fun bubbleY(ctx: Context): Int = sp(ctx).getInt(BUBBLE_Y, -1)

    fun saveBubblePosition(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(BUBBLE_X, x).putInt(BUBBLE_Y, y).apply()
    }

    fun bubbleSizeDp(ctx: Context): Int = sp(ctx).getInt(BUBBLE_SIZE_DP, 52).coerceIn(36, 68)
    fun setBubbleSizeDp(ctx: Context, dp: Int) =
        sp(ctx).edit().putInt(BUBBLE_SIZE_DP, dp.coerceIn(36, 68)).apply()

    fun bubbleAlpha(ctx: Context): Float = sp(ctx).getFloat(BUBBLE_ALPHA, 0.62f).coerceIn(0.1f, 1f)
    fun setBubbleAlpha(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(BUBBLE_ALPHA, v.coerceIn(0.1f, 1f)).apply()

    fun idleFade(ctx: Context): Boolean = sp(ctx).getBoolean(IDLE_FADE, true)
    fun setIdleFade(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(IDLE_FADE, v).apply()

    fun idleAlpha(ctx: Context): Float = sp(ctx).getFloat(IDLE_ALPHA, 0.12f).coerceIn(0f, 1f)
    fun setIdleAlpha(ctx: Context, v: Float) =
        sp(ctx).edit().putFloat(IDLE_ALPHA, v.coerceIn(0f, 1f)).apply()

    fun idleFadeMs(ctx: Context): Long = sp(ctx).getLong(IDLE_FADE_MS, 2000L).coerceIn(500L, 30_000L)

    // ---- behaviour ----

    fun bubbleEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(BUBBLE_ENABLED, true)
    fun setBubbleEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(BUBBLE_ENABLED, v).apply()

    fun startOnBoot(ctx: Context): Boolean = sp(ctx).getBoolean(START_ON_BOOT, false)
    fun setStartOnBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(START_ON_BOOT, v).apply()

    fun lockMethod(ctx: Context): String =
        sp(ctx).getString(LOCK_METHOD, LOCK_METHOD_ADMIN) ?: LOCK_METHOD_ADMIN

    fun setLockMethod(ctx: Context, v: String) = sp(ctx).edit().putString(LOCK_METHOD, v).apply()

    fun defaultTimerMin(ctx: Context): Int = sp(ctx).getInt(DEFAULT_TIMER_MIN, 15).coerceIn(1, 600)
    fun setDefaultTimerMin(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(DEFAULT_TIMER_MIN, v.coerceIn(1, 600)).apply()

    /**
     * Auto-learned: true while we are still willing to try `screenBrightness = 0f` for black dim.
     * Flipped to false the first time 0f is observed switching the display off.
     */
    fun zeroBrightness(ctx: Context): Boolean = sp(ctx).getBoolean(DIM_ZERO_BRIGHTNESS, true)
    fun setZeroBrightness(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(DIM_ZERO_BRIGHTNESS, v).apply()

    // ---- sleep timer ----

    fun timerEndAt(ctx: Context): Long = sp(ctx).getLong(TIMER_END_AT, 0L)

    fun setTimerEndAt(ctx: Context, elapsedRealtime: Long) =
        sp(ctx).edit().putLong(TIMER_END_AT, elapsedRealtime).apply()
}
