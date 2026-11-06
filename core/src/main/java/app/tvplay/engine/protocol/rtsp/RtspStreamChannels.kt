package app.tvplay.engine.protocol.rtsp

import android.util.Log
import app.tvplay.engine.auth.AirPlayCrypto
import app.tvplay.engine.auth.DuplexByteStream
import app.tvplay.engine.auth.concatBytes
import app.tvplay.engine.auth.toHexString
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val expectedPeer: InetAddress,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { onDiagnostic("Video: rejected sender outside the control session") }
            runCatching {
                accepted.tcpNoDelay = true
                accepted.keepAlive = true
                if (accepted.receiveBufferSize < VIDEO_RECEIVE_BUFFER_BYTES) {
                    accepted.receiveBufferSize = VIDEO_RECEIVE_BUFFER_BYTES
                }
            }
            socket = accepted
            safeClose(bound)
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        val header = ByteArray(HEADER_LEN)
        val nonce = ByteArray(12)
        try {
            val input = java.io.BufferedInputStream(sock.getInputStream(), 64 * 1024)
            while (!closed.get()) {
                stats.reading()
                if (!readFullyInto(input, header, 0, HEADER_LEN)) break
                val bodySize = readU32Le(header, 0)
                if (bodySize < 0 || bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                val arrivalNanos = System.nanoTime()
                stats.received(HEADER_LEN + bodySize)
                onMessage(header, body, nonce, stats, arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(
        header: ByteArray,
        body: ByteArray,
        nonce: ByteArray,
        stats: StreamReceiveStats,
        arrivalNanos: Long,
    ) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                if (body.size < ScreenCodec.TAG_SIZE) return
                val start = System.nanoTime()
                AirPlayCrypto.writeNonce64(frameCounter.get(), nonce)
                val payload = AirPlayCrypto.chachaOpen(key, nonce, body, header)
                stats.decrypted(System.nanoTime() - start, body.size)
                frameCounter.incrementAndGet()
                if (firstFrameLogged.compareAndSet(false, true)) {
                    Log.i(
                        TAG,
                        "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                            "head=${payload.hexPrefix(16)} chacha=${AirPlayCrypto.chachaImplementation}",
                    )
                }
                listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload), ScreenCodec.senderNanos(header), arrivalNanos)
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        return if (readFullyInto(input, output, 0, length)) output else null
    }

    private fun readFullyInto(input: InputStream, target: ByteArray, offset: Int, length: Int): Boolean {
        var readTotal = 0
        while (readTotal < length) {
            val read = input.read(target, offset + readTotal, length - readTotal)
            if (read < 0) return false
            readTotal += read
        }
        return true
    }

    private companion object {
        const val TAG = "TVPlay"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val VIDEO_RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /**
     * The rate of the stream's RTP timestamps. Opus is always decoded at 48 kHz, but audioFormat
     * 0x10000000 and 0x20000000 count at 16 and 24 kHz (see [MicrophoneConfig.opusClockRate]).
     */
    val rtpClockRate: Int = sampleRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var started = false

    fun listen(listener: Listener): Pair<Int, Int> {
        val data = bindAnyPort()
        val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
        if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
            runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
        }
        onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
        val control = bindAnyPort()
        dataSocket = data
        controlSocket = control
        dataThread = Thread({ runData(data, listener) }, "airplay-audio-rx").apply {
            isDaemon = true
            start()
        }
        controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
            isDaemon = true
            start()
        }
        return data.localPort to control.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dataSocket?.close()
        controlSocket?.close()
        dataThread?.interrupt()
        controlThread?.interrupt()
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val stats = StreamReceiveStats("audio type=$streamType", onDiagnostic)
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        val nonce = ByteArray(12)
        try {
            while (!closed.get()) {
                packet.length = buffer.size
                try {
                    stats.reading()
                    socket.receive(packet)
                } catch (_: Exception) {
                    if (closed.get()) return else continue
                }
                val wireSize = packet.length
                stats.received(
                    size = wireSize,
                    sequence = if (wireSize >= RTP_HEADER_LEN) {
                        ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                    } else null,
                    timestamp = if (wireSize >= RTP_HEADER_LEN) readU32Be(buffer, 4) else null,
                )
                val packetNumber = receivedPackets.incrementAndGet()
                if (wireSize < RTP_HEADER_LEN + TAIL_LEN) {
                    if (packetNumber == 1) {
                        Log.w(TAG, "audio stream type=$streamType short packet bytes=$wireSize")
                    }
                    listener.onPacket(
                        buffer.copyOf(wireSize),
                        null,
                        null,
                        IOException("audio packet shorter than RTP header plus tail"),
                    )
                    stats.processed()
                    continue
                }

                val sealedEnd = wireSize - NONCE_LEN
                val sealedLength = sealedEnd - RTP_HEADER_LEN
                val payloadLength = sealedLength - TAG_LEN
                buffer.copyInto(nonce, 4, sealedEnd, wireSize)
                val sample = readU32Be(buffer, 4)
                val rtp = ByteArray(RTP_HEADER_LEN + payloadLength)
                buffer.copyInto(rtp, 0, 0, RTP_HEADER_LEN)

                try {
                    AirPlayCrypto.chachaOpenInto(
                        key = key,
                        nonce = nonce,
                        ciphertextAndTag = buffer,
                        ciphertextOffset = RTP_HEADER_LEN,
                        ciphertextLength = sealedLength,
                        aad = buffer,
                        aadOffset = 4,
                        aadLength = RTP_HEADER_LEN - 4,
                        output = rtp,
                        outputOffset = RTP_HEADER_LEN,
                    )
                } catch (error: Exception) {
                    val failureNumber = authenticationFailures.incrementAndGet()
                    val wire = buffer.copyOf(wireSize)
                    if (failureNumber == 1) {
                        Log.w(
                            TAG,
                            "audio stream type=$streamType first decrypt failure wire=${wire.toHexString()}",
                            error,
                        )
                    }
                    listener.onPacket(wire, null, sample, error)
                    stats.processed()
                    continue
                }
                val decryptedNumber = decryptedPackets.incrementAndGet()
                if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                    Log.i(
                        TAG,
                        "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                            "wireBytes=$wireSize payloadBytes=$payloadLength " +
                            "payloadHead=${rtp.copyOfRange(RTP_HEADER_LEN, minOf(rtp.size, RTP_HEADER_LEN + 16)).toHexString()}",
                    )
                } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                    Log.i(
                        TAG,
                        "audio stream type=$streamType decrypted=$decryptedNumber " +
                            "authFailures=${authenticationFailures.get()}",
                    )
                }
                if (!started) {
                    started = true
                    listener.onStarted(sample)
                }
                listener.onRtp(rtp, sample)
                stats.processed()
            }
        } finally {
            stats.flush(ended = true)
        }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            packet.length = buffer.size
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private companion object {
        const val TAG = "TVPlay"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(
                AudioCodecKind.OPUS,
                48_000,
                1,
                payloadType,
                audioType,
                rtpClockRate = MicrophoneConfig.opusClockRate(bits),
            )
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}

