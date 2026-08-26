package com.yhash.scrollvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var cardGlow: GlowView
    private lateinit var stopGlow: GlowView
    private lateinit var stopButton: Button

    private val prefs by lazy { getSharedPreferences("scroll_voice_prefs", MODE_PRIVATE) }
    private var isActive = false
    private var pendingMode: String? = null // mode we're mid-permission-flow for

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

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        modeGroup = findViewById(R.id.modeGroup)
        cardGlow = findViewById(R.id.cardGlow)
        stopGlow = findViewById(R.id.stopGlow)
        stopButton = findViewById(R.id.stopButton)

        // Restore the last-selected mode BEFORE attaching the listener below,
        // so setting it here doesn't itself trigger a start.
        val savedMode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA) ?: VoiceListenerService.MODE_CAMERA
        selectRadioForMode(savedMode)
        applyModeStyling(savedMode)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForCheckedId(checkedId)
            prefs.edit().putString("mode", mode).apply()
            applyModeStyling(mode)
            // Tapping a mode chip is the "start" action - engage immediately
            // (or switch live if something's already running).
            beginPermissionFlow(mode)
        }

        stopButton.setOnClickListener { stopEverything() }
    }

    override fun onResume() {
        super.onResume()
        if (isActive && !isAccessibilityServiceEnabled()) {
            statusText.text = "Waiting for Accessibility permission..."
        }
    }

    private fun modeForCheckedId(checkedId: Int): String = when (checkedId) {
        R.id.modeClap -> VoiceListenerService.MODE_CLAP
        R.id.modeCamera -> VoiceListenerService.MODE_CAMERA
        else -> VoiceListenerService.MODE_VOICE
    }

    private fun selectRadioForMode(mode: String) {
        val id = when (mode) {
            VoiceListenerService.MODE_CLAP -> R.id.modeClap
            VoiceListenerService.MODE_CAMERA -> R.id.modeCamera
            else -> R.id.modeVoice
        }
        findViewById<RadioButton>(id).isChecked = true
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
            statusText.text = "Waiting for overlay permission..."
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
            statusText.text = "Waiting for Accessibility permission..."
            return
        }
        val mode = pendingMode ?: return
        pendingMode = null
        startVoiceService(mode)
        isActive = true
        updateActiveUi(mode)
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
        statusDot.setImageResource(R.drawable.dot_off)
        statusText.text = "Voice control is OFF"
        stopButton.visibility = android.view.View.GONE
    }

    private fun updateActiveUi(mode: String) {
        statusDot.setImageResource(R.drawable.dot_on)
        val modeLabel = when (mode) {
            VoiceListenerService.MODE_CLAP -> "clap"
            VoiceListenerService.MODE_CAMERA -> "camera"
            else -> "voice"
        }
        statusText.text = "Voice control is ON ($modeLabel mode)"
        stopButton.visibility = android.view.View.VISIBLE
    }

    private fun applyModeStyling(mode: String) {
        val palette = ModeStyle.paletteFor(this, mode)
        cardGlow.glowColor = palette.glow
        stopGlow.glowColor = palette.glow

        val density = resources.displayMetrics.density
        stopButton.background = ModeStyle.buildStopButtonDrawable(
            cornerRadiusPx = 28f * density,
            glossHeightPx = (28f * density).toInt(),
            palette = palette
        )
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
}
