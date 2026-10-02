package com.impel.touchlock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * A window that swallows every touch that lands on it. `onTouchEvent` always returns true, so
 * nothing is ever passed on to the app underneath.
 *
 * @param color background colour (transparent for the touch lock, opaque black for dim).
 */
@SuppressLint("ViewConstructor") // Always created in code, never inflated from XML.
internal class TouchBlockerView(context: Context, color: Int) : View(context) {

    init {
        setBackgroundColor(color)
        isClickable = true
        isFocusable = false
    }

    //noinspection ClickableViewAccessibility
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Full-screen, transparent, non-focusable window that absorbs all touches.
 *
 * The window deliberately does *not* set `FLAG_NOT_TOUCH_MODAL`: it is modal, so every touch in
 * the display — status bar and navigation area included — is delivered here first. It is also
 * `FLAG_NOT_FOCUSABLE`, so the app underneath keeps its input focus and keeps playing.
 */
class LockOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    val isShown: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val v = TouchBlockerView(ctx, Color.TRANSPARENT)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            wm.addView(v, p)
        } catch (t: Throwable) {
            // Some OEMs reject an overlay while a secure window is on screen: degrade, never crash.
            return
        }
        view = v
        params = p
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
    }

    /** Re-adds the window so that its z-order follows the current show order. */
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
}
