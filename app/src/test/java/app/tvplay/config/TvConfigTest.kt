package app.tvplay.config

import android.Manifest
import android.os.Build
import app.tvplay.engine.av.DisplaySafeAreaRect
import app.tvplay.engine.av.PhysicalSizeBasis
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.ui.theme.AppAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvConfigTest {

    @Test
    fun hevcSupportRequiresHardwareDecoderUnlessSoftwareExplicitlyChosen() {
        val swOnly = listOf(HevcSupport.Decoder(name = "c2.android.hevc.decoder", hardware = false))
        val hwDecoder = listOf(HevcSupport.Decoder(name = "c2.amlogic.hevc.decoder", hardware = true))

        assertFalse(HevcSupport.usable(requested = true, softwareChosen = false, decoders = swOnly))
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = true, decoders = swOnly))
        assertTrue(HevcSupport.usable(requested = true, softwareChosen = false, decoders = hwDecoder))
        assertFalse(HevcSupport.usable(requested = false, softwareChosen = true, decoders = hwDecoder))
    }

    @Test
    fun wirelessAndWiredPermissionsAdaptBySdkAndWirelessMode() {
        val wifiDirect33 = WirelessPermissions.required(
            hotspotMode = TvWirelessMode.WIFI_DIRECT,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in wifiDirect33)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in wifiDirect33)

        val lanWifi33 = WirelessPermissions.required(
            hotspotMode = TvWirelessMode.LAN_WIFI,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
        )
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), lanWifi33)
        assertTrue(WiredPermissions.required(Build.VERSION_CODES.TIRAMISU).isEmpty())
    }

    @Test
    fun tvSettingsRepositoryPersistsDomainSettingsAcrossConnectionDisplayAudioAndIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TvSettingsRepository(context)

        repository.connection.wirelessEnabled = false
        repository.connection.wirelessHotspotMode = TvWirelessMode.LAN_WIFI
        repository.display.hevcEnabled = true
        repository.display.fps = 30
        repository.display.physicalSizeBasis = PhysicalSizeBasis.HEIGHT
        repository.audio.disableAudioOutput = true
        repository.audio.microphoneEnabled = true
        repository.identity.oemLabel = "Living Room TV"

        assertFalse(repository.connection.wirelessEnabled)
        assertEquals(TvWirelessMode.LAN_WIFI, repository.connection.wirelessHotspotMode)
        assertTrue(repository.display.hevcEnabled)
        assertEquals(30, repository.display.fps)
        assertEquals(PhysicalSizeBasis.HEIGHT, repository.display.physicalSizeBasis)
        assertTrue(repository.audio.disableAudioOutput)
        assertTrue(repository.audio.microphoneEnabled)
        assertEquals("Living Room TV", repository.identity.oemLabel)
    }

    @Test
    fun mfiAssetInstallerExtractsOfflineMfiAssetsAndBuildsCompleteSessionAndStreamConfigs() {
        val context = RuntimeEnvironment.getApplication()
        var signatureCount = 0
        val provider = MfiAssetInstaller.loadProvider(context) { count ->
            signatureCount = count
        }
        assertTrue(MfiAssetInstaller.directory(context).isDirectory)
        assertTrue(provider.readCertificate().isNotEmpty())
        val sig = provider.signChallenge(ByteArray(32) { (it + 1).toByte() })
        assertEquals(64, sig.size)
        assertEquals(32, signatureCount)

        TvPreferencesStore.saveRightHandDrive(context, true)
        TvPreferencesStore.saveOemLabel(context, "客厅电视")
        val display = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080, fps = 60, primaryInputDevice = 3)
        val streamConfig = MfiAssetInstaller.buildStreamServerConfig(context, main = display)
        assertTrue(streamConfig.rightHandDrive)
        assertEquals("客厅电视", streamConfig.oemLabel)
        assertTrue(streamConfig.icons.isNotEmpty())
        assertTrue(streamConfig.icons.first().data.isNotEmpty())

        val sessionConfig = MfiAssetInstaller.buildSessionConfig(
            context = context,
            transport = TvTransportMode.WIRED,
        )
        assertEquals(TvTransportMode.WIRED, sessionConfig.transport)
        assertEquals(6, sessionConfig.hostMac.size)
        assertEquals("TVPlay", sessionConfig.identification.name)

        val lanConfig = MfiAssetInstaller.buildSessionConfig(
            context = context,
            transport = TvTransportMode.WIRELESS,
            wirelessMode = TvWirelessMode.LAN_WIFI,
            lanWifiSsid = "",
        )
        assertEquals("", lanConfig.lanWifiSsid)
    }

    @Test
    fun tvPreferencesStoreRoundTripsAllDisplayAudioAndSafeAreaSettings() {
        val context = RuntimeEnvironment.getApplication()
        TvPreferencesStore.saveDisplayScalePercent(context, 115)
        assertEquals(115, TvPreferencesStore.loadDisplayScalePercent(context))

        TvPreferencesStore.saveUiScalePercent(context, 85)
        assertEquals(85, TvPreferencesStore.loadUiScalePercent(context))

        TvPreferencesStore.saveVideoSurfaceType(context, VideoSurfaceType.SURFACE_VIEW)
        assertEquals(VideoSurfaceType.SURFACE_VIEW, TvPreferencesStore.loadVideoSurfaceType(context))

        TvPreferencesStore.saveCarPlayNightMode(context, CarPlayNightMode.NIGHT)
        assertEquals(CarPlayNightMode.NIGHT, TvPreferencesStore.loadCarPlayNightMode(context))

        TvPreferencesStore.saveAppAppearance(context, AppAppearance.LIGHT)
        assertEquals(AppAppearance.LIGHT, TvPreferencesStore.loadAppAppearance(context))

        val chosenPreset = WaitingScreenColorPreset.entries.last()
        TvPreferencesStore.saveWaitingScreenColorPreset(context, chosenPreset)
        assertEquals(
            chosenPreset,
            TvPreferencesStore.loadWaitingScreenColorPreset(context),
        )
        assertNotNull(TvPreferencesStore.loadWaitingScreenColors(context))

        val rect = DisplaySafeAreaRect(left = 20, top = 10, right = 1900, bottom = 1070)
        TvPreferencesStore.saveSafeAreaRect(context, 1920, 1080, rect)
        assertEquals(rect, TvPreferencesStore.loadSafeAreaRect(context, 1920, 1080))
        TvPreferencesStore.clearSafeAreaRect(context, 1920, 1080)
        assertNull(TvPreferencesStore.loadSafeAreaRect(context, 1920, 1080))

        val wirelessBuf = app.tvplay.engine.av.MediaAudioBuffer.sanitizeWireless(400)
        val wiredBuf = app.tvplay.engine.av.MediaAudioBuffer.sanitizeWired(200)
        TvPreferencesStore.saveMediaBufferMillis(context, wireless = true, millis = wirelessBuf)
        TvPreferencesStore.saveMediaBufferMillis(context, wireless = false, millis = wiredBuf)
        assertEquals(wirelessBuf, TvPreferencesStore.loadMediaBufferMillis(context, wireless = true))
        assertEquals(wiredBuf, TvPreferencesStore.loadMediaBufferMillis(context, wireless = false))

        TvPreferencesStore.clearLockdownRecord(context)
        assertNull(TvPreferencesStore.loadLockdownRecord(context))
    }

    @Test
    fun sanitizeSavedPhoneClearsTvRemotesAndUnpairedDevicesWhileKeepingValidIphone() {
        val context = RuntimeEnvironment.getApplication()
        val remoteOnly = listOf(
            TvPlayBluetooth.PairedDevice(
                name = "小米蓝牙语音遥控器",
                address = "AA:BB:CC:DD:EE:01",
                isLikelyIphone = false,
                isAccessory = true,
            ),
        )
        TvPreferencesStore.savePairedPhone(context, "AA:BB:CC:DD:EE:01", "小米蓝牙语音遥控器")
        TvPlayBluetooth.sanitizeSavedPhone(context, remoteOnly)
        assertNull(TvPreferencesStore.loadPairedPhoneAddress(context))

        val pairedIphoneAndRemote = listOf(
            TvPlayBluetooth.PairedDevice(
                name = "iPhone 16 Pro",
                address = "11:22:33:44:55:66",
                isLikelyIphone = true,
                isAccessory = false,
            ),
            remoteOnly.single(),
        )
        TvPreferencesStore.savePairedPhone(context, "11:22:33:44:55:66", "iPhone 16 Pro")
        TvPlayBluetooth.sanitizeSavedPhone(context, pairedIphoneAndRemote)
        assertEquals("11:22:33:44:55:66", TvPreferencesStore.loadPairedPhoneAddress(context))

        // Unpaired phone address is cleared when bonded list has other devices
        TvPreferencesStore.savePairedPhone(context, "99:88:77:66:55:44", "Old iPhone")
        TvPlayBluetooth.sanitizeSavedPhone(context, pairedIphoneAndRemote)
        assertNull(TvPreferencesStore.loadPairedPhoneAddress(context))
    }
}
