package app.tvplay.engine.protocol.carlink

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * ICCOA CarLink (`com.ucar.protocol`) framing, channel definitions, method indices,
 * and USB AOA2 preamble builder.
 */
enum class CarLinkSourceDevice(val value: Int) {
    CAR(0),
    PHONE(1);

    companion object {
        fun fromValue(v: Int): CarLinkSourceDevice =
            if ((v and 0x01) == 1) PHONE else CAR
    }
}

enum class CarLinkDataFormat(val value: Int) {
    RAW(0),
    PB3(1);

    companion object {
        fun fromValue(v: Int): CarLinkDataFormat =
            if ((v and 0x03) == 1) PB3 else RAW
    }
}

enum class CarLinkMessageType(val value: Int) {
    SEND(0),
    REQ(1),
    RES(2),
    SEND_SYNC(3);

    companion object {
        fun fromValue(v: Int): CarLinkMessageType =
            entries.firstOrNull { it.value == (v and 0x03) } ?: SEND
    }
}

enum class CarLinkCmdCategory(val value: Int) {
    NONE(0),
    CONTROL(1),
    SENSOR(2),
    AUTH(3),
    AUDIO(4),
    VIDEO(5),
    CERT(6),
    ACK(7);

    companion object {
        fun fromValue(v: Int): CarLinkCmdCategory =
            entries.firstOrNull { it.value == (v and 0xFF) } ?: NONE
    }
}

enum class CarLinkChannelType(val port: Int) {
    CUSTOM(0),
    UIBC(4321),
    RTSP(7236),
    RTP(15550),
    AUTH(57209),
    CONTROL(57219),
    MEDIA(57229),
    SENSOR(57239),
    CERT(57249),
    VIDEO(57259);

    companion object {
        fun fromPort(port: Int): CarLinkChannelType =
            entries.firstOrNull { it.port == port } ?: CUSTOM
    }
}

object CarLinkMethodIndex {
    // AUTH (CmdCategory = 3)
    const val AUTH_REQUEST: Short = 1
    const val AUTH_RESPONSE: Short = 2
    const val AUTH_CONFIRM: Short = 3

    // CERT (CmdCategory = 6)
    const val CAR_CERTIFICATE: Short = 1

    // CONTROL (CmdCategory = 1)
    const val HEARTBEAT: Short = 1
    const val GET_PORT_REQUEST: Short = 2
    const val GET_PORT_RESPONSE: Short = 3
    const val NOTIFY_CAR_TO_FOREGROUND: Short = 4
    const val NOTIFY_CAR_TO_BACKGROUND: Short = 5
    const val NOTIFY_MICROPHONE_STATE: Short = 6
    const val NOTIFY_MIRROR_STATE: Short = 7
    const val NOTIFY_AUDIO_PLAYER_STATE: Short = 8
    const val NOTIFY_PHONE_STATE: Short = 9
    const val NOTIFY_MUSIC_INFO: Short = 10
    const val NOTIFY_NAVIGATION_INFO: Short = 11
    const val CUSTOM_KEY_EVENT: Short = 12
    const val VR_CMD_TO_PHONE_MSG: Short = 13
    const val NOTIFY_CALL_HUNG_UP: Short = 14
    const val NOTIFY_SWITCH_DAY_OR_NIGHT: Short = 15
    const val AWAKEN_VOICE_ASSISTANT: Short = 16
    const val AUDIO_PLAYER_CONTROL: Short = 17
    const val NOTIFY_ADD_CAMERA: Short = 18
    const val NOTIFY_REMOVE_CAMERA: Short = 19
    const val CAMERA_STATE: Short = 20
    const val CAMERA_STATE_CHANGED: Short = 21
    const val BLUETOOTH_MAC: Short = 22
    const val DISCONNECT: Short = 23
    const val GET_UCAR_CONFIG_REQUEST: Short = 24
    const val GET_UCAR_CONFIG_RESPONSE: Short = 25
    const val GET_APP_LIST_REQUEST: Short = 26
    const val GET_APP_LIST_RESPONSE: Short = 27
    const val GET_APP_DETAIL_INFO_REQUEST: Short = 28
    const val GET_APP_DETAIL_INFO_RESPONSE: Short = 29
    const val INVOKE_APP: Short = 30
    const val APP_STATE_CHANGED: Short = 31
    const val APP_LIST_CHANGED: Short = 32
    const val POI_ADDRESS: Short = 33
    const val CALL_INFO: Short = 34
    const val PICK_UP_CALL: Short = 35
}

