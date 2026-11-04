package app.tvplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SystemBarSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun defaultsHideBothSystemBarsAndLeaveHarmanOff() {
        assertTrue(AirPlayPersistence.loadHideTopBar(context))
        assertTrue(AirPlayPersistence.loadHideBottomBar(context))
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))
    }

    @Test fun independentCombinationsRoundTrip() {
        for (hideTop in listOf(false, true)) {
            for (hideBottom in listOf(false, true)) {
                AirPlayPersistence.saveHideTopBar(context, hideTop)
                AirPlayPersistence.saveHideBottomBar(context, hideBottom)
                assertEquals(hideTop, AirPlayPersistence.loadHideTopBar(context))
                assertEquals(hideBottom, AirPlayPersistence.loadHideBottomBar(context))
            }
        }
    }

    @Test fun harmanFullscreenRoundTrips() {
        AirPlayPersistence.saveHarmanFullscreen(context, true)
        assertTrue(AirPlayPersistence.loadHarmanFullscreen(context))
        AirPlayPersistence.saveHarmanFullscreen(context, false)
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))
    }
}