/**
 * CarPlay's main buffered audio (WWDC23 "Enhanced buffering").
 */
class BufferedAudioStream(
    private val key: ByteArray,
    private val format: AudioFormat,
    private val sink: MediaSink,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val expectedPeer: InetAddress = InetAddress.getLoopbackAddress(),
) : Closeable {
    internal class Frame(val sequence: Int, val timestamp: Long, val rtp: ByteArray)

    private val id = AudioStreamId(format.payloadType, format.audioType)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var cleanupStarted = false
    @Volatile internal var outputCleanupComplete = false
        private set
    private val server = ServerSocket(0)
    private val boundPort = server.localPort
    @Volatile private var client: Socket? = null
    private val lock = Object()
    private val queue = ArrayDeque<Frame>()
    private val fed = ArrayDeque<Frame>()
    private var queuedBytes = 0L
    private val maxBufferedFrames = (format.sampleRate * MAX_BUFFER_MILLIS / (SAMPLES_PER_FRAME * 1000)).toInt()
    private val pacedFillFloorFrames =
        (format.sampleRate * PACED_FILL_FLOOR_MILLIS / (SAMPLES_PER_FRAME * 1000)).toInt()
    private val pacedFrameMillis =
        (SAMPLES_PER_FRAME * 1000L / (format.sampleRate.toLong() * FILL_RATE_MULTIPLE)).coerceAtLeast(1L)

    private var rate = 0
    private var flushUntil: Long? = null
    private var anchorRtp: Long? = null
    private var anchorNtp: BigInteger? = null
    private var feedStartTimestamp: Long? = null
    private var feedStartNs = 0L
    private var fedSamples = 0L
    private var outputGeneration = 0L

    val port: Int get() = boundPort

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        Thread({ receive() }, "airplay-buffered-rx").apply { isDaemon = true; start() }
        Thread({ feed() }, "airplay-buffered-feed").apply { isDaemon = true; start() }
    }

    private fun receive() {
        var accepted: Socket? = null
        try {
            val socket = acceptPeer() ?: return
            accepted = socket
            server.close()
            log("Buffered audio: connected")
            val input = DataInputStream(socket.getInputStream().buffered())
            var failures = 0
            var receivedBytes = 0L
            var windowStart = nanoTime()
            while (!closed.get()) {
                val length = input.readUnsignedShort()
                if (length < LENGTH_BYTES + RTP_HEADER + TAG_BYTES + NONCE_BYTES) {
                    log("Buffered audio: invalid frame length $length; closed")
                    close()
                    return
                }
                val body = ByteArray(length - LENGTH_BYTES)
                input.readFully(body)
                val frame = open(key, body)
                if (frame == null) {
                    if (failures++ < 3) log("Buffered audio: frame did not open (${body.size} bytes)")
                    continue
                }
                receivedBytes += length
                val now = nanoTime()
                if (now - windowStart >= STATS_WINDOW_NS) {
                    val queuedMillis = synchronized(lock) { queue.size * SAMPLES_PER_FRAME * 1000L / format.sampleRate }
                    log(
                        "Buffered audio: queued ${queuedMillis / 1000} s, received " +
                            "${receivedBytes * 8 * 1_000_000L / ((now - windowStart) / 1000L) / 1000} kbit/s",
                    )
                    receivedBytes = 0
                    windowStart = now
                }
                if (frame.rtp.size <= RTP_HEADER) continue
                var pacing = false
                synchronized(lock) {
                    while (!closed.get() &&
                        flushUntil?.let { before(frame.timestamp, it) } != true &&
                        (queuedBytes + frame.rtp.size > AUDIO_BUFFER_BYTES || queue.size + fed.size >= maxBufferedFrames)
                    ) {
                        lock.wait()
                    }
                    if (closed.get()) return
                    if (flushUntil?.let { before(frame.timestamp, it) } == true) return@synchronized
                    queue.addLast(frame)
                    queuedBytes += frame.rtp.size
                    lock.notifyAll()
                    pacing = queue.size + fed.size >= pacedFillFloorFrames
                }
                if (pacing) pause(pacedFrameMillis)
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered audio: connection ended (${error.javaClass.simpleName})")
        } finally {
            runCatching { accepted?.close() }
            synchronized(lock) { if (client === accepted) client = null }
        }
    }

    private fun acceptPeer(): Socket? {
        while (!closed.get()) {
            val socket = server.accept()
            val accepted = synchronized(lock) {
                if (closed.get()) {
                    runCatching { socket.close() }
                    return null
                }
                if (!isSameOrAuthorizedAirPlayPeer(socket.inetAddress, expectedPeer)) {
                    runCatching { socket.close() }
                    log("Buffered audio: rejected sender outside the control session")
                    false
                } else {
                    runCatching {
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                    }
                    client = socket
                    true
                }
            }
            if (accepted) return socket
        }
        return null
    }

    private fun feed() {
        try {
            while (!closed.get()) {
                synchronized(lock) {
                    if (closed.get()) return
                    retireFedLocked()
                    if (rate == 0 || queue.isEmpty() || leadMillisLocked() >= LEAD_MILLIS) {
                        lock.wait(FEED_POLL_MILLIS)
                    } else {
                        queue.pollFirst()?.also { frame ->
                            fed.addLast(frame)
                            val generation = outputGeneration
                            if (feedStartTimestamp == null) {
                                feedStartTimestamp = frame.timestamp
                                feedStartNs = nanoTime()
                                fedSamples = 0
                                sink.onAudioStarted(id, format, frame.timestamp.toInt())
                            }
                            if (closed.get() || rate == 0 || generation != outputGeneration) return@synchronized
                            fedSamples += SAMPLES_PER_FRAME
                            sink.onAudioRtp(id, format, frame.rtp, frame.timestamp.toInt())
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            if (!closed.get()) Log.w(TAG, "buffered audio feed failed", error)
            close()
        }
    }

    private fun leadMillisLocked(): Long {
        if (feedStartTimestamp == null) return 0
        val playedMillis = (nanoTime() - feedStartNs) / 1_000_000L
        return fedSamples * 1000L / format.sampleRate - playedMillis
    }

    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: BigInteger): Map<String, Any?>? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        if (newRate > 0) {
            val start = rtpTime ?: positionLocked() ?: queue.peekFirst()?.timestamp
            restoreFedLocked()
            if (start != null) dropBeforeLocked(start)
            restartOutputLocked()
            anchorRtp = start
            anchorNtp = nowNtp.add(millisToNtp(START_LATENCY_MILLIS))
        } else {
            val position = positionLocked()
            restoreFedLocked()
            position?.let { anchorRtp = it; dropBeforeLocked(it) }
            anchorNtp = nowNtp
            restartOutputLocked()
        }
        rate = newRate.coerceIn(0, 1)
        lock.notifyAll()
        anchorLocked()
    }

    fun anchor(): Map<String, Any?>? = synchronized(lock) { anchorLocked() }

    fun flush(untilTimestamp: Long?) = synchronized(lock) {
        if (closed.get()) return@synchronized
        restoreFedLocked()
        if (untilTimestamp != null) dropBeforeLocked(untilTimestamp)
        restartOutputLocked()
        lock.notifyAll()
    }

    fun feedback(nowNtp: BigInteger, connectionId: Any?): Map<String, Any?> = synchronized(lock) {
        linkedMapOf<String, Any?>(
            "type" to format.payloadType,
            "sampleRate" to format.sampleRate,
        ).apply {
            val position = positionLocked() ?: return@apply
            put("streamConnectionID", unsignedPlistInteger(connectionId ?: 0L))
            put("timestamp", nowNtp)
            put("timestampRawNs", nanoTime())
            put("sampleTime", position)
        }
    }

    private fun positionLocked(): Long? {
        val start = feedStartTimestamp
        if (start != null && rate > 0) {
            val playedMillis = maxOf(0L, (nanoTime() - feedStartNs) / 1_000_000L - START_LATENCY_MILLIS)
            return (start + playedMillis * format.sampleRate / 1000L) and U32
        }
        return anchorRtp
    }

    private fun anchorLocked(): Map<String, Any?>? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        return anchorPlist(rtp, at, rate)
    }

    private fun dropBeforeLocked(timestamp: Long) {
        flushUntil = timestamp
        while (queue.peekFirst()?.let { before(it.timestamp, timestamp) } == true) {
            queuedBytes -= queue.pollFirst()!!.rtp.size
        }
        lock.notifyAll()
    }

    private fun retireFedLocked() {
        val position = positionLocked() ?: return
        while (fed.peekFirst()?.let { frame ->
                val end = (frame.timestamp + SAMPLES_PER_FRAME) and U32
                end == position || before(end, position)
            } == true
        ) {
            queuedBytes -= fed.removeFirst().rtp.size
        }
        lock.notifyAll()
    }

    private fun restoreFedLocked() {
        while (fed.isNotEmpty()) queue.addFirst(fed.removeLast())
    }

    private fun restartOutputLocked() {
        outputGeneration++
        if (feedStartTimestamp != null) {
            runCatching { sink.onAudioStopped(id) }
                .onFailure { log("Buffered audio: output cleanup failed (${it.javaClass.simpleName})") }
        }
        feedStartTimestamp = null
        fedSamples = 0
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
        synchronized(lock) {
            if (cleanupStarted) return
            cleanupStarted = true
            try {
                runCatching { client?.close() }
                client = null
                queue.clear()
                fed.clear()
                queuedBytes = 0
                rate = 0
                restartOutputLocked()
            } finally {
                outputCleanupComplete = true
                lock.notifyAll()
            }
        }
    }

    companion object {
        private const val TAG = "TVPlay"
        const val STREAM_TYPE = 103
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        private const val MAX_BUFFER_MILLIS = 120_000L
        private const val PACED_FILL_FLOOR_MILLIS = 15_000L
        private const val FILL_RATE_MULTIPLE = 4L
        internal const val LEAD_MILLIS = 1_000L
        internal const val START_LATENCY_MILLIS = 400L
        private const val FEED_POLL_MILLIS = 20L
        private const val STATS_WINDOW_NS = 10_000_000_000L
        private const val SAMPLES_PER_FRAME = 1024L
        private const val LENGTH_BYTES = 2
        private const val RTP_HEADER = 12
        private const val NONCE_BYTES = 8
        private const val TAG_BYTES = 16
        private const val U32 = 0xffff_ffffL
        private const val NTP_UNIX_OFFSET = 2_208_988_800L

        internal fun open(key: ByteArray, body: ByteArray): Frame? {
            if (body.size < RTP_HEADER + TAG_BYTES + NONCE_BYTES) return null
            if ((body[0].toInt() and 0xff) != 0x80) return null
            val sealedEnd = body.size - NONCE_BYTES
            val sealedLength = sealedEnd - RTP_HEADER
            val payloadLength = sealedLength - TAG_BYTES
            val nonce = ByteArray(12).also { body.copyInto(it, 4, sealedEnd, body.size) }
            val rtp = ByteArray(RTP_HEADER + payloadLength)
            body.copyInto(rtp, 0, 0, RTP_HEADER)
            val opened = runCatching {
                AirPlayCrypto.chachaOpenInto(
                    key = key,
                    nonce = nonce,
                    ciphertextAndTag = body,
                    ciphertextOffset = RTP_HEADER,
                    ciphertextLength = sealedLength,
                    aad = body,
                    aadOffset = 4,
                    aadLength = RTP_HEADER - 4,
                    output = rtp,
                    outputOffset = RTP_HEADER,
                )
            }.isSuccess
            if (!opened) return null
            val sequence = ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
            return Frame(sequence, u32(body, 4), rtp)
        }

        internal fun anchorPlist(rtp: Long, ntp: BigInteger, rate: Int): Map<String, Any?> = linkedMapOf(
            "rtpTime" to (rtp and U32),
            "networkTimeSecs" to ntp.shiftRight(32).toLong() - NTP_UNIX_OFFSET,
            "networkTimeFrac" to ntp.and(BigInteger.valueOf(U32)).shiftLeft(32),
            "rate" to rate,
        )

        internal fun millisToNtp(millis: Long): BigInteger =
            BigInteger.valueOf(millis).shiftLeft(32).divide(BigInteger.valueOf(1000))

        internal fun before(a: Long, b: Long): Boolean = ((a - b) and U32) >= 0x8000_0000L

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or
                ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or
                (bytes[offset + 3].toLong() and 0xff)
    }
}

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** A frame with the iPhone's frame time and its arrival time (System.nanoTime); see [ScreenStream.Listener]. */
    fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes)
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
    /** The iPhone asked the receiver to duck its other audio for a navigation prompt (true), or to restore it. */
    fun onAudioDuck(ducked: Boolean) {}
}