/**
 * Packed 1-byte `cmdDescription` field at byte offset 12 of [CarLinkMessageHeader].
 *
 * Bit layout (`com.ucar.protocol.CmdDescription`):
 * - bit 0: [CarLinkSourceDevice] (`0 = CAR`, `1 = PHONE`)
 * - bits 1..2: [CarLinkDataFormat] (`0 = RAW`, `1 = PB3`)
 * - bits 3..4: [CarLinkMessageType] (`0 = SEND`, `1 = REQ`, `2 = RES`, `3 = SEND_SYNC`)
 */
data class CarLinkCmdDescription(
    val source: CarLinkSourceDevice = CarLinkSourceDevice.CAR,
    val format: CarLinkDataFormat = CarLinkDataFormat.PB3,
    val messageType: CarLinkMessageType = CarLinkMessageType.SEND,
) {
    fun toByte(): Byte {
        val packed = (source.value and 0x01) or
            ((format.value and 0x03) shl 1) or
            ((messageType.value and 0x03) shl 3)
        return packed.toByte()
    }

    companion object {
        fun fromByte(b: Byte): CarLinkCmdDescription {
            val v = b.toInt() and 0xFF
            return CarLinkCmdDescription(
                source = CarLinkSourceDevice.fromValue(v and 0x01),
                format = CarLinkDataFormat.fromValue((v ushr 1) and 0x03),
                messageType = CarLinkMessageType.fromValue((v ushr 3) and 0x03),
            )
        }
    }
}

/**
 * 20-byte Big-Endian ICCOA CarLink message header (`com.ucar.protocol.MessageHeader`).
 *
 * Byte layout:
 * - `0..3`: `length` (total packet length including 20-byte header = `20 + bodyLength`)
 * - `4..7`: `seqId`
 * - `8..11`: `timestamp` (epoch seconds)
 * - `12`: `cmdDescription`
 * - `13`: `cmdCategory`
 * - `14..15`: `methodIndex`
 * - `16..17`: `reserved` (0)
 * - `18..19`: `checksum` (`CarLinkCrc16.checksum(headerBytes, 0, 18)`)
 */
