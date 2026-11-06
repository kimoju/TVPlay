package app.tvplay.engine.protocol.iap2

import app.tvplay.engine.auth.DuplexByteStream
import app.tvplay.engine.session.TvAccessoryIdentitySpec
import java.io.ByteArrayOutputStream
import java.io.IOException

/** An iAP2 CSM body parameter: `u16 length | u16 parameterId | payload`. */
class Iap2Parameter(id: Int, payload: ByteArray) {
    val id: Int
    private val bytes: ByteArray

    init {
        require(id in 0..0xffff) { "CSM parameter id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_PARAM_BYTES - HEADER_BYTES) {
            "CSM parameter payload exceeds ${Iap2CsmFramer.MAX_PARAM_BYTES - HEADER_BYTES} bytes"
        }
        this.id = id
        bytes = payload.copyOf()
    }

    /** A defensive copy of the parameter payload, excluding its header. */
    val payload: ByteArray get() = bytes.copyOf()

    internal fun payloadView(): ByteArray = bytes

    override fun equals(other: Any?): Boolean =
        other is Iap2Parameter && id == other.id && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * id + bytes.contentHashCode()

    override fun toString(): String =
        "Iap2Parameter(id=0x${id.toString(16).padStart(4, '0')}, payload=${bytes.size} bytes)"

    companion object {
        const val HEADER_BYTES = 4
    }
}

/**
 * An ordered CSM parameter collection.
 *
 * A list is intentional: CSM permits repeated parameter IDs and preserves wire order. Callers
 * should use [first] or [all] rather than converting the body to a map.
 */
