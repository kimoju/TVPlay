package app.tvplay.engine.av

import android.view.MotionEvent
import kotlin.math.roundToInt

/** Display insets in pixels used for CarPlay viewArea and safeArea declarations. */
data class DisplayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display specification advertised to the iPhone during RTSP `/info`. */
data class TvDisplaySpec(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: DisplayInsets? = null,
    val safeArea: DisplayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    val features: Int? = null,
)

/** Physical width presets controlling CarPlay UI icon size on large screens. */
enum class CarPlayPhysicalSizePreset(val label: String, val widthMillimeters: Int) {
    LARGE("Large", 250),
    MEDIUM("Medium", 300),
    SMALL("Small", 350);

    companion object {
        val DEFAULT = MEDIUM

        fun exact(millimeters: Int): CarPlayPhysicalSizePreset? =
            entries.firstOrNull { it.widthMillimeters == millimeters }
    }
}

enum class PhysicalSizeBasis {
    WIDTH,
    HEIGHT,
}

data class PhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)

/** Physical millimeter & FPS sanitization and aspect-ratio derivation. */
object DisplayMetricMath {
    const val MIN_FPS = 30
    const val MAX_FPS = 60
    const val FPS_STEP = 5
    const val DEFAULT_FPS = MAX_FPS

    const val MIN_WIDTH_PHYSICAL_MM = 100
    const val MAX_WIDTH_PHYSICAL_MM = 400
    const val WIDTH_PHYSICAL_MM_STEP = 5
    const val DEFAULT_WIDTH_PHYSICAL_MM = 200
    val DEFAULT_PHYSICAL_SIZE_BASIS = PhysicalSizeBasis.WIDTH
    const val MIN_REPORTED_PHYSICAL_MM = 1
    const val MAX_REPORTED_PHYSICAL_MM = 2_000

    fun sanitizeFps(value: Int): Int = snapToStep(value, MIN_FPS, MAX_FPS, FPS_STEP)

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(value, MIN_WIDTH_PHYSICAL_MM, MAX_WIDTH_PHYSICAL_MM, WIDTH_PHYSICAL_MM_STEP)

    fun sanitizeReportedPhysicalMm(value: Int): Int =
        value.coerceIn(MIN_REPORTED_PHYSICAL_MM, MAX_REPORTED_PHYSICAL_MM)

    fun resolvePhysicalSizeMm(
        currentWidthPixels: Int,
        currentHeightPixels: Int,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
        referenceMillimeters: Int,
        basis: PhysicalSizeBasis,
    ): PhysicalSizeMm {
        require(currentWidthPixels > 0 && currentHeightPixels > 0) {
            "Current display dimensions must be positive"
        }
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0) {
            "Maximum display dimensions must be positive"
        }
        val referencePixels = when (basis) {
            PhysicalSizeBasis.WIDTH -> currentWidthPixels.toDouble() / maximumWidthPixels
            PhysicalSizeBasis.HEIGHT -> currentHeightPixels.toDouble() / maximumHeightPixels
        }
        val referenceMm = referenceMillimeters.coerceAtLeast(1) * referencePixels
        val widthMm: Double
        val heightMm: Double
        when (basis) {
            PhysicalSizeBasis.WIDTH -> {
                widthMm = referenceMm
                heightMm = widthMm * currentHeightPixels / currentWidthPixels
            }
            PhysicalSizeBasis.HEIGHT -> {
                heightMm = referenceMm
                widthMm = heightMm * currentWidthPixels / currentHeightPixels
            }
        }
        return PhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(Math.round(widthMm).toInt()),
            heightMm = sanitizeReportedPhysicalMm(Math.round(heightMm).toInt()),
        )
    }

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}

/** Display resolution scaling math (tenths and arbitrary percentages, always aligned to even pixels). */
object DisplayScaleMath {
    const val MIN_TENTHS = 3
    const val MAX_TENTHS = 10
    const val DEFAULT_TENTHS = MAX_TENTHS
    const val MIN_PERCENT = 30
    const val MAX_PERCENT = 160

    fun sanitize(tenths: Int): Int = tenths.coerceIn(MIN_TENTHS, MAX_TENTHS)

