package app.tvplay.engine.protocol.aap

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Google Android Auto (AOA2 USB & Wireless GAL/WPP) framing & accessory handshake codec.
 *
 * Supports:
 * - AOA2 USB control request constants (`GET_PROTOCOL = 51`, `SEND_STRING = 52`, `START_ACCESSORY = 53`)
 *   and official Google AOA2 accessory product IDs (`0x2D00`, `0x2D01`, `0x2D04`, `0x2D05`).
 * - GAL (Google Automotive Link) frame header encoding & decoding:
 *   `[channelId:1B][flags:1B][frameLength:2B BE][totalLength:4B BE if FIRST && !LAST]`.
 */
object AndroidAutoProtocol {
    const val GOOGLE_AOA_VENDOR_ID = 0x18D1
    const val AOA_PRODUCT_ID_ACCESSORY = 0x2D00
    const val AOA_PRODUCT_ID_ACCESSORY_ADB = 0x2D01
    const val AOA_PRODUCT_ID_ACCESSORY_AUDIO = 0x2D04
    const val AOA_PRODUCT_ID_ACCESSORY_AUDIO_ADB = 0x2D05

    const val AOA_REQUEST_GET_PROTOCOL = 51
    const val AOA_REQUEST_SEND_STRING = 52
    const val AOA_REQUEST_START_ACCESSORY = 53

    const val AOA_STRING_MANUFACTURER = 0
    const val AOA_STRING_MODEL = 1
    const val AOA_STRING_DESCRIPTION = 2
    const val AOA_STRING_VERSION = 3
    const val AOA_STRING_URI = 4
    const val AOA_STRING_SERIAL = 5

    const val WIRELESS_GAL_TCP_PORT = 5277

    // GAL frame flags
    const val FLAG_FIRST_FRAME = 0x01
    const val FLAG_LAST_FRAME = 0x02
    const val FLAG_CONTROL_MESSAGE = 0x04
    const val FLAG_ENCRYPTED = 0x08
    const val FLAG_BULK_SINGLE = FLAG_FIRST_FRAME or FLAG_LAST_FRAME

    // Standard GAL channel IDs
    const val CHANNEL_CONTROL = 0
    const val CHANNEL_INPUT = 1
    const val CHANNEL_SENSOR = 2
    const val CHANNEL_VIDEO = 3
    const val CHANNEL_MEDIA_AUDIO = 4
    const val CHANNEL_SPEECH_AUDIO = 5
    const val CHANNEL_SYSTEM_AUDIO = 6
    const val CHANNEL_AV_INPUT = 7
    const val CHANNEL_BLUETOOTH = 8

    // Control message IDs
    const val MSG_VERSION_REQUEST = 0x0001
    const val MSG_VERSION_RESPONSE = 0x0002
    const val MSG_SSL_HANDSHAKE = 0x0003
    const val MSG_AUTH_COMPLETE = 0x0004
    const val MSG_SERVICE_DISCOVERY_REQUEST = 0x0005
    const val MSG_SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val MSG_CHANNEL_OPEN_REQUEST = 0x0007
    const val MSG_CHANNEL_OPEN_RESPONSE = 0x0008
    const val MSG_PING_REQUEST = 0x000B
    const val MSG_PING_RESPONSE = 0x000C
    const val MSG_NAVIGATION_FOCUS_REQUEST = 0x000D
    const val MSG_NAVIGATION_FOCUS_RESPONSE = 0x000E
    const val MSG_BYEBYE_REQUEST = 0x000F
    const val MSG_BYEBYE_RESPONSE = 0x0010
    const val MSG_AUDIO_FOCUS_REQUEST = 0x0012
    const val MSG_AUDIO_FOCUS_RESPONSE = 0x0013

    data class GalFrame(
        val channelId: Int,
        val flags: Int,
        val messageType: Int,
        val payload: ByteArray,
        val totalMultiFrameLength: Int? = null,
    ) {
        val isFirstFrame: Boolean get() = (flags and FLAG_FIRST_FRAME) != 0
        val isLastFrame: Boolean get() = (flags and FLAG_LAST_FRAME) != 0
        val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
        val isControlMessage: Boolean get() = (flags and FLAG_CONTROL_MESSAGE) != 0
    }

    fun isAoaAccessoryDevice(vendorId: Int, productId: Int): Boolean =
        vendorId == GOOGLE_AOA_VENDOR_ID && (
            productId == AOA_PRODUCT_ID_ACCESSORY ||
                productId == AOA_PRODUCT_ID_ACCESSORY_ADB ||
                productId == AOA_PRODUCT_ID_ACCESSORY_AUDIO ||
                productId == AOA_PRODUCT_ID_ACCESSORY_AUDIO_ADB
            )

    fun encodeVersionRequest(major: Int = 1, minor: Int = 7): ByteArray {
        val body = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putShort(MSG_VERSION_REQUEST.toShort())
            .putShort(major.toShort())
            .putShort(minor.toShort())
            .array()
        return encodeRawGalPacket(
            channelId = CHANNEL_CONTROL,
            flags = FLAG_BULK_SINGLE,
            body = body,
        )
    }

    fun encodeGalFrame(frame: GalFrame): ByteArray {
        val body = ByteBuffer.allocate(2 + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(frame.messageType.toShort())
            .put(frame.payload)
            .array()
        return encodeRawGalPacket(
            channelId = frame.channelId,
            flags = frame.flags,
            body = body,
            totalLength = frame.totalMultiFrameLength,
        )
    }

    fun encodeRawGalPacket(
        channelId: Int,
        flags: Int,
        body: ByteArray,
        totalLength: Int? = null,
    ): ByteArray {
        val isFirstOnly = (flags and FLAG_FIRST_FRAME) != 0 && (flags and FLAG_LAST_FRAME) == 0
        val headerSize = if (isFirstOnly) 8 else 4
        val packet = ByteArray(headerSize + body.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.put((channelId and 0xFF).toByte())
        buffer.put((flags and 0xFF).toByte())
        buffer.putShort(body.size.toShort())
        if (isFirstOnly) {
            buffer.putInt(totalLength ?: body.size)
        }
        buffer.put(body)
        return packet
    }

    fun decodeGalPacket(packet: ByteArray): GalFrame {
        require(packet.size >= 6) { "GAL packet too short: ${packet.size}" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val channelId = buffer.get().toInt() and 0xFF
        val flags = buffer.get().toInt() and 0xFF
        val frameLength = buffer.short.toInt() and 0xFFFF
        val isFirstOnly = (flags and FLAG_FIRST_FRAME) != 0 && (flags and FLAG_LAST_FRAME) == 0
        val totalLen = if (isFirstOnly) {
            require(packet.size >= 10) { "Multi-frame GAL first packet requires 8-byte header" }
            buffer.int
        } else {
            null
        }
        require(buffer.remaining() >= frameLength && frameLength >= 2) {
            "Invalid GAL frameLength=$frameLength remaining=${buffer.remaining()}"
        }
        val msgType = buffer.short.toInt() and 0xFFFF
        val payload = ByteArray(frameLength - 2)
        buffer.get(payload)
        return GalFrame(
            channelId = channelId,
            flags = flags,
            messageType = msgType,
            payload = payload,
            totalMultiFrameLength = totalLen,
        )
    }
}