class Iap2ParameterList private constructor(
    private val parameters: List<Iap2Parameter>,
) {
    val size: Int get() = parameters.size

    fun isEmpty(): Boolean = parameters.isEmpty()

    fun asList(): List<Iap2Parameter> = parameters.toList()

    operator fun get(index: Int): Iap2Parameter = parameters[index]

    fun has(id: Int): Boolean = parameters.any { it.id == id }

    fun first(id: Int): Iap2Parameter? = parameters.firstOrNull { it.id == id }

    fun require(id: Int, description: String = "parameter 0x${id.toString(16).padStart(4, '0')}"): Iap2Parameter =
        first(id) ?: throw Iap2ProtocolException("Missing required $description")

    fun all(id: Int): List<Iap2Parameter> = parameters.filter { it.id == id }

    fun encode(): ByteArray {
        val encoded = ByteArrayOutputStream()
        for (parameter in parameters) {
            val next = Iap2CsmFramer.encodeParam(parameter.id, parameter.payloadView())
            require(next.size <= MAX_BODY_BYTES - encoded.size()) {
                "Encoded CSM parameters exceed the $MAX_BODY_BYTES-byte message body limit"
            }
            encoded.write(next)
        }
        return encoded.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is Iap2ParameterList && parameters == other.parameters

    override fun hashCode(): Int = parameters.hashCode()

    companion object {
        private const val MAX_BODY_BYTES =
            Iap2CsmFramer.MAX_FRAME_BYTES - Iap2CsmFramer.HEADER_BYTES

        val EMPTY: Iap2ParameterList = Iap2ParameterList(emptyList())

        fun of(parameters: Iterable<Iap2Parameter>): Iap2ParameterList =
            Iap2ParameterList(parameters.toList())

        fun of(vararg parameters: Iap2Parameter): Iap2ParameterList =
            of(parameters.asList())

        /** Parses a complete body and rejects truncated headers, invalid lengths, and trailing bytes. */
        fun parse(encoded: ByteArray): Iap2ParameterList {
            val parameters = ArrayList<Iap2Parameter>()
            var offset = 0
            while (offset < encoded.size) {
                if (encoded.size - offset < Iap2Parameter.HEADER_BYTES) {
                    throw Iap2ProtocolException("Truncated CSM parameter header")
                }
                val length = Iap2WireCodec.readU16(encoded, offset)
                if (length < Iap2Parameter.HEADER_BYTES || length > encoded.size - offset) {
                    throw Iap2ProtocolException("Invalid CSM parameter length $length")
                }
                parameters += Iap2Parameter(
                    id = Iap2WireCodec.readU16(encoded, offset + 2),
                    payload = encoded.copyOfRange(
                        offset + Iap2Parameter.HEADER_BYTES,
                        offset + length,
                    ),
                )
                offset += length
            }
            return Iap2ParameterList(parameters)
        }
    }
}

/** One decoded iAP2 Control Session Message (CSM). */
class Iap2Frame(messageId: Int, payload: ByteArray) {
    val messageId: Int
    private val body: ByteArray

    init {
        require(messageId in 0..0xffff) { "CSM message id must fit in u16" }
        require(payload.size <= Iap2CsmFramer.MAX_BODY_BYTES) {
            "CSM payload exceeds ${Iap2CsmFramer.MAX_BODY_BYTES} bytes"
        }
        this.messageId = messageId
        body = payload.copyOf()
    }

    /** A defensive copy of the body, excluding the CSM header. */
    val payload: ByteArray get() = body.copyOf()

    /** The complete six-byte-header CSM encoded byte count without allocating a copy. */
    val encodedSize: Int get() = Iap2CsmFramer.HEADER_BYTES + body.size

    fun body(): Iap2ParameterList = Iap2ParameterList.parse(body)

    /** A defensive copy of this frame's complete six-byte-header CSM encoding. */
    fun encodedFrame(): ByteArray = Iap2CsmFramer.encodeFrame(messageId, body)

    override fun equals(other: Any?): Boolean =
        other is Iap2Frame && messageId == other.messageId && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * messageId + body.contentHashCode()

    override fun toString(): String =
        "Iap2Frame(messageId=0x${messageId.toString(16).padStart(4, '0')}, payload=${body.size} bytes)"
}

/** A malformed, incomplete, or otherwise invalid iAP2 CSM value. */
open class Iap2ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Big-endian scalar and string codecs shared by every iAP2 endpoint body.
 *
 * The codec intentionally contains no endpoint IDs or business defaults. Optional values are
 * represented by nullable arguments at the builder layer, so an omitted value never becomes a
 * zero-filled wire parameter.
 */
object Iap2WireCodec {
    fun void(): ByteArray = ByteArray(0)

    fun u8(value: Int): ByteArray {
        require(value in 0..0xff) { "u8 value must be in 0..255" }
        return byteArrayOf(value.toByte())
    }

    fun i8(value: Int): ByteArray {
        require(value in -0x80..0x7f) { "i8 value must be in -128..127" }
        return byteArrayOf(value.toByte())
    }

    fun bool(value: Boolean): ByteArray = u8(if (value) 1 else 0)

    fun u16(value: Int): ByteArray {
        require(value in 0..0xffff) { "u16 value must be in 0..65535" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    fun i16(value: Int): ByteArray {
        require(value in -0x8000..0x7fff) { "i16 value must be in -32768..32767" }
        return u16(value and 0xffff)
    }

    fun u32(value: Long): ByteArray {
        require(value in 0..0xffff_ffffL) { "u32 value must be in 0..4294967295" }
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    fun u32(value: Int): ByteArray = u32(value.toLong())

    fun i32(value: Int): ByteArray = u32(value.toLong() and 0xffff_ffffL)

    fun u64(value: Long): ByteArray {
        require(value >= 0) { "u64 value must be non-negative in this API" }
        return byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    fun bytes(value: ByteArray): ByteArray = value.copyOf()

    fun string(value: String): ByteArray {
        require('\u0000' !in value) { "NUL-terminated strings must not contain U+0000" }
        return value.encodeToByteArray() + byteArrayOf(0)
    }

    fun strings(values: Iterable<String>): ByteArray {
        val output = ByteArrayOutputStream()
        for (value in values) output.write(string(value))
        return output.toByteArray()
    }

    fun u16List(values: Iterable<Int>): ByteArray {
        val materialized = values.toList()
        val output = ByteArray(materialized.size * 2)
        materialized.forEachIndexed { index, value ->
            require(value in 0..0xffff) { "u16[] value must be in 0..65535" }
            output[index * 2] = (value ushr 8).toByte()
            output[index * 2 + 1] = value.toByte()
        }
        return output
    }

    fun readU8(bytes: ByteArray, offset: Int = 0): Int = u8(bytes[offset])

    fun readI8(bytes: ByteArray, offset: Int = 0): Int = bytes[offset].toInt()

    fun readBool(bytes: ByteArray, offset: Int = 0, nonzeroIsTrue: Boolean = true): Boolean {
        val value = readU8(bytes, offset)
        return if (nonzeroIsTrue) value != 0 else value == 1
    }

    fun readU16(bytes: ByteArray, offset: Int = 0): Int =
        (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

    fun readI16(bytes: ByteArray, offset: Int = 0): Int {
        val value = readU16(bytes, offset)
        return if (value and 0x8000 != 0) value or -0x1_0000 else value
    }

    fun readU32(bytes: ByteArray, offset: Int = 0): Long =
        (u8(bytes[offset]).toLong() shl 24) or
            (u8(bytes[offset + 1]).toLong() shl 16) or
            (u8(bytes[offset + 2]).toLong() shl 8) or
            u8(bytes[offset + 3]).toLong()

    fun readI32(bytes: ByteArray, offset: Int = 0): Int = readU32(bytes, offset).toInt()

    fun readU64(bytes: ByteArray, offset: Int = 0): Long {
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or u8(bytes[offset + index]).toLong()
        }
        return value
    }

    fun readBytes(payload: ByteArray): ByteArray = payload.copyOf()

    fun readString(payload: ByteArray): String {
        if (payload.isEmpty() || payload.last() != 0.toByte()) {
            throw Iap2ProtocolException("CSM string is not NUL terminated")
        }
        return payload.copyOf(payload.size - 1).decodeToString()
    }

    fun readStrings(payload: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()
        if (payload.last() != 0.toByte()) {
            throw Iap2ProtocolException("CSM string[] is not NUL terminated")
        }
        val values = ArrayList<String>()
        var start = 0
        for (index in payload.indices) {
            if (payload[index] != 0.toByte()) continue
            values += payload.copyOfRange(start, index).decodeToString()
            start = index + 1
        }
        return values
    }

    fun readU16List(payload: ByteArray): List<Int> {
        if (payload.size % 2 != 0) {
            throw Iap2ProtocolException("CSM u16[] payload has odd length ${payload.size}")
        }
        return List(payload.size / 2) { index -> readU16(payload, index * 2) }
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xff
}

/**
 * Pure CSM framing for iAP2 control-session bytes.
 *
 * CSM is `0x4040 | u16 length | u16 messageId | body`, big-endian and without a checksum.
 * The retained receive data is bounded to one largest-valid frame. This class deliberately has no
 * threading, I/O, link-engine, or message-specific knowledge.
 */
class Iap2CsmFramer {
    private val receive = ReceiveBuffer(MAX_FRAME_BYTES)

    /**
     * Adds control-session bytes and returns every newly complete CSM frame.
     *
     * It tolerates arbitrary transport chunking, concatenated frames, garbage before `0x4040`, and
     * invalid lengths below [HEADER_BYTES] by advancing one byte and searching again.
     */
    fun offer(chunk: ByteArray): List<Iap2Frame> {
        if (chunk.isEmpty()) return emptyList()

        val frames = ArrayList<Iap2Frame>()
        var offset = 0
        while (offset < chunk.size) {
            drain(frames)
            val count = minOf(chunk.size - offset, receive.remainingCapacity())
            check(count > 0) { "CSM receive buffer could not make progress" }
            receive.append(chunk, offset, count)
            offset += count
        }
        drain(frames)
        return frames
    }

    private fun drain(frames: MutableList<Iap2Frame>) {
        while (true) {
            while (receive.size >= 2 && !receive.hasStart()) receive.discard(1)
            if (receive.size < HEADER_BYTES) return

            val length = receive.u16(2)
            if (length < HEADER_BYTES) {
                receive.discard(1)
                continue
            }
            if (receive.size < length) return

            val messageId = receive.u16(4)
            val payload = receive.copyOfRange(HEADER_BYTES, length)
            receive.discard(length)
            frames += Iap2Frame(messageId, payload)
        }
    }

    private class ReceiveBuffer(private val maximumSize: Int) {
        private val bytes = ByteArray(maximumSize)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return maximumSize - tail
        }

        fun append(source: ByteArray, offset: Int, count: Int) {
            require(count in 0..remainingCapacity())
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            require(count in 0..size)
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun hasStart(): Boolean = bytes[head] == START_HIGH && bytes[head + 1] == START_LOW

        fun u16(offset: Int): Int =
            ((bytes[head + offset].toInt() and 0xff) shl 8) or
                (bytes[head + offset + 1].toInt() and 0xff)

        fun copyOfRange(from: Int, to: Int): ByteArray = bytes.copyOfRange(head + from, head + to)

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val START = 0x4040
        const val HEADER_BYTES = 6
        const val MIN_FRAME_BYTES = HEADER_BYTES
        const val MAX_FRAME_BYTES = 0xffff
        const val MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES
        const val MAX_PARAM_BYTES = 0xffff
        const val MAX_LINK_CHUNK_BYTES = 65_525

        private const val START_HIGH: Byte = 0x40
        private const val START_LOW: Byte = 0x40

        /** Encodes one complete CSM frame from a message id and its header-excluded body. */
        fun encodeFrame(messageId: Int, payload: ByteArray): ByteArray {
            require(messageId in 0..0xffff) { "CSM message id must fit in u16" }
            require(payload.size <= MAX_BODY_BYTES) {
                "CSM payload exceeds $MAX_BODY_BYTES bytes"
            }
            val frame = ByteArray(HEADER_BYTES + payload.size)
            writeU16(frame, 0, START)
            writeU16(frame, 2, frame.size)
            writeU16(frame, 4, messageId)
            payload.copyInto(frame, HEADER_BYTES)
            return frame
        }

        /** Encodes one CSM parameter: `u16 length | u16 parameterId | payload`. */
        fun encodeParam(parameterId: Int, payload: ByteArray): ByteArray {
            require(parameterId in 0..0xffff) { "CSM parameter id must fit in u16" }
            require(payload.size <= MAX_PARAM_BYTES - Iap2Parameter.HEADER_BYTES) {
                "CSM parameter payload exceeds ${MAX_PARAM_BYTES - Iap2Parameter.HEADER_BYTES} bytes"
            }
            val parameter = ByteArray(Iap2Parameter.HEADER_BYTES + payload.size)
            writeU16(parameter, 0, parameter.size)
            writeU16(parameter, 2, parameterId)
            payload.copyInto(parameter, Iap2Parameter.HEADER_BYTES)
            return parameter
        }

        /** Splits a semantic frame into link payloads no larger than [linkChunkSize]. */
        fun splitForLink(frame: Iap2Frame, linkChunkSize: Int): List<ByteArray> =
            splitForLink(frame.encodedFrame(), linkChunkSize)

        /**
         * Splits exactly one complete CSM frame into link payloads no larger than [linkChunkSize].
         * The complete frame is validated before any defensive-copy chunks are returned.
         */
        fun splitForLink(completeFrame: ByteArray, linkChunkSize: Int): List<ByteArray> {
            require(linkChunkSize in 1..MAX_LINK_CHUNK_BYTES) {
                "iAP2 link chunk size must be in 1..$MAX_LINK_CHUNK_BYTES"
            }
            requireCompleteFrame(completeFrame)
            return List((completeFrame.size + linkChunkSize - 1) / linkChunkSize) { index ->
                val start = index * linkChunkSize
                completeFrame.copyOfRange(start, minOf(start + linkChunkSize, completeFrame.size))
            }
        }

        private fun requireCompleteFrame(frame: ByteArray) {
            require(frame.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) {
                "CSM frame size must be 6..65535"
            }
            require(readU16(frame, 0) == START) { "CSM frame must start with 0x4040" }
            require(readU16(frame, 2) == frame.size) {
                "CSM frame length must equal its complete size"
            }
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)

        private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }
    }
}

/**
 * Ordered, schema-aware builder for one CSM body.
 *
 * The builder preserves insertion order and repeated IDs. When [endpoint] carries a schema, known
 * IDs and wire types are validated at [build]; schema-less/opaque endpoints remain fully usable.
 */
class Iap2BodyBuilder private constructor(
    private val endpoint: Iap2Endpoint?,
    private val explicitMessageId: Int?,
    private val fields: List<Iap2FieldSpec>,
    private val validateSchema: Boolean,
) {
    private data class Entry(val parameter: Iap2Parameter, val wireType: Iap2WireType)

    private val entries = ArrayList<Entry>()

    fun void(id: Int): Iap2BodyBuilder = add(id, Iap2WireCodec.void(), Iap2WireType.VOID)

    fun u8(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u8(value), Iap2WireType.U8)

    fun bool(id: Int, value: Boolean): Iap2BodyBuilder =
        add(id, Iap2WireCodec.bool(value), Iap2WireType.U8)

    fun i8(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i8(value), Iap2WireType.I8)

    fun u16(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u16(value), Iap2WireType.U16)

    fun i16(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i16(value), Iap2WireType.I16)

    fun u32(id: Int, value: Int): Iap2BodyBuilder = u32(id, value.toLong())

    fun u32(id: Int, value: Long): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u32(value), Iap2WireType.U32)

    fun i32(id: Int, value: Int): Iap2BodyBuilder =
        add(id, Iap2WireCodec.i32(value), Iap2WireType.I32)

    fun u64(id: Int, value: Long): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u64(value), Iap2WireType.U64)

    fun bytes(id: Int, value: ByteArray): Iap2BodyBuilder =
        add(id, Iap2WireCodec.bytes(value), Iap2WireType.BYTES)

    fun string(id: Int, value: String): Iap2BodyBuilder =
        add(id, Iap2WireCodec.string(value), Iap2WireType.STRING)

    fun strings(id: Int, values: Iterable<String>): Iap2BodyBuilder =
        add(id, Iap2WireCodec.strings(values), Iap2WireType.STRING_LIST)

    fun u16List(id: Int, values: Iterable<Int>): Iap2BodyBuilder =
        add(id, Iap2WireCodec.u16List(values), Iap2WireType.U16_LIST)

    fun raw(id: Int, payload: ByteArray): Iap2BodyBuilder =
        add(id, payload.copyOf(), Iap2WireType.OPAQUE)

    fun group(id: Int, block: Iap2BodyBuilder.() -> Unit): Iap2BodyBuilder {
        val child = Iap2BodyBuilder(
            endpoint = null,
            explicitMessageId = null,
            fields = schemaField(id)?.children.orEmpty(),
            validateSchema = validateSchema,
        ).apply(block)
        val encoded = child.encodeValidated()
        add(id, encoded, Iap2WireType.GROUP)
        return this
    }

    fun optionalVoid(id: Int, present: Boolean): Iap2BodyBuilder =
        if (present) void(id) else this

    fun optionalU8(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else u8(id, value)

    fun optionalI8(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i8(id, value)

    fun optionalU16(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else u16(id, value)

    fun optionalI16(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i16(id, value)

    fun optionalU32(id: Int, value: Long?): Iap2BodyBuilder =
        if (value == null) this else u32(id, value)

    fun optionalI32(id: Int, value: Int?): Iap2BodyBuilder =
        if (value == null) this else i32(id, value)

    fun optionalU64(id: Int, value: Long?): Iap2BodyBuilder =
        if (value == null) this else u64(id, value)

    fun optionalBytes(id: Int, value: ByteArray?): Iap2BodyBuilder =
        if (value == null) this else bytes(id, value)

    fun optionalString(id: Int, value: String?): Iap2BodyBuilder =
        if (value == null) this else string(id, value)

    fun build(): Iap2Frame {
        val target = endpoint?.id ?: explicitMessageId
            ?: throw IllegalStateException("A raw body builder needs an explicit message id")
        return build(target)
    }

    fun build(messageId: Int): Iap2Frame {
        return Iap2Frame(messageId, encodeValidated())
    }

    private fun encodeValidated(): ByteArray {
        if (validateSchema && fields.isNotEmpty()) validate(fields)
        return Iap2ParameterList.of(entries.map { it.parameter }).encode()
    }

    private fun add(id: Int, payload: ByteArray, wireType: Iap2WireType): Iap2BodyBuilder {
        require(id in 0..0xffff) { "CSM parameter id must fit in u16" }
        entries += Entry(Iap2Parameter(id, payload), wireType)
        return this
    }

    private fun schemaField(id: Int): Iap2FieldSpec? = fields.firstOrNull { it.id == id }

    private fun validate(specs: List<Iap2FieldSpec>) {
        val byId = specs.associateBy(Iap2FieldSpec::id)
        for (entry in entries) {
            val spec = byId[entry.parameter.id]
                ?: throw Iap2ProtocolException(
                    "Unknown body parameter 0x${entry.parameter.id.toString(16).padStart(4, '0')}",
                )
            if (!wireTypeMatches(spec.type, entry.wireType)) {
                throw Iap2ProtocolException(
                    "Body parameter 0x${spec.id.toString(16).padStart(4, '0')} expected ${spec.type}, " +
                        "got ${entry.wireType}",
                )
            }
            if (!spec.repeatable && entries.count { it.parameter.id == spec.id } > 1) {
                throw Iap2ProtocolException(
                    "Body parameter 0x${spec.id.toString(16).padStart(4, '0')} is not repeatable",
                )
            }
        }
        for (spec in specs.filter { it.required }) {
            if (entries.none { it.parameter.id == spec.id }) {
                throw Iap2ProtocolException(
                    "Missing required body parameter 0x${spec.id.toString(16).padStart(4, '0')} (${spec.name})",
                )
            }
        }
    }

    private fun wireTypeMatches(expected: Iap2WireType, actual: Iap2WireType): Boolean =
        expected == Iap2WireType.OPAQUE || expected == actual

    companion object {
        fun of(endpoint: Iap2Endpoint): Iap2BodyBuilder =
            Iap2BodyBuilder(endpoint, null, endpoint.fields, validateSchema = true)

        fun opaque(messageId: Int): Iap2BodyBuilder =
            Iap2BodyBuilder(null, messageId, emptyList(), validateSchema = false)
    }
}

/**
 * Typed reader over an ordered CSM body.
 *
 * The reader does not discard unknown or repeated parameters. Callers can use the scalar helpers
 * for known fields and [raw] or [all] for forward-compatible values.
 */
class Iap2BodyReader private constructor(
    private val parameters: Iap2ParameterList,
) {
    val size: Int get() = parameters.size

    fun isEmpty(): Boolean = parameters.isEmpty()

    fun has(id: Int): Boolean = parameters.has(id)

    fun first(id: Int): Iap2Parameter? = parameters.first(id)

    fun require(id: Int, description: String = defaultDescription(id)): Iap2Parameter =
        parameters.require(id, description)

    fun all(id: Int): List<Iap2Parameter> = parameters.all(id)

    fun raw(id: Int): ByteArray = require(id).payload

    fun optionalRaw(id: Int): ByteArray? = first(id)?.payload

    fun void(id: Int): Boolean {
        val parameter = require(id)
        requireEmpty(parameter, id)
        return true
    }

    fun optionalVoid(id: Int): Boolean = first(id)?.let {
        requireEmpty(it, id)
        true
    } ?: false

    fun u8(id: Int): Int = Iap2WireCodec.readU8(scalar(id, 1))

    fun optionalU8(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 1)
        Iap2WireCodec.readU8(it.payloadView())
    }

    fun i8(id: Int): Int = Iap2WireCodec.readI8(scalar(id, 1))

    fun optionalI8(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 1)
        Iap2WireCodec.readI8(it.payloadView())
    }

    fun bool(id: Int): Boolean = u8(id) != 0

    fun optionalBool(id: Int): Boolean? = optionalU8(id)?.let { it != 0 }

    fun u16(id: Int): Int = Iap2WireCodec.readU16(scalar(id, 2))

    fun optionalU16(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 2)
        Iap2WireCodec.readU16(it.payloadView())
    }

    fun i16(id: Int): Int = Iap2WireCodec.readI16(scalar(id, 2))

    fun optionalI16(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 2)
        Iap2WireCodec.readI16(it.payloadView())
    }

    fun u32(id: Int): Long = Iap2WireCodec.readU32(scalar(id, 4))

    fun optionalU32(id: Int): Long? = first(id)?.let {
        requireSize(it, id, 4)
        Iap2WireCodec.readU32(it.payloadView())
    }

    fun i32(id: Int): Int = Iap2WireCodec.readI32(scalar(id, 4))

    fun optionalI32(id: Int): Int? = first(id)?.let {
        requireSize(it, id, 4)
        Iap2WireCodec.readI32(it.payloadView())
    }

    fun u64(id: Int): Long = Iap2WireCodec.readU64(scalar(id, 8))

    fun optionalU64(id: Int): Long? = first(id)?.let {
        requireSize(it, id, 8)
        Iap2WireCodec.readU64(it.payloadView())
    }

    fun bytes(id: Int): ByteArray = raw(id)

    fun optionalBytes(id: Int): ByteArray? = optionalRaw(id)

    fun string(id: Int): String = try {
        Iap2WireCodec.readString(require(id).payloadView())
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalString(id: Int): String? = first(id)?.let {
        try {
            Iap2WireCodec.readString(it.payloadView())
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun strings(id: Int): List<String> = try {
        Iap2WireCodec.readStrings(require(id).payloadView())
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalStrings(id: Int): List<String>? = first(id)?.let {
        try {
            Iap2WireCodec.readStrings(it.payloadView())
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun u16List(id: Int): List<Int> = try {
        Iap2WireCodec.readU16List(require(id).payloadView())
    } catch (failure: Iap2ProtocolException) {
        throw malformed(id, failure)
    }

    fun optionalU16List(id: Int): List<Int>? = first(id)?.let {
        try {
            Iap2WireCodec.readU16List(it.payloadView())
        } catch (failure: Iap2ProtocolException) {
            throw malformed(id, failure)
        }
    }

    fun group(id: Int): Iap2BodyReader =
        Iap2BodyReader(Iap2ParameterList.parse(require(id).payloadView()))

    fun optionalGroup(id: Int): Iap2BodyReader? = first(id)?.let {
        Iap2BodyReader(Iap2ParameterList.parse(it.payloadView()))
    }

    fun list(): List<Iap2Parameter> = parameters.asList()

    private fun scalar(id: Int, expectedBytes: Int): ByteArray {
        val parameter = require(id)
        requireSize(parameter, id, expectedBytes)
        return parameter.payloadView()
    }

    private fun requireSize(parameter: Iap2Parameter, id: Int, expectedBytes: Int) {
        val size = parameter.payloadView().size
        if (size != expectedBytes) {
            throw Iap2ProtocolException(
                "Parameter 0x${hex(id)} expected $expectedBytes bytes, got $size",
            )
        }
    }

    private fun requireEmpty(parameter: Iap2Parameter, id: Int) {
        if (parameter.payloadView().isNotEmpty()) {
            throw Iap2ProtocolException("Parameter 0x${hex(id)} must be void")
        }
    }

    private fun malformed(id: Int, failure: Iap2ProtocolException): Iap2ProtocolException =
        Iap2ProtocolException("Malformed parameter 0x${hex(id)}", failure)

    private fun defaultDescription(id: Int): String = "parameter 0x${hex(id)}"

    private fun hex(id: Int): String = id.toString(16).padStart(4, '0')

    companion object {
        fun of(frame: Iap2Frame): Iap2BodyReader =
            Iap2BodyReader(Iap2ParameterList.parse(frame.payload))

        fun of(body: Iap2ParameterList): Iap2BodyReader = Iap2BodyReader(body)

        /** Validates the endpoint name without requiring a caller-side cast or map lookup. */
        fun of(endpoint: Iap2Endpoint, frame: Iap2Frame): Iap2BodyReader {
            require(frame.messageId == endpoint.id) {
                "Frame 0x${frame.messageId.toString(16)} is not ${endpoint.name}"
            }
            return of(frame)
        }
    }
}

enum class Iap2Direction {
    IPHONE_TO_ACCESSORY,
    ACCESSORY_TO_IPHONE,
    BIDIRECTIONAL,
}

enum class Iap2Confidence {
    HIGH,
    MEDIUM,
}

/** A wire-level body value kind from the endpoint reference. */
enum class Iap2WireType {
    VOID,
    U8,
    I8,
    U16,
    I16,
    U32,
    I32,
    U64,
    U16_LIST,
    BYTES,
    STRING,
    STRING_LIST,
    GROUP,
    OPAQUE,
}

/**
 * One endpoint field description.
 *
 * A group's [children] use the same parameter namespace as its parent. [repeatable] describes
 * repeated parameter IDs or repeated group records; the body builder and reader preserve both.
 */
data class Iap2FieldSpec(
    val id: Int,
    val name: String,
    val type: Iap2WireType,
    val required: Boolean = false,
    val repeatable: Boolean = false,
    val children: List<Iap2FieldSpec> = emptyList(),
)

/**
 * Metadata for one CSM endpoint.
 *
 * [fields] may be empty for endpoints whose reference body is intentionally opaque or has not yet
 * received a typed schema. The endpoint itself is still registered, named, and usable through the
 * generic body builder.
 */
data class Iap2Endpoint(
    val id: Int,
    val name: String,
    val feature: String,
    val direction: Iap2Direction,
    val confidence: Iap2Confidence = Iap2Confidence.HIGH,
    val fields: List<Iap2FieldSpec> = emptyList(),
)

/**
 * The endpoint registry from IAP2_ENDPOINT_BODY_REFERENCE.zh-CN.md.
 *
 * The registry is intentionally source-level rather than loaded from markdown at runtime. This
 * keeps Android builds deterministic and lets typed message code refer to named endpoints.
 */
object Iap2Endpoints {
    val START_IDENTIFICATION = endpoint(
        0x1d00,
        "StartIdentification",
        "Identification",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val IDENTIFICATION_INFORMATION = endpoint(
        0x1d01,
        "IdentificationInformation",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "name", Iap2WireType.STRING, required = true),
            field(1, "modelIdentifier", Iap2WireType.STRING, required = true),
            field(2, "manufacturer", Iap2WireType.STRING, required = true),
            field(3, "serialNumber", Iap2WireType.STRING, required = true),
            field(4, "firmwareVersion", Iap2WireType.STRING, required = true),
            field(5, "hardwareVersion", Iap2WireType.STRING, required = true),
            field(6, "MessagesSentByAccessory", Iap2WireType.U16_LIST, required = true),
            field(7, "MessagesReceivedFromDevice", Iap2WireType.U16_LIST, required = true),
            field(8, "powerProvidingCapability", Iap2WireType.U8),
            field(9, "maximumCurrentDrawnFromDevice", Iap2WireType.U16),
            field(10, "externalAccessoryProtocols", Iap2WireType.GROUP),
            field(12, "currentLanguage", Iap2WireType.STRING, required = true),
            field(13, "supportedLanguages", Iap2WireType.STRING_LIST, required = true),
            field(
                16,
                "USBHostTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "name", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "interfaceNumber", Iap2WireType.U8),
                    field(4, "isAvailable", Iap2WireType.VOID),
                ),
            ),
            field(
                17,
                "BluetoothTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "name", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "bluetoothMAC", Iap2WireType.BYTES),
                    field(4, "transportName", Iap2WireType.STRING),
                    field(5, "isAvailable", Iap2WireType.VOID),
                ),
            ),
            field(20, "VehicleInformation", Iap2WireType.GROUP),
            field(21, "VehicleStatus", Iap2WireType.GROUP),
            field(22, "LocationInformation", Iap2WireType.GROUP),
            field(
                24,
                "WirelessCarPlayTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "SSID", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "transportIdentifier", Iap2WireType.U16),
                    field(4, "isAvailable", Iap2WireType.VOID),
                    field(5, "isEnabled", Iap2WireType.VOID),
                ),
            ),
            field(
                30,
                "RouteGuidanceDisplayComponent",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "Identifier", Iap2WireType.U16, required = true),
                    field(1, "Name", Iap2WireType.STRING, required = true),
                    field(2, "MaxCurrentRoadNameLength", Iap2WireType.U16),
                    field(4, "MaxAfterManeuverRoadNameLength", Iap2WireType.U16),
                    field(6, "MaxGuidanceManeuverStorageCapacity", Iap2WireType.U16),
                    // Declared last because the phone only sends 0x5204 lane guidance when the
                    // accessory says it can store it.
                    field(7, "MaxLaneGuidanceDescriptionLength", Iap2WireType.U16),
                    field(8, "MaxLaneGuidanceStorageCapacity", Iap2WireType.U16),
                ),
            ),
        ),
    )
    val IDENTIFICATION_ACCEPTED = endpoint(
        0x1d02,
        "IdentificationAccepted",
        "Identification",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val IDENTIFICATION_REJECTED = endpoint(
        0x1d03,
        "IdentificationRejected",
        "Identification",
        Iap2Direction.BIDIRECTIONAL,
    )
    val CANCEL_IDENTIFICATION = endpoint(
        0x1d05,
        "CancelIdentification",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val IDENTIFICATION_INFORMATION_UPDATE = endpoint(
        0x1d06,
        "IdentificationInformationUpdate",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val REQUEST_AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa00,
        "RequestAuthenticationCertificate",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa01,
        "AuthenticationCertificate",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "certificate", Iap2WireType.BYTES, required = true)),
    )
    val REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE = endpoint(
        0xaa02,
        "RequestAuthenticationChallengeResponse",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "challenge", Iap2WireType.BYTES, required = true)),
    )
    val AUTHENTICATION_RESPONSE = endpoint(
        0xaa03,
        "AuthenticationResponse",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "signatureResponse", Iap2WireType.BYTES, required = true)),
    )
    val AUTHENTICATION_FAILED = endpoint(
        0xaa04,
        "AuthenticationFailed",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_SUCCEEDED = endpoint(
        0xaa05,
        "AuthenticationSucceeded",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_CERTIFICATE_SERIAL = endpoint(
        0xaa06,
        "AuthenticationCertificateSerial",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val REQUEST_DEVICE_CERTIFICATE = endpoint(
        0xaa10,
        "RequestDeviceCertificate",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa11,
        "AuthenticationCertificate",
        "DeviceAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val REQUEST_DEVICE_CHALLENGE_RESPONSE = endpoint(
        0xaa12,
        "RequestDeviceChallengeResponse",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_RESPONSE = endpoint(
        0xaa13,
        "AuthenticationResponse",
        "DeviceAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val DEVICE_AUTHENTICATION_FAILED = endpoint(
        0xaa14,
        "DeviceAuthenticationFailed",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_SUCCEEDED = endpoint(
        0xaa15,
        "DeviceAuthenticationSucceeded",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val CARPLAY_AVAILABILITY = endpoint(
        0x4300,
        "CarPlayAvailability",
        "CarPlayConnectionRequest",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(
                0,
                "wired",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wiredAvailable", Iap2WireType.U8),
                    field(1, "usbIdentifier", Iap2WireType.STRING),
                ),
            ),
            field(
                1,
                "wireless",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wirelessAvailable", Iap2WireType.U8),
                    field(1, "bluetoothIdentifier", Iap2WireType.STRING),
                ),
            ),
            field(
                2,
                "themeAssets",
                Iap2WireType.GROUP,
                children = listOf(field(0, "themeAssetsAvailable", Iap2WireType.U8)),
            ),
        ),
    )
    val CARPLAY_START_SESSION = endpoint(
        0x4301,
        "CarPlayStartSession",
        "CarPlayConnectionRequest",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(
                0,
                "wired",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wiredIP", Iap2WireType.STRING, repeatable = true),
                    field(1, "reserved", Iap2WireType.U32),
                ),
            ),
            field(
                1,
                "wireless",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "SSID", Iap2WireType.STRING),
                    field(1, "passphrase", Iap2WireType.STRING),
                    field(2, "channel", Iap2WireType.U8),
                    field(3, "wirelessIP", Iap2WireType.STRING, repeatable = true),
                    field(4, "securityType", Iap2WireType.U8),
                ),
            ),
            field(2, "airPlayPort", Iap2WireType.U32),
            field(3, "deviceIdentifier", Iap2WireType.STRING),
            field(4, "publicKey", Iap2WireType.STRING),
            field(5, "sourceVersion", Iap2WireType.STRING),
            field(6, "SDKVersion", Iap2WireType.STRING),
            field(
                7,
                "clusterAsset",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "assetID", Iap2WireType.STRING),
                    field(1, "assetVersion", Iap2WireType.U32),
                ),
            ),
            field(8, "mutualAuth", Iap2WireType.U8),
        ),
    )
    val AVAILABLE_DIGITAL_CAR_KEYS = endpoint(
        0x4302,
        "AvailableDigitalCarKeys",
        "DigitalCarKey_Matching",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val MATCHED_DIGITAL_CAR_KEYS = endpoint(
        0x4303,
        "MatchedDigitalCarKeys",
        "DigitalCarKey_Matching",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )

    val REQUEST_WIFI_INFORMATION = endpoint(
        0x5700,
        "RequestWiFiInformation",
        "WiFiSharing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val WIFI_INFORMATION = endpoint(
        0x5701,
        "WiFiInformation",
        "WiFiSharing",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "status", Iap2WireType.U8, required = true),
            field(1, "securityType", Iap2WireType.U8),
            field(2, "SSID", Iap2WireType.STRING),
            field(3, "passphrase", Iap2WireType.STRING),
        ),
    )
    val REQUEST_ACCESSORY_WIFI_CONFIGURATION = endpoint(
        0x5702,
        "RequestAccessoryWiFiConfigurationInformation",
        "WiFiSharing",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val ACCESSORY_WIFI_CONFIGURATION = endpoint(
        0x5703,
        "AccessoryWiFiConfigurationInformation",
        "WiFiSharing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "BSSID", Iap2WireType.BYTES),
            field(1, "SSID", Iap2WireType.STRING),
            field(2, "passphrase", Iap2WireType.STRING),
            field(3, "securityType", Iap2WireType.U8),
            field(4, "channel", Iap2WireType.U8),
        ),
    )

    val GPRMC_DATA_STATUS_VALUES = endpoint(
        0xfff0,
        "GPRMCDataStatusValues",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val START_LOCATION_INFORMATION = endpoint(
        0xfffa,
        "StartLocationInformation",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val LOCATION_INFORMATION = endpoint(
        0xfffb,
        "LocationInformation",
        "Location",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "NMEA", Iap2WireType.STRING)),
    )
    val STOP_LOCATION_INFORMATION = endpoint(
        0xfffc,
        "StopLocationInformation",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )

    val START_POWER_UPDATES = endpoint(0xae00, "StartPowerUpdates", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val POWER_UPDATE = endpoint(0xae01, "PowerUpdate", "Power", Iap2Direction.IPHONE_TO_ACCESSORY)
    val STOP_POWER_UPDATES = endpoint(0xae02, "StopPowerUpdates", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val POWER_SOURCE_UPDATE = endpoint(
        0xae03,
        "PowerSourceUpdate",
        "Power",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "AvailableCurrentForDevice", Iap2WireType.U16),
            field(1, "IsDeviceBatteryCharging", Iap2WireType.U8),
            field(3, "AvailableSiphoningCurrent", Iap2WireType.U16),
            field(4, "ReserveCurrent", Iap2WireType.U16),
            field(5, "MaxNonSiphoningCurrentExceeded", Iap2WireType.VOID),
        ),
    )
    val ACCESSORY_POWER_UPDATE = endpoint(0xae05, "AccessoryPowerUpdate", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val ACCESSORY_RESET_BASE_CURRENT =
        endpoint(0xae06, "AccessoryResetBaseCurrent", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)

    val START_ROUTE_GUIDANCE_UPDATES = endpoint(
        0x5200,
        "StartRouteGuidanceUpdates",
        "Navigation",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_ROUTE_GUIDANCE_UPDATES = endpoint(
        0x5203,
        "StopRouteGuidanceUpdates",
        "Navigation",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_NOW_PLAYING_UPDATES = endpoint(
        0x5000,
        "StartNowPlayingUpdates",
        "Now Playing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_NOW_PLAYING_UPDATES = endpoint(
        0x5002,
        "StopNowPlayingUpdates",
        "Now Playing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_COMMUNICATIONS_UPDATES = endpoint(
        0x4157,
        "startCommunicationsUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_CALL_STATE_UPDATES = endpoint(
        0x4154,
        "startCallStateUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_COMMUNICATIONS_UPDATES = endpoint(
        0x4159,
        "stopCommunicationsUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_CALL_STATE_UPDATES = endpoint(
        0x4156,
        "stopCallStateUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val DEVICE_INFORMATION_UPDATE = endpoint(
        0x4e09,
        "DeviceInformationUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "deviceName", Iap2WireType.STRING)),
    )
    val DEVICE_LANGUAGE_UPDATE = endpoint(
        0x4e0a,
        "DeviceLanguageUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "language", Iap2WireType.STRING)),
    )
    val DEVICE_TIME_UPDATE = endpoint(
        0x4e0b,
        "DeviceTimeUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "secondsSinceReferenceDate", Iap2WireType.U64),
            field(1, "timeZoneOffsetMinutes", Iap2WireType.I16),
            field(2, "daylightSavingsOffsetMinutes", Iap2WireType.I8),
        ),
    )
    val DEVICE_UUID_UPDATE = endpoint(
        0x4e0c,
        "DeviceUuidUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "deviceUUID", Iap2WireType.STRING)),
    )
    val WIRELESS_CARPLAY_UPDATE = endpoint(
        0x4e0d,
        "WirelessCarPlayUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "availability", Iap2WireType.U8, required = true)),
    )
    val DEVICE_TRANSPORT_IDENTIFIER = endpoint(
        0x4e0e,
        "DeviceTransportIdentifierNotification",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "bluetoothMAC", Iap2WireType.STRING),
            field(1, "usbTransportIdentifier", Iap2WireType.STRING),
        ),
    )

    val EXTERNAL_ACCESSORY_OPEN_SESSION = endpoint(
        0xea00,
        "externalAccessory_openEASession",
        "ExternalAccessory",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val EXTERNAL_ACCESSORY_CLOSE_SESSION = endpoint(
        0xea01,
        "externalAccessory_closeEASession",
        "ExternalAccessory",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val EXTERNAL_ACCESSORY_STATUS_SESSION = endpoint(
        0xea03,
        "externalaccessory_statusEASessionHandler",
        "ExternalAccessory",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val PING = endpoint(0x0000, "pingHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val USER_NOTIFICATION = endpoint(0x0001, "userNotificationHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val SEND_PING_ACC = endpoint(0x0004, "sendPingAccHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val PING_ACC = endpoint(0x0005, "pingAccHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val DIGITAL_AUDIO_START = endpoint(0xda00, "digitalAudio_start", "DigitalAudio", Iap2Direction.ACCESSORY_TO_IPHONE)
    val DIGITAL_AUDIO_INFORMATION_UPDATE =
        endpoint(0xda01, "DigitalAudioInformationUpdate", "DigitalAudio", Iap2Direction.IPHONE_TO_ACCESSORY)
    val DIGITAL_AUDIO_STOP = endpoint(0xda02, "digitalAudio_stop", "DigitalAudio", Iap2Direction.ACCESSORY_TO_IPHONE)

    /**
     * The remaining reference endpoints are registered here without a claimed schema. Their body
     * can still be built and read through the generic body API, but no enum or field ID is invented.
     */
    val ALL: List<Iap2Endpoint> = listOf(
        START_IDENTIFICATION,
        IDENTIFICATION_INFORMATION,
        IDENTIFICATION_ACCEPTED,
        IDENTIFICATION_REJECTED,
        CANCEL_IDENTIFICATION,
        IDENTIFICATION_INFORMATION_UPDATE,
        REQUEST_AUTHENTICATION_CERTIFICATE,
        AUTHENTICATION_CERTIFICATE,
        REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE,
        AUTHENTICATION_RESPONSE,
        AUTHENTICATION_FAILED,
        AUTHENTICATION_SUCCEEDED,
        AUTHENTICATION_CERTIFICATE_SERIAL,
        REQUEST_DEVICE_CERTIFICATE,
        DEVICE_AUTHENTICATION_CERTIFICATE,
        REQUEST_DEVICE_CHALLENGE_RESPONSE,
        DEVICE_AUTHENTICATION_RESPONSE,
        DEVICE_AUTHENTICATION_FAILED,
        DEVICE_AUTHENTICATION_SUCCEEDED,
        CARPLAY_AVAILABILITY,
        CARPLAY_START_SESSION,
        AVAILABLE_DIGITAL_CAR_KEYS,
        MATCHED_DIGITAL_CAR_KEYS,
        REQUEST_WIFI_INFORMATION,
        WIFI_INFORMATION,
        REQUEST_ACCESSORY_WIFI_CONFIGURATION,
        ACCESSORY_WIFI_CONFIGURATION,
        GPRMC_DATA_STATUS_VALUES,
        START_LOCATION_INFORMATION,
        LOCATION_INFORMATION,
        STOP_LOCATION_INFORMATION,
        START_POWER_UPDATES,
        POWER_UPDATE,
        STOP_POWER_UPDATES,
        POWER_SOURCE_UPDATE,
        ACCESSORY_POWER_UPDATE,
        ACCESSORY_RESET_BASE_CURRENT,
        START_ROUTE_GUIDANCE_UPDATES,
        STOP_ROUTE_GUIDANCE_UPDATES,
        START_NOW_PLAYING_UPDATES,
        STOP_NOW_PLAYING_UPDATES,
        START_COMMUNICATIONS_UPDATES,
        START_CALL_STATE_UPDATES,
        STOP_COMMUNICATIONS_UPDATES,
        STOP_CALL_STATE_UPDATES,
        DEVICE_INFORMATION_UPDATE,
        DEVICE_LANGUAGE_UPDATE,
        DEVICE_TIME_UPDATE,
        DEVICE_UUID_UPDATE,
        WIRELESS_CARPLAY_UPDATE,
        DEVICE_TRANSPORT_IDENTIFIER,
        EXTERNAL_ACCESSORY_OPEN_SESSION,
        EXTERNAL_ACCESSORY_CLOSE_SESSION,
        EXTERNAL_ACCESSORY_STATUS_SESSION,
        PING,
        USER_NOTIFICATION,
        SEND_PING_ACC,
        PING_ACC,
        DIGITAL_AUDIO_START,
        DIGITAL_AUDIO_INFORMATION_UPDATE,
        DIGITAL_AUDIO_STOP,
        registryEndpoint(0xA100, "StartVehicleStatusUpdates", "Vehicle", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xA101, "VehicleStatusUpdate", "Vehicle", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xA102, "StopVehicleStatusUpdates", "Vehicle", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5001, "NowPlayingUpdate", "Now Playing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5003, "SetNowPlayingInfo", "Now Playing", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C00, "StartMediaLibraryInformation", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C01, "MediaLibraryInformation", "Media Library", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4C02, "StopMediaLibraryInformation", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C03, "StartMediaLibraryUpdates", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C04, "MediaLibraryUpdate", "Media Library", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4C05, "StopMediaLibraryUpdates", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C06, "PlayMediaLibraryCurrentSelection", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C07, "PlayMediaLibraryItems", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C08, "PlayMediaLibraryCollection", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C09, "PlayMediaLibrarySpecial", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x5201, "RouteGuidanceUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5202, "RouteGuidanceManeuverUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5204, "LaneGuidanceInfoUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4155, "CallStateUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4158, "CommunicationsUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x415A, "initiateCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415B, "acceptCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415C, "endCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415D, "swapCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415E, "mergeCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415F, "holdStatusUpdateHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4160, "muteStatusUpdateHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4161, "sendDTMFHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4170, "startListUpdatesHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4171, "ListUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4172, "stopListUpdatesHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(
            0x4E01,
            "BluetoothComponentInfo",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x4E03,
            "StartBluetoothConnectionUpdates",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x4E04,
            "BluetoothConnectionUpdate",
            "Bluetooth connection status",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x4E05,
            "StopBluetoothConnectionUpdates",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0xAD00, "appLinks_start", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD01, "AppLinksUpdate", "App links and launch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xAD02, "appLinks_stop", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD03, "appLinks_requestAppIcons", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD04, "AppIconResponse", "App links and launch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xEA02, "requestAppLaunchHandler", "External Accessory", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6800, "hid_StartHIDMsgHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6801, "hid_OutReport", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x6802, "hid_AccessoryHIDReportHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6803, "hid_StopHIDMsgHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6804, "hid_GetReport", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x6805,
            "hid_AccessoryHIDGetReportResponseHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x6807, "hid_ComponentUpdate", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x7E00,
            "usbHostMode_StartUSBHostModeHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x7E02,
            "usbHostMode_StopUSBHostModeHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5400,
            "assistiveTouch_startAssistiveTouchHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5401,
            "assistiveTouch_stopAssistiveTouchHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5402,
            "assistiveTouch_startAssistiveTouchUpdateHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x5403, "NotifyStatusUpdate", "VoiceOver and AssistiveTouch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x5404,
            "assistiveTouch_stopAssistiveTouchUpdateHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5601,
            "voiceOver_moveCursorHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5602,
            "voiceOver_selectCursorHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5603,
            "voiceOver_scrollPageHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5606,
            "voiceOver_speakTextHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5608,
            "voiceOver_pauseSpeakingHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5609,
            "voiceOver_resumeSpeakingHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560B,
            "voiceOver_startInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560C,
            "VoiceOverInformation",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x560D,
            "voiceOver_stopInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560E,
            "voiceOver_updateInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560F,
            "voiceOver_startCursorInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5610,
            "VoiceOverCursorInformation",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x5611,
            "voiceOver_stopCursorInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5612,
            "VoiceOver_startVoiceOverHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5613,
            "VoiceOver_stopVoiceOverHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x0B00, "oobBtPairing2_startPairing", "BLE and OOB pairing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x0B01,
            "oobBtPairing2_accessoryInfoHandler",
            "BLE and OOB pairing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x0B02, "oobBtPairing2_statusHandler", "BLE and OOB pairing", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x0B03, "oobBtPairing2_stopPairing", "BLE and OOB pairing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x10DA,
            "destinationSharing_StartDestinationInformation",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x10DB,
            "DestinationInformationUpdate",
            "Destination sharing",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x10DC,
            "destinationSharing_StopDestinationInformation",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x10DD,
            "destinationSharing_DestinationInformationStatus",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x00B0, "oobBtPairing_startPairing", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x00B1,
            "oobBtPairing_accessoryInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x00B2,
            "oobBtPairing_pairingLinkKeyInfo",
            "Other endpoints",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x00B3,
            "oobBtPairing_completionInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x00B4, "oobBtPairing_stopPairing", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D00, "StartRoadObjectDetection", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D01, "RoadObjectDetectionUpdate", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D02, "StopRoadObjectDetection", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0xB102,
            "blePairing_accessoryStateUpdateHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0xB103,
            "blePairing_accessoryPairingInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0xB105,
            "blePairing_accessoryPairingDataHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0xB107, "blePairing_stopBLEUpdates", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
    )

    private val byId: Map<Int, Iap2Endpoint> = ALL.associateBy(Iap2Endpoint::id)

    fun byId(id: Int): Iap2Endpoint? = byId[id]

    fun require(id: Int): Iap2Endpoint =
        byId(id) ?: throw IllegalArgumentException("Unknown iAP2 endpoint 0x${id.toString(16).padStart(4, '0')}")

    private fun endpoint(
        id: Int,
        name: String,
        feature: String,
        direction: Iap2Direction,
        confidence: Iap2Confidence = Iap2Confidence.HIGH,
        fields: List<Iap2FieldSpec> = emptyList(),
    ): Iap2Endpoint = Iap2Endpoint(id, name, feature, direction, confidence, fields)

    private fun registryEndpoint(
        id: Int,
        name: String,
        feature: String,
        direction: Iap2Direction,
    ): Iap2Endpoint = Iap2Endpoint(id, name, feature, direction, fields = emptyList())

    private fun field(
        id: Int,
        name: String,
        type: Iap2WireType,
        required: Boolean = false,
        repeatable: Boolean = false,
        children: List<Iap2FieldSpec> = emptyList(),
    ): Iap2FieldSpec = Iap2FieldSpec(id, name, type, required, repeatable, children)
}

/** Reusable entry points for constructing and reading iAP2 CSM bodies. */
object Iap2Messages {
    inline fun build(
        endpoint: Iap2Endpoint,
        block: Iap2BodyBuilder.() -> Unit,
    ): Iap2Frame = Iap2BodyBuilder.of(endpoint).apply(block).build()

    inline fun buildRaw(
        messageId: Int,
        block: Iap2BodyBuilder.() -> Unit,
    ): Iap2Frame = Iap2BodyBuilder.opaque(messageId).apply(block).build()

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2BodyReader.of(frame)
}

/** Parameters carried by the optional `wireless` group of 0x4301. */
data class Iap2WirelessSessionParameters(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val ipAddresses: List<String>,
    val securityType: Int,
) {
    init {
        require(ssid.isNotBlank()) { "wireless SSID must not be blank" }
        require(securityType == 0 || passphrase.isNotEmpty()) { "secured wireless network requires a passphrase" }
        require(channel in 0..0xff) { "wireless channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "wireless IP address list must not be empty" }
        require(securityType in 0..0xff) { "wireless security type must be in 0..255" }
    }
}

data class Iap2ClusterAsset(
    val assetId: String,
    val assetVersion: Long,
) {
    init {
        require(assetId.isNotBlank()) { "cluster asset ID must not be blank" }
        require(assetVersion in 0..0xffff_ffffL) { "cluster asset version must fit in u32" }
    }
}

/** Typed codec for the high-value CarPlay endpoints. */
object Iap2CarPlayMessages {
    fun startSession(
        airPlayPort: Int,
        publicKey: String,
        sourceVersion: String,
        wiredIpv6Addresses: List<String> = emptyList(),
        wiredReserved: Long? = null,
        wireless: Iap2WirelessSessionParameters? = null,
        deviceIdentifier: String? = null,
        sdkVersion: String? = null,
        clusterAsset: Iap2ClusterAsset? = null,
        mutualAuth: Boolean? = null,
    ): Iap2Frame {
        require(airPlayPort in 1..0xffff) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion must not be empty" }

        return Iap2Messages.build(Iap2Endpoints.CARPLAY_START_SESSION) {
            if (wiredIpv6Addresses.isNotEmpty() || wiredReserved != null) {
                group(0) {
                    wiredIpv6Addresses.forEach { string(0, it) }
                    optionalU32(1, wiredReserved)
                }
            }
            wireless?.let {
                group(1) {
                    string(0, it.ssid)
                    optionalString(1, it.passphrase.takeIf { p -> p.isNotEmpty() && it.securityType != 0 })
                    u8(2, it.channel.takeIf { ch -> ch > 0 } ?: 36)
                    it.ipAddresses.forEach { address -> string(3, address) }
                    u8(4, it.securityType)
                }
            }
            u32(2, airPlayPort)
            optionalString(3, deviceIdentifier)
            string(4, publicKey)
            string(5, sourceVersion)
            optionalString(6, sdkVersion)
            clusterAsset?.let {
                group(7) {
                    string(0, it.assetId)
                    u32(1, it.assetVersion)
                }
            }
            optionalU8(8, mutualAuth?.let { if (it) 1 else 0 })
        }
    }

    fun availability(frame: Iap2Frame): Iap2CarPlayAvailability {
        require(frame.messageId == Iap2Endpoints.CARPLAY_AVAILABILITY.id) {
            "Expected CarPlayAvailability, got 0x${frame.messageId.toString(16)}"
        }
        return availability(frame.payload)
    }

    /** Decodes an already extracted 0x4300 body without requiring the caller to rebuild a frame. */
    fun availability(payload: ByteArray): Iap2CarPlayAvailability {
        val body = Iap2BodyReader.of(Iap2ParameterList.parse(payload))
        val wired = body.optionalGroup(0)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "wired"),
                identifier = it.optionalString(1),
            )
        }
        val wireless = body.optionalGroup(1)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "wireless"),
                identifier = it.optionalString(1),
            )
        }
        val themeAssets = body.optionalGroup(2)?.let {
            Iap2AvailabilityState(
                available = decodeAvailability(it.optionalU8(0), "themeAssets"),
                identifier = null,
            )
        }
        return Iap2CarPlayAvailability(wired, wireless, themeAssets)
    }

    private fun decodeAvailability(value: Int?, label: String): Boolean? {
        if (value == null) return null
        if (value !in 0..1) {
            throw Iap2ProtocolException("$label availability must be 0 or 1, got $value")
        }
        return value == 1
    }
}

