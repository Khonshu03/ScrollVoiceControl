package com.yhash.scrollvoice

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
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
            VoiceListenerService.MODE_VOICE to Color.parseColor("#FFEC4899"),
            VoiceListenerService.MODE_CLAP to Color.parseColor("#FF6366F1"),
            VoiceListenerService.MODE_CAMERA to Color.parseColor("#FFF43F5E")
        )

        // Hand-landmark inference only yields ~10-20 position updates/sec.
        // Snapping the overlay straight to each new point makes it visibly
        // teleport on a 90/120Hz screen even though the position itself is
        // already smoothed upstream. Animating between points at this
        // duration bridges the gap so motion reads as continuous.
        private const val CURSOR_MOVE_DURATION_MS = 90L
    }

    private var windowManager: WindowManager? = null
    private var dotView: ImageView? = null
    private var cursorView: ImageView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var moveAnimator: ValueAnimator? = null

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
        moveAnimator?.cancel()
        moveAnimator = null
        cursorView?.let { windowManager?.removeView(it) }
        cursorView = null
    }

    /**
     * Moves the cursor to the tracked index fingertip position (0f-1f on
     * each axis) and fades it in only while a pointing gesture is actively
     * detected, fading out otherwise. Safe to call frequently - it's cheap
     * layout param updates, not view recreation.
     */
    fun updateCursor(normalizedX: Float, normalizedY: Float, pointing: Boolean) {
        ensureCursor()
        val view = cursorView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return

        val metrics = resources.displayMetrics
        val topMargin = 140 * metrics.density
        val bottomMargin = 140 * metrics.density
        val usableHeight = (metrics.heightPixels - topMargin - bottomMargin).coerceAtLeast(0f)
        val targetY = (topMargin + normalizedY.coerceIn(0f, 1f) * usableHeight).toInt()
        val targetX = (normalizedX.coerceIn(0f, 1f) * metrics.widthPixels).toInt()

        if (params.x != targetX || params.y != targetY) {
            animateCursorTo(view, params, targetX, targetY)
        }
        view.animate().alpha(if (pointing) 0.85f else 0f).setDuration(150).start()
    }

    /** Glides the overlay from its current on-screen position to the new target instead of snapping to it. */
    private fun animateCursorTo(view: ImageView, params: WindowManager.LayoutParams, targetX: Int, targetY: Int) {
        moveAnimator?.cancel()
        val startX = params.x
        val startY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CURSOR_MOVE_DURATION_MS
            // Linear on purpose: the position fed in is already exponentially
            // smoothed upstream, so an easing curve here would just stack a
            // second lag on top of that rather than adding anything useful.
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
        }
        moveAnimator = animator
        animator.start()
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