data class CarLinkMessageHeader(
    val bodyLength: Int,
    val seqId: Int,
    val timestamp: Int = (System.currentTimeMillis() / 1000L).toInt(),
    val cmdDescription: CarLinkCmdDescription = CarLinkCmdDescription(),
    val cmdCategory: CarLinkCmdCategory = CarLinkCmdCategory.CONTROL,
    val methodIndex: Short = 0,
    val reserved: Short = 0,
) {
    val totalLength: Int
        get() = HEADER_LENGTH + bodyLength

    init {
        require(bodyLength in 0..MAX_BODY_LENGTH) { "Invalid CarLink bodyLength=$bodyLength" }
    }

    fun encode(): ByteArray {
        val bytes = ByteArray(HEADER_LENGTH)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(totalLength)
        buffer.putInt(seqId)
        buffer.putInt(timestamp)
        buffer.put(cmdDescription.toByte())
        buffer.put(cmdCategory.value.toByte())
        buffer.putShort(methodIndex)
        buffer.putShort(reserved)
        val crc = CarLinkCrc16.checksumShort(bytes, 0, 18)
        buffer.putShort(crc)
        return bytes
    }

    companion object {
        const val HEADER_LENGTH = 20
        const val MAX_BODY_LENGTH = 16 * 1024 * 1024

        fun decode(bytes: ByteArray, offset: Int = 0): CarLinkMessageHeader {
            require(offset >= 0 && offset + HEADER_LENGTH <= bytes.size) {
                "Need at least $HEADER_LENGTH bytes for CarLinkMessageHeader"
            }
            val expectedCrc = CarLinkCrc16.checksumShort(bytes, offset, 18)
            val buffer = ByteBuffer.wrap(bytes, offset, HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
            val totalLength = buffer.int
            require(totalLength in HEADER_LENGTH..(HEADER_LENGTH + MAX_BODY_LENGTH)) {
                "Invalid CarLink frame totalLength=$totalLength"
            }
            val seqId = buffer.int
            val timestamp = buffer.int
            val desc = CarLinkCmdDescription.fromByte(buffer.get())
            val category = CarLinkCmdCategory.fromValue(buffer.get().toInt() and 0xFF)
            val methodIndex = buffer.short
            val reserved = buffer.short
            val wireCrc = buffer.short
            require(wireCrc == expectedCrc) {
                "CarLink header CRC16 mismatch: wire=0x${(wireCrc.toInt() and 0xFFFF).toString(16)} " +
                    "expected=0x${(expectedCrc.toInt() and 0xFFFF).toString(16)}"
            }
            return CarLinkMessageHeader(
                bodyLength = totalLength - HEADER_LENGTH,
                seqId = seqId,
                timestamp = timestamp,
                cmdDescription = desc,
                cmdCategory = category,
                methodIndex = methodIndex,
                reserved = reserved,
            )
        }
    }
}

/**
 * Complete ICCOA CarLink wire frame (`20-byte header + body`).
 */
data class CarLinkFrame(
    val header: CarLinkMessageHeader,
    val body: ByteArray,
) {
    init {
        require(header.bodyLength == body.size) {
            "Header bodyLength=${header.bodyLength} does not match body.size=${body.size}"
        }
    }

    fun encode(): ByteArray {
        val headerBytes = header.encode()
        if (body.isEmpty()) return headerBytes
        val frame = ByteArray(headerBytes.size + body.size)
        System.arraycopy(headerBytes, 0, frame, 0, headerBytes.size)
        System.arraycopy(body, 0, frame, headerBytes.size, body.size)
        return frame
    }
}

object CarLinkFrameCodec {
    fun buildFrame(
        seqId: Int,
        category: CarLinkCmdCategory,
        methodIndex: Short,
        body: ByteArray = ByteArray(0),
        source: CarLinkSourceDevice = CarLinkSourceDevice.CAR,
        format: CarLinkDataFormat = CarLinkDataFormat.PB3,
        messageType: CarLinkMessageType = CarLinkMessageType.SEND,
        timestamp: Int = (System.currentTimeMillis() / 1000L).toInt(),
    ): CarLinkFrame {
        val header = CarLinkMessageHeader(
            bodyLength = body.size,
            seqId = seqId,
            timestamp = timestamp,
            cmdDescription = CarLinkCmdDescription(
                source = source,
                format = format,
                messageType = messageType,
            ),
            cmdCategory = category,
            methodIndex = methodIndex,
        )
        return CarLinkFrame(header = header, body = body)
    }

    fun buildAckFrame(requestHeader: CarLinkMessageHeader): CarLinkFrame =
        buildFrame(
            seqId = requestHeader.seqId,
            category = CarLinkCmdCategory.ACK,
            methodIndex = requestHeader.methodIndex,
            body = ByteArray(0),
            source = CarLinkSourceDevice.CAR,
            format = CarLinkDataFormat.RAW,
            messageType = CarLinkMessageType.RES,
        )

    @Throws(IOException::class)
    fun readFrame(input: InputStream): CarLinkFrame? {
        val headerBytes = ByteArray(CarLinkMessageHeader.HEADER_LENGTH)
        val first = input.read()
        if (first < 0) return null
        headerBytes[0] = first.toByte()
        readFully(input, headerBytes, 1, CarLinkMessageHeader.HEADER_LENGTH - 1)
        val header = CarLinkMessageHeader.decode(headerBytes)
        val body = ByteArray(header.bodyLength)
        if (body.isNotEmpty()) {
            readFully(input, body, 0, body.size)
        }
        return CarLinkFrame(header, body)
    }

    @Throws(IOException::class)
    fun writeFrame(output: OutputStream, frame: CarLinkFrame) {
        output.write(frame.encode())
        output.flush()
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var cursor = offset
        while (remaining > 0) {
            val read = input.read(buffer, cursor, remaining)
            if (read < 0) throw EOFException("Unexpected EOF while reading CarLink frame")
            cursor += read
            remaining -= read
        }
    }
}

/**
 * Builds the USB AOA2 accessory identification preamble (`com.share.connect.usb.AOAPreambleBuilder`).
 *
 * Format: `"%s;%s;%s;%s;%s;%s"` with:
 * - `carId`: 12 hex characters (6-byte Car Bluetooth MAC)
 * - `modelId`: 8 hex characters (4-byte Car Model ID)
 * - `carName`: UTF-8 string truncated to <= 16 bytes
 * - `pinCode`: 12 hex characters (hex-encoded 6-digit ASCII PIN, e.g., `"864209"` -> `"383634323039"`)
 * - `protocolVersion`: `"1.0"`
 * - `vendorData`: 4 hex characters (2-byte vendor data)
 */
object CarLinkAoaPreambleBuilder {
    private val HEX_12 = Regex("^[0-9A-Fa-f]{12}$")
    private val HEX_8 = Regex("^[0-9A-Fa-f]{8}$")
    private val HEX_4 = Regex("^[0-9A-Fa-f]{4}$")
    private val VERSION = Regex("^[0-9]+\\.[0-9]+$")

    fun encodePinToHex12(pin6Digits: String): String {
        val clean = pin6Digits.trim()
        if (HEX_12.matches(clean) && clean.any { it !in '0'..'9' }) {
            return clean.uppercase(Locale.US)
        }
        val sixDigits = clean.filter { it.isDigit() }.padEnd(6, '0').take(6)
        return sixDigits.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun truncateCarName(carName: String, maxBytes: Int = 16): String {
        val trimmed = carName.trim().ifEmpty { "TVPlay" }
        val utf8 = trimmed.toByteArray(Charsets.UTF_8)
        if (utf8.size <= maxBytes) return trimmed
        val builder = StringBuilder()
        var usedBytes = 0
        for (ch in trimmed) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (usedBytes + chBytes > maxBytes - 3) break
            builder.append(ch)
            usedBytes += chBytes
        }
        builder.append("…")
        return builder.toString()
    }

    fun build(
        carIdHex12: String,
        modelIdHex8: String = "00010001",
        carName: String = "TVPlay",
        pinCode: String = "864209",
        protocolVersion: String = "1.0",
        vendorDataHex4: String = "0000",
    ): String {
        val normalizedCarId = carIdHex12.replace(":", "").trim().uppercase(Locale.US)
        val normalizedModelId = modelIdHex8.trim().uppercase(Locale.US)
        val normalizedVendor = vendorDataHex4.trim().uppercase(Locale.US)
        val normalizedPin = if (HEX_12.matches(pinCode) && pinCode.length == 12) {
            pinCode.uppercase(Locale.US)
        } else {
            encodePinToHex12(pinCode)
        }
        require(HEX_12.matches(normalizedCarId)) { "carId must be 12 hex characters: $carIdHex12" }
        require(HEX_8.matches(normalizedModelId)) { "modelId must be 8 hex characters: $modelIdHex8" }
        require(HEX_12.matches(normalizedPin)) { "pinCode must encode to 12 hex characters: $pinCode" }
        require(VERSION.matches(protocolVersion)) { "protocolVersion must be M.m: $protocolVersion" }
        require(HEX_4.matches(normalizedVendor)) { "vendorData must be 4 hex characters: $vendorDataHex4" }
        val safeName = truncateCarName(carName)
        return String.format(
            Locale.US,
            "%s;%s;%s;%s;%s;%s",
            normalizedCarId,
            normalizedModelId,
            safeName,
            normalizedPin,
            protocolVersion,
            normalizedVendor,
        )
    }
}
