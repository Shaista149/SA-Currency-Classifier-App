package com.example.sacurrencyclassifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.sacurrencyclassifier.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentMode = CurrencyClassifier.Mode.NOTES   // notes is the default on launch

    // activity result launchers — registered here so they survive configuration changes
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { launchResult(it) }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
    }

    private val galleryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openGallery()
        else Toast.makeText(this, "Storage permission needed", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SACurrencyClassifier)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModeToggle()
        setupButtons()
    }

    private fun setupModeToggle() {
        binding.toggleGroup.check(binding.btnNotes.id)   // start on notes
        updateModeUI()

        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    binding.btnNotes.id -> CurrencyClassifier.Mode.NOTES
                    binding.btnCoins.id -> CurrencyClassifier.Mode.COINS
                    else -> CurrencyClassifier.Mode.NOTES
                }
                updateModeUI()
            }
        }
    }

    // updates the description text under the toggle to reflect the currently selected mode
    private fun updateModeUI() {
        binding.tvModeDescription.text = when (currentMode) {
            CurrencyClassifier.Mode.NOTES ->
                "Identifies South African banknotes\nR10 · R20 · R50 · R100 · R200"
            CurrencyClassifier.Mode.COINS ->
                "Identifies South African coins\n5c · 10c · 20c · 50c · R1 · R2 · R5"
        }
    }

    private fun setupButtons() {
        binding.btnCamera.setOnClickListener  { checkCameraAndLaunch() }
        binding.btnGallery.setOnClickListener { checkGalleryAndOpen() }
    }

    private fun checkCameraAndLaunch() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> launchCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // READ_MEDIA_IMAGES on API 33+, READ_EXTERNAL_STORAGE on older versions
    private fun checkGalleryAndOpen() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission)
                    == PackageManager.PERMISSION_GRANTED -> openGallery()
            else -> galleryPermissionLauncher.launch(permission)
        }
    }

    private fun launchCamera() {
        val intent = Intent(this, CameraActivity::class.java)
        intent.putExtra(CameraActivity.EXTRA_MODE, currentMode.name)
        startActivity(intent)
    }

    private fun openGallery() {
        galleryLauncher.launch("image/*")
    }

    // gallery picks go straight to CropActivity — same flow as camera captures
    private fun launchResult(uri: Uri) {
        val intent = Intent(this, CropActivity::class.java).apply {
            putExtra(CropActivity.EXTRA_IMAGE_URI, uri.toString())
            putExtra(CropActivity.EXTRA_MODE, currentMode.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
