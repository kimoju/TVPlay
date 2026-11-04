package app.tvplay.ui

import android.content.Intent
import android.hardware.usb.UsbManager
import android.view.KeyEvent
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.ui.stage.TvStageActivity
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.workbench.SettingsCategory
import app.tvplay.ui.workbench.TvWorkbenchActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvUiTest {

    @Test
    fun workbenchAndStageLaunchWithoutCrashInWirelessAndUsbModes() {
        val workbenchController = Robolectric.buildActivity(TvWorkbenchActivity::class.java).setup()
        val workbench = workbenchController.get()
        assertFalse(workbench.isFinishing)

        // Exercise wireless stage launch, quick menu, picture tuning, and status transitions
        val wirelessStageController = Robolectric.buildActivity(
            TvStageActivity::class.java,
            Intent().putExtra("wireless", true),
        ).setup()
        val wirelessStage = wirelessStageController.get()
        assertFalse(wirelessStage.isFinishing)

        // Open quick control overlay via MENU key
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        // Close quick control overlay via BACK key
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))

        // Exercise all CarPlayStatus updates on TvStageActivity
        val statusMethod = TvStageActivity::class.java.getDeclaredMethod(
            "onCarPlayStatusChanged",
            CarPlayStatus::class.java,
        ).apply { isAccessible = true }
        val statuses = listOf(
            CarPlayStatus.DiscoveringMfi,
            CarPlayStatus.MfiReady,
            CarPlayStatus.StartingHotspot,
            CarPlayStatus.HotspotReady("DIRECT-TVPlay", "5GHz", 149, "02:00:00:00:00:01", "192.168.49.1", "Wi-Fi Direct"),
            CarPlayStatus.WaitingForPairedIphone,
            CarPlayStatus.ConnectingBluetooth,
            CarPlayStatus.RunningWireless,
            CarPlayStatus.WirelessActive,
            CarPlayStatus.DiscoveringIphone,
            CarPlayStatus.WaitingForIphone,
            CarPlayStatus.IphoneNotListed(false),
            CarPlayStatus.IphoneNotListed(true),
            CarPlayStatus.RequestingIphonePermission,
            CarPlayStatus.WaitingForReenumeration,
            CarPlayStatus.SelectingConfiguration,
            CarPlayStatus.OpeningDataPaths,
            CarPlayStatus.Pairing,
            CarPlayStatus.ConnectingControl,
            CarPlayStatus.AttachingNetwork,
            CarPlayStatus.RunningControl,
            CarPlayStatus.Failed("test failure", false, WirelessStartupFailure.FIRST_TCP_TIMEOUT, TvFailureReason.NO_PAIRED_IPHONE),
            CarPlayStatus.ControlEnded,
        )
        for (status in statuses) {
            statusMethod.invoke(wirelessStage, status)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Exercise picture tuning overlay
        val openPictureMethod = TvStageActivity::class.java.getDeclaredMethod("openPictureTuningOverlay").apply {
            isAccessible = true
        }
        openPictureMethod.invoke(wirelessStage)
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        wirelessStage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))

        wirelessStageController.pause().stop().destroy()

        // Exercise USB stage launch
        val usbStageController = Robolectric.buildActivity(
            TvStageActivity::class.java,
            Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED),
        ).setup()
        assertFalse(usbStageController.get().isFinishing)
        usbStageController.pause().stop().destroy()

        workbenchController.pause().stop().destroy()
    }

    @Test
    fun tvColorSchemeProvidesDistinctDarkAndLightTenFootPalettes() {
        val dark = TvColorScheme.of(true)
        val light = TvColorScheme.of(false)
        assertTrue(dark.night)
        assertFalse(light.night)
        assertNotEquals(dark.background, light.background)
        assertNotEquals(dark.primaryText, light.primaryText)
        assertNotEquals(0, dark.focusRing)
    }

    @Test
    fun appAppearanceResolvesNightFlagDeterministically() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("dark"))
        assertEquals(AppAppearance.LIGHT, AppAppearance.fromKey("light"))
        assertEquals(AppAppearance.AUTO, AppAppearance.fromKey("auto"))
    }

    @Test
    fun workbenchSettingsTabsExposeExpectedFourSectionOrder() {
        val tabs = SettingsCategory.entries
        assertEquals(
            listOf(
                SettingsCategory.CONNECTION,
                SettingsCategory.DISPLAY,
                SettingsCategory.AUDIO,
                SettingsCategory.ABOUT,
            ),
            tabs,
        )
    }
}
