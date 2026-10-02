package com.impel.touchlock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The long-press menu: a small non-focusable overlay window.
 *
 * It is `FLAG_NOT_FOCUSABLE` (so the app underneath keeps focus) and `FLAG_WATCH_OUTSIDE_TOUCH`
 * (so tapping anywhere else dismisses it). The countdown text ticks once per second **only while
 * this window is on screen**; the moment it is dismissed the handler chain ends.
 */
internal class AppMenu(
    private val ctx: Context,
    private val onAction: (String) -> Unit,
    private val timerLabel: () -> String?
) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var view: MenuContainer? = null
    private var params: WindowManager.LayoutParams? = null
    private var timerRow: TextView? = null
    private var dimRow: TextView? = null

    private val pad = Ui.dp(ctx, 12f)
    private val radius = Ui.dp(ctx, 14f)
    private val textColor = ctx.getColor(R.color.menu_text)

    val isShown: Boolean get() = view != null

    private val ticker = object : Runnable {
        override fun run() {
            refreshCountdown()
            if (view != null) handler.postDelayed(this, 1000L)
        }
    }

    fun show(anchorX: Int, anchorY: Int) {
        if (view != null) {
            dismiss()
            return
        }

        val container = MenuContainer(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Ui.rounded(
                fill = ctx.getColor(R.color.menu_bg),
                radiusPx = radius.toFloat(),
                strokeColor = 0x33FFFFFF,
                strokePx = Ui.dp(ctx, 1f)
            )
            onOutside = { dismiss() }
        }

        val timer = TextView(ctx).apply {
            setTextColor(textColor)
            textSize = 15f
            setPadding(pad, Ui.dp(ctx, 4f), pad, Ui.dp(ctx, 4f))
            setOnClickListener { fire(ACT_TIMER + 0) } // tapping the countdown cancels it
        }
        timerRow = timer

        val dim = row("Black dim", ACT_DIM)
        dimRow = dim

        container.addView(
            TextView(ctx).apply {
                text = ctx.getString(R.string.app_name)
                setTextColor(0x99FFFFFF.toInt())
                textSize = 11f
                letterSpacing = 0.08f
                setPadding(pad, pad, pad, Ui.dp(ctx, 6f))
            }
        )
        container.addView(timer)
        container.addView(presetRow())
        container.addView(row("Custom…", ACT_CUSTOM))
        container.addView(divider())
        container.addView(dim)
        container.addView(row("Settings", ACT_SETTINGS))
        container.addView(row("Quit bubble", ACT_QUIT))

        // Measure before adding so the window lands in the right place on the first frame.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(Ui.dp(ctx, 220f), View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(widthSpec, heightSpec)

        val screen = Ui.realScreenSize(ctx)
        var x = anchorX
        var y = anchorY
        if (x + container.measuredWidth > screen.x) x = screen.x - container.measuredWidth
        if (y + container.measuredHeight > screen.y) y = screen.y - container.measuredHeight
        x = x.coerceAtLeast(0)
        y = y.coerceAtLeast(0)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        try {
            wm.addView(container, p)
        } catch (_: Throwable) {
            return
        }
        view = container
        params = p
        refreshCountdown()
        handler.postDelayed(ticker, 1000L)
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        params = null
        timerRow = null
        dimRow = null
        handler.removeCallbacks(ticker)
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
    }

    /** Keeps z-order deterministic when the service re-stacks its windows. */
    fun reattach() {
        val v = view ?: return
        val p = params ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
        try {
            wm.addView(v, p)
        } catch (_: Throwable) {
        }
    }

    fun setDim(on: Boolean) {
        dimRow?.text = if (on) "Black dim: on" else "Black dim"
    }

    fun refreshCountdown() {
        val label = timerLabel()
        timerRow?.text = if (label == null) {
            ctx.getString(R.string.menu_timer_off)
        } else {
            ctx.getString(R.string.menu_timer_active, label)
        }
    }

    // ---------------------------------------------------------------- views

    private fun presetRow(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (minutes in intArrayOf(5, 10, 15, 30, 60)) {
            addView(preset(minutes))
        }
    }

    private fun preset(minutes: Int): TextView = TextView(ctx).apply {
        text = String.format(Locale.US, "%d", minutes)
        gravity = Gravity.CENTER
        setTextColor(textColor)
        textSize = 15f
        background = Ui.rowBackground(0x44FFFFFF, 0x1AFFFFFF)
        setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 8f))
        layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = Ui.dp(ctx, 4f) }
        isClickable = true
        setOnClickListener { fire(ACT_TIMER + minutes) }
    }

    private fun row(label: String, action: String): TextView = TextView(ctx).apply {
        text = label
        setTextColor(textColor)
        textSize = 15f
        setPadding(pad, Ui.dp(ctx, 10f), pad, Ui.dp(ctx, 10f))
        background = Ui.rowBackground(0x33FFFFFF)
        isClickable = true
        setOnClickListener { fire(action) }
    }

    private fun divider(): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 1f)
        )
        setBackgroundColor(0x22FFFFFF)
    }

    private fun fire(action: String) {
        dismiss()
        onAction(action)
    }

    private class MenuContainer(context: Context) : LinearLayout(context) {
        var onOutside: (() -> Unit)? = null

        init {
            isClickable = true
        }

        @SuppressLint("ClickableViewAccessibility") // Container only: its children handle clicks.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutside?.invoke()
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    companion object {
        const val ACT_TIMER = "timer:"
        const val ACT_CUSTOM = "custom"
        const val ACT_DIM = "dim"
        const val ACT_SETTINGS = "settings"
        const val ACT_QUIT = "quit"
    }
}
