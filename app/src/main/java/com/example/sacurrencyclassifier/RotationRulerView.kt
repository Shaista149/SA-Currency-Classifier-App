package com.example.sacurrencyclassifier

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

// horizontal ruler the user drags to set a fine rotation angle for the crop
// ticks scroll left/right as the angle changes — dragging right increases the angle
class RotationRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onAngleChanged: ((Float) -> Unit)? = null

    private var currentAngle = 0f    // degrees, clamped to ±45
    private val range = 45f          // degrees visible on each side of centre
    private var lastX = 0f

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0B429.toInt()   // amber — matches the zero label colour
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0B429.toInt()   // zero label is amber so it stands out from the other tick labels
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    fun setAngle(angle: Float) {
        currentAngle = angle.coerceIn(-range, range)
        invalidate()
    }

    fun reset() {
        currentAngle = 0f
        invalidate()
        onAngleChanged?.invoke(0f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val degreesPerPixel = (range * 2) / w

        // draw a tick for every degree in range, major ticks every 5°
        for (deg in -range.toInt()..range.toInt()) {
            val x = cx + (deg - currentAngle) / degreesPerPixel
            if (x < 0 || x > w) continue

            val isMajor = deg % 5 == 0
            val tickH   = if (isMajor) h * 0.55f else h * 0.3f
            tickPaint.alpha = if (isMajor) 200 else 100
            canvas.drawLine(x, h / 2f - tickH / 2f, x, h / 2f + tickH / 2f, tickPaint)

            if (isMajor) {
                val paint = if (deg == 0) zeroPaint else textPaint
                canvas.drawText("$deg°", x, h * 0.92f, paint)
            }
        }

        // fixed centre indicator line — shows where the current angle sits against the ruler
        canvas.drawLine(cx, 0f, cx, h * 0.75f, centerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> lastX = event.x
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val degreesPerPixel = (range * 2) / width
                // dragging left moves the ruler right, which increases the angle
                currentAngle = (currentAngle - dx * degreesPerPixel).coerceIn(-range, range)
                lastX = event.x
                invalidate()
                onAngleChanged?.invoke(currentAngle)
            }
        }
        return true
    }
}