data class Iap2AvailabilityState(
    val available: Boolean?,
    val identifier: String?,
)

data class Iap2CarPlayAvailability(
    val wired: Iap2AvailabilityState?,
    val wireless: Iap2AvailabilityState?,
    val themeAssets: Iap2AvailabilityState?,
)

/** Typed codecs for Wi-Fi sharing and Wireless CarPlay notifications. */
object Iap2WirelessMessages {
    fun accessoryWiFiConfiguration(
        ssid: String,
        passphrase: String,
        channel: Int,
        securityType: Int,
        bssid: ByteArray? = null,
    ): Iap2Frame = Iap2Messages.build(Iap2Endpoints.ACCESSORY_WIFI_CONFIGURATION) {
        optionalBytes(0, bssid)
        string(1, ssid)
        optionalString(2, passphrase.takeIf { it.isNotEmpty() && securityType != 0 })
        u8(3, securityType)
        optionalU8(4, channel.takeIf { it > 0 })
    }

    fun wifiInformation(frame: Iap2Frame): Iap2WifiInformation {
        require(frame.messageId == Iap2Endpoints.WIFI_INFORMATION.id) {
            "Expected WiFiInformation, got 0x${frame.messageId.toString(16)}"
        }
        val body = Iap2Messages.reader(frame)
        val status = body.u8(0)
        if (status != 0) return Iap2WifiInformation(status)
        return Iap2WifiInformation(
            status = status,
            securityType = body.optionalU8(1),
            ssid = body.optionalString(2),
            passphrase = body.optionalString(3),
        )
    }

