package app.tvplay.ui.theme

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.widget.Button
import androidx.core.graphics.ColorUtils
import java.io.ByteArrayOutputStream

enum class TvPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * TVPlay action button with pill geometry, palette-driven fill/outline, ripple feedback,
 * and a high-visibility focus border for Android TV D-pad navigation.
 */
class TvPlayButton(
    context: Context,
    kind: TvPlayButtonKind? = null,
) : Button(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    init {
        isAllCaps = false
        isFocusable = true
        stateListAnimator = null
        elevation = 0f
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (kind != null) {
            style(kind, TvColorScheme.resolve(context), overlay = false)
        }
    }

    fun style(
        kind: TvPlayButtonKind = TvPlayButtonKind.SECONDARY,
        palette: TvColorScheme,
        overlay: Boolean = false,
    ) {
        val fill = when (kind) {
            TvPlayButtonKind.PRIMARY -> if (overlay) palette.overlayAccent else palette.accent
            TvPlayButtonKind.SECONDARY -> if (overlay) palette.overlayButton else palette.button
            TvPlayButtonKind.DANGER -> if (overlay) palette.overlayDanger else palette.danger
            TvPlayButtonKind.TONAL_DANGER -> palette.tonalDanger
        }
        val label = when (kind) {
            TvPlayButtonKind.PRIMARY -> palette.onAccent
            TvPlayButtonKind.SECONDARY -> if (overlay) palette.overlayButtonText else palette.buttonText
            TvPlayButtonKind.DANGER -> palette.onDanger
            TvPlayButtonKind.TONAL_DANGER -> palette.onTonalDanger
        }
        val stroke = when (kind) {
            TvPlayButtonKind.SECONDARY -> if (overlay) palette.overlayOutline else palette.outline
            TvPlayButtonKind.TONAL_DANGER -> palette.tonalDangerOutline
            else -> 0
        }
        val disabledFill = ColorUtils.setAlphaComponent(fill, 0x66)
        val disabledLabel = ColorUtils.setAlphaComponent(label, 0x77)
        val disabledStroke = if (stroke != 0) ColorUtils.setAlphaComponent(stroke, 0x55) else 0
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(),
                ),
                intArrayOf(disabledLabel, label),
            ),
        )
        val radius = dp(18).toFloat()
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(radius, disabledFill, disabledStroke))
            addState(intArrayOf(), shape(radius, fill, stroke))
        }
        background = RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            states,
            shape(radius, 0xFFFFFFFF.toInt(), 0),
        )
        foreground = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(ColorUtils.setAlphaComponent(palette.focusRing, 0x22))
                    setStroke(dp(4), palette.focusRing)
                },
            )
        }
    }

    private fun shape(radius: Float, fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }
}

interface AppAppearanceOwner {
    val currentAppNight: Boolean
}

fun Context.appDialogTheme(): Int {
    val night = (this as? AppAppearanceOwner)?.currentAppNight ?: resolveAppNightNow()
    return if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
    else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
}

fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

@JvmName("appDialogBuilderExt")
fun Context.appDialogBuilder(): AlertDialog.Builder = AlertDialog.Builder(this, appDialogTheme())

fun appDialogBuilder(context: Context): AlertDialog.Builder = context.appDialogBuilder()

fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme())

/** Keeps custom AirPlay home icons inside a small square PNG so an oversized picker file cannot stall /info. */
object AirPlayIconSizing {
    const val MAX_PIXELS = 192

    fun fit(encoded: ByteArray): Pair<Int, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_PIXELS * 2 && bounds.outHeight / sample > MAX_PIXELS * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val crop = minOf(decoded.width, decoded.height)
        val x = (decoded.width - crop) / 2
        val y = (decoded.height - crop) / 2
        val square = if (x == 0 && y == 0 && decoded.width == crop && decoded.height == crop) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, x, y, crop, crop)
        }
        val size = crop.coerceIn(1, MAX_PIXELS)
        val scaled = if (square.width == size) square else Bitmap.createScaledBitmap(square, size, size, true)
        val output = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
        return size to output.toByteArray()
    }
}
