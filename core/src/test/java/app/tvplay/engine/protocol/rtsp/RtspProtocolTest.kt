package app.tvplay.engine.protocol.rtsp

import app.tvplay.engine.av.ViewportTouchContact
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