    fun wirelessCarPlayAvailability(frame: Iap2Frame): Boolean {
        require(frame.messageId == Iap2Endpoints.WIRELESS_CARPLAY_UPDATE.id) {
            "Expected WirelessCarPlayUpdate, got 0x${frame.messageId.toString(16)}"
        }
        return when (val value = Iap2Messages.reader(frame).u8(0)) {
            0 -> false
            1 -> true
            else -> throw Iap2ProtocolException("WirelessCarPlayUpdate availability must be 0 or 1, got $value")
        }
    }

    fun deviceTransportIdentifier(frame: Iap2Frame): Iap2DeviceTransportIdentifier {
        require(frame.messageId == Iap2Endpoints.DEVICE_TRANSPORT_IDENTIFIER.id) {
            "Expected DeviceTransportIdentifierNotification, got 0x${frame.messageId.toString(16)}"
        }
        val body = Iap2Messages.reader(frame)
        return Iap2DeviceTransportIdentifier(
            bluetoothMac = body.optionalString(0),
            usbTransportIdentifier = body.optionalString(1),
        )
    }
}

typealias Iap2WifiMessages = Iap2WirelessMessages

/** Typed accessory-authentication bodies, including the BAA certificate package layout. */
object Iap2AuthenticationMessages {
    fun accessoryCertificate(certificate: ByteArray): Iap2Frame =
        Iap2Messages.build(Iap2Endpoints.AUTHENTICATION_CERTIFICATE) {
            bytes(0, certificate)
        }

