package com.yhash.scrollvoice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

class VoiceListenerService : Service() {

    companion object {
        private const val TAG = "VoiceListenerService"
        private const val CHANNEL_ID = "voice_listener_channel"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_MODE = "mode"
        const val MODE_VOICE = "voice"
        const val MODE_CLAP = "clap"
        const val MODE_MOTION = "motion"
        const val MODE_CAMERA = "camera"

        private val COMMANDS = mapOf(
            "down" to "down",
            "next" to "down",
            "up" to "up",
            "back" to "back",
            "previous" to "up"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var clapDetector: ClapDetector? = null
    private var motionDetector: MotionDetector? = null
    private var handGestureDetector: HandGestureDetector? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var mode = MODE_VOICE
    private var lastCommandTime = 0L

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_VOICE
        startForegroundForMode(mode)
        startOverlay()
        if (!isRunning) {
            isRunning = true
            when (mode) {
                MODE_CLAP -> {
                    requestDucking()
                    startClapDetection()
                }
                MODE_MOTION -> startMotionDetection()
                MODE_CAMERA -> startCameraGestureDetection()
                else -> {
                    requestDucking()
                    startListening()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        clapDetector?.stop()
        clapDetector = null
        motionDetector?.stop()
        motionDetector = null
        handGestureDetector?.stop()
        handGestureDetector = null
        abandonDucking()
        stopOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /**
     * The manifest declares foregroundServiceType="microphone|camera" since
     * this single service can run in either mode. But on Android 10+, if
     * startForeground() is called WITHOUT an explicit type, the system
     * demands every type listed in the manifest be satisfied - meaning
     * Motion mode (which needs neither permission) would still require both
     * mic and camera permissions and crash. Passing the explicit type here
     * for each mode avoids that.
     */
    private fun startForegroundForMode(mode: String) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = when (mode) {
                MODE_VOICE, MODE_CLAP -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                MODE_CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE // MODE_MOTION needs neither
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestDucking() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonDucking() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private fun startOverlay() {
        if (android.provider.Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java).apply {
                putExtra(EXTRA_MODE, mode)
            })
        }
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun startClapDetection() {
        clapDetector = ClapDetector(
            onClapBurst = { count ->
                val direction = when (count) {
                    1 -> "down"
                    2 -> "up"
                    3 -> "back"
                    else -> null
                }
                if (direction != null) {
                    Log.d(TAG, "Clap burst: $count -> $direction")
                    ScrollAccessibilityService.instance?.performScroll(direction)
                    OverlayService.instance?.pulse()
                }
            },
            onError = { OverlayService.instance?.showError() }
        )
        clapDetector?.start()
    }

    private fun startMotionDetection() {
        motionDetector = MotionDetector(
            context = this,
            onTilt = { direction ->
                Log.d(TAG, "Motion tilt -> $direction")
                ScrollAccessibilityService.instance?.performScroll(direction)
                OverlayService.instance?.pulse()
            },
            onError = { OverlayService.instance?.showError() }
        )
        motionDetector?.start()
    }

    private fun startCameraGestureDetection() {
        handGestureDetector = HandGestureDetector(
            context = this,
            onSwipe = { direction ->
                Log.d(TAG, "Camera swipe -> $direction")
                ScrollAccessibilityService.instance?.performScroll(direction)
                OverlayService.instance?.pulse()
            },
            onPositionUpdate = { x, y, pointing ->
                OverlayService.instance?.updateCursor(x, y, pointing)
            },
            onError = { OverlayService.instance?.showError() }
        )
        handGestureDetector?.start()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device")
            OverlayService.instance?.showError()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    handleResults(results)
                    restartListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // React to partial (in-progress) results too, so a
                    // command fires as soon as it's heard rather than
                    // waiting for the recognizer to decide speech ended.
                    handleResults(partialResults)
                }

                override fun onError(error: Int) {
                    restartListening()
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        runRecognizer()
    }

    private fun runRecognizer() {
        if (!isRunning) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListening() {
        if (!isRunning) return
        handler.postDelayed({ runRecognizer() }, 300)
    }

    private fun handleResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        val now = System.currentTimeMillis()
        if (now - lastCommandTime < 1200) return // debounce partial + final firing twice

        for (phrase in matches) {
            val lower = phrase.lowercase()
            for ((keyword, direction) in COMMANDS) {
                if (lower.contains(keyword)) {
                    Log.d(TAG, "Command recognized: '$phrase' -> $direction")
                    lastCommandTime = now
                    ScrollAccessibilityService.instance?.performScroll(direction)
                    OverlayService.instance?.pulse()
                    return
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val text = when (mode) {
            MODE_CLAP -> "Listening for claps"
            MODE_MOTION -> "Watching for phone tilt"
            MODE_CAMERA -> "Watching for a pointing finger"
            else -> getString(R.string.notification_text_listening)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
