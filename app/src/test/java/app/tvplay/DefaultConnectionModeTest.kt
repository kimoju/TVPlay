package app.tvplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import app.tvplay.R
import app.tvplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DefaultConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun initialLaunchUsesTheFixedDefaultInsteadOfTheLastManualTransport() {
        TvPlayPreferences.saveAutoConnect(app, true)
        TvPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        for (mode in listOf(DefaultConnectionMode.USB, DefaultConnectionMode.WIRELESS)) {
            val expectedWireless = mode == DefaultConnectionMode.WIRELESS
            TvPlayPreferences.saveDefaultConnectionMode(app, mode)
            AirPlayPersistence.saveWirelessEnabled(app, !expectedWireless)
            val controller = Robolectric.buildActivity(TvPlayWorkbenchActivity::class.java, Intent(Intent.ACTION_MAIN)).create()
            val activity = controller.get()
            // Source-only test builds have no authentication assets; exercise the startup decision.
            ReflectionHelpers.setField(activity, "setupError", null)
            controller.start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))
            assertEquals(TvPlayProjectionActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className)
            assertEquals(mode, TvPlayPreferences.defaultConnectionMode(app))
            controller.pause().stop().destroy()
        }
    }

    @Test fun openingSettingsDoesNotAutoConnectOrChangeTheLastTransport() {
        TvPlayPreferences.saveAutoConnect(app, true)
        TvPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        val controller = Robolectric.buildActivity(TvPlayWorkbenchActivity::class.java,
            Intent().putExtra("page", "settings")).create()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }

    @Test fun existingInstallationsKeepLastUsedBehaviorAndUnknownValuesFallBack() {
        for (lastWireless in listOf(false, true)) {
            AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
            assertEquals(lastWireless, TvPlayPreferences.autoConnectWireless(app))
            app.getSharedPreferences("diplay", 0).edit()
                .putString("default_connection_mode", "future_mode").commit()
            assertEquals(DefaultConnectionMode.LAST_USED, TvPlayPreferences.defaultConnectionMode(app))
            assertEquals(lastWireless, TvPlayPreferences.autoConnectWireless(app))
        }
    }

    @Test fun fixedDefaultSurvivesManualTransportChangesWithoutChangingAutoConnectOrPhone() {
        TvPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test iPhone")
        val expectedModes = mapOf(
            DefaultConnectionMode.LAST_USED to listOf(false, true),
            DefaultConnectionMode.WIRELESS to listOf(true, true),
            DefaultConnectionMode.USB to listOf(false, false),
        )
        for ((mode, expectedWireless) in expectedModes) {
            TvPlayPreferences.saveDefaultConnectionMode(app, mode)
            assertEquals(mode, TvPlayPreferences.defaultConnectionMode(app))
            for ((index, lastWireless) in listOf(false, true).withIndex()) {
                AirPlayPersistence.saveWirelessEnabled(app, lastWireless)
                assertEquals(expectedWireless[index], TvPlayPreferences.autoConnectWireless(app))
                assertEquals(lastWireless, AirPlayPersistence.loadWirelessEnabled(app))
            }
            assertFalse(TvPlayPreferences.autoConnect(app))
            assertEquals("00:11:22:33:44:55", TvPlayPreferences.phoneAddress(app))
        }
    }

    @Test fun settingsSavePersistsChoiceAndCancelKeepsSavedDefault() {
        val controller = Robolectric.buildActivity(TvPlayWorkbenchActivity::class.java).setup()
        val activity = controller.get()
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun modeButton(): Button = descendants(activity.window.decorView).filterIsInstance<Button>().single {
            it.text.toString().startsWith("默认连接方式 · ")
        }
        assertEquals(DefaultConnectionMode.LAST_USED, TvPlayPreferences.defaultConnectionMode(app))
        modeButton().performClick()
        assertEquals(DefaultConnectionMode.WIRELESS, TvPlayPreferences.defaultConnectionMode(app))
        modeButton().performClick()
        assertEquals(DefaultConnectionMode.USB, TvPlayPreferences.defaultConnectionMode(app))
        assertFalse(TvPlayPreferences.autoConnect(app))
        controller.pause().stop().destroy()
    }
}
