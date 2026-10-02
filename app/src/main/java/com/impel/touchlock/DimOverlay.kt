package com.impel.touchlock

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Solid black, full-screen window whose `screenBrightness` is pushed to the minimum.
 *
 * Because `android.permission.WRITE_SETTINGS` is deliberately *not* requested, the brightness
 * override only ever applies while this window is visible — the user's system setting is never
 * modified and is restored automatically the moment the window is removed.
 *
 * `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF` is 0f, which some devices interpret as
 * "turn the display off"; the service watches for that and falls back to [MIN_VISIBLE].
 */
class DimOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    val isShown: Boolean get() = view != null

    fun show(brightness: Float) {
        if (view != null) {
            setBrightness(brightness)
            return
        }
        val v = TouchBlockerView(ctx, Color.BLACK)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            screenBrightness = brightness
        }
        try {
            wm.addView(v, p)
        } catch (t: Throwable) {
            return
        }
        view = v
        params = p
    }

    fun setBrightness(brightness: Float) {
        val p = params ?: return
        if (p.screenBrightness == brightness) return
        p.screenBrightness = brightness
        val v = view ?: return
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
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

    companion object {
        /** Below this, some panels simply refuse to light up. */
        const val MIN_VISIBLE = 0.01f
    }
}
