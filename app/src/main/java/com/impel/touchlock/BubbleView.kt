package com.impel.touchlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The floating bubble, drawn entirely on a Canvas: no bitmap, no XML drawable, no child views.
 * One flat view whose `onDraw` costs two circles plus a small padlock path.
 *
 * `alpha` is animated by the service for the idle fade; alpha does not affect hit testing,
 * so a fully transparent bubble can still be tapped to bring itself back.
 */
class BubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x59000000
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x99FFFFFF.toInt()
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val shackle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val body = RectF()
    private val shackleBounds = RectF()

    var locked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Amber tint while locked, neutral while free. */
    fun setAccentLocked(locked: Boolean) {
        val accent = if (locked) 0xFFFFC46B.toInt() else 0xFFFFFFFF.toInt()
        glyph.color = accent
        shackle.color = accent
        ring.color = if (locked) 0xCCFFC46B.toInt() else 0x99FFFFFF.toInt()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = (if (w < h) w else h) / 2f - ring.strokeWidth

        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)

        if (locked) drawPadlock(canvas, cx, cy, r) else canvas.drawCircle(cx, cy, r * 0.26f, glyph)
    }

    private fun drawPadlock(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val bodyWidth = r * 0.86f
        val bodyHeight = r * 0.60f
        val bodyTop = cy - bodyHeight * 0.12f
        val bodyBottom = bodyTop + bodyHeight

        body.set(cx - bodyWidth / 2f, bodyTop, cx + bodyWidth / 2f, bodyBottom)
        canvas.drawRoundRect(body, r * 0.16f, r * 0.16f, glyph)

        val sr = r * 0.30f
        shackle.strokeWidth = r * 0.16f
        shackleBounds.set(cx - sr, bodyTop - sr * 2f, cx + sr, bodyTop)
        canvas.drawArc(shackleBounds, 180f, 180f, false, shackle)
    }
}
