package com.impel.touchlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * One low-importance, ongoing notification. It doubles as the "unlock" escape hatch and as a
 * live countdown: [setUsesChronometer] + [setChronometerCountDown] make the *system* render the
 * remaining time, so the app never wakes up once per second.
 */
internal object Notifier {

    const val CHANNEL_ID = "touch_lock"
    const val ID = 1
    private const val ID_LOCK_FAILED = 2
    private const val ID_OVERLAY_NEEDED = 3

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.channel_name),
                    // LOW: no sound, no heads-up, and it never wakes the screen.
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = ctx.getString(R.string.channel_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    enableLights(false)
                }
            )
        }
    }

    /** Shown once when the service could not start because the overlay permission is missing. */
    fun overlayNeeded(ctx: Context) {
        ensureChannel(ctx)
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(ctx.getString(R.string.notif_overlay_needed))
            .setContentText(ctx.getString(R.string.notif_overlay_needed_text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, 8))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ID_OVERLAY_NEEDED, notification)
        } catch (_: Throwable) {
        }
    }

    private fun openAppIntent(ctx: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        ctx, requestCode,
        Intent(ctx, SettingsActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Plain (non-ongoing) notification used when the sleep timer could not lock the screen. */
    fun lockFailed(ctx: Context) {
        ensureChannel(ctx)
        val notification = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(ctx.getString(R.string.notif_lock_failed))
            .setContentText(ctx.getString(R.string.notif_lock_failed_text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, 9))
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_LOCK_FAILED, notification)
        } catch (_: Throwable) {
        }
    }

    fun build(
        ctx: Context,
        locked: Boolean,
        dimmed: Boolean,
        timerEndWallClock: Long
    ): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, SettingsActivity::class.java),
            flags
        )

        val toggle = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, BubbleService::class.java)
                .setAction(if (locked) BubbleService.ACTION_UNLOCK else BubbleService.ACTION_LOCK),
            flags
        )

        val dim = PendingIntent.getService(
            ctx, 2,
            Intent(ctx, BubbleService::class.java)
                .setAction(if (dimmed) BubbleService.ACTION_DIM_OFF else BubbleService.ACTION_DIM_ON),
            flags
        )

        val stop = PendingIntent.getService(
            ctx, 3,
            Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_STOP),
            flags
        )

        val title = when {
            dimmed -> ctx.getString(R.string.notif_dim)
            locked -> ctx.getString(R.string.notif_locked)
            else -> ctx.getString(R.string.notif_running)
        }
        val text = when {
            dimmed -> ctx.getString(R.string.notif_locked_text)
            locked -> ctx.getString(R.string.notif_locked_text)
            else -> ctx.getString(R.string.notif_running_text)
        }

        val actionIcon = Icon.createWithResource(ctx, R.drawable.ic_stat_lock)

        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(
                Notification.Action.Builder(
                    actionIcon,
                    ctx.getString(
                        if (locked) R.string.notif_action_unlock else R.string.notif_action_lock
                    ),
                    toggle
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    actionIcon,
                    ctx.getString(R.string.notif_action_dim),
                    dim
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    actionIcon,
                    ctx.getString(R.string.notif_action_stop),
                    stop
                ).build()
            )

        if (timerEndWallClock > 0L) {
            builder.setWhen(timerEndWallClock)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }

        return builder.build()
    }
}
