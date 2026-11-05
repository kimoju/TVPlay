package app.tvplay.ui.theme

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import androidx.core.graphics.ColorUtils
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class TvPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Vector icon glyphs following `ui-ux-pro-max` rules:
 * - Zero emojis used as structural icons.
 * - Consistent 2dp rounded-cap geometric stroke across the entire interface.
 * - Token-driven tinting for both dark and light modes.
 */
enum class TvIconGlyph {
    PLAY,
    WIFI,
    USB,
    LAN,
    BLUETOOTH,
    PHONE,
    DISPLAY,
    AUDIO,
    MIC,
    SLIDERS,
    FPS_GAUGE,
    MOON,
    REFRESH,
    POWER,
    CLOUD_OTA,
    RADAR,
    SHIELD_MFI,
    LOG_FILE,
    HOME,
    CHECK,
    SPARKLES,
    SETTINGS,
}

/**
 * Resolution-independent Canvas vector icon drawable (`24x24` coordinate space).
 */
class TvVectorIconDrawable(
    private val glyph: TvIconGlyph,
    private var tintColor: Int,
    private val sizePx: Int,
    private val strokeWidthPx: Float,
) : Drawable() {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tintColor
        strokeWidth = strokeWidthPx
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = tintColor
    }

    fun setTintInt(color: Int) {
        if (tintColor != color) {
            tintColor = color
            strokePaint.color = color
            fillPaint.color = color
            invalidateSelf()
        }
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        val scale = min(b.width(), b.height()) / 24f
        canvas.scale(scale, scale)
        strokePaint.strokeWidth = (strokeWidthPx / scale).coerceAtLeast(1.6f)

        val path = Path()
        when (glyph) {
            TvIconGlyph.PLAY -> {
                path.moveTo(7f, 5f)
                path.lineTo(19f, 12f)
                path.lineTo(7f, 19f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            TvIconGlyph.WIFI -> {
                canvas.drawArc(RectF(2f, 5f, 22f, 23f), 220f, 100f, false, strokePaint)
                canvas.drawArc(RectF(5.5f, 9f, 18.5f, 21f), 220f, 100f, false, strokePaint)
                canvas.drawArc(RectF(9f, 13f, 15f, 19f), 220f, 100f, false, strokePaint)
                canvas.drawCircle(12f, 18.5f, 1.5f, fillPaint)
            }
            TvIconGlyph.USB -> {
                canvas.drawLine(12f, 3.5f, 12f, 18f, strokePaint)
                path.moveTo(9.5f, 6.5f)
                path.lineTo(12f, 3.5f)
                path.lineTo(14.5f, 6.5f)
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(12f, 19.5f, 2f, strokePaint)
                path.reset()
                path.moveTo(12f, 14f)
                path.lineTo(7.5f, 11f)
                path.lineTo(7.5f, 8.5f)
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(7.5f, 7.5f, 1.3f, fillPaint)
                path.reset()
                path.moveTo(12f, 12.5f)
                path.lineTo(16.5f, 9.5f)
                path.lineTo(16.5f, 7.5f)
                canvas.drawPath(path, strokePaint)
                canvas.drawRect(15.2f, 5.5f, 17.8f, 7.8f, fillPaint)
            }
            TvIconGlyph.LAN -> {
                canvas.drawRoundRect(RectF(3.5f, 13.5f, 20.5f, 19.5f), 2.5f, 2.5f, strokePaint)
                canvas.drawCircle(7.5f, 16.5f, 1.1f, fillPaint)
                canvas.drawCircle(11.5f, 16.5f, 1.1f, fillPaint)
                canvas.drawLine(16f, 13.5f, 17.5f, 6.5f, strokePaint)
                canvas.drawLine(8f, 13.5f, 6.5f, 8f, strokePaint)
                canvas.drawArc(RectF(8.5f, 4f, 15.5f, 10f), 200f, 140f, false, strokePaint)
            }
            TvIconGlyph.BLUETOOTH -> {
                path.moveTo(7f, 7f)
                path.lineTo(17f, 17f)
                path.lineTo(12f, 21f)
                path.lineTo(12f, 3f)
                path.lineTo(17f, 7f)
                path.lineTo(7f, 17f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.PHONE -> {
                canvas.drawRoundRect(RectF(6.5f, 2.5f, 17.5f, 21.5f), 3f, 3f, strokePaint)
                canvas.drawLine(10.5f, 5.2f, 13.5f, 5.2f, strokePaint)
                canvas.drawCircle(12f, 18.5f, 1f, fillPaint)
            }
            TvIconGlyph.DISPLAY -> {
                canvas.drawRoundRect(RectF(2.5f, 4f, 21.5f, 16.5f), 2.5f, 2.5f, strokePaint)
                canvas.drawLine(8f, 20f, 16f, 20f, strokePaint)
                canvas.drawLine(12f, 16.5f, 12f, 20f, strokePaint)
            }
            TvIconGlyph.AUDIO -> {
                path.moveTo(4f, 9.5f)
                path.lineTo(8f, 9.5f)
                path.lineTo(13f, 5f)
                path.lineTo(13f, 19f)
                path.lineTo(8f, 14.5f)
                path.lineTo(4f, 14.5f)
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawArc(RectF(13f, 8f, 18f, 16f), -55f, 110f, false, strokePaint)
                canvas.drawArc(RectF(14.5f, 5f, 21.5f, 19f), -55f, 110f, false, strokePaint)
            }
            TvIconGlyph.MIC -> {
                canvas.drawRoundRect(RectF(9f, 3f, 15f, 13.5f), 3f, 3f, strokePaint)
                canvas.drawArc(RectF(6f, 7.5f, 18f, 17f), 0f, 180f, false, strokePaint)
                canvas.drawLine(12f, 17f, 12f, 20.5f, strokePaint)
                canvas.drawLine(8.5f, 20.5f, 15.5f, 20.5f, strokePaint)
            }
            TvIconGlyph.SLIDERS -> {
                canvas.drawLine(4f, 7f, 20f, 7f, strokePaint)
                canvas.drawLine(4f, 12f, 20f, 12f, strokePaint)
                canvas.drawLine(4f, 17f, 20f, 17f, strokePaint)
                canvas.drawCircle(9f, 7f, 2.2f, fillPaint)
                canvas.drawCircle(15f, 12f, 2.2f, fillPaint)
                canvas.drawCircle(11f, 17f, 2.2f, fillPaint)
            }
            TvIconGlyph.FPS_GAUGE -> {
                canvas.drawArc(RectF(3f, 4.5f, 21f, 21.5f), 165f, 210f, false, strokePaint)
                canvas.drawLine(12f, 14f, 16.5f, 8.5f, strokePaint)
                canvas.drawCircle(12f, 14f, 1.8f, fillPaint)
            }
            TvIconGlyph.MOON -> {
                path.moveTo(19.5f, 14.5f)
                path.quadTo(12f, 16f, 9.5f, 8.5f)
                path.quadTo(8.8f, 5.8f, 10.2f, 3.8f)
                path.quadTo(4f, 5.5f, 4.2f, 12.2f)
                path.quadTo(4.5f, 19.5f, 12f, 19.8f)
                path.quadTo(17.5f, 20f, 19.5f, 14.5f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.REFRESH -> {
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -30f, 290f, false, strokePaint)
                path.moveTo(16f, 3.5f)
                path.lineTo(20f, 7.5f)
                path.lineTo(15.2f, 8.5f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.POWER -> {
                canvas.drawArc(RectF(4.5f, 5.5f, 19.5f, 20f), -50f, 280f, false, strokePaint)
                canvas.drawLine(12f, 3f, 12f, 11.5f, strokePaint)
            }
            TvIconGlyph.CLOUD_OTA -> {
                path.moveTo(6.5f, 17.5f)
                path.cubicTo(3f, 17.5f, 2.5f, 12.5f, 6f, 11.5f)
                path.cubicTo(6.8f, 6.5f, 13.5f, 5.5f, 15.8f, 9.5f)
                path.cubicTo(20f, 9.5f, 21.5f, 14.5f, 18f, 17.5f)
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(12f, 11.5f, 12f, 19.5f, strokePaint)
                path.reset()
                path.moveTo(9.2f, 17f)
                path.lineTo(12f, 19.8f)
                path.lineTo(14.8f, 17f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.RADAR -> {
                canvas.drawCircle(12f, 12f, 2.2f, fillPaint)
                canvas.drawCircle(12f, 12f, 6f, strokePaint)
                canvas.drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), -45f, 90f, false, strokePaint)
                canvas.drawArc(RectF(2.5f, 2.5f, 21.5f, 21.5f), 135f, 90f, false, strokePaint)
            }
            TvIconGlyph.SHIELD_MFI -> {
                path.moveTo(12f, 3f)
                path.lineTo(19f, 6f)
                path.lineTo(19f, 11.5f)
                path.quadTo(19f, 17.5f, 12f, 21f)
                path.quadTo(5f, 17.5f, 5f, 11.5f)
                path.lineTo(5f, 6f)
                path.close()
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(9f, 12f)
                path.lineTo(11.2f, 14.2f)
                path.lineTo(15.2f, 9.8f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.LOG_FILE -> {
                canvas.drawRoundRect(RectF(5f, 3f, 19f, 21f), 2.5f, 2.5f, strokePaint)
                canvas.drawLine(8.5f, 8.5f, 15.5f, 8.5f, strokePaint)
                canvas.drawLine(8.5f, 12.5f, 15.5f, 12.5f, strokePaint)
                canvas.drawLine(8.5f, 16.5f, 12.5f, 16.5f, strokePaint)
            }
            TvIconGlyph.HOME -> {
                path.moveTo(3.5f, 11f)
                path.lineTo(12f, 4f)
                path.lineTo(20.5f, 11f)
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(6f, 10f)
                path.lineTo(6f, 19.5f)
                path.lineTo(18f, 19.5f)
                path.lineTo(18f, 10f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.CHECK -> {
                path.moveTo(5f, 12.5f)
                path.lineTo(10f, 17.5f)
                path.lineTo(19f, 7f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.SPARKLES -> {
                path.moveTo(12f, 3f)
                path.quadTo(12.5f, 11.5f, 21f, 12f)
                path.quadTo(12.5f, 12.5f, 12f, 21f)
                path.quadTo(11.5f, 12.5f, 3f, 12f)
                path.quadTo(11.5f, 11.5f, 12f, 3f)
                canvas.drawPath(path, strokePaint)
            }
            TvIconGlyph.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3.2f, strokePaint)
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = 12f + (6.2f * cos(angle)).toFloat()
                    val y1 = 12f + (6.2f * sin(angle)).toFloat()
                    val x2 = 12f + (8.8f * cos(angle)).toFloat()
                    val y2 = 12f + (8.8f * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object TvVectorIcons {
    fun drawable(
        context: Context,
        glyph: TvIconGlyph,
        color: Int,
        sizeDp: Int = 18,
        strokeDp: Float = 2f,
    ): TvVectorIconDrawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(12)
        val strokePx = (strokeDp * density).coerceAtLeast(1.5f)
        return TvVectorIconDrawable(glyph, color, sizePx, strokePx).apply {
            setBounds(0, 0, sizePx, sizePx)
        }
    }

    /**
     * Builds a 40x40dp rounded squircle icon badge well for Bento cards and setting rows.
     */
    fun badgeWell(
        context: Context,
        glyph: TvIconGlyph,
        palette: TvColorScheme,
        accented: Boolean = false,
        active: Boolean = accented,
        sizeDp: Int = 38,
        iconDp: Int = 18,
    ): FrameLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)
        val boxPx = dp(sizeDp)
        val isHighlighted = accented || active
        val iconColor = if (isHighlighted) palette.onAccent else palette.accent
        val iconDrawable = drawable(context, glyph, iconColor, sizeDp = iconDp)

        return FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(boxPx, boxPx)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isHighlighted) {
                    intArrayOf(
                        ColorUtils.blendARGB(palette.accent, Color.WHITE, 0.16f),
                        palette.accent,
                    )
                } else {
                    intArrayOf(
                        palette.iconWellSurface,
                        ColorUtils.blendARGB(palette.iconWellSurface, palette.subtleSurface, 0.45f),
                    )
                },
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (isHighlighted) palette.accent else palette.iconWellOutline)
            }
            addView(
                ImageView(context).apply {
                    setImageDrawable(iconDrawable)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER),
            )
        }
    }

    /**
     * Styles a standard Android [Switch] into a modern tvOS/iOS capsule toggle without emojis or
     * legacy Holo drawables.
     */
    fun styleIosSwitch(switchView: Switch, palette: TvColorScheme) {
        val density = switchView.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt().coerceAtLeast(1)

        val trackWidth = dp(46)
        val trackHeight = dp(26)
        val thumbSize = dp(20)
        val inset = dp(3)

        fun buildTrack(checked: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = trackHeight / 2f
            setSize(trackWidth, trackHeight)
            setColor(if (checked) palette.accent else palette.trackOff)
            setStroke(
                dp(1),
                if (checked) ColorUtils.blendARGB(palette.accent, Color.WHITE, 0.25f) else palette.outline,
            )
        }

        fun buildThumb(checked: Boolean): Drawable {
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(thumbSize, thumbSize)
                setColor(if (checked) Color.WHITE else ColorUtils.blendARGB(palette.primaryText, palette.secondaryText, 0.25f))
            }
            return LayerDrawable(arrayOf(circle)).apply {
                setLayerInset(0, inset, inset, inset, inset)
            }
        }

        switchView.textOn = ""
        switchView.textOff = ""
        switchView.showText = false
        switchView.splitTrack = false
        switchView.switchMinWidth = trackWidth
        switchView.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), buildTrack(true))
            addState(intArrayOf(), buildTrack(false))
        }
        switchView.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), buildThumb(true))
            addState(intArrayOf(), buildThumb(false))
        }
        switchView.thumbTintList = null
        switchView.trackTintList = null
    }
}

/**
 * Spatial OLED/Cinema ambient mesh background drawable for [TvWorkbenchActivity] and [StageWaitingOverlay].
 * Renders deep vertical base gradient + dual radial light pools + subtle architectural grid lines.
 */
class AmbientMeshBackgroundDrawable(
    private val palette: TvColorScheme,
    private val pureBlack: Boolean = false,
) : Drawable() {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = if (palette.night) 0x09FFFFFF else 0x080F172A
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)

        basePaint.shader = LinearGradient(
            0f,
            0f,
            w * 0.4f,
            h,
            if (pureBlack) Color.BLACK else palette.backgroundGradientTop,
            if (pureBlack) Color.BLACK else palette.backgroundGradientBottom,
            Shader.TileMode.CLAMP,
        )
        glowPrimaryPaint.shader = RadialGradient(
            w * 0.18f,
            h * 0.16f,
            min(w, h) * 0.65f,
            intArrayOf(palette.ambientGlowPrimary, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowSecondaryPaint.shader = RadialGradient(
            w * 0.85f,
            h * 0.82f,
            min(w, h) * 0.58f,
            intArrayOf(palette.ambientGlowSecondary, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        canvas.drawRect(b, glowPrimaryPaint)
        canvas.drawRect(b, glowSecondaryPaint)

        // Subtle concentric spatial signal arcs in top-left corner
        val cx = b.width() * 0.20f
        val cy = b.height() * 0.24f
        val baseRadius = min(b.width(), b.height()) * 0.18f
        for (i in 1..3) {
            canvas.drawCircle(cx, cy, baseRadius * i, gridPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * Glassmorphic Bento hero card background with radial spotlight and concentric radar signal rings.
 */
class HeroBentoCardDrawable(
    private val palette: TvColorScheme,
    private val highlighted: Boolean = false,
    private val cornerRadiusPx: Float = 48f,
    private val strokeWidthPx: Float = 2f,
    activeGlow: Boolean = highlighted,
) : Drawable() {
    private val isHighlighted = highlighted || activeGlow
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x22 else 0x18)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = if (isHighlighted) palette.accent else palette.heroBorder
    }
    private val rectF = RectF()
    private val clipPath = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rectF.set(
            bounds.left + strokeWidthPx / 2f,
            bounds.top + strokeWidthPx / 2f,
            bounds.right - strokeWidthPx / 2f,
            bounds.bottom - strokeWidthPx / 2f,
        )
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            w,
            h,
            palette.heroGradientStart,
            palette.heroGradientEnd,
            Shader.TileMode.CLAMP,
        )
        val spotColor = if (highlighted) {
            ColorUtils.setAlphaComponent(palette.success, if (palette.night) 0x30 else 0x1A)
        } else {
            ColorUtils.setAlphaComponent(palette.accent, if (palette.night) 0x32 else 0x1A)
        }
        spotlightPaint.shader = RadialGradient(
            w * 0.88f,
            h * 0.22f,
            min(w, h) * 0.78f,
            intArrayOf(spotColor, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, fillPaint)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, spotlightPaint)

        // Concentric radar arcs on top-right of the hero card
        val cx = bounds.right - bounds.width() * 0.14f
        val cy = bounds.top + bounds.height() * 0.26f
        val step = min(bounds.width(), bounds.height()) * 0.16f
        for (i in 1..4) {
            canvas.drawCircle(cx, cy, step * i, ringPaint)
        }
        canvas.restoreToCount(save)
        canvas.drawRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, strokePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * TVPlay action button with pill/squircle geometry, optional vector icon, ripple feedback,
 * and a non-jittering high-visibility focus border for Android TV D-pad navigation.
 */
class TvPlayButton(
    context: Context,
    kind: TvPlayButtonKind? = null,
    private var iconGlyph: TvIconGlyph? = null,
) : Button(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)

    init {
        isAllCaps = false
        isFocusable = true
        stateListAnimator = null
        elevation = 0f
        minHeight = dp(48)
        minimumHeight = dp(48)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        compoundDrawablePadding = dp(8)
        letterSpacing = 0.01f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        if (kind != null) {
            style(kind, TvColorScheme.resolve(context), overlay = false, glyph = iconGlyph)
        }
    }

    fun setVectorIcon(glyph: TvIconGlyph?, color: Int = currentTextColor, sizeDp: Int = 17) {
        iconGlyph = glyph
        val drawable = glyph?.let { TvVectorIcons.drawable(context, it, color, sizeDp = sizeDp) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    fun style(
        kind: TvPlayButtonKind = TvPlayButtonKind.SECONDARY,
        palette: TvColorScheme,
        overlay: Boolean = false,
        glyph: TvIconGlyph? = iconGlyph,
    ) {
        iconGlyph = glyph
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
            TvPlayButtonKind.PRIMARY -> ColorUtils.blendARGB(fill, Color.WHITE, 0.30f)
            TvPlayButtonKind.SECONDARY -> if (overlay) palette.overlayOutline else palette.outline
            TvPlayButtonKind.DANGER -> ColorUtils.blendARGB(fill, Color.WHITE, 0.25f)
            TvPlayButtonKind.TONAL_DANGER -> palette.tonalDangerOutline
        }
        val focusedFill = ColorUtils.blendARGB(fill, Color.WHITE, if (kind == TvPlayButtonKind.PRIMARY) 0.16f else 0.12f)
        val focusedStroke = if (kind == TvPlayButtonKind.PRIMARY) Color.WHITE else palette.focusRing
        val disabledFill = ColorUtils.setAlphaComponent(fill, 0x55)
        val disabledLabel = ColorUtils.setAlphaComponent(label, 0x66)
        val disabledStroke = if (stroke != 0) ColorUtils.setAlphaComponent(stroke, 0x44) else 0
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(),
                ),
                intArrayOf(disabledLabel, label),
            ),
        )
        if (glyph != null) {
            setVectorIcon(glyph, label)
        }
        val radius = dp(14).toFloat()
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(radius, disabledFill, disabledStroke, dp(1)))
            addState(intArrayOf(android.R.attr.state_focused), gradientShape(radius, focusedFill, focusedStroke, dp(2)))
            addState(intArrayOf(), gradientShape(radius, fill, stroke, dp(1)))
        }
        background = RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            states,
            shape(radius, Color.WHITE, 0, 0),
        )
        foreground = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(ColorUtils.setAlphaComponent(palette.focusRing, 0x16))
                    setStroke(dp(2), focusedStroke)
                },
            )
        }
    }

    private fun shape(radius: Float, fill: Int, stroke: Int, strokeWidthPx: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (stroke != 0 && strokeWidthPx > 0) setStroke(strokeWidthPx, stroke)
    }

    private fun gradientShape(radius: Float, fill: Int, stroke: Int, strokeWidthPx: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            ColorUtils.blendARGB(fill, Color.WHITE, 0.10f),
            fill,
        ),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        if (stroke != 0 && strokeWidthPx > 0) setStroke(strokeWidthPx, stroke)
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