    /**
     * Wraps an already encoded 0xAA01 body, as returned by the remote MFi/BAA transport.
     * The body is parsed before wrapping so malformed parameter framing cannot escape this layer.
     */
    fun accessoryCertificateBody(body: ByteArray): Iap2Frame {
        Iap2ParameterList.parse(body)
        return Iap2Frame(Iap2Endpoints.AUTHENTICATION_CERTIFICATE.id, body)
    }

    /**
     * The remote BAA service returns a package whose three fields are already the final 0xAA01
     * parameter sequence. It is intentionally built through the raw endpoint wrapper because this
     * package uses a schema variant that is not represented by the baseline MFi endpoint.
     */
    fun baaCertificatePackage(leaf: ByteArray, intermediate: ByteArray): Iap2Frame =
        Iap2Messages.buildRaw(Iap2Endpoints.AUTHENTICATION_CERTIFICATE.id) {
            bytes(0, leaf)
            u8(1, 1)
            bytes(2, intermediate)
        }

    fun response(signature: ByteArray): Iap2Frame =
        Iap2Messages.build(Iap2Endpoints.AUTHENTICATION_RESPONSE) {
            bytes(0, signature)
        }

    fun challenge(frame: Iap2Frame): ByteArray {
        require(frame.messageId == Iap2Endpoints.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE.id) {
            "Expected RequestAuthenticationChallengeResponse, got 0x${frame.messageId.toString(16)}"
        }
        return Iap2Messages.reader(frame).bytes(0)
    }
}

