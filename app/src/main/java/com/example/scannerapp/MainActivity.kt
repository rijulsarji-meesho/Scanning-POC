package com.example.scannerapp // Use your actual package

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.PermissionRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private val TRUSTED_ORIGINS = setOf(
        "https://7ba988ab857d.ngrok-free.app", // Replace with your actual trusted origin
    )

    private var pendingPermissionRequest: PermissionRequest? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermissionRequest
            pendingPermissionRequest = null
            if (granted && req != null && isTrustedOrigin(req.origin)) {
                req.grant(req.resources)
            } else {
                req?.deny()
            }
        }

    private lateinit var webView: WebView
    private lateinit var scannerContainer: View // Or FrameLayout, if you need to add PreviewView programmatically
    private lateinit var cameraPreviewView: PreviewView

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var isScannerVisible = false

    private val isScanningPaused = AtomicBoolean(false)
    private val pauseHandler = Handler(Looper.getMainLooper())
    private val scanPauseDurationMs = 5000L // 5 seconds


    @SuppressLint("SetJavaScriptEnabled", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Assuming you created activity_main.xml

        webView = findViewById(R.id.webView)
        scannerContainer = findViewById(R.id.scanner_container) // Your FrameLayout from XML
        cameraPreviewView = PreviewView(this) // Create PreviewView programmatically
        // Or find it if you added it directly to activity_main.xml:
        // cameraPreviewView = findViewById(R.id.camera_preview_view)


        // Add PreviewView to the container if creating programmatically
        if (scannerContainer is android.widget.FrameLayout) {
            (scannerContainer as android.widget.FrameLayout).addView(
                cameraPreviewView,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }


        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        setupWebView()

        // Load your web content
        // webView.loadUrl("file:///android_asset/index.html")
        webView.loadUrl(TRUSTED_ORIGINS.first()) // Load your trusted origin
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Important for HTTPS sites with resources loaded over HTTP, if any.
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.settings.javaScriptCanOpenWindowsAutomatically = true // If your web app needs this
        webView.settings.allowFileAccess = false // Security best practice
        webView.settings.allowContentAccess = true // For file:///android_asset/ if needed

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val originAllowed = isTrustedOrigin(request.origin)
                val wantsVideo =
                    request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

                if (!originAllowed || !wantsVideo) {
                    request.deny()
                    return
                }

                if (hasOsCameraPermission()) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermissionRequest == request) pendingPermissionRequest = null
                super.onPermissionRequestCanceled(request)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, errorCode: Int, description: String, failingUrl: String
            ) {
                android.util.Log.e("WV", "Error $errorCode $description at $failingUrl")
            }
        }
        webView.addJavascriptInterface(Bridge(), "Android")
    }

    inner class Bridge {
        @JavascriptInterface
        fun openScanner() {
            runOnUiThread {
                if (hasOsCameraPermission()) {
                    showScannerView()
                } else {
                    // Request permission, then show scanner if granted
                    // You might want to pass a callback or use an event bus
                    // for more complex permission handling flows.
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    // For this example, we'll assume the user grants it and tries again
                    // or you handle the result of cameraPermissionLauncher to call showScannerView.
                    // A more robust solution would handle the grant/deny explicitly here.
                }
            }
        }

        @JavascriptInterface
        fun closeScanner() { // Add a way to close from JS if needed
            runOnUiThread {
                hideScannerView()
            }
        }
    }

    private fun showScannerView() {
        if (isScannerVisible) return
        isScannerVisible = true
        scannerContainer.visibility = View.VISIBLE
        // Adjust WebView layout if necessary (e.g., using layout weights or constraints)
        // For LinearLayout with weights, you might need to update LayoutParams
        val webViewLayoutParams = webView.layoutParams as android.widget.LinearLayout.LayoutParams
        webViewLayoutParams.weight = 0.5f // Example: give half height to WebView
        webView.layoutParams = webViewLayoutParams

        val scannerLayoutParams =
            scannerContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        scannerLayoutParams.weight = 0.5f // Example: give half height to Scanner
        scannerContainer.layoutParams = scannerLayoutParams


        startCamera()
    }

    private fun hideScannerView() {
        if (!isScannerVisible) return
        isScannerVisible = false
        scannerContainer.visibility = View.GONE
        stopCamera() // Ensure camera is released
        // Reset WebView to full height
        val webViewLayoutParams = webView.layoutParams as android.widget.LinearLayout.LayoutParams
        webViewLayoutParams.weight = 1.0f // WebView takes full weight
        webView.layoutParams = webViewLayoutParams

        val scannerLayoutParams =
            scannerContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        scannerLayoutParams.weight = 0.0f // Scanner takes no weight
        scannerContainer.layoutParams = scannerLayoutParams

        // Optionally, inform JS that scanner was closed
        webView.evaluateJavascript("window.onScannerClosed && window.onScannerClosed()", null)
    }


    private fun startCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreviewAndAnalysis(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll() // Release the camera
        }, ContextCompat.getMainExecutor(this))
    }

    private var toneGenerator: ToneGenerator? = null

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindPreviewAndAnalysis(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(cameraPreviewView.surfaceProvider)

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: RuntimeException) {
            android.util.Log.w("ScannerApp", "Failed to create ToneGenerator", e)
            toneGenerator = null // Handle case where ToneGenerator can't be created
        }

        imageAnalysis.setAnalyzer(cameraExecutor, { imageProxy ->
            if (isScanningPaused.get()) {
                imageProxy.close() // Still need to close the proxy
                return@setAnalyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                processImage(image, onSuccess = { result ->
                    // This callback runs on a background thread.
                    // Switch to main thread to update UI or WebView.
                    runOnUiThread {
                        if (isScanningPaused.get()) { // Double check, in case it was paused while this was enqueued
                            return@runOnUiThread
                        }

                        playBeep()
                        isScanningPaused.set(true);
                        if (isScannerVisible) { // Only process if scanner is still open
                            val js =
                                "window.onScanResult && window.onScanResult(${jsQuote(result)})"
                            webView.evaluateJavascript(js, null)
                            // Consider if you want to automatically hide the scanner after a successful scan
                            // hideScannerView()
                        }

                        // Schedule unpause
                        pauseHandler.removeCallbacksAndMessages(null);
                        pauseHandler.postDelayed({
                            isScanningPaused.set(false)
                            android.util.Log.d("ScannerApp", "Scanning resumed after pause.")
                        }, scanPauseDurationMs)
                    }
                }, onFailure = {
                    // Optional: handle scan failure/no barcode found in frame
                }, onComplete = {
                    imageProxy.close() // IMPORTANT: Close the ImageProxy
                })
            } else {
                imageProxy.close() // Ensure closed even if mediaImage is null
            }
        })

        try {
            cameraProvider.unbindAll() // Unbind use cases before rebinding
            cameraProvider.bindToLifecycle(
                this as LifecycleOwner, // Activity is a LifecycleOwner
                cameraSelector,
                preview,
                imageAnalysis // Add imageAnalysis use case
            )
        } catch (exc: Exception) {
            android.util.Log.e("ScannerApp", "Use case binding failed", exc)
        }
    }

    private var lastValue: String? = null
    private var stableCount = 0
    private val requiredStableReads = 2

    private fun processImage(
        image: InputImage,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        onComplete: () -> Unit
    ) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS) // Adjust as needed
            .build()
        val scanner = BarcodeScanning.getClient(options)

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

                    if (stableCount >= requiredStableReads) {
                        onSuccess(value)
                    }
                }

