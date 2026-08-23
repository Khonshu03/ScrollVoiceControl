package com.yhash.scrollvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var toggleSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var modeGroup: RadioGroup

    private val prefs by lazy { getSharedPreferences("scroll_voice_prefs", MODE_PRIVATE) }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayThenAccessibility()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
            toggleSwitch.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleSwitch = findViewById(R.id.toggleSwitch)
        statusText = findViewById(R.id.statusText)
        modeGroup = findViewById(R.id.modeGroup)

        val savedMode = prefs.getString("mode", VoiceListenerService.MODE_VOICE)
        when (savedMode) {
            VoiceListenerService.MODE_CLAP -> findViewById<android.widget.RadioButton>(R.id.modeClap).isChecked = true
            VoiceListenerService.MODE_MOTION -> findViewById<android.widget.RadioButton>(R.id.modeMotion).isChecked = true
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForCheckedId(checkedId)
            prefs.edit().putString("mode", mode).apply()
            if (toggleSwitch.isChecked) {
                startVoiceService(mode)
            }
        }

        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startFlow()
            } else {
                stopService(Intent(this, VoiceListenerService::class.java))
                stopService(Intent(this, OverlayService::class.java))
                statusText.text = "Voice control is OFF"
            }
        }
    }

    private fun modeForCheckedId(checkedId: Int): String = when (checkedId) {
        R.id.modeClap -> VoiceListenerService.MODE_CLAP
        R.id.modeMotion -> VoiceListenerService.MODE_MOTION
        else -> VoiceListenerService.MODE_VOICE
    }

    override fun onResume() {
        super.onResume()
        if (toggleSwitch.isChecked && !isAccessibilityServiceEnabled()) {
            statusText.text = "Waiting for Accessibility permission..."
        }
    }

    private fun startFlow() {
        val mode = prefs.getString("mode", VoiceListenerService.MODE_VOICE) ?: VoiceListenerService.MODE_VOICE
        // Motion mode needs no microphone at all.
        if (mode != VoiceListenerService.MODE_MOTION && !hasRecordAudioPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        val mode = prefs.getString("mode", VoiceListenerService.MODE_VOICE) ?: VoiceListenerService.MODE_VOICE
        startVoiceService(mode)
        val modeLabel = when (mode) {
            VoiceListenerService.MODE_CLAP -> "clap"
            VoiceListenerService.MODE_MOTION -> "motion"
            else -> "voice"
        }
        statusText.text = "Voice control is ON ($modeLabel mode)"
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