data class Iap2WifiInformation(
    val status: Int,
    val securityType: Int? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
) {
    val successful: Boolean get() = status == 0
}

data class Iap2DeviceTransportIdentifier(
    val bluetoothMac: String?,
    val usbTransportIdentifier: String?,
)

/** Typed codecs for power and subscription requests used during bring-up. */
object Iap2ControlMessages {
    fun powerSourceUpdate(
        availableCurrentMilliAmps: Int,
        charging: Boolean? = null,
        availableSiphoningCurrentMilliAmps: Int? = null,
        reserveCurrentMilliAmps: Int? = null,
        maxNonSiphoningCurrentExceeded: Boolean = false,
    ): Iap2Frame = Iap2Messages.build(Iap2Endpoints.POWER_SOURCE_UPDATE) {
        u16(0, availableCurrentMilliAmps)
        optionalU8(1, charging?.let { if (it) 1 else 0 })
        optionalU16(3, availableSiphoningCurrentMilliAmps)
        optionalU16(4, reserveCurrentMilliAmps)
        optionalVoid(5, maxNonSiphoningCurrentExceeded)
    }

    fun startNowPlayingUpdates(): Iap2Frame =
        Iap2Messages.build(Iap2Endpoints.START_NOW_PLAYING_UPDATES) {
            group(0) {
                listOf(1, 4, 6, 12, 26).forEach(::void)
            }
            group(1) {
                listOf(0, 1, 7).forEach(::void)
            }
        }

