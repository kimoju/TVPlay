package app.tvplay.ui

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.tvplay.config.TvPreferencesStore
import app.tvplay.config.TvSettingsRepository
import app.tvplay.engine.av.LiveVideoCounters
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.protocol.rtsp.AirPlayDeviceInfo
import app.tvplay.engine.protocol.rtsp.AirPlayKnobState
import app.tvplay.engine.protocol.rtsp.AirPlaySession
import app.tvplay.engine.protocol.rtsp.AirPlaySessionListener
import app.tvplay.engine.protocol.rtsp.CarPlayMediaButton
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayPicture
import app.tvplay.runtime.CarPlayBackgroundSession
import app.tvplay.runtime.PendingReconnect
import app.tvplay.ui.stage.StageFpsHudOverlay
import app.tvplay.ui.stage.StagePictureTuningOverlay
import app.tvplay.ui.stage.TvStageActivity
import app.tvplay.ui.stage.fpsCounterColor
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.ui.workbench.SettingsCategory
import app.tvplay.ui.workbench.TvWorkbenchActivity
import app.tvplay.ui.workbench.WorkbenchHeroStage
import app.tvplay.ui.workbench.WorkbenchSettingsTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@Config(sdk = [34])
class TvUiTest {

    private fun collectAllViews(root: View): List<View> {
        val result = mutableListOf<View>()
        fun traverse(view: View) {
            result.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    traverse(view.getChildAt(i))
                }
            }
        }
        traverse(root)
        return result
    }

    private fun collectAllText(root: View): List<String> =
        collectAllViews(root)
            .filterIsInstance<TextView>()
            .map { it.text?.toString().orEmpty() }
            .filter { it.isNotBlank() }

    @Test
    fun workbenchHeroStageAndSettingsTabsExerciseAllModesAndTabsWithoutCrash() {
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        val workbenchController = Robolectric.buildActivity(TvWorkbenchActivity::class.java).setup()
        val workbench = workbenchController.get()
        assertFalse(workbench.isFinishing)

        val stagePaneField = TvWorkbenchActivity::class.java.getDeclaredField("stagePane").apply {
            isAccessible = true
        }
        val settingsPaneField = TvWorkbenchActivity::class.java.getDeclaredField("settingsPane").apply {
            isAccessible = true
        }
        val stagePane = stagePaneField.get(workbench) as WorkbenchHeroStage
        val settingsPane = settingsPaneField.get(workbench) as WorkbenchSettingsTabs
        val repo = TvSettingsRepository(workbench)

        // Switch transport mode to USB Wired via hero stage pill
        val usbModeBtn = collectAllViews(stagePane.root).filterIsInstance<TextView>().first {
            it.text.toString().endsWith("USB 有线直连")
        }
        usbModeBtn.performClick()
        assertFalse(repo.connection.wirelessEnabled)
        val launchBtnTextUsb = (stagePane.primaryLaunchButton as TextView).text.toString()
        assertTrue(launchBtnTextUsb.contains("USB"))

        // Switch transport mode back to Wireless via hero stage pill
        val wirelessModeBtn = collectAllViews(stagePane.root).filterIsInstance<TextView>().first {
            it.text.toString().endsWith("无线 CarPlay")
        }
        wirelessModeBtn.performClick()
        assertTrue(repo.connection.wirelessEnabled)

        // Switch wireless sub-mode between LAN Wi-Fi and Wi-Fi Direct
        val lanWifiBtn = collectAllViews(stagePane.root).filterIsInstance<TextView>().first {
            "家庭局域网" in it.text.toString()
        }
        lanWifiBtn.performClick()
        assertEquals(TvWirelessMode.LAN_WIFI, repo.connection.wirelessHotspotMode)

        val wifiDirectBtn = collectAllViews(stagePane.root).filterIsInstance<TextView>().first {
            it.text.toString().endsWith("Wi-Fi Direct 直连")
        }
        wifiDirectBtn.performClick()
        assertEquals(TvWirelessMode.WIFI_DIRECT, repo.connection.wirelessHotspotMode)

        // Exercise every settings tab (CONNECTION, DISPLAY, AUDIO, ABOUT) and click cards
        for (category in SettingsCategory.entries) {
            settingsPane.selectCategory(category)
            val texts = collectAllText(settingsPane.root)
            assertTrue("Tab $category should render non-empty cards", texts.isNotEmpty())

            val clickableCards = collectAllViews(settingsPane.root).filter { view ->
                view.isClickable && view !is TextView
            }
            assertTrue("Tab $category should have interactive setting cards", clickableCards.isNotEmpty())
        }

        // Test DISPLAY tab cycling resolution scale, UI density, FPS, HEVC, Night Mode
        settingsPane.selectCategory(SettingsCategory.DISPLAY)
        val initialFps = repo.display.fps
        val fpsCard = collectAllViews(settingsPane.root).last { view ->
            view is ViewGroup && view.isClickable && collectAllText(view).any { "60 FPS 高帧率投屏" in it }
        }
        fpsCard.performClick()
        assertNotEquals(initialFps, repo.display.fps)

        // Test AUDIO tab toggling microphone and audio focus
        settingsPane.selectCategory(SettingsCategory.AUDIO)
        val initialMic = repo.audio.microphoneEnabled
        val micCard = collectAllViews(settingsPane.root).last { view ->
            view is ViewGroup && view.isClickable && collectAllText(view).any { "启用电视 / 遥控器语音麦克风" in it }
        }
        micCard.performClick()
        assertEquals(!initialMic, repo.audio.microphoneEnabled)

        // Test Choose iPhone dialog (empty bonded list shows Chinese guidance dialog)
        val showChooseMethod = TvWorkbenchActivity::class.java.getDeclaredMethod(
            "showChooseIphoneDialog",
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        showChooseMethod.invoke(workbench, false)

        workbenchController.pause().stop().destroy()
    }

    @Test
    fun stageActivityExercisesQuickControlMenuPictureTuningFpsHudDpadTouchAndSessionHandoff() {
        val context = RuntimeEnvironment.getApplication()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()

        val stageController = Robolectric.buildActivity(
            TvStageActivity::class.java,
            Intent().putExtra("wireless", true),
        ).setup()
        val stage = stageController.get()
        assertFalse(stage.isFinishing)

        // 1. Open quick control overlay via MENU key and exercise quick buttons
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))

        val quickOverlayField = TvStageActivity::class.java.getDeclaredField("quickControlOverlay").apply {
            isAccessible = true
        }
        var quickOverlay = quickOverlayField.get(stage) as? View
        assertNotNull(quickOverlay)

        // Click "分辨率缩放" button inside quick control overlay
        val initialScale = TvPreferencesStore.loadDisplayScalePercent(stage)
        val scaleButton = collectAllViews(quickOverlay!!).filterIsInstance<TextView>().first {
            "分辨率缩放" in it.text.toString()
        }
        scaleButton.performClick()
        assertNotEquals(initialScale, TvPreferencesStore.loadDisplayScalePercent(stage))
        assertTrue(PendingReconnect.isPending())

        // Click "视频帧率" button inside refreshed quick control overlay
        quickOverlay = quickOverlayField.get(stage) as? View
        val initialStageFps = TvPreferencesStore.loadFps(stage)
        val fpsButton = collectAllViews(quickOverlay!!).filterIsInstance<TextView>().first {
            "视频帧率" in it.text.toString()
        }
        fpsButton.performClick()
        assertNotEquals(initialStageFps, TvPreferencesStore.loadFps(stage))

        // Click "实时帧率浮窗 (FPS)" button
        quickOverlay = quickOverlayField.get(stage) as? View
        val fpsHudButton = collectAllViews(quickOverlay!!).filterIsInstance<TextView>().first {
            "实时帧率浮窗" in it.text.toString()
        }
        fpsHudButton.performClick()
        assertTrue(TvPreferencesStore.loadFpsCounter(stage))

        // Click "CarPlay 外观" button
        quickOverlay = quickOverlayField.get(stage) as? View
        val nightButton = collectAllViews(quickOverlay!!).filterIsInstance<TextView>().first {
            "CarPlay 外观" in it.text.toString()
        }
        nightButton.performClick()

        // Click "画面色彩与对比度调节" button to open StagePictureTuningOverlay
        quickOverlay = quickOverlayField.get(stage) as? View
        val pictureButton = collectAllViews(quickOverlay!!).filterIsInstance<TextView>().first {
            "画面色彩与对比度调节" in it.text.toString()
        }
        pictureButton.performClick()

        val pictureOverlayField = TvStageActivity::class.java.getDeclaredField("pictureTuningOverlay").apply {
            isAccessible = true
        }
        val pictureOverlay = pictureOverlayField.get(stage) as? StagePictureTuningOverlay
        assertNotNull(pictureOverlay)
        val resetLabel = stage.getString(app.tvplay.R.string.picture_reset)
        val doneLabel = stage.getString(app.tvplay.R.string.picture_done)
        val doneBtn = collectAllViews(pictureOverlay!!).filterIsInstance<TextView>().first {
            it.text.toString() == doneLabel
        }
        doneBtn.performClick()

        // Directly exercise StagePictureTuningOverlay with adjustmentsAvailable = true
        var activePanelClosed = false
        val activePicturePanel = StagePictureTuningOverlay(
            context = stage,
            adjustmentsAvailable = true,
            initialPalette = TvColorScheme.DARK,
            close = { activePanelClosed = true },
        )
        val sliders = collectAllViews(activePicturePanel).filterIsInstance<SeekBar>()
        assertEquals(4, sliders.size)
        sliders.first().progress = 60
        val originalSwitch = collectAllViews(activePicturePanel).filterIsInstance<Switch>().first()
        originalSwitch.isChecked = true
        assertFalse(sliders.first().isEnabled)
        originalSwitch.isChecked = false
        assertTrue(sliders.first().isEnabled)

        val resetBtn = collectAllViews(activePicturePanel).filterIsInstance<TextView>().first {
            it.text.toString() == resetLabel
        }
        resetBtn.performClick()
        assertEquals(
            0,
            CarPlayPicture.value(CarPlayPicture.preferences(context), CarPlayPicture.BRIGHTNESS),
        )
        val activeDoneBtn = collectAllViews(activePicturePanel).filterIsInstance<TextView>().first {
            it.text.toString() == doneLabel
        }
        activeDoneBtn.performClick()
        assertTrue(activePanelClosed)

        // 2. Exercise all CarPlayStatus transitions
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
            statusMethod.invoke(stage, status)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // 3. Simulate active streaming state, first video frame, D-pad knob/media keys, and touch events
        val streamingActiveField = TvStageActivity::class.java.getDeclaredField("streamingActive").apply {
            isAccessible = true
        }
        val firstFrameField = TvStageActivity::class.java.getDeclaredField("firstFrameRendered").apply {
            isAccessible = true
        }
        streamingActiveField.setBoolean(stage, true)
        firstFrameField.setBoolean(stage, true)
        CarPlayBackgroundSession.active = true
        val refreshVisibility = TvStageActivity::class.java.getDeclaredMethod("refreshOverlayVisibility").apply {
            isAccessible = true
        }
        refreshVisibility.invoke(stage)

        // Dispatch D-pad and media keys through TvStageActivity and TvRemoteInputController
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        stage.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))

        var knobEvents = 0
        var mediaEvents = 0
        val controllerHandled = app.tvplay.remote.TvRemoteInputController.dispatchKeyEvent(
            context = stage,
            event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
            legacySiriPresses = mutableSetOf(),
            menuOpen = false,
            suppressBackReleaseAfterLongPress = false,
            setSuppressBackRelease = {},
            hasActiveScreenStream = true,
            sessionActive = true,
            sendKnob = { _, _ ->
                knobEvents++
                true
            },
            sendMediaButton = {
                mediaEvents++
                true
            },
            onToggleQuickMenu = {},
            onOpenQuickMenu = {},
            onRequestSiri = {},
        )
        assertEquals(true, controllerHandled)
        assertEquals(1, knobEvents)

        // Dispatch touch events
        val now = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 500f, 300f, 0)
        val upEvent = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, 500f, 300f, 0)
        assertTrue(stage.onTouchEvent(downEvent))
        assertTrue(stage.onTouchEvent(upEvent))
        downEvent.recycle()
        upEvent.recycle()

        // 4. Test returnToWorkbench while streamingActive == true keeps CarPlayBackgroundSession alive
        val returnMethod = TvStageActivity::class.java.getDeclaredMethod("returnToWorkbench").apply {
            isAccessible = true
        }
        returnMethod.invoke(stage)
        assertFalse("Stage should stay alive in background while streamingActive is true", stage.isFinishing)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.active)

        // 5. Test disconnectAndExit cleanly stops session and finishes TvStageActivity
        val disconnectMethod = TvStageActivity::class.java.getDeclaredMethod("disconnectAndExit").apply {
            isAccessible = true
        }
        disconnectMethod.invoke(stage)
        assertTrue(stage.isFinishing)
        assertFalse(CarPlayBackgroundSession.hasSession())

        stageController.pause().stop().destroy()

        // 6. Exercise USB mode stage launch and verify USB_DEVICE_ATTACHED does not hijack wireless mode
        val usbStageController = Robolectric.buildActivity(
            TvStageActivity::class.java,
            Intent().putExtra("wireless", false),
        ).setup()
        val usbStage = usbStageController.get()
        assertFalse(usbStage.isFinishing)
        assertFalse(TvPreferencesStore.loadWirelessEnabled(usbStage))
        usbStageController.newIntent(Intent().putExtra("wireless", true))
        assertTrue(TvPreferencesStore.loadWirelessEnabled(usbStage))
        // Spurious USB cable attachment while in Wireless mode must NOT flip wirelessEnabled back to false
        usbStageController.newIntent(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        assertTrue(TvPreferencesStore.loadWirelessEnabled(usbStage))
        usbStageController.pause().stop().destroy()
    }

    @Test
    fun stageFpsHudOverlayUpdatesTextAndColorsAcrossFpsThresholds() {
        val context = RuntimeEnvironment.getApplication()
        var currentCounters: LiveVideoCounters? = LiveVideoCounters(
            received = 0L,
            rendered = 0L,
            decodeNanos = 0L,
            decodeSamples = 0L,
        )
        val handler = android.os.Handler(Looper.getMainLooper())
        val hud = StageFpsHudOverlay(context, handler) { currentCounters }

        hud.start()
        assertEquals(View.VISIBLE, hud.view.visibility)
        shadowOf(Looper.getMainLooper()).idle()

        currentCounters = LiveVideoCounters(
            received = 60L,
            rendered = 60L,
            decodeNanos = 180_000_000L,
            decodeSamples = 60L,
        )
        hud.stop()
        assertEquals(View.GONE, hud.view.visibility)

        assertEquals(0xFF4CD964.toInt(), fpsCounterColor(60))
        assertEquals(0xFFFFCC00.toInt(), fpsCounterColor(30))
        assertEquals(0xFFFF3B30.toInt(), fpsCounterColor(15))
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

    @Test
    fun stageWaitingOverlayPreservesHotspotConfigurationErrorMessage() {
        val context = RuntimeEnvironment.getApplication()
        val msg = "当前连接的路由器 Wi-Fi (Home5G) 需要密码，请在「连接与配对」中填写该 Wi-Fi 密码"
        val status = CarPlayStatus.Failed(
            message = msg,
            startupFailure = app.tvplay.engine.network.WirelessStartupFailure.HOTSPOT_CONFIGURATION,
        )
        assertEquals(
            msg,
            app.tvplay.ui.stage.StageWaitingOverlay.friendlyStage(
                context = context,
                status = status,
                wirelessEnabled = true,
                wirelessHotspotMode = TvWirelessMode.LAN_WIFI,
            ),
        )
    }
}
