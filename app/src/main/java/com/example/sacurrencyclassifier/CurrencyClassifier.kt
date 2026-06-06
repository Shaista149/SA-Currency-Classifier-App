package com.example.sacurrencyclassifier

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class CurrencyClassifier(context: Context, val mode: Mode) : AutoCloseable {

    enum class Mode(val modelFile: String, val classesFile: String, val label: String) {
        NOTES("banknote_model.tflite", "banknote_classes.json", "Banknote"),
        COINS("coin_model.tflite", "coin_classes.json", "Coin")
    }

    data class Prediction(val label: String, val confidence: Float)

    private val IMG_SIZE = 224
    private val PIXEL_MEAN = 127.5f
    private val PIXEL_STD  = 127.5f     // matches the [-1, 1] normalisation used during training

    private val interpreter: Interpreter
    private val classMap: Map<Int, String>

    init {
        // load model bytes into a direct ByteBuffer — TFLite requires a direct buffer, not a heap one
        val modelBytes = context.assets.open(mode.modelFile).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(modelBytes)
            rewind()
        }
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { numThreads = 2 })

        // classes.json maps index strings to label strings e.g. {"0": "R10_new_back", ...}
        // keys come in as strings from JSON so we convert them to ints for the lookup
        val json = context.assets.open(mode.classesFile).bufferedReader().use { it.readText() }
        val type = object : TypeToken<Map<String, String>>() {}.type
        val raw: Map<String, String> = Gson().fromJson(json, type)
        classMap = raw.mapKeys { it.key.toInt() }
    }

    // main entry point — auto-crops the note if possible, then runs inference at all 4 rotations
    fun classify(bitmap: Bitmap, topK: Int = 3): List<Prediction> {
        val cropped = tryAutoCrop(bitmap) ?: bitmap
        return classifyAllRotations(cropped, topK)
    }

    // runs inference at 0/90/180/270° and returns results from whichever rotation scored highest
    // handles notes photographed in any orientation without requiring the user to align them
    private fun classifyAllRotations(bitmap: Bitmap, topK: Int): List<Prediction> {
        val rotations = listOf(0f, 90f, 180f, 270f)
        var bestResults: List<Prediction> = emptyList()
        var bestConfidence = -1f

        for (degrees in rotations) {
            val rotated = if (degrees == 0f) bitmap else rotateBitmap(bitmap, degrees)
            val results = runInference(rotated, topK)
            val topConfidence = results.firstOrNull()?.confidence ?: 0f
            if (topConfidence > bestConfidence) {
                bestConfidence = topConfidence
                bestResults = results
            }
        }
        return bestResults
    }

    // tries to find the note/coin and crop to it using luminance variance to detect content edges
    // scans row and column variance — high variance rows/cols contain the subject, low variance is background
    // returns null if the crop covers >85% of the original (background is probably too uniform to crop reliably)
    private fun tryAutoCrop(bitmap: Bitmap): Bitmap? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            // convert to grayscale using standard luminance weights
            val gray = IntArray(w * h) { i ->
                val px = pixels[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }

            // rows/cols with high variance contain detail — low variance rows are plain background
            val rowVariance = FloatArray(h) { row ->
                val rowPixels = (0 until w).map { gray[row * w + it] }
                val mean = rowPixels.average()
                rowPixels.map { (it - mean).pow(2) }.average().toFloat()
            }
            val colVariance = FloatArray(w) { col ->
                val colPixels = (0 until h).map { gray[it * w + col] }
                val mean = colPixels.average()
                colPixels.map { (it - mean).pow(2) }.average().toFloat()
            }

            val varianceThreshold = 200f
            val activeRows = (0 until h).filter { rowVariance[it] > varianceThreshold }
            val activeCols = (0 until w).filter { colVariance[it] > varianceThreshold }

            if (activeRows.isEmpty() || activeCols.isEmpty()) return null

            // add a 2% padding around the detected content so we don't clip the note edges
            val top    = (activeRows.first() - h * 0.02f).toInt().coerceAtLeast(0)
            val bottom = (activeRows.last()  + h * 0.02f).toInt().coerceAtMost(h - 1)
            val left   = (activeCols.first() - w * 0.02f).toInt().coerceAtLeast(0)
            val right  = (activeCols.last()  + w * 0.02f).toInt().coerceAtMost(w - 1)

            val cropW = right - left
            val cropH = bottom - top

            // if the crop is almost the full image, the background has no clear boundary so skip it
            val areaRatio = (cropW.toFloat() * cropH) / (w.toFloat() * h)
            if (areaRatio > 0.85f || cropW < 50 || cropH < 50) return null

            Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        } catch (e: Exception) {
            android.util.Log.w("CurrencyClassifier", "Auto-crop failed: ${e.message}")
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun runInference(bitmap: Bitmap, topK: Int): List<Prediction> {
        val input = preprocess(bitmap)
        val outputSize = interpreter.getOutputTensor(0).shape()[1]
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return output[0].indices
            .map { i -> Prediction(formatLabel(classMap[i] ?: "unknown"), output[0][i]) }
            .sortedByDescending { it.confidence }
            .take(topK)
    }

    // resize to 224x224 then normalise each channel to [-1, 1] — matches the training pipeline
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
        val buf = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        scaled.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16 and 0xFF) - PIXEL_MEAN) / PIXEL_STD)  // R
            buf.putFloat(((px shr 8  and 0xFF) - PIXEL_MEAN) / PIXEL_STD)  // G
            buf.putFloat(((px        and 0xFF) - PIXEL_MEAN) / PIXEL_STD)  // B
        }
        buf.rewind()
        return buf
    }

    // converts raw class strings like "R50_new_front" to "R50 New (Front)" for display
    private fun formatLabel(raw: String): String {
        val parts = raw.split("_")
        if (parts.size < 3) return raw
        return "${parts[0]} ${parts[1].replaceFirstChar { it.uppercase() }} (${parts[2].replaceFirstChar { it.uppercase() }})"
    }

    override fun close() = interpreter.close()
}
