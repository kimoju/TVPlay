package app.tvplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import androidx.core.graphics.ColorUtils

internal enum class TvPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Button for views built in code outside `TvPlayWorkbenchActivity`, whose own `button()` stays private.
 * The corner radius follows the control height, as in `TvPlayWorkbenchActivity.buttonShape`.
 */
internal class TvPlayButton(context: Context) : Button(context) {
    private var kind = TvPlayButtonKind.SECONDARY
    private var palette: TvPlayPalette? = null
    private var overlay = false

    init {
        isAllCaps = false
        stateListAnimator = null
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** [overlay] picks the in-car menu colours instead of the app chrome colours. */
    fun style(kind: TvPlayButtonKind, palette: TvPlayPalette, overlay: Boolean) {
        this.kind = kind
        this.palette = palette
        this.overlay = overlay
        repaint()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) repaint()
    }

    private fun repaint() {
        val palette = palette ?: return
        val density = resources.displayMetrics.density
        val fill: Int
        val text: Int
        val stroke: Int
        when (kind) {
            TvPlayButtonKind.PRIMARY -> {
                fill = if (overlay) palette.overlayAccent else palette.accent
                text = if (overlay) palette.overlayOnAccent else palette.onAccent
                stroke = fill
            }
            TvPlayButtonKind.SECONDARY -> {
                fill = if (overlay) palette.overlayTrackOff else palette.button
                text = if (overlay) palette.overlayPrimaryText else palette.primaryText
                stroke = if (overlay) fill else palette.outline
            }
            TvPlayButtonKind.DANGER -> {
                fill = palette.overlayDanger
                text = Color.WHITE
                stroke = fill
            }
            TvPlayButtonKind.TONAL_DANGER -> {
                val surface = if (overlay) palette.overlayBackground else palette.surface
                fill = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.16f)
                text = if (ColorUtils.calculateLuminance(surface) < 0.5)
                    ColorUtils.blendARGB(palette.overlayDanger, Color.WHITE, 0.40f)
                else palette.overlayDanger
                stroke = ColorUtils.blendARGB(surface, palette.overlayDanger, 0.48f)
            }
        }
        val radius = minOf(24f * density, (if (height > 0) height else minHeight) * 20f / 56)
        setTextColor(text)
        background = RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius
                setStroke((1 * density).toInt().coerceAtLeast(1), stroke)
            },
            null,
        )
        // Remote and D-pad users need to see where they are; touch mode never shows it.
        // Primary buttons already fill with the accent color, so their focus ring uses the
        // high-contrast foreground color instead of blending into the button background.
        val focusStrokeColor = when (kind) {
            TvPlayButtonKind.PRIMARY -> text
            TvPlayButtonKind.DANGER -> Color.WHITE
            else -> palette.focusRing
        }
        val focusFillColor = when (kind) {
            TvPlayButtonKind.PRIMARY, TvPlayButtonKind.DANGER ->
                ColorUtils.setAlphaComponent(Color.WHITE, 0x26)
            else -> ColorUtils.setAlphaComponent(palette.focusRing, 0x22)
        }
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(focusFillColor)
                cornerRadius = radius
                setStroke((4 * density).toInt().coerceAtLeast(2), focusStrokeColor)
            })
        }
    }
}
