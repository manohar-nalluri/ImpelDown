package com.impel.touchlock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager

/** Small, allocation-light helpers shared by the overlays. */
internal object Ui {

    fun dp(ctx: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
    ).toInt()

    /** A rounded rectangle without inflating a single drawable resource. */
    fun rounded(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Pressed-state row background built in code: cheaper than a ripple, no XML. */
    fun rowBackground(pressed: Int, normal: Int = 0x00000000): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressed))
        addState(intArrayOf(), ColorDrawable(normal))
    }

    /** Real display size in pixels, including the status/navigation bar areas. */
    fun realScreenSize(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also { wm.defaultDisplay.getRealSize(it) }
    }

    /** x = top inset (status bar), y = bottom inset (navigation bar). */
    fun systemBars(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return Point(insets.top, insets.bottom)
        }
        return Point(frameworkDimen(ctx, "status_bar_height"), frameworkDimen(ctx, "navigation_bar_height"))
    }

    private fun frameworkDimen(ctx: Context, name: String): Int {
        // Reflection into the framework's own dimens is the only way to read the status/navigation
        // bar height before Android 11 (API 30), where WindowInsets is not available to services.
        @SuppressLint("DiscouragedApi")
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }
}