internal fun currentScreenListener(type: Int, sink: MediaSink, isCurrent: () -> Boolean) = object : ScreenStream.Listener {
    override fun onCodec(codec: VideoCodec) { if (isCurrent()) sink.onVideoCodec(type, codec) }
    override fun onConfig(codecData: ByteArray) { if (isCurrent()) sink.onVideoConfig(type, codecData) }
    override fun onFrame(naluBytes: ByteArray) { if (isCurrent()) sink.onVideoFrame(type, naluBytes) }
    override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (isCurrent()) sink.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
    }
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink].
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: RtspControlSession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    ) {
        val position = FeedbackPositionMonitor(format.rtpClockRate)
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (DuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val pendingIapTunnels = ConcurrentHashMap<RtspControlSession, PendingIapTunnel>()
    private val bufferedStreams = ConcurrentHashMap<RtspControlSession, BufferedStream>()
    private val microphoneScheduler by lazy {
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "carplay-mic-start").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(MICROPHONE_SCHEDULER_IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    private val duckingSession = AtomicReference<RtspControlSession?>()

    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: RtspControlSession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val retiredBufferedSessions = Collections.newSetFromMap(WeakHashMap<RtspControlSession, Boolean>())

    private class BufferedTransition(val session: RtspControlSession, val candidate: BufferedStream?) {
        var cancelled = false
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: RtspControlSession): Boolean {
        bufferedTransition?.takeIf { it.candidate == null && it.retiring?.stream?.outputCleanupComplete == true }
            ?.let { bufferedTransition = null }
        return !session.isClosed && session !in retiredBufferedSessions && bufferedTransition == null
    }

    @Volatile private var iapTunnelHandler: ((DuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((DuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: RtspControlSession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val peer = session.remoteAddress ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${unsignedPlistDecimal(stream["streamConnectionID"])}")
        streams.keys.filter { it.type == type }.forEach { existingKey ->
            streams.remove(existingKey)?.close()
        }
        val screen = ScreenStream(key, peer, session::logDebug)
        sink.setVideoDiagnosticHandler(type) {
            if (type == STREAM_TYPE_MAIN_SCREEN && streams[streamKey] === screen && it == "first frame rendered") {
                session.videoFrameRendered()
            }
            session.logDebug("Video: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (streams[streamKey] === screen) {
                val sent = session.sendCommand(mapOf("type" to "forceKeyFrame"))
                session.logDebug("Video recovery: requested keyframe sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener by currentScreenListener(type, sink, isCurrent = { streams[streamKey] === screen }) {
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    val removedCurrent = streams.remove(streamKey, screen)
                    if (!removedCurrent) return
                    session.onStreamClosedByPeer(type)
                    if (streams.keys.none { it.type == type }) {
                        sink.onScreenStreamActive(type, false)
                    }
                    if (cause != null && !session.hasActiveMediaStreams()) {
                        session.close()
                    }
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: RtspControlSession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        streams.keys.filter { it.type == type && it.audioType == audioType }.forEach { existingKey ->
            streams.remove(existingKey)?.close()
            audioMeta.remove(existingKey)
        }
        val removedMic = pendingMicrophone.keys
            .filter { it.type == type && it.audioType == audioType }
            .count { pendingMicrophone.remove(it) != null }
        if (removedMic > 0) sink.onMicrophoneStopped(streamId)
        sink.onAudioStopped(streamId)

        val key = outputKey(session, stream) ?: return null
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)
        if (microphone != null) pendingMicrophone[streamKey] = microphone
        if (type == STREAM_TYPE_MAIN_AUDIO && audioType != "media") {
            session.logDebug(
                "Audio: setup type=$type audioType=$audioType input=${stream["input"] ?: "none"} " +
                    "micPort=${if ((stream["dataPort"] as? Number)?.toInt()?.let { it > 0 } == true) "yes" else "no"} " +
                    "microphone=${if (microphone != null) "on" else "off"} keys=${stream.keys.sorted().joinToString(",")}" +
                    (stream["vocoderInfo"]?.let { " vocoderInfo=${it.toString().take(200)}" } ?: ""),
            )
        }

        val clockProbe = RtpClockProbe()
        val microphoneStart = microphone?.let { config -> MicrophoneStart { sink.onMicrophoneStarted(streamId, config) } }
        val audio = AudioStream(key, type, session::logDebug)
        val (dataPort, controlPort) = audio.listen(
            object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) {
                    meta.firstSample = firstSample
                    meta.originNs = System.nanoTime()
                    sink.onAudioStarted(streamId, format, firstSample)
                    microphoneStart?.onDownlink()
                }

                override fun onRtp(rtp: ByteArray, sample: Int) {
                    meta.position.observeRtp(sample)
                    clockProbe.observe(sample, System.nanoTime())?.let { hz ->
                        session.logDebug(
                            "Audio: rtp clock type=$type audioType=$audioType " +
                                "audioFormat=0x${java.lang.Long.toHexString((stream["audioFormat"] as? Number)?.toLong() ?: 0L)} " +
                                "measuredHz=$hz microphoneClockHz=${microphone?.rtpClockRate ?: "none"}",
                        )
                    }
                    sink.onAudioRtp(streamId, format, rtp, sample)
                }
            },
        )
        streams[streamKey] = audio
        audioMeta[streamKey] = meta
        if (microphoneStart != null && MicrophoneStart.startsWithoutDownlink(audioType)) {
            microphoneStart.scheduleSilentStart(microphoneScheduler, MicrophoneStart.SILENT_START_MILLIS) { startNow ->
                var started = false
                pendingMicrophone.computeIfPresent(streamKey) { _, pending ->
                    if (pending === microphone) started = startNow()
                    pending
                }
                if (started) {
                    session.logDebug(
                        "Audio: microphone started without downlink audio type=$type audioType=$audioType " +
                            "afterMs=${MicrophoneStart.SILENT_START_MILLIS}",
                    )
                }
            }
        }
        return linkedMapOf(
            "type" to type,
            "dataPort" to dataPort,
            "controlPort" to controlPort,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
        )
    }

    override fun onBufferedAudio(session: RtspControlSession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val key = stream["shk"] as? ByteArray
        if (key == null || key.size != 32) {
            Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
            return null
        }
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, "media")
        if (format.codec != AudioCodecKind.AAC_LC) {
            Log.w(TAG, "buffered audio format ${stream["audioFormat"]} is not AAC-LC; declined")
            return null
        }
        if (stream["ct"]?.let { it !is Number || it.toInt() != 4 } == true ||
            stream["spf"]?.let { it !is Number || it.toInt() != 1024 } == true
        ) {
            Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
            return null
        }
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, format, sink, session::logDebug, expectedPeer = peer)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            if (!mayInstallBufferedLocked(session)) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    if (oldSession !== session) retiredBufferedSessions.add(oldSession)
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val retired = runCatching { previous?.stream?.close() }.isSuccess &&
            previous?.stream?.outputCleanupComplete != false
        val installed = synchronized(bufferedOwnershipLock) {
            val usable = retired && bufferedTransition === transition && !transition.cancelled &&
                !session.isClosed && session !in retiredBufferedSessions
            if (usable) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            if (bufferedTransition === transition) {
                bufferedTransition = if (retired) null else BufferedTransition(session, null).also { it.retiring = previous }
            }
            usable
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream client=${stream["clientID"]} rate=${format.sampleRate} port=${buffered.port}")
        return linkedMapOf(
            "type" to type,
            "dataPort" to buffered.port,
            "audioBufferSize" to BufferedAudioStream.AUDIO_BUFFER_BYTES,
        )
    }

    override fun onBufferedAudioControl(
        session: RtspControlSession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        val rtpTime = (body["rtpTime"] as? Number)?.toLong()
        val rate = when ((body["rate"] as? Number)?.toDouble()) {
            0.0 -> 0
            1.0 -> 1
            else -> null
        }
        return when (method) {
            "SETRATE", "SETRATEANCHORTIME" -> {
                if (rate == null || rate !in 0..1) null else buffered.setRate(rtpTime, rate, session.syncedNtp())
            }
            "GETANCHOR" -> buffered.anchor()
            "FLUSHBUFFERED" -> {
                (body["flushUntilTS"] as? Number)?.toLong()?.let { buffered.flush(it) }
                null
            }
            else -> null
        }
    }

    override fun onDataStream(session: RtspControlSession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        val peer = session.remoteAddress ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            expectedPeer = peer,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            if (!session.hasActiveMediaStreams()) {
                                session.close()
                            }
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        val newDataStream = if (handler != null) bridge else tunnel
        streams.put(StreamKey(session, STREAM_TYPE_DATA), newDataStream)?.let { previous ->
            if (previous !== newDataStream) {
                runCatching { previous.close() }
            }
        }
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: RtspControlSession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            if (!session.hasActiveMediaStreams()) {
                session.close()
            }
        }
    }

    override fun onFeedback(session: RtspControlSession): Map<String, Any?>? {
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            val clockRate = meta.format.rtpClockRate
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to clockRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * clockRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
                meta.position.onFeedback(meta.type, entry["streamConnectionID"], sampleTime, nowNs)
                    ?.let(session::logDebug)
            }
            entry
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: RtspControlSession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        tornDown.forEach { key ->
            val streamId = AudioStreamId(key.type, key.audioType)
            streams.remove(key)?.close()
            audioMeta.remove(key)
            val removedMic = pendingMicrophone.remove(key) != null
            if (removedMic && pendingMicrophone.keys.none { it.type == key.type && it.audioType == key.audioType }) {
                sink.onMicrophoneStopped(streamId)
            }
            if (
                key.type != STREAM_TYPE_MAIN_SCREEN &&
                key.type != STREAM_TYPE_DATA &&
                key.type != BufferedAudioStream.STREAM_TYPE &&
                streams.keys.none { it.type == key.type && it.audioType == key.audioType }
            ) {
                sink.onAudioStopped(streamId)
            }
        }
        if (type == STREAM_TYPE_MAIN_SCREEN && tornDown.isNotEmpty() && streams.keys.none { it.type == type }) {
            sink.onScreenStreamActive(type, false)
        }
    }

    override fun onAudioDuck(session: RtspControlSession, ducked: Boolean) {
        if (ducked) duckingSession.set(session) else duckingSession.compareAndSet(session, null)
        sink.onAudioDuck(ducked)
    }

    override fun onSessionClosed(session: RtspControlSession) {
        if (duckingSession.compareAndSet(session, null)) sink.onAudioDuck(false)
        clearPendingIapTunnel(session)
        retireBuffered(session)
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        sessionStreams.forEach { streams.remove(it)?.close() }
        audioMeta.keys.filter { it.session === session }.forEach { audioMeta.remove(it) }
        val removedMics = pendingMicrophone.keys.filter { it.session === session }.also { keys ->
            keys.forEach { pendingMicrophone.remove(it) }
        }
        removedMics.forEach { key ->
            if (pendingMicrophone.keys.none { it.type == key.type && it.audioType == key.audioType }) {
                sink.onMicrophoneStopped(AudioStreamId(key.type, key.audioType))
            }
        }
        sessionStreams.forEach { key ->
            when (key.type) {
                STREAM_TYPE_MAIN_SCREEN -> {
                    if (streams.keys.none { it.type == key.type }) {
                        sink.onScreenStreamActive(key.type, false)
                    }
                }
                STREAM_TYPE_DATA, BufferedAudioStream.STREAM_TYPE -> Unit
                else -> {
                    if (streams.keys.none { it.type == key.type && it.audioType == key.audioType }) {
                        sink.onAudioStopped(AudioStreamId(key.type, key.audioType))
                    }
                }
            }
        }
    }

    private fun detachBufferedLocked(session: RtspControlSession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: RtspControlSession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            bufferedTransition?.takeIf { it.session === session }?.let {
                it.cancelled = true
                pending = it.candidate
            }
            detachBufferedLocked(session).also {
                if (it != null && bufferedTransition == null) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (retirement != null && bufferedTransition === retirement &&
                    previous?.stream?.outputCleanupComplete == true
                ) {
                    bufferedTransition = null
                }
            }
        }
    }

    private fun replacePendingIapTunnel(session: RtspControlSession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: RtspControlSession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: RtspControlSession, stream: Map<String, Any?>): ByteArray? =
        dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)

    private fun microphoneConfig(
        session: RtspControlSession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (!microphoneEnabled || type != STREAM_TYPE_MAIN_AUDIO) return null
        if (!wantsMicrophone(format.audioType, stream)) return null
        val port = (stream["dataPort"] as? Number)?.toInt() ?: return null
        if (port !in 1..65535) return null
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / format.sampleRate).toInt().coerceIn(5, 60)
        } else {
            MicrophoneConfig.pcmFrameMillis(format.sampleRate, format.channels)
        }
        val opusBitrate = when {
            formatBits and OPUS_48K != 0L -> 96_000
            formatBits and OPUS_24K != 0L -> 64_000
            else -> 48_000
        }
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            opusClockRate = MicrophoneConfig.opusClockRate(formatBits),
        )
    }

    private fun dataStreamKey(
        session: RtspControlSession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    companion object {
        fun wantsMicrophone(audioType: String, stream: Map<String, Any?>): Boolean = when (audioType) {
            "telephony", "speechrecognition" -> true
            "default", "compatibility" -> when (val input = stream["input"]) {
                is Boolean -> input
                is Number -> input.toInt() != 0
                else -> (stream["dataPort"] as? Number)?.toInt()?.let { it in 1..65535 } == true
            }
            else -> false
        }

        private const val TAG = "TVPlay"
        private const val MICROPHONE_SCHEDULER_IDLE_SECONDS = 5L
        private const val STREAM_TYPE_MAIN_SCREEN = 110
        private const val STREAM_TYPE_MAIN_AUDIO = 100
        private const val STREAM_TYPE_DATA = 130
        private const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        private const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val OPUS_24K = 0x20000000L
        private const val OPUS_48K = 0x40000000L
    }
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
    val opusClockRate: Int = OPUS_CAPTURE_RATE,
) {
    val samplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) {
            OPUS_SAMPLES_PER_PACKET
        } else {
            maxOf(1, sampleRate * frameMillis / 1000)
        }

    val rtpClockRate: Int
        get() = if (codec == AudioCodecKind.OPUS) opusClockRate else sampleRate

    val rtpSamplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) opusClockRate * OPUS_FRAME_MILLIS / 1000 else samplesPerPacket

    val frameBytes: Int
        get() = samplesPerPacket * channels * 2

    companion object {
        private const val OPUS_SAMPLES_PER_PACKET = 960
        private const val OPUS_CAPTURE_RATE = 48_000
        private const val OPUS_FRAME_MILLIS = 20

        fun opusClockRate(formatBits: Long): Int = when {
            formatBits and 0x40000000L != 0L -> 48_000
            formatBits and 0x20000000L != 0L -> 24_000
            formatBits and 0x10000000L != 0L -> 16_000
            else -> OPUS_CAPTURE_RATE
        }

        fun pcmFrameMillis(sampleRate: Int, channels: Int): Int = PCM_FRAME_MILLIS.firstOrNull { millis ->
            MicrophonePacketizer.SEAL_OVERHEAD + sampleRate * millis / 1000 * channels * 2 <= MAX_UDP_PAYLOAD
        } ?: PCM_FRAME_MILLIS.last()

        private val PCM_FRAME_MILLIS = listOf(20, 10, 5)
        private const val MAX_UDP_PAYLOAD = 1500 - 40 - 8
    }
}

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(
    var sequence: Int = 0,
    var timestamp: Int = 0,
    var nonce: Long = 0,
)