    fun subscriptions(): List<Iap2Frame> = listOf(
        startNowPlayingUpdates(),
        Iap2Messages.build(Iap2Endpoints.START_ROUTE_GUIDANCE_UPDATES) {
            u16(0, 42)
            void(1)
            void(2)
        },
        Iap2Messages.build(Iap2Endpoints.START_POWER_UPDATES) {
            void(4)
            void(5)
            void(6)
        },
        Iap2Messages.build(Iap2Endpoints.START_COMMUNICATIONS_UPDATES) {
            void(0)
            void(4)
            void(5)
        },
        Iap2Messages.build(Iap2Endpoints.START_CALL_STATE_UPDATES) {
            void(0)
            void(1)
            void(2)
            void(3)
            void(4)
            void(11)
        },
    )

    fun locationInformation(nmeaSentence: String): Iap2Frame {
        require(nmeaSentence.isNotEmpty()) { "NMEA sentence must not be empty" }
        return Iap2Messages.build(Iap2Endpoints.LOCATION_INFORMATION) {
            string(0, nmeaSentence)
        }
    }
}

typealias Iap2PowerMessages = Iap2ControlMessages
typealias Iap2CommunicationMessages = Iap2ControlMessages

/** Typed identification message helper delegating to [Iap2IdentificationClient]. */
object Iap2IdentificationMessages {
    fun identificationInformation(
        config: TvAccessoryIdentitySpec,
        omitted: Set<Int> = emptySet(),
    ): Iap2Frame = Iap2IdentificationClient.identificationInformation(config, omitted)
}

/**
 * One immediately readable and writable iAP2 CSM session.
 *
 * [Iap2CsmChannel] remains the lower-level frame transport. This class is the protocol-facing
 * facade used by services: it accepts endpoint builders, sends complete frames, receives complete
 * frames, and exposes the typed body reader.
 */
