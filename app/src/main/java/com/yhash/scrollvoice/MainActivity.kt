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
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var statusDot: ImageView
    private lateinit var modeGroup: RadioGroup
    private lateinit var stopButton: Button
    private lateinit var stopGlow: GlowView
    private lateinit var cardGlow: GlowView

    private var isRunning = false

    private val prefs by lazy { getSharedPreferences("scroll_voice_prefs", MODE_PRIVATE) }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayThenAccessibility()
        } else {
            val mode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA)
            val permissionName = if (mode == VoiceListenerService.MODE_CAMERA) "Camera" else "Microphone"
            Toast.makeText(this, "$permissionName permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        modeGroup = findViewById(R.id.modeGroup)
        stopButton = findViewById(R.id.stopButton)
        stopGlow = findViewById(R.id.stopGlow)
        cardGlow = findViewById(R.id.cardGlow)

        val savedMode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA)
        when (savedMode) {
            VoiceListenerService.MODE_VOICE -> findViewById<android.widget.RadioButton>(R.id.modeVoice).isChecked = true
            VoiceListenerService.MODE_CLAP -> findViewById<android.widget.RadioButton>(R.id.modeClap).isChecked = true
            else -> findViewById<android.widget.RadioButton>(R.id.modeCamera).isChecked = true
        }

        // Picking a mode starts it right away - no separate on/off switch.
        // Picking a different mode while one is already running just
        // restarts with the new mode.
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForCheckedId(checkedId)
            prefs.edit().putString("mode", mode).apply()
            startFlow()
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, VoiceListenerService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            isRunning = false
            stopButton.visibility = android.view.View.GONE
            statusText.text = "Voice control is OFF"
            statusDot.setImageResource(R.drawable.dot_off)
            cardGlow.animate().alpha(0.15f).setDuration(300).start()
        }
    }

    private fun modeForCheckedId(checkedId: Int): String = when (checkedId) {
        R.id.modeVoice -> VoiceListenerService.MODE_VOICE
        R.id.modeClap -> VoiceListenerService.MODE_CLAP
        else -> VoiceListenerService.MODE_CAMERA
    }

    override fun onResume() {
        super.onResume()
        if (isRunning && !isAccessibilityServiceEnabled()) {
            statusText.text = "Waiting for Accessibility permission..."
        }
    }

    private fun startFlow() {
        val mode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA) ?: VoiceListenerService.MODE_CAMERA
        when (mode) {
            VoiceListenerService.MODE_CAMERA -> {
                if (hasCameraPermission()) {
                    checkOverlayThenAccessibility()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            else -> {
                if (hasRecordAudioPermission()) {
                    checkOverlayThenAccessibility()
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    private fun checkOverlayThenAccessibility() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Allow \"display over other apps\" so the status dot/cursor can show",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            statusText.text = "Waiting for overlay permission..."
            return
        }
        proceedAfterPermission()
    }

    private fun proceedAfterPermission() {
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
        val mode = prefs.getString("mode", VoiceListenerService.MODE_CAMERA) ?: VoiceListenerService.MODE_CAMERA
        startVoiceService(mode)
        isRunning = true
        stopButton.visibility = android.view.View.VISIBLE
        val modeLabel = when (mode) {
            VoiceListenerService.MODE_CLAP -> "clap"
            VoiceListenerService.MODE_CAMERA -> "camera"
            else -> "voice"
        }
        statusText.text = "Voice control is ON ($modeLabel mode)"
        statusDot.setImageResource(R.drawable.dot_on)
        applyModeStyle(mode)
    }

    /** Recolors the Stop button and both glows to match whichever mode is actually running. */
    private fun applyModeStyle(mode: String) {
        val density = resources.displayMetrics.density
        val palette = ModeStyle.paletteFor(this, mode)
        stopButton.background = ModeStyle.buildStopButtonDrawable(
            cornerRadiusPx = 28f * density,
            glossHeightPx = (20f * density).toInt(),
            palette = palette
        )
        stopGlow.glowColor = palette.base
        cardGlow.glowColor = palette.base
        cardGlow.animate().alpha(0.5f).setDuration(300).start()
    }

    private fun startVoiceService(mode: String) {
        val serviceIntent = Intent(this, VoiceListenerService::class.java).apply {
            putExtra(VoiceListenerService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

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
