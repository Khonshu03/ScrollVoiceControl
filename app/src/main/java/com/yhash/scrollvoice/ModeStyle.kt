package com.yhash.scrollvoice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat

/**
 * Per-mode color palette (matches the gradients used for the mode cards in
 * activity_main.xml, and OverlayService's status-dot colors) plus a runtime
 * drawable builder, so the Stop button and its glow can recolor to whichever
 * mode is actually running instead of staying a fixed purple - the same
 * touch the Figma reference's power button does.
 */
object ModeStyle {

    data class Palette(val light: Int, val base: Int, val dark: Int, val glow: Int)

    fun paletteFor(context: Context, mode: String): Palette = when (mode) {
        VoiceListenerService.MODE_VOICE -> Palette(
            light = ContextCompat.getColor(context, R.color.mode_voice_light),
            base = ContextCompat.getColor(context, R.color.mode_voice),
            dark = ContextCompat.getColor(context, R.color.mode_voice_dark),
            glow = ContextCompat.getColor(context, R.color.mode_voice_glow)
        )
        VoiceListenerService.MODE_CLAP -> Palette(
            light = ContextCompat.getColor(context, R.color.mode_clap_light),
            base = ContextCompat.getColor(context, R.color.mode_clap),
            dark = ContextCompat.getColor(context, R.color.mode_clap_dark),
            glow = ContextCompat.getColor(context, R.color.mode_clap_glow)
        )
        else -> Palette(
            light = ContextCompat.getColor(context, R.color.mode_camera_light),
            base = ContextCompat.getColor(context, R.color.mode_camera),
            dark = ContextCompat.getColor(context, R.color.mode_camera_dark),
            glow = ContextCompat.getColor(context, R.color.mode_camera_glow)
        )
    }

    /**
     * Builds the same layered look as the static btn_stop_default/pressed
     * XML drawables (3-stop gradient + top gloss band + stroke) but with
     * runtime colors, wrapped in a press-state selector.
     */
    fun buildStopButtonDrawable(cornerRadiusPx: Float, glossHeightPx: Int, palette: Palette): StateListDrawable {
        fun pill(top: Int, mid: Int, bottom: Int, strokeColor: Int, glossAlpha: Int): LayerDrawable {
            val base = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(bottom, mid, top)).apply {
                cornerRadius = cornerRadiusPx
            }
            val gloss = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf((glossAlpha shl 24) or 0xFFFFFF, 0x00FFFFFF)
            ).apply {
                cornerRadius = cornerRadiusPx
            }
            val stroke = GradientDrawable().apply {
                cornerRadius = cornerRadiusPx
                setStroke(2, strokeColor)
                setColor(android.graphics.Color.TRANSPARENT)
            }
            val layered = LayerDrawable(arrayOf(base, gloss, stroke))
            // Confine the gloss layer to a band at the top of the pill,
            // rather than letting it stretch across the whole height.
            layered.setLayerHeight(1, glossHeightPx)
            layered.setLayerGravity(1, android.view.Gravity.TOP or android.view.Gravity.FILL_HORIZONTAL)
            return layered
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(palette.base, palette.dark, palette.dark, palette.light, 0x40))
            addState(intArrayOf(), pill(palette.light, palette.base, palette.dark, palette.light, 0x59))
        }
    }
}
