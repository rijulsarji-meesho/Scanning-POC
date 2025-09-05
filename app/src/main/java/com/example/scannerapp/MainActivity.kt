package com.example.scannerapp  // <-- use your actual package

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.nativescannerapp.ScannerActivity
class MainActivity : ComponentActivity() {

    private val TRUSTED_ORIGINS = setOf(
        "https://8e585a1248c5.ngrok-free.app",
    )

    private var pendingPermissionRequest: PermissionRequest? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermissionRequest
            pendingPermissionRequest = null
            if (granted && req != null && isTrustedOrigin(req.origin)) {
                // Grant only the resources requested (usually video)
                req.grant(req.resources)
            } else {
                req?.deny()
            }
        }
    private lateinit var webView: WebView

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val js = if (result != null)
            "window.onScanResult && window.onScanResult(${jsQuote(result)})"
        else
            "window.onScanCancel && window.onScanCancel()"
        webView.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // This is where getUserMedia lands. Decide and grant/deny.
                    val originAllowed = isTrustedOrigin(request.origin)
                    val wantsVideo = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    // If your page also asks microphone, include RESOURCE_AUDIO_CAPTURE.

                    if (!originAllowed || !wantsVideo) {
                        request.deny()
                        return
                    }

                    // If app already has OS camera permission, grant immediately
                    if (hasOsCameraPermission()) {
                        request.grant(request.resources)
                        return
                    }

                    // Ask the OS for camera permission first; remember the WebView request
                    pendingPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingPermissionRequest == request) pendingPermissionRequest = null
                    super.onPermissionRequestCanceled(request)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, errorCode: Int, description: String, failingUrl: String
                ) {
                    android.util.Log.e("WV", "Error $errorCode $description at $failingUrl")
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }

        setContentView(webView)



        // Load the offline React bundle from /assets
//        webView.loadUrl("file:///android_asset/index.html")
        // If you host it online instead:
//         webView.loadUrl("https://teal-sopapillas-c236a6.netlify.app/")
        webView.loadUrl("https://8e585a1248c5.ngrok-free.app")

    }

    inner class Bridge {
        @JavascriptInterface
        fun openScanner() {
            runOnUiThread { scanLauncher.launch(Unit) }
        }
    }

    private fun jsQuote(s: String): String {
        return org.json.JSONObject.quote(s) // safe JS string quoting
    }

    private fun hasOsCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun isTrustedOrigin(origin: android.net.Uri): Boolean {
        // origin is like: https://host:port
        val scheme = origin.scheme ?: return false
        val host = origin.host ?: return false
        val port = if (origin.port != -1) ":${origin.port}" else ""
        val normalized = "$scheme://$host$port"
        // Compare ignoring trailing slashes
        return TRUSTED_ORIGINS.any { it.trimEnd('/') == normalized }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) {
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

// Small contract to start ScannerActivity and return a String result
class ScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(context, ScannerActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == android.app.Activity.RESULT_OK)
            intent?.getStringExtra("data")
        else null
}