/**
 * Seals one PCM microphone frame in the wired CarPlay RTP layout.
 */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8
    const val SEAL_OVERHEAD = RTP_HEADER_LEN + TAG_LEN + NONCE_LEN

    fun sealPacket(
        key: ByteArray,
        payloadType: Int,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7f).toByte()
        putU16Be(header, 2, counters.sequence)
        putU32Be(header, 4, counters.timestamp)

        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counters.nonce)
        val sealed = AirPlayCrypto.chachaSeal(
            key = key,
            nonce = nonce,
            plaintext = body,
            aad = header.copyOfRange(4, RTP_HEADER_LEN),
        )

        val packet = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(packet, 0)
        sealed.copyInto(packet, RTP_HEADER_LEN)
        putU64Le(packet, RTP_HEADER_LEN + sealed.size, counters.nonce)

        counters.sequence = (counters.sequence + 1) and 0xffff
        counters.timestamp += samples
        counters.nonce++
        return packet
    }

    /** Captured Android PCM is little-endian; CarPlay's wired microphone payload is big-endian. */
    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
        val output = pcmLittleEndian.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }

    private fun putU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 0 until 8) {
            target[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}

/**
 * Starts one stream's microphone at most once: at the first downlink packet, or [SILENT_START_MILLIS]
 * after SETUP when the iPhone asks for input but plays nothing.
 */
class MicrophoneStart(private val start: () -> Unit) {
    private val claimed = AtomicBoolean()
    @Volatile private var silentStart: Future<*>? = null

    fun onDownlink() {
        silentStart?.cancel(false)
        if (claimed.compareAndSet(false, true)) start()
    }

    fun scheduleSilentStart(
        scheduler: ScheduledExecutorService,
        delayMillis: Long,
        whileCurrent: (startNow: () -> Boolean) -> Unit,
    ) {
        silentStart = scheduler.schedule(
            {
                whileCurrent {
                    if (claimed.compareAndSet(false, true)) {
                        start()
                        true
                    } else {
                        false
                    }
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        const val SILENT_START_MILLIS = 500L

        fun startsWithoutDownlink(audioType: String): Boolean = audioType != "telephony"
    }
}

/** Receive-thread timing only: no payloads, endpoint addresses, or route data. */
class StreamReceiveStats(
    private val label: String,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var processingStart = windowStart
    private var packets = 0
    private var bytes = 0L
    private var maxReadNs = 0L
    private var readsOver250Ms = 0
    private var seqGapAfterReadOver250Ms = 0
    private var seqMissingAfterReadOver250Ms = 0
    private var lastReceivedNs = 0L
    private var maxInterArrivalNs = 0L
    private var maxProcessNs = 0L
    private var lastTimestamp: Int? = null
    private var nextSequence: Int? = null
    private var forwardGapPackets = 0
    private var sequenceGapEvents = 0
    private var maxSequenceGap = 0
    private var lastSequenceGap = "none"
    private var lastSequenceGapAtMs = -1L
    private var lateOrDuplicate = 0
    private var decryptSamples = 0
    private var decryptBytes = 0L
    private var decryptSumNs = 0L
    private var maxDecryptNs = 0L

    fun reading() { readStart = nowNs() }

    fun received(size: Int, sequence: Int? = null, timestamp: Int? = null) {
        processingStart = nowNs()
        val readNs = processingStart - readStart
        maxReadNs = maxOf(maxReadNs, readNs)
        if (readNs >= LONG_READ_NS) readsOver250Ms++
        if (lastReceivedNs != 0L) {
            maxInterArrivalNs = maxOf(maxInterArrivalNs, processingStart - lastReceivedNs)
        }
        lastReceivedNs = processingStart
        packets++
        bytes += size
        if (sequence != null) {
            val expected = nextSequence
            val delta = if (expected == null) 0 else (sequence - expected) and 0xffff
            if (delta < 0x8000) {
                forwardGapPackets += delta
                if (delta > 0) {
                    sequenceGapEvents++
                    if (readNs >= LONG_READ_NS) {
                        seqGapAfterReadOver250Ms++
                        seqMissingAfterReadOver250Ms += delta
                    }
                    maxSequenceGap = maxOf(maxSequenceGap, delta)
                    lastSequenceGap = "expected=$expected received=$sequence missing=$delta " +
                        "previousRtpTs=${lastTimestamp?.toUnsignedLong() ?: "unknown"} " +
                        "receivedRtpTs=${timestamp?.toUnsignedLong() ?: "unknown"}"
                    lastSequenceGapAtMs = (processingStart - windowStart) / 1_000_000L
                }
                nextSequence = (sequence + 1) and 0xffff
            } else {
                lateOrDuplicate++
            }
        }
        if (timestamp != null) lastTimestamp = timestamp
    }

    fun decrypted(durationNs: Long, size: Int) {
        decryptSamples++
        decryptBytes += size
        decryptSumNs += durationNs
        maxDecryptNs = maxOf(maxDecryptNs, durationNs)
    }

    fun processed() {
        maxProcessNs = maxOf(maxProcessNs, nowNs() - processingStart)
        flush()
    }

    fun flush(ended: Boolean = false) {
        val now = nowNs()
        if (!ended && now - windowStart < 5_000_000_000L) return
        runCatching {
            report(
                "Receive: $label packets=$packets bytes=$bytes readMaxMs=${maxReadNs / 1_000_000} " +
                    "processMaxUs=${maxProcessNs / 1000} seqForwardGaps=$forwardGapPackets " +
                    "lateOrDuplicate=$lateOrDuplicate interArrivalMaxMs=${maxInterArrivalNs / 1_000_000} " +
                    "seqGapEvents=$sequenceGapEvents seqGapMax=$maxSequenceGap " +
                    "seqGapLast=[$lastSequenceGap] seqGapAtMs=$lastSequenceGapAtMs ended=$ended " +
                    "windowMs=${(now - windowStart).coerceAtLeast(0) / 1_000_000} " +
                    "readsOver250Ms=$readsOver250Ms seqGapAfterReadOver250Ms=$seqGapAfterReadOver250Ms " +
                    "seqMissingAfterReadOver250Ms=$seqMissingAfterReadOver250Ms" + decryptSummary(),
            )
        }
        windowStart = now
        decryptSamples = 0
        decryptBytes = 0
        decryptSumNs = 0
        maxDecryptNs = 0
        packets = 0
        bytes = 0
        maxReadNs = 0
        readsOver250Ms = 0
        seqGapAfterReadOver250Ms = 0
        seqMissingAfterReadOver250Ms = 0
        maxInterArrivalNs = 0
        maxProcessNs = 0
        forwardGapPackets = 0
        sequenceGapEvents = 0
        maxSequenceGap = 0
        lastSequenceGap = "none"
        lastSequenceGapAtMs = -1L
        lateOrDuplicate = 0
    }

    private fun Int.toUnsignedLong(): Long = toLong() and 0xffff_ffffL

    private fun decryptSummary(): String {
        if (decryptSamples == 0) return ""
        val mbPerSecond = if (decryptSumNs == 0L) 0L else decryptBytes * 1_000L / decryptSumNs
        return " decryptAvgUs=${decryptSumNs / decryptSamples / 1000} decryptMaxUs=${maxDecryptNs / 1000} " +
            "decryptMBps=$mbPerSecond"
    }

    private companion object {
        const val LONG_READ_NS = 250_000_000L
    }
}

/**
 * Measures the RTP clock the iPhone uses on one audio stream from its own downlink packets.
 */
class RtpClockProbe(private val windowNs: Long = 2_000_000_000L) {
    private var firstSample = 0L
    private var firstNs = 0L
    private var started = false
    private var done = false

    @Synchronized
    fun observe(sample: Int, nowNs: Long): Int? {
        if (done) return null
        val unsigned = sample.toLong() and 0xffff_ffffL
        if (!started) {
            started = true
            firstSample = unsigned
            firstNs = nowNs
            return null
        }
        val elapsedNs = nowNs - firstNs
        if (elapsedNs < windowNs) return null
        done = true
        val ticks = (unsigned - firstSample) and 0xffff_ffffL
        return Math.round(ticks * 1_000_000_000.0 / elapsedNs).toInt()
    }
}

/**
 * Compares the playback position reported in /feedback with the newest RTP sample received.
 */
class FeedbackPositionMonitor(
    private val sampleRate: Int,
    private val repeatNs: Long = 10_000_000_000L,
) {
    private var received: Long? = null
    private var baselineLogged = false
    private var lastOverrunNs: Long? = null

    @Synchronized
    fun observeRtp(sample: Int) {
        val unsigned = sample.toLong() and 0xffff_ffffL
        val current = received
        if (current == null || samplesAhead(unsigned, current) > 0) received = unsigned
    }

    @Synchronized
    fun onFeedback(type: Int, connectionId: Any?, reported: Long, nowNs: Long): String? {
        val received = received ?: return null
        val ahead = samplesAhead(reported, received)
        val overrun = ahead > OVERRUN_SECONDS * sampleRate
        val first = !baselineLogged
        if (!first && !overrun) return null
        if (!first && lastOverrunNs?.let { nowNs - it < repeatNs } == true) return null
        baselineLogged = true
        if (overrun) lastOverrunNs = nowNs
        return "Audio: feedback type=$type id=${connectionId ?: 0} reported=$reported received=$received " +
            "aheadMs=${ahead * 1000 / sampleRate}" + if (overrun) " overrun=true" else ""
    }

    companion object {
        const val OVERRUN_SECONDS = 0.1

        fun samplesAhead(sample: Long, base: Long): Long = ((sample - base) and 0xffff_ffffL).toInt().toLong()
    }
}

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    private val expectedPeer: InetAddress,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val readCounter = AtomicLong(0)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = if (bindAddress is Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondarySocket ->
            servers += secondarySocket
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener bound=" +
                    "${secondarySocket.localSocketAddress}",
            )
        }.onFailure { error ->
            safeClose(secondary)
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener failed address=" +
                    "$secondaryAddress port=${bound.localPort}: ${error.message}",
            )
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug("AirPlay iAP tunnel accepting local=${bound.localSocketAddress}")
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get() && socket == null) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            if (!isSameOrAuthorizedAirPlayPeer(accepted.inetAddress, expectedPeer)) {
                safeClose(accepted)
                listener.onDebug("AirPlay iAP tunnel rejected sender outside the control session")
                continue
            }
            accepted.tcpNoDelay = true
            accepted.keepAlive = true
            socket = accepted
            servers.toList().forEach(::safeClose)
            servers.clear()
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
            return
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext += buffer.copyOf(read)
                val decrypted = decryptFrames(ciphertext)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                plaintext = parsePackages(plaintext)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
        var scanOffset = 0
        var totalPlainBytes = 0
        while (buffer.size - scanOffset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, scanOffset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - scanOffset < frameLength) break
            totalPlainBytes += length
            scanOffset += frameLength
        }
        if (scanOffset == 0) return ByteArray(0) to buffer
        val plaintext = ByteArray(totalPlainBytes)
        val nonce = ByteArray(12)
        var offset = 0
        var plainOffset = 0
        while (offset < scanOffset) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            AirPlayCrypto.writeNonce64(readCounter.get(), nonce)
            val written = AirPlayCrypto.chachaOpenInto(
                key = readKey,
                nonce = nonce,
                ciphertextAndTag = buffer,
                ciphertextOffset = offset + FRAME_HEADER_LEN,
                ciphertextLength = length + TAG_SIZE,
                aad = buffer,
                aadOffset = offset,
                aadLength = FRAME_HEADER_LEN,
                output = plaintext,
                outputOffset = plainOffset,
            )
            plainOffset += written
            readCounter.incrementAndGet()
            offset += frameLength
        }
        val out = if (plainOffset == plaintext.size) plaintext else plaintext.copyOf(plainOffset)
        return out to buffer.copyOfRange(offset, buffer.size)
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        var offset = 0
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            if (size < PACKAGE_HEADER_LEN || size > MAX_PACKAGE) throw IllegalStateException("bad package size $size")
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                listener.onDebug("AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}")
                listener.onIap(buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size))
            }
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}

