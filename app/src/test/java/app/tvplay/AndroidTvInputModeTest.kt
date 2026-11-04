package app.tvplay

import android.content.pm.PackageManager
import android.content.res.Configuration
import app.tvplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AndroidTvInputModeTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun resetOverride() {
        AndroidTvInputMode.forceTvPlayModeForTest = null
    }

    @Test fun touchscreenKeepsAndroidNavigationWhenTvPlayConfigDisabled() {
        AndroidTvInputMode.forceTvPlayModeForTest = false
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        assertFalse(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }

    @Test fun televisionUsesRemoteNavigationEvenWhenItReportsTouch() {
        AndroidTvInputMode.forceTvPlayModeForTest = false
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }

    @Test fun nonTouchDisplayUsesRemoteNavigation() {
        AndroidTvInputMode.forceTvPlayModeForTest = false
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
    }

    @Test fun tvPlayModeDefaultsToWifiDirectAndBuildsWorkbench() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertTrue(AndroidTvInputMode.isTvPlayMode(context))
        assertTrue(AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context))
        assertEquals(
            WirelessHotspotMode.WIFI_P2P,
            AirPlayPersistence.loadWirelessHotspotMode(context),
        )
        val activity = Robolectric.buildActivity(TvPlayWorkbenchActivity::class.java).create().get()
        assertNotNull(activity.window.decorView)
    }
}