//                if (barcodes.isNotEmpty()) {
//                    barcodes.firstNotNullOfOrNull { it.rawValue }?.let {
//                        onSuccess(it)
//                    }
//                }
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
            .addOnCompleteListener {
                onComplete() // This is where imageProxy.close() will be called
                scanner.close() // Close the ML Kit scanner
            }
    }

    private fun playBeep() {
        if (!isScanningPaused.get()) {
            Log.d("ScannerApp", "playBeep() called, toneGenerator is: $toneGenerator")
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 250) // 150ms duration
        }
    }

    private fun jsQuote(s: String?): String {
        return org.json.JSONObject.quote(s ?: "") // Handle null safely
    }

    private fun hasOsCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun isTrustedOrigin(origin: android.net.Uri): Boolean {
        val scheme = origin.scheme ?: return false
        val host = origin.host ?: return false
        val port = if (origin.port != -1) ":${origin.port}" else ""
        val normalized = "$scheme://$host$port"
        return TRUSTED_ORIGINS.any { it.trimEnd('/') == normalized }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Consider if you need to hideScannerView() or just stopCamera() if activity is paused
        if (isScannerVisible) {
            stopCamera() // Release camera when paused if scanner is active
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (isScannerVisible && hasOsCameraPermission()) {
            startCamera() // Restart camera if scanner was active and permission is granted
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android") // Clean up JS interface
            webView.stopLoading()
            webView.webViewClient = WebViewClient() // Set a dummy client to avoid leaks
            webView.webChromeClient = WebChromeClient()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        }
    }
}
