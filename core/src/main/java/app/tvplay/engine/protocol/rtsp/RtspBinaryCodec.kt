package app.tvplay.engine.protocol.rtsp

import app.tvplay.engine.av.DisplayInsets
import app.tvplay.engine.av.DisplayMetricMath
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.session.TvStreamServerConfig
import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean

typealias RtspRequest = RtspMessageCodec.Request
typealias RtspResponse = RtspMessageCodec.Response
typealias RtspMessage = RtspMessageCodec
typealias BplistValue = Any?
typealias AirPlayInfoPlist = RtspInfoPayload

/**
 * RTSP/HTTP-style request framing for the CarPlay control channel.
 *
 * The control connection on TCP :7000 speaks a text request line plus headers plus an optional
 * body, using the same framing RTSP and HTTP share. Encryption, once pair-verify completes, wraps
 * this framing in a separate layer.
 */
object RtspMessageCodec {
    data class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val protocol: String? = null,
        val status: Int? = null,
        val statusText: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class Parsed(val messages: List<Request>, val rest: ByteArray)

    /** Real requests carry headers under 1 KiB; the cap leaves ample room. */
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** Above the largest legitimate control payload. */
    private const val MAX_BODY_BYTES = 1024 * 1024

    private val headerEnd = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val statusText = mapOf(
        200 to "OK",
        400 to "Bad Request",
        404 to "Not Found",
        500 to "Internal Server Error",
    )

    /**
     * Parses as many complete messages as are buffered, retaining any partial remainder.
     *
     * @throws ProtocolException when a header or body exceeds its cap, so a peer cannot make the caller buffer without limit.
     */
    fun parseMessages(buffer: ByteArray): Parsed {
        val messages = ArrayList<Request>()
        var offset = 0
        while (offset < buffer.size) {
            val headerEndIndex = indexOf(buffer, headerEnd, offset)
            if (headerEndIndex == null || headerEndIndex - offset > MAX_HEADER_BYTES) {
                if (buffer.size - offset > MAX_HEADER_BYTES) throw ProtocolException("RTSP header over $MAX_HEADER_BYTES bytes")
                break
            }
            val headerText = String(buffer, offset, headerEndIndex - offset, Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) {
                offset = headerEndIndex + headerEnd.size
                continue
            }
            val requestLine = lines.first().split(" ", limit = 3)
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val separator = line.indexOf(':')
                if (separator == -1) continue
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
            val bodyStart = headerEndIndex + headerEnd.size
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (contentLength > MAX_BODY_BYTES) throw ProtocolException("RTSP body of $contentLength bytes over $MAX_BODY_BYTES")
            val bodyEnd = bodyStart + contentLength
            if (bodyEnd > buffer.size) break
            messages.add(
                Request(
                    method = requestLine[0],
                    path = requestLine.getOrElse(1) { "" },
                    protocol = requestLine.getOrElse(2) { "RTSP/1.0" },
                    headers = headers,
                    body = buffer.copyOfRange(bodyStart, bodyEnd),
                ),
            )
            offset = bodyEnd
        }
        return Parsed(messages, buffer.copyOfRange(offset, buffer.size))
    }

    /** Builds a response, echoing the request protocol and CSeq. */
    fun buildResponse(request: Request, response: Response): ByteArray {
        val protocol = response.protocol ?: request.protocol
        val status = response.status ?: 200
        val statusLine = response.statusText ?: statusText[status] ?: "OK"
        val body = response.body
        val headers = LinkedHashMap<String, String>(response.headers)
        request.headers["cseq"]?.let { headers["CSeq"] = it }
        headers["Content-Length"] = body.size.toString()

        val head = buildString {
            append(protocol).append(' ').append(status).append(' ').append(statusLine).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        val wire = ByteArray(head.length + body.size)
        for (index in 0 until head.length) {
            wire[index] = head[index].code.toByte()
        }
        if (body.isNotEmpty()) {
            body.copyInto(wire, head.length)
        }
        return wire
    }

    private fun indexOf(source: ByteArray, target: ByteArray, start: Int): Int? {
        if (target.isEmpty()) return start
        val first = target[0]
        for (index in start..source.size - target.size) {
            if (source[index] == first && matches(source, index, target)) return index
        }
        return null
    }

    private fun matches(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        for (index in target.indices) {
            if (source[offset + index] != target[index]) return false
        }
        return true
    }
}

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        for (i in magic.indices) {
            require(bytes[i] == magic[i]) { "bplist: bad magic" }
        }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: bad offset or reference size" }
        val declaredObjects = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        require(declaredObjects in 1..trailer.toLong() && topObject in 0 until declaredObjects) { "bplist: bad object count" }
        require(
            offsetTable in magic.size.toLong()..trailer.toLong() &&
                offsetTable + declaredObjects * offsetSize <= trailer,
        ) { "bplist: bad offset table" }
        val numObjects = declaredObjects.toInt()

