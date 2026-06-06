package com.example.sacurrencyclassifier

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

// custom view that draws the concentric arc logo — each ring is a partial arc of a different colour
// radiusFraction and strokeFraction are both relative to half the view size so the logo scales cleanly
class RingLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Ring(
        val radiusFraction: Float,    // distance from centre as a fraction of half the view size
        val color: Int,
        val strokeFraction: Float,    // arc thickness as a fraction of half the view size
        val sweepDegrees: Float,
        val startAngle: Float
    )

    // rings ordered outermost to innermost — each starts at a different angle so they spiral inward
    private val rings = listOf(
        Ring(0.920f, Color.parseColor("#f07048"), 0.160f, 215f, -20f),   // coral
        Ring(0.720f, Color.parseColor("#27a244"), 0.160f, 215f,  50f),   // green
        Ring(0.530f, Color.parseColor("#3aa0d8"), 0.140f, 215f, 130f),   // sky blue
        Ring(0.360f, Color.parseColor("#f0b429"), 0.130f, 215f, 210f),   // amber
        Ring(0.210f, Color.parseColor("#e8641a"), 0.110f, 215f, 290f),   // burnt orange
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND   // rounded ends make the arcs look less mechanical
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }

    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx   = width  / 2f
        val cy   = height / 2f
        val half = minOf(cx, cy)

        rings.forEach { ring ->
            val r  = ring.radiusFraction * half
            val sw = ring.strokeFraction * half
            paint.color = ring.color
            paint.strokeWidth = sw
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, ring.startAngle, ring.sweepDegrees, false, paint)
        }

        // "R" in the centre — text size and vertical offset are fractions of half so they scale with the view
        textPaint.textSize = half * 0.38f
        canvas.drawText("R", cx, cy + (half * 0.13f), textPaint)
    }
}
