package com.yhash.scrollvoice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Detects a forward or backward tilt of the phone using the accelerometer -
 * no touching the screen at all. Tilt the top of the phone away from you
 * (like nodding it forward) to scroll down; tilt it back toward you to
 * scroll up. No camera, no mic - completely immune to video/audio noise.
 */
class MotionDetector(
    context: Context,
    private val onTilt: (direction: String) -> Unit,
    private val onError: (() -> Unit)? = null
) : SensorEventListener {

    companion object {
        private const val TAG = "MotionDetector"

        // How far (in m/s^2) the smoothed Y-axis reading must move from
        // neutral to count as a deliberate tilt. Raise this if it triggers
        // too easily just from normal hand movement; lower it if it's hard
        // to trigger.
        private const val TILT_THRESHOLD = 3.5

        // Must return this close to neutral before another tilt can register,
        // so holding the phone tilted doesn't repeat-fire.
        private const val NEUTRAL_BAND = 1.5

        // Minimum time between triggers regardless of motion, as a safety net.
        private const val COOLDOWN_MS = 700L

        private const val SMOOTHING_ALPHA = 0.15
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var baselineY: Double? = null
    private var smoothedY = 0.0
    private var armed = true // true = ready to fire, false = waiting to return to neutral
    private var lastTriggerTime = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            Log.e(TAG, "No accelerometer available on this device")
            onError?.invoke()
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val y = event.values[1].toDouble()

        // First reading establishes the "phone held normally" baseline.
        if (baselineY == null) {
            baselineY = y
            smoothedY = y
            return
        }

        smoothedY = SMOOTHING_ALPHA * y + (1 - SMOOTHING_ALPHA) * smoothedY
        val delta = smoothedY - (baselineY ?: 0.0)

        val now = System.currentTimeMillis()

        if (armed && now - lastTriggerTime > COOLDOWN_MS) {
            when {
                delta > TILT_THRESHOLD -> {
                    fireTilt("up")
                    armed = false
                }
                delta < -TILT_THRESHOLD -> {
                    fireTilt("down")
                    armed = false
                }
            }
        } else if (!armed && kotlin.math.abs(delta) < NEUTRAL_BAND) {
            // Phone has returned close to level - ready for the next tilt.
            armed = true
        }
    }

    private fun fireTilt(direction: String) {
        lastTriggerTime = System.currentTimeMillis()
        Log.d(TAG, "Tilt detected -> $direction")
        onTilt(direction)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
