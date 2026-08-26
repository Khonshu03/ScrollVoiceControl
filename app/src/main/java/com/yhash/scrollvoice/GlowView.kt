package com.yhash.scrollvoice

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Renders a soft, genuinely-blurred glow behind a shape - the halo-behind-a-
 * button look. Android's `<shape>` radial-gradient drawables can only fake
 * this with hard-edged concentric rings, which reads as banded rather than
 * soft. This uses a real [BlurMaskFilter] instead, which needs software
 * rendering (mask filters aren't supported on the hardware-accelerated
 * layer) - that's set automatically here, so nothing else has to opt in.
 *
 * Two separate sizes matter here, and conflating them is what caused the
 * glow to look clipped/rectangular before: the *shape* (glowShapeWidth /
 * glowShapeHeight) should roughly match the real button sitting on top of
 * it, while the *view* itself (its layout_width/height) needs to be
 * noticeably bigger than that shape - the extra space on all sides is what
 * the blur fades into. As a rule of thumb, leave at least ~2.5x the blur
 * radius of empty margin around the shape on every side, or the blur hits
 * the view's edge before it's fully faded and you get a visible cutoff.
 */
class GlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var glowColor: Int = 0xFF8B5CF6.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var blurRadiusPx: Float = 16f * resources.displayMetrics.density
        set(value) {
            field = value
            rebuildBlur()
            invalidate()
        }

    var shapeCornerRadiusPx: Float = 28f * resources.displayMetrics.density
        set(value) {
            field = value
            invalidate()
        }

    /** Width/height of the actual glowing shape - should roughly match the real button, not the view's own bounds. */
    var shapeWidthPx: Float = -1f
        set(value) {
            field = value
            layoutRect()
            invalidate()
        }

    var shapeHeightPx: Float = -1f
        set(value) {
            field = value
            layoutRect()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        context.theme.obtainStyledAttributes(attrs, R.styleable.GlowView, 0, 0).apply {
            try {
                glowColor = getColor(R.styleable.GlowView_glowColor, glowColor)
                blurRadiusPx = getDimension(R.styleable.GlowView_glowBlurRadius, blurRadiusPx)
                shapeCornerRadiusPx = getDimension(R.styleable.GlowView_glowCornerRadius, shapeCornerRadiusPx)
                shapeWidthPx = getDimension(R.styleable.GlowView_glowShapeWidth, shapeWidthPx)
                shapeHeightPx = getDimension(R.styleable.GlowView_glowShapeHeight, shapeHeightPx)
            } finally {
                recycle()
            }
        }
        rebuildBlur()
    }

    private fun rebuildBlur() {
        paint.maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRect()
    }

    private fun layoutRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Fall back to "fill minus blur radius" only if no explicit shape
        // size was set - explicit sizing (matching the real button) is
        // strongly preferred so the blur has real room to fade.
        val shapeW = if (shapeWidthPx > 0f) shapeWidthPx else (w - blurRadiusPx * 2f)
        val shapeH = if (shapeHeightPx > 0f) shapeHeightPx else (h - blurRadiusPx * 2f)
        val left = (w - shapeW) / 2f
        val top = (h - shapeH) / 2f
        rect.set(left, top, left + shapeW, top + shapeH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = glowColor
        canvas.drawRoundRect(rect, shapeCornerRadiusPx, shapeCornerRadiusPx, paint)
    }
}
