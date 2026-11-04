package app.tvplay.engine.network

import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.session.TvStreamServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineNetworkTest {

    @Test
    fun bonjourProtocolBuildsExpectedAirPlayTxtRecords() {
        val identity = AccessoryIdentity.generate()
        val config = TvStreamServerConfig(
            deviceId = "AA:BB:CC:DD:EE:FF",
            deviceName = "TVPlay Living Room",
            btMac = "AA:BB:CC:DD:EE:FF",
            sourceVersion = "420.34",
            main = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080),
            model = "TVPlay,1",
        )
        val txt = CarPlayBonjourProtocol.airPlayTxtRecords(config, identity)
        assertEquals("AA:BB:CC:DD:EE:FF", txt["deviceid"])
        assertEquals("TVPlay,1", txt["model"])
        assertEquals(identity.publicKeyHex, txt["pk"])
        assertTrue(txt.containsKey("features"))
        assertEquals("0x4", txt["flags"])
    }

    @Test
    fun bluetoothRadioAndLinkChecksClassifyHardwareRealism() {
        assertEquals(
            BluetoothRadioCheck.NO_ADAPTER,
            BluetoothRadioCheck.blocker(adapterPresent = false, enabled = false, pausedByDiPlay = false),
        )
        assertEquals(
            BluetoothRadioCheck.RADIO_OFF,
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = false),
        )
        assertNull(
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = true),
        )
        assertNull(
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = true, pausedByDiPlay = false),
        )

        assertTrue(BluetoothLinkCheck.looksUnreal(connectMillis = 12L, bytesReceived = 0L))
        assertFalse(BluetoothLinkCheck.looksUnreal(connectMillis = 220L, bytesReceived = 0L))
        assertFalse(BluetoothLinkCheck.looksUnreal(connectMillis = 12L, bytesReceived = 64L))
        assertTrue(BluetoothLinkCheck.controlProvesUnreal(controlConnected = true, controlMillis = 10L))
    }

    @Test
    fun bonjourEventDiagnosticSummaryRedactsSensitiveEndpointDetails() {
        val resolved = CarPlayBonjourEvent.Resolved(
            CarPlayBonjourEndpoint(
                serviceName = "My Secret iPhone",
                host = "192.168.1.50",
                port = 7000,
                bluetoothId = "11:22:33:44:55:66",
            ),
        )
        val summary = resolved.diagnosticSummary()
        assertFalse("My Secret iPhone" in summary)
        assertFalse("192.168.1.50" in summary)
        assertFalse("11:22:33:44:55:66" in summary)
        assertTrue("family=IPv4" in summary)
    }
}
