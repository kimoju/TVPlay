package app.tvplay.engine.protocol.rtsp

import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.av.DisplayInsets
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.av.ViewportTouchContact
import app.tvplay.engine.session.TvIconAsset
import app.tvplay.engine.session.TvStreamServerConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspProtocolTest {

    @Test
    fun rtspMessageCodecParsesPipelinesAndRetainsPartialMessage() {
        val body1 = "hello".toByteArray(Charsets.US_ASCII)
        val req1 = "POST /pair-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body1.size}\r\n\r\n".toByteArray() + body1
        val partial = "GET /info RTSP/1.0\r\nCSeq: 2\r\n".toByteArray()
        val parsed = RtspMessageCodec.parseMessages(req1 + partial)
        assertEquals(1, parsed.messages.size)
        assertEquals("POST", parsed.messages[0].method)
        assertEquals("/pair-setup", parsed.messages[0].path)
        assertEquals("1", parsed.messages[0].headers["cseq"])
        assertArrayEquals(body1, parsed.messages[0].body)
        assertArrayEquals(partial, parsed.rest)
    }

    @Test
    fun bplistCodecEncodesAndDecodesNestedMapsListsAndPrimitives() {
        val original = linkedMapOf<String, Any?>(
            "model" to "TVPlay,1",
            "statusFlags" to 4L,
            "features" to 0x100029ffL,
            "active" to true,
            "modes" to listOf("Knob", "Touch"),
            "bytes" to byteArrayOf(1, 2, 3, 4),
        )
        val encoded = BplistCodec.encode(original)
        val decoded = BplistCodec.decode(encoded) as Map<*, *>
        assertEquals("TVPlay,1", decoded["model"])
        assertEquals(4L, decoded["statusFlags"])
        assertEquals(true, decoded["active"])
        assertEquals(listOf("Knob", "Touch"), decoded["modes"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded["bytes"] as ByteArray)
    }

    @Test
    fun hidKnobAndTouchReportsEncodeExpectedBinaryLayout() {
        val knob = AirPlayHid.knobReport(
            AirPlayKnobState(select = true, home = false, back = true, x = -1, y = 1, wheel = 2),
        )
        assertEquals(4, knob.size)
        assertEquals(0x05.toByte(), knob[0])
        assertEquals((-1).toByte(), knob[1])
        assertEquals(1.toByte(), knob[2])
        assertEquals(2.toByte(), knob[3])

        val touch = AirPlayHid.touchReport(
            listOf(ViewportTouchContact(id = 0, down = true, x = 512.0, y = 256.0)),
        )
        assertEquals(12, touch.size)
        assertEquals(0.toByte(), touch[0])
        assertEquals(1.toByte(), touch[1])
        assertTrue(touch[2] == 0x00.toByte() && touch[3] == 0x02.toByte())
    }

    @Test
    fun rtspInfoPayloadEncodesOemIconsSafeAreaRightHandDriveAndTvKnobDescriptor() {
        val fakeIcon = TvIconAsset(widthPixels = 120, heightPixels = 120, data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val display = TvDisplaySpec(
            widthPixels = 1920,
            heightPixels = 1080,
            fps = 60,
            primaryInputDevice = 3,
            safeArea = DisplayInsets(top = 10, bottom = 10, left = 20, right = 20),
            safeAreaDrawOutside = true,
        )
        val config = TvStreamServerConfig(
            deviceName = "TVPlay",
            deviceId = "02:11:22:33:44:55",
            btMac = "02:11:22:33:44:55",
            sourceVersion = "950.7.1",
            main = display,
            icons = listOf(fakeIcon),
            oemLabel = "TVPlay",
            rightHandDrive = true,
        )

        val encoded = BplistCodec.encode(RtspInfoPayload.build(config))
        val decoded = BplistCodec.decode(encoded) as Map<*, *>

        assertEquals(true, decoded["rightHandDrive"])
        assertEquals(true, decoded["oemIconVisible"])
        assertEquals("TVPlay", decoded["oemIconLabel"])

        val oemIcons = decoded["oemIcons"] as? List<*>
        assertNotNull(oemIcons)
        assertEquals(1, oemIcons!!.size)
        val iconEntry = oemIcons[0] as Map<*, *>
        assertEquals(120L, iconEntry["widthPixels"])
        assertEquals(120L, iconEntry["heightPixels"])
        assertArrayEquals(fakeIcon.data, iconEntry["imageData"] as ByteArray)

        val displays = decoded["displays"] as List<*>
        val mainDisplay = displays[0] as Map<*, *>
        assertEquals(1920L, mainDisplay["widthPixels"])
        assertEquals(1080L, mainDisplay["heightPixels"])
        assertEquals(60L, mainDisplay["maxFPS"])
        assertEquals(3L, mainDisplay["primaryInputDevice"])
        val viewAreas = mainDisplay["viewAreas"] as List<*>
        val viewArea = viewAreas[0] as Map<*, *>
        val safeArea = viewArea["safeArea"] as Map<*, *>
        assertEquals(true, safeArea["drawUIOutsideSafeArea"])
        assertEquals(1880L, safeArea["widthPixels"])
        assertEquals(1060L, safeArea["heightPixels"])

        val hidDevices = decoded["hidDevices"] as List<*>
        assertEquals(4, hidDevices.size)

        val configNoIcons = config.copy(icons = emptyList(), rightHandDrive = false)
        val decodedNoIcons = BplistCodec.decode(BplistCodec.encode(RtspInfoPayload.build(configNoIcons))) as Map<*, *>
        assertEquals(false, decodedNoIcons["rightHandDrive"])
        assertFalse(decodedNoIcons.containsKey("oemIcons"))
    }
}
