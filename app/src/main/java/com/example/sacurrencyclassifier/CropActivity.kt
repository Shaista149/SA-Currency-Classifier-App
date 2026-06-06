package com.example.sacurrencyclassifier

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.graphics.RectF
import com.example.sacurrencyclassifier.databinding.ActivityCropBinding
import java.io.File
import java.io.FileOutputStream

class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_MODE = "mode"
    }

    private lateinit var binding: ActivityCropBinding
    private var originalBitmap: Bitmap? = null
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ruler drives the fine rotation of the crop overlay
        binding.rotationRuler.onAngleChanged = { angle ->
            binding.cropView.setCropRotation(angle)
        }

        mode = intent.getStringExtra(EXTRA_MODE) ?: CurrencyClassifier.Mode.NOTES.name
        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI) ?: run {
            finish(); return
        }
        val uri = Uri.parse(uriString)

        val bitmap = loadAndRotate(uri)
        originalBitmap = bitmap
        binding.cropView.setBitmap(bitmap)

        binding.btnConfirm.setOnClickListener { confirmCrop() }
        binding.btnCancel.setOnClickListener  { finish() }

        binding.btnRotate.setOnClickListener {
            val current = originalBitmap ?: return@setOnClickListener

            // record where the crop sits as fractions of the image so we can remap it after rotation
            val oldCrop  = binding.cropView.getCropRect()
            val oldImage = binding.cropView.getImageRect()
            val leftFrac   = (oldCrop.left   - oldImage.left) / oldImage.width()
            val topFrac    = (oldCrop.top    - oldImage.top)  / oldImage.height()
            val rightFrac  = (oldCrop.right  - oldImage.left) / oldImage.width()
            val bottomFrac = (oldCrop.bottom - oldImage.top)  / oldImage.height()

            val matrix = Matrix()
            matrix.postRotate(90f)
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
            originalBitmap = rotated
            binding.cropView.setBitmap(rotated)

            // after 90° clockwise rotation the axes swap: left←top, top←(1-right), right←bottom, bottom←(1-left)
            val newImage = binding.cropView.getImageRect()
            val newCrop = RectF(
                newImage.left + topFrac          * newImage.width(),
                newImage.top  + (1f - rightFrac) * newImage.height(),
                newImage.left + bottomFrac       * newImage.width(),
                newImage.top  + (1f - leftFrac)  * newImage.height()
            )
            binding.cropView.setCropRect(newCrop)
            binding.rotationRuler.reset()   // fine rotation resets when the image flips 90°
        }
    }

    // decodes the bitmap then applies any EXIF rotation so the image is always upright
    private fun loadAndRotate(uri: Uri): Bitmap {
        val stream = contentResolver.openInputStream(uri)!!
        val raw = BitmapFactory.decodeStream(stream)
        stream.close()
        return contentResolver.openInputStream(uri)?.use { exifStream ->
            val exif = ExifInterface(exifStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } ?: raw
    }

    private fun confirmCrop() {
        val cropped = binding.cropView.getCroppedBitmap() ?: run {
            Toast.makeText(this, "Crop failed", Toast.LENGTH_SHORT).show()
            return
        }

        // save to cache then pass the URI to ResultActivity — avoids bitmap size limits on intent extras
        val file = File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        val croppedUri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file)

        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_IMAGE_URI, croppedUri.toString())
            putExtra(ResultActivity.EXTRA_MODE, mode)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
