package app.tvplay.engine.protocol.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2ProtocolTest {

    @Test
    fun parameterListPreservesOrderAndRepeatedIds() {
        val list = Iap2ParameterList.of(
            Iap2Parameter(0x0001, Iap2WireCodec.u16(10)),
            Iap2Parameter(0x0002, Iap2WireCodec.string("TVPlay")),
            Iap2Parameter(0x0001, Iap2WireCodec.u16(20)),
        )
        assertEquals(3, list.size)
        assertTrue(list.has(0x0001))
        assertEquals(2, list.all(0x0001).size)
        val encoded = list.encode()
        val decoded = Iap2ParameterList.parse(encoded)
        assertEquals(list, decoded)
        assertEquals("TVPlay", Iap2WireCodec.readString(decoded.require(0x0002).payload))
    }

    @Test
    fun csmFramerHandlesChunkedAndConcatenatedFramesWithLeadingNoise() {
        val framer = Iap2CsmFramer()
        val body1 = Iap2ParameterList.of(
            Iap2Parameter(1, Iap2WireCodec.u8(42)),
        ).encode()
        val body2 = Iap2ParameterList.of(
            Iap2Parameter(2, Iap2WireCodec.string("CarPlay")),
        ).encode()
        val frame1 = Iap2CsmFramer.encodeFrame(0x1D00, body1)
        val frame2 = Iap2CsmFramer.encodeFrame(0x1D02, body2)

        val stream = byteArrayOf(0x00, 0x11, 0x22) + frame1 + frame2
        val firstPart = framer.offer(stream.copyOfRange(0, 5))
        assertTrue(firstPart.isEmpty())
        val rest = framer.offer(stream.copyOfRange(5, stream.size))
        assertEquals(2, rest.size)
        assertEquals(0x1D00, rest[0].messageId)
        assertArrayEquals(body1, rest[0].payload)
        assertEquals(0x1D02, rest[1].messageId)
        assertArrayEquals(body2, rest[1].payload)
    }

    @Test
    fun splitForLinkChunksCompleteFrameWithoutLoss() {
        val payload = ByteArray(250) { (it and 0xff).toByte() }
        val encoded = Iap2CsmFramer.encodeFrame(0xAA00, payload)
        val chunks = Iap2CsmFramer.splitForLink(encoded, 64)
        assertEquals(4, chunks.size)
        val framer = Iap2CsmFramer()
        val reassembled = chunks.flatMap { framer.offer(it) }
        assertEquals(1, reassembled.size)
        assertEquals(0xAA00, reassembled[0].messageId)
        assertArrayEquals(payload, reassembled[0].payload)
    }

    @Test
    fun wireCodecEncodesAndDecodesIntegersAndStrings() {
        val u32Bytes = Iap2WireCodec.u32(0x12345678L)
        assertEquals(0x12345678L, Iap2WireCodec.readU32(u32Bytes))
        val strBytes = Iap2WireCodec.string("Apple CarPlay")
        assertNotNull(strBytes)
        assertEquals("Apple CarPlay", Iap2WireCodec.readString(strBytes))
    }
}
