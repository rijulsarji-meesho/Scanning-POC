package com.example.nativescannerapp  // <-- use your actual package

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    // We scan only QR + EANs (expand if you need more)
    private val scanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .build()
        BarcodeScanning.getClient(options)
    }

    private val isDone = AtomicBoolean(false)
    private var lastValue: String? = null
    private var stableCount = 0
    private val requiredStableReads = 2 // "consistent confident result" = 2 consecutive frames

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContentView(previewView)

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val executor = Executors.newSingleThreadExecutor()

            analysis.setAnalyzer(executor) { imageProxy ->
                if (isDone.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(
                    mediaImage, imageProxy.imageInfo.rotationDegrees
                )

                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        val first = barcodes.firstOrNull()
                        val value = first?.rawValue

                        if (value != null) {
                            if (value == lastValue) {
                                stableCount++
                            } else {
                                lastValue = value
                                stableCount = 1
                            }

                            if (stableCount >= requiredStableReads && isDone.compareAndSet(false, true)) {
                                // Return result to MainActivity → WebView
                                setResult(RESULT_OK, Intent().putExtra("data", value))
                                imageProxy.close()
                                finish()
                                Log.e("VALUE", value);
                                return@addOnSuccessListener
                            }
                        }
                        imageProxy.close()
                    }
                    .addOnFailureListener {
                        imageProxy.close()
                    }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }
}