class Iap2Session private constructor(
    private val channel: Iap2CsmChannel,
    private val traceContext: String,
    private val onTrace: (String) -> Unit,
) : AutoCloseable {
    val isClosed: Boolean get() = channel.isClosed

    fun awaitReady(timeoutMillis: Long): Boolean {
        val ready = channel.awaitReady(timeoutMillis)
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        try {
            channel.send(frame, timeoutMillis)
            emitFrameTrace(Iap2TraceDirection.TX, frame)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.TX,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
            throw failure
        }
    }

    fun send(
        endpoint: Iap2Endpoint,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.build(endpoint, block), timeoutMillis)
    }

    fun sendRaw(
        messageId: Int,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.buildRaw(messageId, block), timeoutMillis)
    }

    fun recv(timeoutMillis: Long): Iap2Frame? {
        return try {
            channel.recv(timeoutMillis)?.also { emitFrameTrace(Iap2TraceDirection.RX, it) }
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.RX,
                    traceContext,
                    messageId = null,
                    failure = failure,
                ),
            )
            throw failure
        }
    }

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2Messages.reader(frame)

    override fun close() {
        try {
            channel.close()
        } finally {
            emitTrace("IAP2 CLOSE [$traceContext]")
        }
    }

    private fun emitFrameTrace(direction: Iap2TraceDirection, frame: Iap2Frame) {
        try {
            emitTrace(Iap2FrameFormatter.format(direction, traceContext, frame))
        } catch (failure: Exception) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    direction,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
        }
    }

    private fun emitTrace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change protocol behavior.
        }
    }

    companion object {
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        fun open(
            underlying: DuplexByteStream,
            traceContext: String = "wired",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.open(underlying, onArtwork), traceContext, onTrace)

        fun openWireless(
            underlying: DuplexByteStream,
            traceContext: String = "wireless",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openWireless(underlying, onArtwork), traceContext, onTrace)

        fun openTunnel(
            underlying: DuplexByteStream,
            traceContext: String = "wireless-tunnel",
            onTrace: (String) -> Unit = {},
            onArtwork: (Iap2ArtworkTransfer) -> Unit = {},
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openTunnel(underlying, onArtwork), traceContext, onTrace)

        fun wrap(
            channel: Iap2CsmChannel,
            traceContext: String = "iap2",
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(channel, traceContext, onTrace)
    }
}

enum class Iap2TraceDirection(val label: String) {
    TX("TX"),
    RX("RX"),
}

/**
 * Human-readable rendering for the on-device iAP2 trace.
 *
 * Known endpoints use their field names and wire types. Unknown/medium-confidence endpoints still
 * show ordered parameter IDs and safe scalar/string/byte summaries, so no frame is invisible just
 * because it has not received a strict schema yet.
 */
object Iap2FrameFormatter {
    private const val MAX_PARAMETERS = 80
    private const val MAX_STRING_CHARS = 240
    private const val MAX_HEX_BYTES = 24

    fun format(
        direction: Iap2TraceDirection,
        context: String,
        frame: Iap2Frame,
    ): String {
        val endpoint = Iap2Endpoints.byId(frame.messageId)
        val encoded = frame.encodedFrame()
        val parameters = try {
            Iap2ParameterList.parse(frame.payload)
        } catch (_: Iap2ProtocolException) {
            null
        }
        val lines = ArrayList<String>()
        if (parameters == null) {
            lines += "  body=malformed TLV"
        } else {
            appendParameters(
                parameters = parameters,
                specs = endpoint?.fields.orEmpty(),
                depth = 1,
                lines = lines,
            )
        }
        lines += "  raw-body=${hexPreview(frame.payload)}"

        val endpointText = endpoint?.let { " ${it.name}" }.orEmpty()
        return buildString {
            append("IAP2 ")
            append(direction.label)
            append(" [")
            append(context)
            append("] 0x")
            append(frame.messageId.toString(16).padStart(4, '0'))
            append(endpointText)
            append(" frame=")
            append(encoded.size)
            append("B body=")
            append(if (parameters == null) "malformed" else "${parameters.size} params")
            lines.forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    fun formatFailure(
        direction: Iap2TraceDirection,
        context: String,
        messageId: Int?,
        failure: Throwable,
    ): String = buildString {
        append("IAP2 ")
        append(direction.label)
        append(" [")
        append(context)
        append(']')
        messageId?.let {
            append(" 0x")
            append(it.toString(16).padStart(4, '0'))
            append(Iap2Endpoints.byId(it)?.let { endpoint -> " ${endpoint.name}" }.orEmpty())
        }
        append(" FAILED: ")
        append(failure.message ?: failure.javaClass.simpleName)
    }

    private fun appendParameters(
        parameters: Iap2ParameterList,
        specs: List<Iap2FieldSpec>,
        depth: Int,
        lines: MutableList<String>,
    ) {
        val fields = specs.associateBy(Iap2FieldSpec::id)
        parameters.asList().take(MAX_PARAMETERS).forEach { parameter ->
            val field = fields[parameter.id]
            val name = field?.name ?: "param"
            val value = if (field == null) {
                formatOpaque(parameter.payload)
            } else {
                formatKnown(parameter.payload, field, depth)
            }
            lines += "${indent(depth)}[${parameter.id}] $name: $value"
        }
        if (parameters.size > MAX_PARAMETERS) {
            lines += "${indent(depth)}... ${parameters.size - MAX_PARAMETERS} more parameters"
        }
    }

    private fun formatKnown(
        payload: ByteArray,
        spec: Iap2FieldSpec,
        depth: Int,
    ): String = try {
        when (spec.type) {
            Iap2WireType.VOID -> if (payload.isEmpty()) "void" else "void? ${hexPreview(payload)}"
            Iap2WireType.U8 -> scalar("u8", payload, 1, Iap2WireCodec.readU8(payload))
            Iap2WireType.I8 -> scalar("i8", payload, 1, Iap2WireCodec.readI8(payload))
            Iap2WireType.U16 -> scalar("u16", payload, 2, Iap2WireCodec.readU16(payload))
            Iap2WireType.I16 -> scalar("i16", payload, 2, Iap2WireCodec.readI16(payload))
            Iap2WireType.U32 -> scalar("u32", payload, 4, Iap2WireCodec.readU32(payload))
            Iap2WireType.I32 -> scalar("i32", payload, 4, Iap2WireCodec.readI32(payload))
            Iap2WireType.U64 -> scalar("u64", payload, 8, Iap2WireCodec.readU64(payload))
            Iap2WireType.U16_LIST -> Iap2WireCodec.readU16List(payload)
                .joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }
            Iap2WireType.BYTES -> bytes(payload)
            Iap2WireType.STRING -> quote(truncate(Iap2WireCodec.readString(payload)))
            Iap2WireType.STRING_LIST -> Iap2WireCodec.readStrings(payload)
                .joinToString(prefix = "[", postfix = "]") { quote(truncate(it)) }
            Iap2WireType.GROUP -> {
                val nested = Iap2ParameterList.parse(payload)
                val nestedLines = ArrayList<String>()
                appendParameters(nested, spec.children, depth + 1, nestedLines)
                if (nestedLines.isEmpty()) "{}" else nestedLines.joinToString("\n", prefix = "{\n", postfix = "\n${indent(depth)}}")
            }
            Iap2WireType.OPAQUE -> formatOpaque(payload)
        }
    } catch (_: Exception) {
        formatOpaque(payload)
    }

    private fun formatOpaque(payload: ByteArray): String {
        if (payload.isEmpty()) return "void"
        readableString(payload)?.let { return quote(truncate(it)) }
        return when (payload.size) {
            1 -> "u8=${u8(payload[0])} (0x${hexPreview(payload)})"
            2 -> "u16=${Iap2WireCodec.readU16(payload)} (0x${hexPreview(payload)})"
            4 -> "u32=${Iap2WireCodec.readU32(payload)} (0x${hexPreview(payload)})"
            8 -> "u64=${Iap2WireCodec.readU64(payload)} (0x${hexPreview(payload)})"
            else -> bytes(payload)
        }
    }

    private fun scalar(
        name: String,
        payload: ByteArray,
        size: Int,
        value: Number,
    ): String {
        require(payload.size == size) { "expected $size bytes" }
        return "$name=$value (0x${hexPreview(payload)})"
    }

    private fun bytes(payload: ByteArray): String =
        "bytes=${payload.size} [${hexPreview(payload)}]"

    private fun readableString(payload: ByteArray): String? {
        if (payload.isEmpty() || payload.last() != 0.toByte()) return null
        val content = payload.copyOf(payload.size - 1)
        if (content.any { byte ->
                val value = byte.toInt() and 0xff
                value < 0x20 && value != 0x09
            }
        ) {
            return null
        }
        val decoded = content.decodeToString()
        if (decoded.contains('\uFFFD')) return null
        return decoded
    }

    private fun quote(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t") + "\""

    private fun truncate(value: String): String =
        if (value.length <= MAX_STRING_CHARS) value
        else value.take(MAX_STRING_CHARS) + "..."

    private fun hexPreview(payload: ByteArray): String {
        if (payload.isEmpty()) return "<empty>"
        val shown = payload.take(MAX_HEX_BYTES).joinToString(" ") {
            "%02x".format(it.toInt() and 0xff)
        }
        return if (payload.size <= MAX_HEX_BYTES) shown else "$shown ... (+${payload.size - MAX_HEX_BYTES}B)"
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    private fun indent(depth: Int): String = "  ".repeat(depth)
}
