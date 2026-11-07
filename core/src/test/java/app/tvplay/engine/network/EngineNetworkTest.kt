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

    @Test
    fun ethernetIpv6CodecFastHelpersMatchFullFrameOperations() {
        val dstMac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
        val srcMac = byteArrayOf(0x02, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte())
        val ipv6Payload = ByteArray(64) { it.toByte() }.also {
            it[0] = 0x60 // IPv6 version 6
            it[6] = 58   // ICMPv6
            it[40] = 135.toByte() // Neighbor Solicitation (not NA 136)
        }
        val frame = EthernetIpv6Codec.build(srcMac, dstMac, ipv6Payload)
        assertTrue(EthernetIpv6Codec.isIpv6Frame(frame))
        assertTrue(EthernetIpv6Codec.macEquals(frame, 6, srcMac))
        assertFalse(EthernetIpv6Codec.macEquals(frame, 6, dstMac))

        val oversizedBuffer = ByteArray(128)
        ipv6Payload.copyInto(oversizedBuffer, 0)
        assertFalse(EthernetIpv6Codec.isNeighborAdvertisement(oversizedBuffer, ipv6Payload.size))
        val sliceFrame = EthernetIpv6Codec.build(srcMac, dstMac, oversizedBuffer, 0, ipv6Payload.size)
        org.junit.Assert.assertArrayEquals(frame, sliceFrame)
    }

    @Test
    fun p2pOwnershipGeneratesIccoaCompatible16CharSsidPrefix() {
        val prefix = P2pOwnership.prefix("app.tvplay", "scope-123")
        assertTrue(prefix.startsWith("DIRECT-vs-"))
        assertEquals(14, prefix.length)
        val candidateSsid = "${prefix}A9"
        assertEquals(16, candidateSsid.toByteArray(Charsets.UTF_8).size)
        assertTrue(P2pOwnership.canReclaim(isOwner = true, ssid = candidateSsid, recorded = null, prefix = prefix))
        assertFalse(P2pOwnership.canReclaim(isOwner = true, ssid = "${prefix}A999", recorded = null, prefix = prefix))
    }
}
