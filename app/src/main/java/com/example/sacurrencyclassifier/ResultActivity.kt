package com.example.sacurrencyclassifier

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.sacurrencyclassifier.databinding.ActivityResultBinding
import kotlinx.coroutines.*

class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_MODE      = "mode"
    }

    private lateinit var binding: ActivityResultBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())   // tied to activity lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val modeName  = intent.getStringExtra(EXTRA_MODE) ?: CurrencyClassifier.Mode.NOTES.name
        val mode      = CurrencyClassifier.Mode.valueOf(modeName)

        if (uriString == null) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.tvMode.text = "${mode.label} classifier"

        runClassification(Uri.parse(uriString), mode)
    }

    private fun runClassification(uri: Uri, mode: CurrencyClassifier.Mode) {
        binding.progressBar.visibility  = View.VISIBLE
        binding.resultsGroup.visibility = View.GONE

        scope.launch {
            try {
                val predictions = withContext(Dispatchers.IO) {
                    // decode bitmap on IO thread — decoding large images on Main causes jank
                    val stream = contentResolver.openInputStream(uri)
                        ?: error("Cannot open image")
                    val bitmap = BitmapFactory.decodeStream(stream)
                    stream.close()

                    withContext(Dispatchers.Main) {
                        binding.imgPreview.setImageBitmap(bitmap)   // show preview while inference runs
                    }

                    val classifier = CurrencyClassifier(applicationContext, mode)
                    val results = classifier.classify(bitmap, topK = 3)
                    classifier.close()
                    results
                }

                binding.progressBar.visibility  = View.GONE
                binding.resultsGroup.visibility = View.VISIBLE
                displayResults(predictions)

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@ResultActivity,
                    "Classification failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayResults(predictions: List<CurrencyClassifier.Prediction>) {
        if (predictions.isEmpty()) return

        val top = predictions[0]

        binding.tvTopLabel.text      = top.label
        binding.tvTopConfidence.text = "%.1f%%".format(top.confidence * 100)
        binding.progressTop.progress = (top.confidence * 100).toInt()

        // colour the confidence readout: green ≥70%, amber ≥40%, red below that
        val colour = when {
            top.confidence >= 0.70f -> getColor(R.color.confidence_high)
            top.confidence >= 0.40f -> getColor(R.color.confidence_mid)
            else                    -> getColor(R.color.confidence_low)
        }
        binding.tvTopConfidence.setTextColor(colour)
        binding.progressTop.setIndicatorColor(colour)

        if (predictions.size > 1) {
            val p2 = predictions[1]
            binding.tvLabel2.text         = p2.label
            binding.tvConf2.text          = "%.1f%%".format(p2.confidence * 100)
            binding.progressBar2.progress = (p2.confidence * 100).toInt()
        }

        if (predictions.size > 2) {
            val p3 = predictions[2]
            binding.tvLabel3.text         = p3.label
            binding.tvConf3.text          = "%.1f%%".format(p3.confidence * 100)
            binding.progressBar3.progress = (p3.confidence * 100).toInt()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()   // cancel any in-flight inference if the activity is dismissed
    }
}