    fun label(tenths: Int): String {
        val value = sanitize(tenths)
        return "${value / 10}.${value % 10}x"
    }

    fun apply(display: TvDisplaySpec, tenths: Int): TvDisplaySpec {
        val value = sanitize(tenths)
        return display.copy(
            widthPixels = scalePixels(display.widthPixels, value),
            heightPixels = scalePixels(display.heightPixels, value),
        )
    }

    fun applyPercent(display: TvDisplaySpec, percent: Int): TvDisplaySpec {
        val value = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        fun scale(pixels: Int): Int {
            require(pixels > 0) { "pixels must be positive" }
            val scaled = ((pixels.toLong() * value + 50L) / 100L).toInt().coerceAtLeast(1)
            return if (scaled % 2 == 0) scaled else scaled + 1
        }
        return display.copy(
            widthPixels = scale(display.widthPixels),
            heightPixels = scale(display.heightPixels),
        )
    }

    private fun scalePixels(pixels: Int, tenths: Int): Int {
        require(pixels > 0) { "pixels must be positive" }
        val scaled = ((pixels.toLong() * tenths + 5L) / 10L).toInt().coerceAtLeast(1)
        return if (scaled % 2 == 0) scaled else scaled + 1
    }
}

/** UI density canvas scaling (75%, 85%, 100%, 115%) capped at 4K UHD. */
object UiDensityScale {
    const val DEFAULT = 100
    val presets = listOf(75, 85, DEFAULT, 115)

    fun sanitize(percent: Int): Int = percent.takeIf { it in presets } ?: DEFAULT

    fun label(percent: Int): String = when (sanitize(percent)) {
        75 -> "Smaller"
        85 -> "Small"
        115 -> "Large"
        else -> "Default"
    }

    fun apply(display: TvDisplaySpec, percent: Int): TvDisplaySpec {
        val scale = sanitize(percent)
        if (scale == DEFAULT) return display
        require(display.widthPixels > 0 && display.heightPixels > 0)
        fun pixels(value: Int): Int = ((value.toLong() * 100 / scale + 1) / 2 * 2).toInt()
        val width = pixels(display.widthPixels)
        val height = pixels(display.heightPixels)
        if (scale < DEFAULT && (maxOf(width, height) > 3840 || minOf(width, height) > 2160)) return display
        fun insets(value: DisplayInsets?): DisplayInsets? = value?.let {
            it.copy(
                top = (it.top * height.toDouble() / display.heightPixels).roundToInt(),
                bottom = (it.bottom * height.toDouble() / display.heightPixels).roundToInt(),
                left = (it.left * width.toDouble() / display.widthPixels).roundToInt(),
                right = (it.right * width.toDouble() / display.widthPixels).roundToInt(),
            )
        }
        return display.copy(
            widthPixels = width,
            heightPixels = height,
            viewArea = insets(display.viewArea),
            safeArea = insets(display.safeArea),
        )
    }
}

/** Safe-area rectangle in pixel coordinates. */
data class DisplaySafeAreaRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) {
            "Safe area coordinates must describe a non-empty rectangle"
        }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clampTo(widthPixels: Int, heightPixels: Int): DisplaySafeAreaRect {
        require(widthPixels > 0 && heightPixels > 0) { "Dimensions must be positive" }
        val clampedLeft = left.coerceIn(0, widthPixels - 1)
        val clampedTop = top.coerceIn(0, heightPixels - 1)
        val clampedRight = right.coerceIn(clampedLeft + 1, widthPixels)
        val clampedBottom = bottom.coerceIn(clampedTop + 1, heightPixels)
        return DisplaySafeAreaRect(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }
}

object DisplaySafeAreaMath {
    fun full(widthPixels: Int, heightPixels: Int): DisplaySafeAreaRect {
        require(widthPixels > 0 && heightPixels > 0) { "Dimensions must be positive" }
        return DisplaySafeAreaRect(0, 0, widthPixels, heightPixels)
    }

