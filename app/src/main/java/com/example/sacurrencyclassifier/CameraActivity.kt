package com.example.sacurrencyclassifier

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.sacurrencyclassifier.databinding.ActivityCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import android.view.View

class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        private const val TAG = "CameraActivity"
    }

    private var camera: Camera? = null
    private var isTorchOn = false

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var mode: CurrencyClassifier.Mode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = CurrencyClassifier.Mode.valueOf(
            intent.getStringExtra(EXTRA_MODE) ?: CurrencyClassifier.Mode.NOTES.name
        )

        cameraExecutor = Executors.newSingleThreadExecutor()   // camera ops run on a dedicated thread

        binding.tvCameraMode.text = "${mode.label} mode"
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnBack.setOnClickListener { finish() }

        startCamera()
        setupTapToFocus()
        setupTorchToggle()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // minimise latency so the shutter feels instant
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(windowManager.defaultDisplay.rotation)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // timestamp in filename so repeated captures don't overwrite each other
        val photoFile = File(
            cacheDir,
            "capture_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = FileProvider.getUriForFile(
                        this@CameraActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    launchResult(uri)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                    Toast.makeText(this@CameraActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun setupTapToFocus() {
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)   // auto-cancel after 3s so it doesn't lock permanently
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
                showFocusRing(event.x, event.y)
            }
            true
        }
    }

    // brief focus ring animation at tap location — fades out over 800ms
    private fun showFocusRing(x: Float, y: Float) {
        binding.focusRing.apply {
            translationX = x - width / 2f
            translationY = y - height / 2f
            visibility = View.VISIBLE
            alpha = 1f
            animate().alpha(0f).setDuration(800).withEndAction {
                visibility = View.GONE
            }.start()
        }
    }

    private fun setupTorchToggle() {
        binding.btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            binding.btnTorch.icon = ContextCompat.getDrawable(
                this,
                if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        }
    }

    private fun launchResult(uri: Uri) {
        val intent = Intent(this, CropActivity::class.java).apply {
            putExtra(CropActivity.EXTRA_IMAGE_URI, uri.toString())
            putExtra(CropActivity.EXTRA_MODE, mode.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        camera?.cameraControl?.enableTorch(false)   // always turn off torch when leaving
        cameraExecutor.shutdown()
    }
}