        val offsets = LongArray(numObjects)
        for (index in offsets.indices) {
            val offset = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offset in magic.size.toLong() until offsetTable) { "bplist: object offset out of range" }
            offsets[index] = offset
        }
        val allowance = bytes.size.toLong() * WORK_PER_BYTE + WORK_BASE
        val limits = Limits(minOf(allowance, MAX_READS), minOf(allowance, MAX_PAYLOAD_BYTES))
        return readObject(bytes, offsets, refSize, topObject.toInt(), 0, limits)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(PLACEHOLDER_LEAF)
            when (value) {
                null -> nodes[index] = NULL_LEAF
                is Boolean -> nodes[index] = if (value) TRUE_LEAF else FALSE_LEAF
                is BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(encodeReal64(number.toDouble().toRawBits()))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(encodeReal64(number.toRawBits()))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = DataLeaf(value)
                is List<*> -> {
                    val refs = IntArray(value.size)
                    for (i in value.indices) {
                        refs[i] = add(value[i])
                    }
                    nodes[index] = Container(0xa, value.size, refs)
                }
                is Map<*, *> -> {
                    val count = value.size
                    val refs = IntArray(count * 2)
                    var keyIdx = 0
                    for (key in value.keys) {
                        refs[keyIdx++] = add(key.toString())
                    }
                    var valIdx = count
                    for (entryValue in value.values) {
                        refs[valIdx++] = add(entryValue)
                    }
                    nodes[index] = Container(0xd, count, refs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        var cursor = magic.size
        val offsets = IntArray(nodes.size)
        for (i in nodes.indices) {
            offsets[i] = cursor
            cursor += nodes[i].serializedSize(refSize)
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        val totalSize = offsetTableOffset + offsets.size * offsetSize + 32
        val output = ByteArray(totalSize)
        magic.copyInto(output, 0)
        var writeCursor = magic.size
        for (i in nodes.indices) {
            writeCursor = nodes[i].writeTo(output, writeCursor, refSize)
        }
        for (offset in offsets) {
            writeBigEndian(output, writeCursor, offset.toLong(), offsetSize)
            writeCursor += offsetSize
        }
        output[writeCursor + 6] = offsetSize.toByte()
        output[writeCursor + 7] = refSize.toByte()
        writeBigEndian(output, writeCursor + 8, nodes.size.toLong(), 8)
        writeBigEndian(output, writeCursor + 16, topIndex.toLong(), 8)
        writeBigEndian(output, writeCursor + 24, offsetTableOffset.toLong(), 8)
        return output
    }

    private fun readObject(
        bytes: ByteArray,
        offsets: LongArray,
        refSize: Int,
        index: Int,
        depth: Int,
        limits: Limits,
    ): Any? {
        require(depth <= MAX_DEPTH) { "bplist: nested too deep" }
        require(--limits.reads >= 0) { "bplist: expands to more objects than its size allows" }
        require(index in offsets.indices) { "bplist: reference out of range" }
        var position = offsets[index].toInt()
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            val intBytes = 1 shl (sizeMarker and 0x0f)
            require(intBytes <= 8) { "bplist: count wider than 8 bytes" }
            val count = readBigEndianLong(bytes, position.toLong(), intBytes)
            position += intBytes
            require(count in 0..(bytes.size - position).toLong()) { "bplist: count larger than the data" }
            return count.toInt()
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                require(nibble <= 4) { "bplist: integer wider than 16 bytes" }
                val size = 1 shl nibble
                if (size == 16) {
                    require(position + 16 <= bytes.size) { "bplist: value runs past the data" }
                    readBigEndianLong(bytes, (position + 8).toLong(), 8)
                } else {
                    readBigEndianLong(bytes, position.toLong(), size)
                }
            }
            0x2 -> {
                val size = 1 shl nibble
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            0x3 -> java.util.Date(
                Math.round((Double.fromBits(readBigEndianLong(bytes, position.toLong(), 8)) + APPLE_EPOCH_SECONDS) * 1000),
            )
            0x4 -> {
                val count = readCount()
                charge(limits, count.toLong())
                bytes.copyOfRange(position, position + count)
            }
            0x8 -> {
                require(nibble <= 7) { "bplist: uid wider than 8 bytes" }
                readBigEndianLong(bytes, position.toLong(), nibble + 1)
            }
            0x5 -> {
                val count = readCount()
                charge(limits, count.toLong())
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                require(count.toLong() * 2 <= bytes.size - position) { "bplist: string longer than the data" }
                charge(limits, count.toLong() * 2)
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa, 0xc -> {
                val count = readCount()
                require(count.toLong() * refSize <= bytes.size - position) { "bplist: array larger than the data" }
                val array = ArrayList<Any?>(minOf(count, INITIAL_CAPACITY))
                for (i in 0 until count) {
                    val reference = readReference(bytes, position + i.toLong() * refSize, refSize)
                    array.add(readObject(bytes, offsets, refSize, reference, depth + 1, limits))
                }
                array
            }
            0xd -> {
                val count = readCount()
                require(count.toLong() * 2 * refSize <= bytes.size - position) { "bplist: dictionary larger than the data" }
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readReference(bytes, position + i.toLong() * refSize, refSize)
                    val valueReference = readReference(bytes, position + (count + i).toLong() * refSize, refSize)
                    val key = readObject(bytes, offsets, refSize, keyReference, depth + 1, limits)
                    require(key is String) { "bplist: dictionary key is not a string" }
                    dict[key] = readObject(bytes, offsets, refSize, valueReference, depth + 1, limits)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        var ascii = true
        for (index in 0 until value.length) {
            if (value[index].code > 0x7f) {
                ascii = false
                break
            }
        }
        val mSize = markerSize(value.length)
        return if (ascii) {
            val out = ByteArray(mSize + value.length)
            var pos = writeMarker(out, 0, 0x5, value.length)
            for (index in 0 until value.length) {
                out[pos++] = value[index].code.toByte()
            }
            out
        } else {
            val out = ByteArray(mSize + value.length * 2)
            var pos = writeMarker(out, 0, 0x6, value.length)
            for (index in 0 until value.length) {
                val ch = value[index].code
                out[pos++] = (ch ushr 8).toByte()
                out[pos++] = ch.toByte()
            }
            out
        }
    }

    private fun encodeReal64(rawBits: Long): ByteArray {
        val out = ByteArray(9)
        out[0] = 0x23
        writeBigEndian(out, 1, rawBits, 8)
        return out
    }

    private fun encodeInt(number: Long): ByteArray {
        val size: Int
        val log: Int
        when {
            number > 0xffffffffL -> { size = 8; log = 3 }
            number > 0xffffL -> { size = 4; log = 2 }
            number > 0xffL -> { size = 2; log = 1 }
            else -> { size = 1; log = 0 }
        }
        val out = ByteArray(1 + size)
        out[0] = (0x10 or log).toByte()
        writeBigEndian(out, 1, number, size)
        return out
    }

    private fun encodeUnsignedInt(number: BigInteger): ByteArray {
        val size: Int
        val log: Int
        when {
            number.bitLength() > 32 -> { size = 8; log = 3 }
            number.bitLength() > 16 -> { size = 4; log = 2 }
            number.bitLength() > 8 -> { size = 2; log = 1 }
            else -> { size = 1; log = 0 }
        }
        val out = ByteArray(1 + size)
        out[0] = (0x10 or log).toByte()
        writeBigEndian(out, 1, number.toLong(), size)
        return out
    }

    private fun markerSize(count: Int): Int = when {
        count < 0x0f -> 1
        count > 0xffff -> 6
        count > 0xff -> 4
        else -> 3
    }

    private fun writeMarker(target: ByteArray, offset: Int, type: Int, count: Int): Int {
        if (count < 0x0f) {
            target[offset] = ((type shl 4) or count).toByte()
            return offset + 1
        }
        val size: Int
        val log: Int
        when {
            count > 0xffff -> { size = 4; log = 2 }
            count > 0xff -> { size = 2; log = 1 }
            else -> { size = 1; log = 0 }
        }
        target[offset] = ((type shl 4) or 0x0f).toByte()
        target[offset + 1] = (0x10 or log).toByte()
        writeBigEndian(target, offset + 2, count.toLong(), size)
        return offset + 2 + size
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readReference(bytes: ByteArray, offset: Long, size: Int): Int {
        val reference = readBigEndianLong(bytes, offset, size)
        require(reference in 0..Int.MAX_VALUE.toLong()) { "bplist: reference out of range" }
        return reference.toInt()
    }

    private fun charge(limits: Limits, size: Long) {
        if (size <= SMALL_PAYLOAD_BYTES) return
        limits.payload -= size
        require(limits.payload >= 0) { "bplist: shared data is read more often than its size allows" }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(offset >= 0 && offset + size <= bytes.size) { "bplist: value runs past the data" }
        var value = 0L
        val base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private class Limits(var reads: Long, var payload: Long)

    private sealed class Node {
        abstract fun serializedSize(refSize: Int): Int
        abstract fun writeTo(target: ByteArray, offset: Int, refSize: Int): Int
    }

    private class Leaf(val body: ByteArray) : Node() {
        override fun serializedSize(refSize: Int): Int = body.size
        override fun writeTo(target: ByteArray, offset: Int, refSize: Int): Int {
            body.copyInto(target, offset)
            return offset + body.size
        }
    }

    private class DataLeaf(val payload: ByteArray) : Node() {
        override fun serializedSize(refSize: Int): Int = markerSize(payload.size) + payload.size
        override fun writeTo(target: ByteArray, offset: Int, refSize: Int): Int {
            val next = writeMarker(target, offset, 0x4, payload.size)
            payload.copyInto(target, next)
            return next + payload.size
        }
    }

    private class Container(val type: Int, val count: Int, val refs: IntArray) : Node() {
        override fun serializedSize(refSize: Int): Int = markerSize(count) + refs.size * refSize
        override fun writeTo(target: ByteArray, offset: Int, refSize: Int): Int {
            var pos = writeMarker(target, offset, type, count)
            for (ref in refs) {
                writeBigEndian(target, pos, ref.toLong(), refSize)
                pos += refSize
            }
            return pos
        }
    }

    private val PLACEHOLDER_LEAF = Leaf(ByteArray(0))
    private val NULL_LEAF = Leaf(byteArrayOf(0x00))
    private val FALSE_LEAF = Leaf(byteArrayOf(0x08))
    private val TRUE_LEAF = Leaf(byteArrayOf(0x09))

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
    private const val MAX_DEPTH = 32
    private const val WORK_PER_BYTE = 4L
    private const val WORK_BASE = 1024L
    private const val MAX_READS = 100_000L
    private const val MAX_PAYLOAD_BYTES = 4L * 1024 * 1024
    private const val SMALL_PAYLOAD_BYTES = 64L
    private const val INITIAL_CAPACITY = 64
}

/**
 * CarPlay timing-port clock sync.
 *
 * The accessory drives a two-way RTCP-style exchange with the phone: PT_REQUEST (210) carries our
 * transmit timestamp and PT_RESPONSE (211) echoes it while adding the phone's receive and
 * transmit stamps. The resulting offset and round-trip time steer a local monotonic clock onto
 * the phone's media-clock domain, which /feedback reports.
 */
class NtpClock : Closeable {
    private val running = AtomicBoolean(false)
    private val socketLock = Any()
    private val clockLock = Any()

    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var sender: Thread? = null
    @Volatile private var peer: InetSocketAddress? = null

    private var clockOffsetNs = wallClockNtpOffsetNs(System.nanoTime())
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var pickCount = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: BigInteger? = null
    private var synced = false
    private val requestBuf = ByteArray(NTP_PACKET_BYTES)
    private val responseBuf = ByteArray(NTP_PACKET_BYTES)
    private val responsePacket = DatagramPacket(responseBuf, responseBuf.size)

    fun listen(): Int {
        synchronized(socketLock) {
            socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        }
        if (!running.getAndSet(true)) {
            val bound = DatagramSocket(null)
            bound.reuseAddress = true
            runCatching { bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0)) }
                .recoverCatching { bound.bind(InetSocketAddress(0)) }
                .getOrThrow()
            synchronized(socketLock) { socket = bound }
            receiver = Thread(::runReceiver, "airplay-ntp-rx").apply { isDaemon = true; start() }
            return bound.localPort
        }
        return synchronized(socketLock) { socket?.localPort ?: 0 }
    }

    fun start(peerAddress: InetAddress, port: Int) {
        require(port > 0) { "timing port must be positive" }
        peer = InetSocketAddress(peerAddress, port)
        synchronized(socketLock) {
            if (sender?.isAlive == true) return
            sender = Thread(::runSender, "airplay-ntp-tx").apply { isDaemon = true; start() }
        }
    }

    /** Now in the phone's synchronized clock domain, as an unsigned NTP64 value. */
    fun syncedNtp(): BigInteger = synchronized(clockLock) {
        ntpFromNanos(System.nanoTime() + clockOffsetNs)
    }

    override fun close() {
        running.set(false)
        synchronized(socketLock) {
            socket?.close()
            socket = null
        }
        sender?.interrupt()
        receiver?.interrupt()
    }

    private fun runSender() {
        while (running.get()) {
            try {
                sendRequest()
                Thread.sleep(REQUEST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(REQUEST_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun sendRequest() {
        val destination = peer ?: return
        val packet = requestBuf
        packet.fill(0)
        packet[0] = 0x80.toByte()
        packet[1] = PT_REQUEST.toByte()
        writeU16Be(packet, 2, 7)
        val t1 = syncedNtp()
        synchronized(clockLock) { pendingT1 = t1 }
        writeNtp(t1, packet, NTP_TRANSMIT_OFFSET)
        currentSocket()?.send(DatagramPacket(packet, packet.size, destination))
    }

    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running.get()) {
            val sock = currentSocket() ?: return
            packet.setData(buffer, 0, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: Exception) {
                if (running.get()) continue else return
            }
            val length = packet.length
            val address = packet.address
            if (length >= NTP_PACKET_BYTES && address != null) {
                handleMessage(buffer, InetSocketAddress(address, packet.port))
            }
        }
    }

    private fun handleMessage(message: ByteArray, source: InetSocketAddress) {
        when (message[1].toInt() and 0xff) {
            PT_REQUEST -> respondToRequest(message, source)
            PT_RESPONSE -> if (peer?.address?.let { isSameOrAuthorizedAirPlayPeer(source.address, it) } == true) {
                handleResponse(message)
            }
        }
    }

    private fun respondToRequest(request: ByteArray, source: InetSocketAddress) {
        val response = responseBuf
        response.fill(0)
        response[0] = 0x80.toByte()
        response[1] = PT_RESPONSE.toByte()
        writeU16Be(response, 2, 7)
        request.copyInto(response, NTP_ORIGINATE_OFFSET, NTP_TRANSMIT_OFFSET, NTP_PACKET_BYTES)
        writeNtp(syncedNtp(), response, NTP_RECEIVE_OFFSET)
        writeNtp(syncedNtp(), response, NTP_TRANSMIT_OFFSET)
        responsePacket.setData(response, 0, response.size)
        responsePacket.socketAddress = source
        currentSocket()?.send(responsePacket)
    }

    private fun handleResponse(response: ByteArray) {
        val t4 = syncedNtp()
        val t1 = readNtp(response, NTP_ORIGINATE_OFFSET)
        val t2 = readNtp(response, NTP_RECEIVE_OFFSET)
        val t3 = readNtp(response, NTP_TRANSMIT_OFFSET)

        synchronized(clockLock) {
            val pending = pendingT1
            if (pending == null || pending != t1) return
            pendingT1 = null
        }

        val offset = t2.subtract(t1).add(t3.subtract(t4)).toDouble() * 0.5 / TWO32_DOUBLE
        val rtt = t4.subtract(t1).subtract(t3.subtract(t2)).toDouble() / TWO32_DOUBLE
        if (rtt < 0.0) return

        synchronized(clockLock) {
            if (rtt < pickRtt) {
                pickRtt = rtt
                pickOffset = offset
            }
            if (--pickCount > 0) return

            val selectedRtt = pickRtt
            val selectedOffset = pickOffset
            pickCount = PICK_COUNT
            pickRtt = Double.POSITIVE_INFINITY

            val useSample = delays.all { selectedRtt <= it }
            delays[delayIndex] = selectedRtt
            delayIndex = (delayIndex + 1) % DELAY_WINDOW
            if (useSample) applyOffset(selectedOffset)
        }
    }

    private fun applyOffset(offsetSec: Double) {
        val stepping = !synced || Math.abs(offsetSec) > STEP_THRESHOLD_SEC
        val applied = if (stepping) offsetSec else offsetSec * SLEW_GAIN
        clockOffsetNs += Math.round(applied * 1e9)
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            pendingT1 = null
            synced = true
        }
    }

    private fun currentSocket(): DatagramSocket? = synchronized(socketLock) { socket }

    private companion object {
        const val PT_REQUEST = 210
        const val PT_RESPONSE = 211
        const val NTP_PACKET_BYTES = 32
        const val NTP_ORIGINATE_OFFSET = 8
        const val NTP_RECEIVE_OFFSET = 16
        const val NTP_TRANSMIT_OFFSET = 24
        const val REQUEST_INTERVAL_MS = 1_000L
        const val RECEIVE_BUFFER_BYTES = 2_048
        const val STEP_THRESHOLD_SEC = 0.128
        const val SLEW_GAIN = 1.0 / 8.0
        const val DELAY_WINDOW = 8
        const val PICK_COUNT = 2
        const val TWO32_DOUBLE = 0x1_0000_0000L.toDouble()
    }
}

private const val NTP_EPOCH_OFFSET = 2_208_988_800L
private val TWO32 = BigInteger.ONE.shiftLeft(32)
private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)

private fun wallClockNtpOffsetNs(monoNs: Long): Long {
    val ntpNs = ntp64Now()
        .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))
        .multiply(NANOS_PER_SECOND)
        .shiftRight(32)
    return ntpNs.subtract(BigInteger.valueOf(monoNs)).toLong()
}

private fun ntp64Now(): BigInteger {
    val seconds = System.currentTimeMillis() / 1000.0 + NTP_EPOCH_OFFSET
    val whole = Math.floor(seconds).toLong()
    val fraction = Math.floor((seconds - whole) * TWO32.toDouble()).toLong()
    return BigInteger.valueOf(whole).shiftLeft(32).or(BigInteger.valueOf(fraction))
}

private fun ntpFromNanos(ns: Long): BigInteger {
    val seconds = BigInteger.valueOf(Math.floorDiv(ns, 1_000_000_000L))
    val nanos = BigInteger.valueOf(Math.floorMod(ns, 1_000_000_000L))
    return seconds.shiftLeft(32).or(nanos.shiftLeft(32).divide(NANOS_PER_SECOND))
}

private fun writeNtp(value: BigInteger, target: ByteArray, offset: Int) {
    val raw = value.toLong()
    for (index in 7 downTo 0) {
        target[offset + index] = ((raw ushr ((7 - index) * 8)) and 0xffL).toByte()
    }
}

private fun readNtp(value: ByteArray, offset: Int): BigInteger =
    BigInteger(1, value.copyOfRange(offset, offset + 8))

private fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = ((value ushr 8) and 0xff).toByte()
    target[offset + 1] = (value and 0xff).toByte()
}

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object RtspInfoPayload {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L
    private const val CARPLAY_AUDIO_FEATURES = 0x10004540a00L
    private val CARPLAY_FEATURES_NO_AUDIO = CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    /** Discovery and /info must describe the same receiver capabilities. */
    fun features(config: TvStreamServerConfig): Long =
        if (config.disableAudioOutput) CARPLAY_FEATURES_NO_AUDIO else CARPLAY_FEATURES

    fun build(config: TvStreamServerConfig): Map<String, Any?> {
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to features(config),
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "rightHandDrive" to config.rightHandDrive,
            "modes" to modes(),
        )
        if (!config.disableAudioOutput) {
            info["audioLatencies"] = audioLatencies()
            info["audioFormats"] = audioFormats(
                config.entertainmentSampleRate,
                config.microphone,
                config.mainBufferedAudio,
                config.microphoneOpus,
            )
        }
        info["extendedFeatures"] = listOf("vocoderInfo", "enhancedRequestCarUI")
        info["displays"] = displays
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        if (config.hevc) info["hevcInfo"] = emptyMap<String, Any?>()
        if (config.bufferedAudioOutputEnabled) info["mainBufferedInfo"] = emptyMap<String, Any?>()
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun audioLatencies(): List<Map<String, Any?>> {
        fun base(type: Int, audioType: String? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "inputLatencyMicros" to 0L,
                "outputLatencyMicros" to 0L,
            )
            if (audioType != null) entry["audioType"] = audioType
            return entry
        }
        return listOf(
            base(100), base(100, "default"), base(100, "media"), base(100, "telephony"),
            base(100, "speechRecognition"), base(100, "alert"), base(101), base(101, "default"),
            base(102, "default"),
        )
    }

    private fun audioFormats(
        entertainmentRate: Int,
        microphone: Boolean,
        mainBuffered: Boolean = false,
        microphoneOpus: Boolean = true,
    ): List<Map<String, Any?>> {
        fun format(type: Int, audioType: String, outputFormats: Int, inputFormats: Int? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "audioType" to audioType,
                "audioOutputFormats" to outputFormats,
            )
            if (inputFormats != null) entry["audioInputFormats"] = inputFormats
            return entry
        }

        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fc
        val pcm = pcmVoice or (if (is48) 0xc000 else 0xc00)
        val pcmMono = 0x154 or (if (is48) 0x4000 else 0x400)
        val opus = 0x70000000
        val aacLc = if (is48) 0x800000 else 0x400000
        val pcmInput = if (microphone) pcmMono else null
        val wirelessInput = if (microphone) (if (microphoneOpus) pcmMono or opus else pcmMono) else null

        return listOf(
            format(100, "compatibility", pcm, pcmInput),
            format(101, "compatibility", pcm),
            format(100, "default", pcm or opus, wirelessInput),
            format(100, "alert", pcm or opus),
            format(100, "media", pcm),
            format(100, "telephony", pcmMono or opus, wirelessInput),
            format(100, "speechRecognition", pcmMono or opus, wirelessInput),
            format(101, "default", pcm or opus),
            format(102, "media", aacLc),
        ) + if (mainBuffered) {
            listOf(format(BufferedAudioStream.STREAM_TYPE, "media", aacLc))
        } else {
            emptyList()
        }
    }

    private fun displayEntry(display: TvDisplaySpec, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = DisplayMetricMath.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: DisplayMetricMath.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = DisplayMetricMath.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = DisplayMetricMath.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (display.features ?: (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS)),
            "primaryInputDevice" to display.primaryInputDevice,
            "uiAppearanceMode" to 0,
            "uiAppearanceSetting" to 0,
            "mapAppearanceMode" to 0,
            "mapAppearanceSetting" to 0,
            "viewAreas" to listOf(areaDict(display)),
            "initialViewArea" to 0,
        )
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(display: TvDisplaySpec): Map<String, Any?> {
        val width = display.widthPixels
        val height = display.heightPixels
        val view = display.viewArea ?: DisplayInsets()
        val safe = display.safeArea ?: DisplayInsets()
        return linkedMapOf<String, Any?>(
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
            "safeArea" to linkedMapOf<String, Any?>(
                "widthPixels" to (width - safe.left - safe.right),
                "heightPixels" to (height - safe.top - safe.bottom),
                "originXPixels" to safe.left,
                "originYPixels" to safe.top,
                "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
            ),
        )
    }
}
