package com.yhash.scrollvoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val prefs by lazy { getSharedPreferences("scroll_voice_prefs", MODE_PRIVATE) }
    private var isActive = false

    // Which mode we're actively mid-permission-flow for, so the launcher
    // callbacks below know what to resume once permission is granted.
    private var pendingMode: String? = null

    private val requestMicPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayThenAccessibility()
        } else {
            Toast.makeText(this, "Microphone permission is required for this mode", Toast.LENGTH_SHORT).show()
            pendingMode = null
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayThenAccessibility()
        } else {
            Toast.makeText(this, "Camera permission is required for this mode", Toast.LENGTH_SHORT).show()
            pendingMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun modeForCheckedId(mode: String): String = when (mode) {
        VoiceListenerService.MODE_CLAP -> VoiceListenerService.MODE_CLAP
        VoiceListenerService.MODE_CAMERA -> VoiceListenerService.MODE_CAMERA
        else -> VoiceListenerService.MODE_VOICE
    }

    private fun beginPermissionFlow(mode: String) {
        pendingMode = mode
        val needsMic = mode == VoiceListenerService.MODE_VOICE || mode == VoiceListenerService.MODE_CLAP
        val needsCamera = mode == VoiceListenerService.MODE_CAMERA

        if (needsMic && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (needsCamera && !hasPermission(Manifest.permission.CAMERA)) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        checkOverlayThenAccessibility()
    }

    private fun checkOverlayThenAccessibility() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow \"display over other apps\" so the status dot can show",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        proceedAfterPermissions()
    }

    private fun proceedAfterPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Turn on \"Scroll Voice Control\" under Accessibility settings",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val mode = pendingMode ?: return
        pendingMode = null
        startVoiceService(mode)
        isActive = true
    }

    private fun startVoiceService(mode: String) {
        val serviceIntent = Intent(this, VoiceListenerService::class.java).apply {
            putExtra(VoiceListenerService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopEverything() {
        stopService(Intent(this, VoiceListenerService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        isActive = false
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ScrollAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * Bridge exposed to the web UI as `window.Android`. Important: these
     * methods are invoked by the WebView on a background JS-bridge thread,
     * NOT the main thread - anything touching permissions, activities, or
     * services has to hop back to the main thread via runOnUiThread first,
     * or it can silently misbehave or crash.
     */
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun setServiceActive(active: Boolean) {
            runOnUiThread {
                if (active) {
                    val mode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA)
                        ?: VoiceListenerService.MODE_CAMERA
                    beginPermissionFlow(mode)
                } else {
                    stopEverything()
                }
            }
        }

        @JavascriptInterface
        fun setMode(mode: String) {
            runOnUiThread {
                val normalized = modeForCheckedId(mode)
                prefs.edit().putString("mode", normalized).apply()
                // Only restart live if we're already running (permissions
                // for this app are already granted at that point). If not
                // active, this just remembers the choice for next start -
                // it must NOT bypass beginPermissionFlow's checks.
                if (isActive) {
                    startVoiceService(normalized)
                }
            }
        }
    }
}
