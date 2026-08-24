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
 * Two always-on-top overlay elements shown while a detection mode is running:
 *
 *  1. A small status dot (top-right) whose color tells you at a glance
 *     what's going on:
 *      - mode color (blue/orange/teal) = that detector is armed and watching
 *      - bright green flash = a command/swipe/clap was just recognized
 *      - red = something failed (detector didn't start, or a gesture got
 *        rejected/cancelled) - nothing will happen even though it looks "on"
 *
 *  2. In Camera mode only: a subtle floating cursor circle that tracks your
 *     hand's vertical position in front of the camera in real time, so you
 *     can see what the app currently "sees" while you're swiping in the
 *     air. It fades in while your hand is moving and fades out when it's
 *     still/gone.
 */
class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set

        private val DEFAULT_COLOR = Color.parseColor("#E6555555")
        private val PULSE_COLOR = Color.parseColor("#FF4CAF50")
        private val ERROR_COLOR = Color.parseColor("#FFF44336")
        private val CURSOR_COLOR = Color.parseColor("#CCFFFFFF")

        private val MODE_COLORS = mapOf(
            VoiceListenerService.MODE_VOICE to Color.parseColor("#FF2196F3"),
            VoiceListenerService.MODE_CLAP to Color.parseColor("#FFFF9800"),
            VoiceListenerService.MODE_CAMERA to Color.parseColor("#FF009688")
        )
    }

    private var windowManager: WindowManager? = null
    private var dotView: ImageView? = null
    private var cursorView: ImageView? = null
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
        removeCursor()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun showDot() {
        if (dotView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = (22 * resources.displayMetrics.density).toInt()

        val dot = ImageView(this).apply {
            background = makeCircleDrawable(DEFAULT_COLOR)
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
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

    private fun ensureCursor() {
        if (cursorView != null) return
        val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }

        val sizePx = (34 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            background = makeCircleDrawable(CURSOR_COLOR)
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * resources.displayMetrics.density).toInt()
            y = (resources.displayMetrics.heightPixels / 2)
        }
        wm.addView(view, params)
        cursorView = view
    }

    private fun removeCursor() {
        cursorView?.let { windowManager?.removeView(it) }
        cursorView = null
    }

    /**
     * Moves the cursor to reflect the camera detector's current vertical
     * motion centroid (0f = top of frame, 1f = bottom) and fades it in
     * while `active` (something's moving in front of the camera), fading
     * it out when idle. Safe to call frequently - it's cheap layout param
     * updates, not view recreation.
     */
    fun updateCursor(normalizedY: Float, active: Boolean) {
        ensureCursor()
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        val metrics = resources.displayMetrics
        val topMargin = 140 * metrics.density
        val bottomMargin = 140 * metrics.density
        val usableHeight = (metrics.heightPixels - topMargin - bottomMargin).coerceAtLeast(0f)
        val targetY = (topMargin + normalizedY.coerceIn(0f, 1f) * usableHeight).toInt()

        if (params.y != targetY) {
            params.y = targetY
            windowManager?.updateViewLayout(view, params)
        }
        view.animate().alpha(if (active) 0.7f else 0f).setDuration(150).start()
    }

    /** Sets the persistent "armed and watching" color for the active mode, clearing any prior error. */
    private fun setModeColor(color: Int) {
        baseColor = color
        isError = false
        applyDisplayColor()
    }

    /** Flags the current mode's detector (or a gesture attempt) as failed - dot goes and stays red. */
    fun showError() {
        isError = true
        applyDisplayColor()
    }

    /** Clears a previously flagged error once things are working again (e.g. a gesture actually completes). */
    fun clearError() {
        if (!isError) return
        isError = false
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
