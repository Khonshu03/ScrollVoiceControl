package com.yhash.scrollvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var toggleSwitch: SwitchMaterial
    private lateinit var statusText: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            proceedAfterPermission()
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

        toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startFlow()
            } else {
                stopService(Intent(this, VoiceListenerService::class.java))
                statusText.text = "Voice control is OFF"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect real state in case the user flipped the accessibility
        // setting while this activity was in the background.
        if (toggleSwitch.isChecked && !isAccessibilityServiceEnabled()) {
            statusText.text = "Waiting for Accessibility permission..."
        }
    }

    private fun startFlow() {
        if (!hasRecordAudioPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
            // Leave the switch on; user will come back after enabling it.
            statusText.text = "Waiting for Accessibility permission..."
            return
        }
        val serviceIntent = Intent(this, VoiceListenerService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        statusText.text = "Voice control is ON - say \"down\", \"up\", or \"back\""
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
