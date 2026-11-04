package app.tvplay.engine.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAvTest {

    @Test
    fun displayMetricMathSnapsFpsAndPhysicalMillimeters() {
        assertEquals(30, DisplayMetricMath.sanitizeFps(15))
        assertEquals(60, DisplayMetricMath.sanitizeFps(75))
        assertEquals(45, DisplayMetricMath.sanitizeFps(44))
        assertEquals(200, DisplayMetricMath.sanitizeWidthPhysicalMm(202))
        val size = DisplayMetricMath.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 320,
            basis = PhysicalSizeBasis.WIDTH,
        )
        assertEquals(320, size.widthMm)
        assertEquals(180, size.heightMm)
    }

    @Test
    fun displayScaleAndUiDensityKeepDimensionsEvenAndWithin4KUhdCap() {
        val base = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080)
        val scaled75 = DisplayScaleMath.applyPercent(base, 75)
        assertEquals(0, scaled75.widthPixels % 2)
        assertEquals(0, scaled75.heightPixels % 2)
        assertEquals(1440, scaled75.widthPixels)
        assertEquals(810, scaled75.heightPixels)

        val densitySmaller = UiDensityScale.apply(base, 75)
        assertTrue(densitySmaller.widthPixels > base.widthPixels)
        assertEquals(0, densitySmaller.widthPixels % 2)

        val uhd = TvDisplaySpec(widthPixels = 3840, heightPixels = 2160)
        val uhdSmaller = UiDensityScale.apply(uhd, 75)
        assertEquals(uhd.widthPixels, uhdSmaller.widthPixels)
        assertEquals(uhd.heightPixels, uhdSmaller.heightPixels)
    }

    @Test
    fun safeAreaClampsToDisplayBoundsAndDerivesSymmetricInsets() {
        val rect = DisplaySafeAreaRect(left = 40, top = 20, right = 1880, bottom = 1060)
        val insets = DisplaySafeAreaMath.toInsets(rect, 1920, 1080, 1920, 1080)
        assertEquals(40, insets.left)
        assertEquals(40, insets.right)
        assertEquals(20, insets.top)
        assertEquals(20, insets.bottom)
    }
}
