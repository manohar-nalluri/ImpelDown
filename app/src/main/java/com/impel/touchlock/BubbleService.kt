package com.impel.touchlock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import kotlin.math.abs

/**
 * The single long-lived component of the app. It owns:
 *
 *  * the floating bubble window (always present while running),
 *  * the full-screen touch-lock window (only while locked),
 *  * the black dim window (only while dimmed),
 *  * the long-press menu window (only while open),
 *  * the sleep timer and the ongoing notification.
 *
 * Everything runs on the main looper. There are no threads, no coroutines, no polling loops and no
 * wakelocks: while the bubble is simply sitting on screen the process is completely idle.
 */
class BubbleService : Service(),
    View.OnTouchListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private lateinit var timer: SleepTimerManager

    private var bubble: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var lockOverlay: LockOverlay? = null
    private var dimOverlay: DimOverlay? = null
    private var menu: AppMenu? = null

    private var locked = false
    private var dimmed = false
    private var dimStartedAt = 0L

    private var running = false
    private var touching = false
    private var restackPending = false
    private var dragged = false
    private var longPressFired = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0

    private var fadeAnim: ValueAnimator? = null
    private var snapAnim: ValueAnimator? = null
    private var screenReceiver: ScreenStateReceiver? = null

    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        timer = SleepTimerManager(this)
        Notifier.ensureChannel(this)

        // Must happen within 5 s of startForegroundService(), before anything else can fail.
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            // Without the overlay permission there is nothing this service could do.
            Notifier.overlayNeeded(this)
            stopSelf()
            return
        }

        running = true
        timer.onChanged = {
            updateNotification()
            menu?.refreshCountdown()
        }
        timer.onFired = { onTimerFired() }
        SleepTimerManager.instance = timer

        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this)
        addBubble()
        instance = this
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOCK -> lock()
            ACTION_UNLOCK -> unlock()
            ACTION_DIM_ON -> setDim(true)
            ACTION_DIM_OFF -> setDim(false)
            ACTION_TOGGLE_LOCK -> if (locked) unlock() else lock()
            ACTION_SET_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                if (minutes > 0) startTimer(minutes) else cancelTimer()
            }
            ACTION_CANCEL_TIMER -> cancelTimer()
            else -> Unit // START_STICKY restart with a null intent: the bubble is already back.
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        if (SleepTimerManager.instance === timer) SleepTimerManager.instance = null

        handler.removeCallbacksAndMessages(null)
        fadeAnim?.cancel()
        snapAnim?.cancel()
        fadeAnim = null
        snapAnim = null

        menu?.dismiss()
        menu = null
        dimOverlay?.hide()
        dimOverlay = null
        lockOverlay?.hide()
        lockOverlay = null
        bubble?.let { view ->
            try {
                wm.removeViewImmediate(view)
            } catch (_: Throwable) {
            }
        }
        bubble = null
        bubbleParams = null

        unregisterScreenReceiver()
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = Notifier.build(this, locked = false, dimmed = false, timerEndWallClock = 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID, notification)
        }
    }

    // ------------------------------------------------------------------ bubble window

    private fun addBubble() {
        if (bubble != null) return

        val size = Ui.dp(this, Prefs.bubbleSizeDp(this).toFloat())
        val view = BubbleView(this).apply {
            setOnTouchListener(this@BubbleService)
            locked = this@BubbleService.locked
            setAccentLocked(this@BubbleService.locked)
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable => the app underneath keeps input focus and keeps playing.
            // No FLAG_NOT_TOUCH_MODAL => the bubble gets its own touches, nobody else's.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val screen = Ui.realScreenSize(this)
        val savedX = Prefs.bubbleX(this)
        params.x = if (savedX < 0) {
            (screen.x - size - Ui.dp(this, 6f)).coerceAtLeast(0)
        } else {
            savedX.coerceIn(0, (screen.x - size).coerceAtLeast(0))
        }
        val savedY = Prefs.bubbleY(this)
        params.y = if (savedY < 0) {
            screen.y * 40 / 100
        } else {
            savedY.coerceIn(0, (screen.y - size).coerceAtLeast(0))
        }

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            stopSelf()
            return
        }
        bubble = view
        bubbleParams = params
        view.alpha = baseAlpha()
        scheduleIdleFade(2_500L)
    }

    /**
     * Re-adds every overlay so their z-order is always dim < lock < bubble < menu.
     *
     * Never call this while a finger is down: removing the bubble's window in the middle of a
     * gesture makes the input system drop/re-target the stream, which can leak the tail of the
     * gesture to the app underneath. While touching, the re-stack is deferred to ACTION_UP.
     */
    private fun restack() {
        if (touching) {
            restackPending = true
            return
        }
        val view = bubble ?: return
        val params = bubbleParams ?: return
        try {
            wm.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
        dimOverlay?.reattach()
        lockOverlay?.reattach()
        try {
            wm.addView(view, params)
        } catch (_: Throwable) {
        }
        menu?.reattach()
    }

    // ------------------------------------------------------------------ lock / dim

    private fun lock() {
        if (locked || !running) return
        locked = true
        bubble?.locked = true
        bubble?.setAccentLocked(true)
        if (lockOverlay == null) {
            lockOverlay = LockOverlay(this).also { it.show() }
        }
        restack()
        wakeUpBubble(LOCK_INDICATOR_MS)
        updateNotification()
        toast(getString(R.string.locked_toast))
    }

    private fun unlock() {
        if (!locked) return
        locked = false
        setDimInternal(false)
        lockOverlay?.hide()
        lockOverlay = null
        bubble?.locked = false
        bubble?.setAccentLocked(false)
        restack()
        wakeUpBubble()
        updateNotification()
        toast(getString(R.string.unlocked_toast))
    }

    private fun setDim(on: Boolean) {
        if (!running) return
        if (on) {
            if (!locked) lock()
            setDimInternal(true)
        } else {
            setDimInternal(false)
        }
        restack()
        menu?.setDim(dimmed)
        updateNotification()
    }

    private fun setDimInternal(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        handler.removeCallbacks(zeroBrightnessWatchdog)
        if (on) {
            dimStartedAt = SystemClock.elapsedRealtime()
            dimOverlay = DimOverlay(this).also { it.show(dimBrightness()) }
            if (Prefs.zeroBrightness(this)) {
                // Some panels go dark for 0f without ever broadcasting ACTION_SCREEN_OFF.
                handler.postDelayed(zeroBrightnessWatchdog, ZERO_BRIGHTNESS_PROBE_MS)
            }
        } else {
            dimOverlay?.hide()
            dimOverlay = null
        }
        updateScreenReceiver()
    }

    /**
     * Second chance at the 0f -> 1 % fallback: if the display stopped being interactive while we
     * are dimming, remember that this device treats BRIGHTNESS_OVERRIDE_OFF as "screen off".
     */
    private val zeroBrightnessWatchdog = Runnable {
        if (!dimmed) return@Runnable
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            Prefs.setZeroBrightness(this, false)
            dimOverlay?.setBrightness(DimOverlay.MIN_VISIBLE)
        }
    }

    private fun dimBrightness(): Float =
        // 0f is BRIGHTNESS_OVERRIDE_OFF: the documented "turn the screen off" value. We try it
        // first (it is the true minimum on panels that accept it) and the screen-off watchdog in
        // onScreenOff() learns to fall back to 1 % on devices that treat it as "display off".
        if (Prefs.zeroBrightness(this)) 0f else DimOverlay.MIN_VISIBLE

    // ------------------------------------------------------------------ touch

    //noinspection ClickableViewAccessibility
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                dragged = false
                longPressFired = false
                downRawX = event.rawX
                downRawY = event.rawY
                downX = params.x
                downY = params.y
                snapAnim?.cancel()
                handler.removeCallbacks(idleFadeRunnable)
                fadeTo(baseAlpha(), 100L)
                handler.postDelayed(longPressRunnable, if (locked) HOLD_UNLOCK_MS else HOLD_MENU_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragged = true
                    handler.removeCallbacks(longPressRunnable)
                    menu?.dismiss()
                }
                if (dragged) moveBubble(downX + dx.toInt(), downY + dy.toInt())
            }

            MotionEvent.ACTION_UP -> {
                touching = false
                handler.removeCallbacks(longPressRunnable)
                // A long press has already been handled: the finger coming up is not a tap.
                if (dragged) snapToEdge() else if (!longPressFired) handleTap()
                if (restackPending) {
                    restackPending = false
                    restack()
                }
                scheduleIdleFade(idleDelay())
            }

            MotionEvent.ACTION_CANCEL -> {
                touching = false
                handler.removeCallbacks(longPressRunnable)
                if (restackPending) {
                    restackPending = false
                    restack()
                }
                scheduleIdleFade(idleDelay())
            }
        }
        return true
    }

    private fun handleTap() {
        if (menu?.isShown == true) {
            menu?.dismiss()
            return
        }
        // While locked a single tap is ignored on purpose: only the deliberate hold unlocks.
        if (!locked) lock()
    }

    private val longPressRunnable = Runnable {
        val view = bubble ?: return@Runnable
        longPressFired = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (locked) unlock() else openMenu()
    }

    private fun moveBubble(x: Int, y: Int) {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val screen = Ui.realScreenSize(this)
        val bars = Ui.systemBars(this)
        val maxX = (screen.x - params.width).coerceAtLeast(0)
        val minY = bars.x
        val maxY = (screen.y - params.height - bars.y).coerceAtLeast(minY)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(minY, maxY)
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun snapToEdge() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val screen = Ui.realScreenSize(this)
        val targetX = if (params.x + params.width / 2 < screen.x / 2) 0 else screen.x - params.width
        if (params.x == targetX) {
            saveBubblePosition()
            return
        }
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = SNAP_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Throwable) {
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = saveBubblePosition()
            })
            start()
        }
    }

    private fun saveBubblePosition() {
        val params = bubbleParams ?: return
        Prefs.saveBubblePosition(this, params.x, params.y)
    }

    // ------------------------------------------------------------------ idle fade

    private val idleFadeRunnable = Runnable {
        if (!touching) fadeTo(idleTarget(), IDLE_FADE_ANIM_MS)
    }

    private fun baseAlpha(): Float = Prefs.bubbleAlpha(this)

    private fun idleTarget(): Float = when {
        // The lock indicator always dims out after a moment, whatever the idle setting says.
        locked -> LOCKED_IDLE_ALPHA
        Prefs.idleFade(this) -> Prefs.idleAlpha(this)
        else -> baseAlpha()
    }

    private fun idleDelay(): Long = if (locked) LOCK_INDICATOR_MS else Prefs.idleFadeMs(this)

    private fun scheduleIdleFade(delay: Long) {
        if (!running) return
        handler.removeCallbacks(idleFadeRunnable)
        handler.postDelayed(idleFadeRunnable, delay.coerceAtLeast(150L))
    }

    /**
     * Back to full opacity + restart the idle countdown. Called on any interaction, and — when the
     * optional accessibility service is enabled — on any touch anywhere on the screen.
     */
    private fun wakeUpBubble(holdMs: Long = idleDelay()) {
        if (!running) return
        val view = bubble ?: return
        if (view.alpha < baseAlpha() - 0.01f) fadeTo(baseAlpha(), 120L)
        scheduleIdleFade(holdMs)
    }

    private fun fadeTo(target: Float, duration: Long) {
        val view = bubble ?: return
        fadeAnim?.cancel()
        if (duration <= 0L || abs(view.alpha - target) < 0.01f) {
            view.alpha = target
            return
        }
        fadeAnim = ValueAnimator.ofFloat(view.alpha, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator -> view.alpha = animator.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ menu

    private fun openMenu() {
        val params = bubbleParams ?: return
        val m = menu ?: AppMenu(
            ctx = this,
            onAction = { action -> onMenuAction(action) },
            timerLabel = { if (timer.isActive()) timer.remainingLabel() else null }
        ).also { menu = it }
        m.setDim(dimmed)
        // The menu is added after the bubble, so it is already on top: no re-stack needed here.
        m.show(params.x, params.y)
    }

    private fun onMenuAction(action: String) {
        when {
            action == AppMenu.ACT_DIM -> setDim(!dimmed)
            action == AppMenu.ACT_SETTINGS -> openSettings(focusTimer = false)
            action == AppMenu.ACT_CUSTOM -> openSettings(focusTimer = true)
            action == AppMenu.ACT_QUIT -> stopSelf()
            action.startsWith(AppMenu.ACT_TIMER) -> {
                val minutes = action.removePrefix(AppMenu.ACT_TIMER).toIntOrNull() ?: 0
                if (minutes > 0) startTimer(minutes) else cancelTimer()
            }
        }
    }

    private fun openSettings(focusTimer: Boolean) {
        val intent = Intent(this, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SettingsActivity.EXTRA_FOCUS_TIMER, focusTimer)
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ sleep timer

    private fun startTimer(minutes: Int) {
        timer.set(minutes)
        updateScreenReceiver()
        updateNotification()
        menu?.refreshCountdown()
        toast(getString(R.string.timer_set, minutes))
    }

    private fun cancelTimer() {
        timer.cancel()
        updateScreenReceiver()
        updateNotification()
        menu?.refreshCountdown()
    }

    /** The alarm already locked the screen in [SleepTimerReceiver]; only refresh local state. */
    private fun onTimerFired() {
        updateScreenReceiver()
        updateNotification()
        menu?.refreshCountdown()
        toast(getString(R.string.timer_fired))
    }

    // ------------------------------------------------------------------ screen state

    /**
     * Registered only while it can actually do something (timer running or black dim on), so the
     * app holds no receiver at all while idle.
     */
    private fun updateScreenReceiver() {
        val needed = timer.isActive() || dimmed
        if (needed && screenReceiver == null) {
            val receiver = ScreenStateReceiver { onScreenOff() }
            try {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
                screenReceiver = receiver
            } catch (_: Throwable) {
            }
        } else if (!needed) {
            unregisterScreenReceiver()
        }
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private fun onScreenOff() {
        if (dimmed && SystemClock.elapsedRealtime() - dimStartedAt < SCREEN_OFF_LEARN_MS) {
            // screenBrightness = 0f switched this panel off. Learn it and switch to 1 %.
            handler.removeCallbacks(zeroBrightnessWatchdog)
            Prefs.setZeroBrightness(this, false)
            dimOverlay?.setBrightness(DimOverlay.MIN_VISIBLE)
            return
        }
        // A manual screen-off cancels a pending sleep timer.
        if (timer.isActive()) cancelTimer()
    }

    // ------------------------------------------------------------------ notification / prefs

    private fun updateNotification() {
        if (!running) return
        val endWallClock = if (timer.isActive()) timer.endAtWallClock() else 0L
        val notification = Notifier.build(this, locked, dimmed, endWallClock)
        try {
            getSystemService(NotificationManager::class.java).notify(Notifier.ID, notification)
        } catch (_: Throwable) {
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.BUBBLE_SIZE_DP -> resizeBubble()
            Prefs.BUBBLE_ALPHA -> if (!touching) fadeTo(baseAlpha(), 150L)
            Prefs.IDLE_ALPHA, Prefs.IDLE_FADE -> scheduleIdleFade(idleDelay())
        }
    }

    private fun resizeBubble() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val size = Ui.dp(this, Prefs.bubbleSizeDp(this).toFloat())
        if (params.width == size && params.height == size) return
        params.width = size
        params.height = size
        val screen = Ui.realScreenSize(this)
        val bars = Ui.systemBars(this)
        params.x = params.x.coerceIn(0, (screen.x - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(bars.x, (screen.y - size - bars.y).coerceAtLeast(bars.x))
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Throwable) {
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val ACTION_START = "com.impel.touchlock.action.START"
        const val ACTION_STOP = "com.impel.touchlock.action.STOP"
        const val ACTION_LOCK = "com.impel.touchlock.action.LOCK"
        const val ACTION_UNLOCK = "com.impel.touchlock.action.UNLOCK"
        const val ACTION_TOGGLE_LOCK = "com.impel.touchlock.action.TOGGLE_LOCK"
        const val ACTION_DIM_ON = "com.impel.touchlock.action.DIM_ON"
        const val ACTION_DIM_OFF = "com.impel.touchlock.action.DIM_OFF"
        const val ACTION_SET_TIMER = "com.impel.touchlock.action.SET_TIMER"
        const val ACTION_CANCEL_TIMER = "com.impel.touchlock.action.CANCEL_TIMER"
        const val EXTRA_MINUTES = "minutes"

        private const val HOLD_UNLOCK_MS = 1500L
        private const val HOLD_MENU_MS = 450L
        private const val LOCK_INDICATOR_MS = 3000L
        private const val LOCKED_IDLE_ALPHA = 0.35f
        private const val IDLE_FADE_ANIM_MS = 260L
        private const val SNAP_ANIM_MS = 160L
        private const val SCREEN_OFF_LEARN_MS = 2500L
        private const val ZERO_BRIGHTNESS_PROBE_MS = 900L

        @Volatile
        private var instance: BubbleService? = null

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            val intent = Intent(ctx, BubbleService::class.java).setAction(ACTION_START)
            // minSdk is 26, so startForegroundService is always available.
            ctx.startForegroundService(intent)
        }

        /** Sends a command to the running service; a no-op when it is not running. */
        fun send(ctx: Context, action: String, minutes: Int = 0) {
            if (instance == null) return
            val intent = Intent(ctx, BubbleService::class.java).setAction(action)
            if (minutes > 0) intent.putExtra(EXTRA_MINUTES, minutes)
            try {
                ctx.startService(intent)
            } catch (_: Throwable) {
            }
        }

        /** Called by [LockAccessibilityService] when the user touches the screen anywhere. */
        fun onUserTouchGlobal() {
            instance?.wakeUpBubble()
        }
    }
}
