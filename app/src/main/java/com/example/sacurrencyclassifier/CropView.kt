package com.example.sacurrencyclassifier

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var imageRect = RectF()
    private var cropRect = RectF()
    private var rotationDegrees = 0f

    // MOVE drags the whole crop box, corner modes resize from that handle
    private enum class DragMode { NONE, MOVE, TL, TR, BL, BR }
    private var dragMode = DragMode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private val handleSize = 40f   // tap target radius for corner handles

    private val dimPaint = Paint().apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = 0xFF27a244.toInt()   // brand green
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = 0xFF27a244.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = 0x6627a244.toInt()   // semi-transparent green for the rule-of-thirds grid
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    // pinch-to-zoom scales the crop box around the pinch centre
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val focusX = detector.focusX
                val focusY = detector.focusY
                val newWidth  = (cropRect.width()  * scaleFactor).coerceAtLeast(80f)
                val newHeight = (cropRect.height() * scaleFactor).coerceAtLeast(80f)
                val newLeft   = (focusX - (focusX - cropRect.left)  * scaleFactor).coerceAtLeast(imageRect.left)
                val newTop    = (focusY - (focusY - cropRect.top)   * scaleFactor).coerceAtLeast(imageRect.top)
                val newRight  = (newLeft + newWidth).coerceAtMost(imageRect.right)
                val newBottom = (newTop  + newHeight).coerceAtMost(imageRect.bottom)
                cropRect.set(newLeft, newTop, newRight, newBottom)
                invalidate()
                return true
            }
        })

    fun setCropRotation(degrees: Float) { rotationDegrees = degrees; invalidate() }
    fun getCropRotation() = rotationDegrees
    fun getImageRect() = RectF(imageRect)
    fun getCropRect()  = RectF(cropRect)
    fun setCropRect(rect: RectF) { cropRect = rect; invalidate() }

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        rotationDegrees = 0f
        if (width > 0 && height > 0) layoutImage(bmp, width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap?.let { layoutImage(it, w, h) }
    }

    // centres the image in the view at the largest scale that fits, with a 2% inset for the initial crop box
    private fun layoutImage(bmp: Bitmap, vw: Int, vh: Int) {
        val scale = min(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
        val iw = bmp.width * scale
        val ih = bmp.height * scale
        val left = (vw - iw) / 2f
        val top  = (vh - ih) / 2f
        imageRect = RectF(left, top, left + iw, top + ih)
        val padX = iw * 0.02f
        val padY = ih * 0.02f
        cropRect = RectF(left + padX, top + padY, left + iw - padX, top + ih - padY)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return

        val cx = (imageRect.left + imageRect.right)  / 2f
        val cy = (imageRect.top  + imageRect.bottom) / 2f

        // rotate the image around its centre for fine-angle correction
        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        canvas.drawBitmap(bmp, null, imageRect, null)
        canvas.restore()

        // dim the four regions outside the crop box
        canvas.drawRect(imageRect.left, imageRect.top,  imageRect.right, cropRect.top,    dimPaint)
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, cropRect.top,   cropRect.left,   cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top,   imageRect.right, cropRect.bottom, dimPaint)

        // rule-of-thirds grid
        val thirdW = cropRect.width()  / 3
        val thirdH = cropRect.height() / 3
        canvas.drawLine(cropRect.left + thirdW,     cropRect.top,    cropRect.left + thirdW,     cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + thirdW * 2, cropRect.top,    cropRect.left + thirdW * 2, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH,     cropRect.right, cropRect.top + thirdH,     gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2, cropRect.right, cropRect.top + thirdH * 2, gridPaint)

        canvas.drawRect(cropRect, borderPaint)

        // corner handles
        canvas.drawCircle(cropRect.left,  cropRect.top,    handleSize / 2, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top,    handleSize / 2, handlePaint)
        canvas.drawCircle(cropRect.left,  cropRect.bottom, handleSize / 2, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleSize / 2, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true   // let the pinch detector consume the event

        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // pick which part of the crop box was touched — corners take priority over MOVE
                dragMode = when {
                    near(x, cropRect.left)  && near(y, cropRect.top)    -> DragMode.TL
                    near(x, cropRect.right) && near(y, cropRect.top)    -> DragMode.TR
                    near(x, cropRect.left)  && near(y, cropRect.bottom) -> DragMode.BL
                    near(x, cropRect.right) && near(y, cropRect.bottom) -> DragMode.BR
                    cropRect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                val minSize = 80f   // minimum crop dimension to prevent collapsing to a point
                when (dragMode) {
                    DragMode.MOVE -> {
                        // clamp so the box can't be dragged outside the image
                        val newL = max(imageRect.left,   cropRect.left   + dx)
                        val newT = max(imageRect.top,    cropRect.top    + dy)
                        val newR = min(imageRect.right,  cropRect.right  + dx)
                        val newB = min(imageRect.bottom, cropRect.bottom + dy)
                        if (newR - newL >= minSize && newB - newT >= minSize)
                            cropRect.offset(newL - cropRect.left, newT - cropRect.top)
                    }
                    DragMode.TL -> {
                        cropRect.left = max(imageRect.left, min(cropRect.left + dx, cropRect.right  - minSize))
                        cropRect.top  = max(imageRect.top,  min(cropRect.top  + dy, cropRect.bottom - minSize))
                    }
                    DragMode.TR -> {
                        cropRect.right = min(imageRect.right, max(cropRect.right + dx, cropRect.left + minSize))
                        cropRect.top   = max(imageRect.top,   min(cropRect.top   + dy, cropRect.bottom - minSize))
                    }
                    DragMode.BL -> {
                        cropRect.left   = max(imageRect.left,   min(cropRect.left   + dx, cropRect.right - minSize))
                        cropRect.bottom = min(imageRect.bottom, max(cropRect.bottom + dy, cropRect.top   + minSize))
                    }
                    DragMode.BR -> {
                        cropRect.right  = min(imageRect.right,  max(cropRect.right  + dx, cropRect.left + minSize))
                        cropRect.bottom = min(imageRect.bottom, max(cropRect.bottom + dy, cropRect.top  + minSize))
                    }
                    else -> {}
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> dragMode = DragMode.NONE
        }
        return true
    }

    private fun near(a: Float, b: Float) = Math.abs(a - b) < handleSize

    // applies the fine rotation angle then extracts the crop region in bitmap coordinates
    fun getCroppedBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees)
        val rotatedBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)

        // scale from view coordinates to bitmap coordinates
        val scaleX = rotatedBmp.width  / imageRect.width()
        val scaleY = rotatedBmp.height / imageRect.height()
        val left   = ((cropRect.left   - imageRect.left) * scaleX).toInt().coerceIn(0, rotatedBmp.width)
        val top    = ((cropRect.top    - imageRect.top)  * scaleY).toInt().coerceIn(0, rotatedBmp.height)
        val right  = ((cropRect.right  - imageRect.left) * scaleX).toInt().coerceIn(0, rotatedBmp.width)
        val bottom = ((cropRect.bottom - imageRect.top)  * scaleY).toInt().coerceIn(0, rotatedBmp.height)
        if (right <= left || bottom <= top) return rotatedBmp
        return Bitmap.createBitmap(rotatedBmp, left, top, right - left, bottom - top)
    }
}