/**
 * Full-duplex adapter for the CarPlay iAP2 DataStream.
 *
 * Inbound iAP2 bytes come from [IapTunnel]; outbound bytes are sent over the encrypted AirPlay
 * event channel using the `iAPSendMessage` command.
 */
class AirPlayIapTunnelStream(
    private val session: RtspControlSession,
    private val tunnel: IapTunnel,
    private val sendCommand: (ByteArray, Long) -> Boolean = { data, timeoutMillis ->
        session.sendIapMessage(data, timeoutMillis)
    },
) : DuplexByteStream {
    private val lock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var terminalFailure: Throwable? = null
    private var closeLocation: String? = null

    fun listen(): Int = tunnel.listen(
        object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                session.logTrace("AirPlay iAP tunnel connected remote=${remoteAddress ?: "unknown"}")
            }

            override fun onIap(bytes: ByteArray) = offer(bytes)

            override fun onDebug(message: String) {
                session.logTrace(message)
            }

            override fun onClosed(cause: Throwable?) {
                session.logTrace(
                    "AirPlay iAP tunnel closed cause=" +
                        "${cause?.javaClass?.simpleName ?: "peer EOF"}: " +
                        "${cause?.message ?: "no detail"}",
                )
                val shouldCloseSession = markFailed(cause)
                tunnel.close()
                if (shouldCloseSession && !session.hasActiveMediaStreams()) {
                    session.close()
                }
            }
        },
    )

    override fun send(data: ByteArray) {
        synchronized(lock) {
            throwTerminalFailureLocked()
            if (closed) throw closedFailureLocked()
        }
        if (!tunnel.awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("CarPlay iAP tunnel peer did not connect")
        }
        synchronized(lock) {
            throwTerminalFailureLocked()
            if (closed) throw closedFailureLocked()
        }
        if (!sendCommand(data, EVENT_READY_TIMEOUT_MILLIS)) {
            throw IOException("AirPlay event channel rejected an iAP message")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                throwTerminalFailureLocked()
                if (closed) return EMPTY
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeLocation = closeCaller()
            lock.notifyAll()
        }
        tunnel.close()
    }

    private fun offer(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (closed || terminalFailure != null) return
            if (pendingBytes + bytes.size > MAX_PENDING_BYTES) {
                terminalFailure = IOException("AirPlay iAP tunnel pending-data limit exceeded")
                lock.notifyAll()
                tunnel.close()
                return
            }
            pending.addLast(bytes.copyOf())
            pendingBytes += bytes.size
            lock.notifyAll()
        }
    }

    private fun markFailed(cause: Throwable?): Boolean {
        synchronized(lock) {
            if (closed || terminalFailure != null) return false
            terminalFailure = cause ?: IOException("AirPlay iAP tunnel terminated")
            lock.notifyAll()
            return true
        }
    }

    private fun throwTerminalFailureLocked() {
        val failure = terminalFailure ?: return
        throw IOException(
            "AirPlay iAP tunnel failed: " +
                "${failure.javaClass.simpleName}: ${failure.message ?: "unknown error"}",
            failure,
        )
    }

    private fun closedFailureLocked(): IOException = IOException(
        buildString {
            append("AirPlay iAP tunnel is closed")
            closeLocation?.let { append("; requested by $it") }
        },
    )

    private fun closeCaller(): String =
        Thread.currentThread().stackTrace
            .drop(2)
            .take(8)
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) return chunk

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        return head
    }

    private companion object {
        const val MAX_PENDING_BYTES = 1_048_576
        const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val EVENT_READY_TIMEOUT_MILLIS = 10_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val EMPTY = ByteArray(0)
    }
}
