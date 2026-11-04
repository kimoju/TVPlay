package app.tvplay.config

import android.Manifest
import android.os.Build
import app.tvplay.engine.av.PhysicalSizeBasis
import app.tvplay.engine.session.TvWirelessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