    fun resolve(
        mapping: DisplaySafeAreaRect?,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
    ): DisplaySafeAreaRect = mapping?.clampTo(activityWidthPixels, activityHeightPixels)
        ?: full(activityWidthPixels, activityHeightPixels)

    fun toInsets(
        mapping: DisplaySafeAreaRect?,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        displayWidthPixels: Int,
        displayHeightPixels: Int,
    ): DisplayInsets {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) { "Activity dimensions must be positive" }
        require(displayWidthPixels > 0 && displayHeightPixels > 0) { "Display dimensions must be positive" }
        val rect = resolve(mapping, activityWidthPixels, activityHeightPixels)
        var left = scaleCoordinate(rect.left, activityWidthPixels, displayWidthPixels).coerceIn(0, displayWidthPixels - 1)
        var top = scaleCoordinate(rect.top, activityHeightPixels, displayHeightPixels).coerceIn(0, displayHeightPixels - 1)
        var right = scaleCoordinate(rect.right, activityWidthPixels, displayWidthPixels).coerceIn(left + 1, displayWidthPixels)
        var bottom = scaleCoordinate(rect.bottom, activityHeightPixels, displayHeightPixels).coerceIn(top + 1, displayHeightPixels)
        if ((right - left) % 2 != 0) {
            if (right < displayWidthPixels) right += 1 else left = (left - 1).coerceAtLeast(0)
        }
        if ((bottom - top) % 2 != 0) {
            if (bottom < displayHeightPixels) bottom += 1 else top = (top - 1).coerceAtLeast(0)
        }
        return DisplayInsets(
            top = top,
            bottom = displayHeightPixels - bottom,
            left = left,
            right = displayWidthPixels - right,
        )
    }

    private fun scaleCoordinate(coordinate: Int, source: Int, target: Int): Int =
        ((coordinate.toLong() * target + source / 2L) / source).toInt()
}

object SafeAreaStringCodec {
    fun encode(rect: DisplaySafeAreaRect): String =
        listOf(rect.left, rect.top, rect.right, rect.bottom).joinToString(",")

    fun decode(value: String?): DisplaySafeAreaRect? {
        val parts = value?.split(',') ?: return null
        if (parts.size != 4) return null
        return try {
            DisplaySafeAreaRect(
                left = parts[0].toInt(),
                top = parts[1].toInt(),
                right = parts[2].toInt(),
                bottom = parts[3].toInt(),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Aspect-ratio-preserving viewport layout inside the TV window. */
data class ViewportVideoLayout(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    companion object {
        fun fit(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int): ViewportVideoLayout {
            if (canvasWidth <= 0 || canvasHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return ViewportVideoLayout(
                    0f,
                    0f,
                    viewWidth.coerceAtLeast(0).toFloat(),
                    viewHeight.coerceAtLeast(0).toFloat(),
                )
            }
            val scale = minOf(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return ViewportVideoLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
    }
}

/** Normalized multitouch contact in `[0.0, 1.0]` coordinates. */
data class ViewportTouchContact(
    val id: Int,
    val x: Double,
    val y: Double,
    val down: Boolean,
)

/** Converts Android [MotionEvent] pointers into normalized [ViewportTouchContact] items. */
object ViewportTouchMapper {
    fun contacts(event: MotionEvent, viewWidth: Int, viewHeight: Int): List<ViewportTouchContact> =
        contacts(event, ViewportVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))

    fun contacts(event: MotionEvent, content: ViewportVideoLayout): List<ViewportTouchContact> {
        val width = content.width.coerceAtLeast(1f)
        val height = content.height.coerceAtLeast(1f)
        val action = event.actionMasked
        val liftedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val count = event.pointerCount
        val contacts = ArrayList<ViewportTouchContact>(count)
        for (index in 0 until count) {
            contacts.add(
                ViewportTouchContact(
                    id = event.getPointerId(index),
                    x = ((event.getX(index) - content.left).toDouble() / width).coerceIn(0.0, 1.0),
                    y = ((event.getY(index) - content.top).toDouble() / height).coerceIn(0.0, 1.0),
                    down = !allUp && index != liftedIndex,
                ),
            )
        }
        return contacts
    }
}
