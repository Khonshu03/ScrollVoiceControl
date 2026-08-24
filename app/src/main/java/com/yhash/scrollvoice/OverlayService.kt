package com.yhash.scrollvoice

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * Small always-on-top status dot shown while a detection mode is running.
 * Its color tells you at a glance what's going on:
 *  - mode color (blue/orange/purple/teal) = that detector is armed and watching
 *  - bright green flash = a command/swipe/tilt/clap was just recognized
 *  - red = the detector for the current mode failed to start (e.g. no
 *    accelerometer, camera unavailable, mic init failed) - nothing will
 *    happen even though the toggle looks "on", so don't rely on this mode.
 */
class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set

        private val DEFAULT_COLOR = Color.parseColor("#E6555555")
        private val PULSE_COLOR = Color.parseColor("#FF4CAF50")
        private val ERROR_COLOR = Color.parseColor("#FFF44336")

        private val MODE_COLORS = mapOf(
            VoiceListenerService.MODE_VOICE to Color.parseColor("#FF2196F3"),
            VoiceListenerService.MODE_CLAP to Color.parseColor("#FFFF9800"),
            VoiceListenerService.MODE_MOTION to Color.parseColor("#FF9C27B0"),
            VoiceListenerService.MODE_CAMERA to Color.parseColor("#FF009688")
        )
    }

    private var windowManager: WindowManager? = null
    private var dotView: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var baseColor: Int = DEFAULT_COLOR
    private var isError: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        showDot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(VoiceListenerService.EXTRA_MODE)
        setModeColor(MODE_COLORS[mode] ?: DEFAULT_COLOR)
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        removeDot()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showDot() {
        if (dotView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = (22 * resources.displayMetrics.density).toInt()

        val dot = ImageView(this).apply {
            background = makeCircleDrawable(DEFAULT_COLOR)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * resources.displayMetrics.density).toInt()
            y = (160 * resources.displayMetrics.density).toInt()
        }

        windowManager?.addView(dot, params)
        dotView = dot
    }

    private fun removeDot() {
        dotView?.let { windowManager?.removeView(it) }
        dotView = null
    }

    /** Sets the persistent "armed and watching" color for the active mode, clearing any prior error. */
    private fun setModeColor(color: Int) {
        baseColor = color
        isError = false
        applyDisplayColor()
    }

    /** Flags the current mode's detector as failed to start - dot goes and stays red. */
    fun showError() {
        isError = true
        applyDisplayColor()
    }

    /** Brief green flash for a recognized command, then back to the mode color (or red, if in error state). */
    fun pulse() {
        val view = dotView ?: return
        view.background = makeCircleDrawable(PULSE_COLOR)
        handler.postDelayed({
            applyDisplayColor()
        }, 350)
    }

    private fun applyDisplayColor() {
        dotView?.background = makeCircleDrawable(if (isError) ERROR_COLOR else baseColor)
    }

    private fun makeCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
