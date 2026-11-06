package app.tvplay.engine.av

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import app.tvplay.engine.protocol.rtsp.AudioCodecKind
import app.tvplay.engine.protocol.rtsp.MicrophoneConfig
import app.tvplay.engine.protocol.rtsp.MicrophoneCounters
import app.tvplay.engine.protocol.rtsp.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.concentus.OpusApplication
import org.concentus.OpusSignal

/** Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone uplink. */
internal interface MicrophoneOpusEncoder : Closeable {
    /** Short implementation name for diagnostic reports. */
    val implementation: String

    val available: Boolean

    /** Encodes one 20 ms little-endian 16-bit PCM frame and returns the Opus access units it produced. */
    fun encode(pcm: ByteArray): List<ByteArray>
}

internal object MicrophoneOpusEncoders {
    /**
     * Android's MediaCodec Opus encoder exists only from Android 10 (API 29). Wireless CarPlay sends
     * calls and Siri as Opus, so older devices fall back to the bundled software encoder.
     */
    fun create(
        bitrate: Int,
        platform: (Int) -> MicrophoneOpusEncoder = { OpusEncoder(it) },
        software: (Int) -> MicrophoneOpusEncoder = { SoftwareOpusEncoder(it) },
    ): MicrophoneOpusEncoder? {
        val platformEncoder = platform(bitrate)
        if (platformEncoder.available) return platformEncoder
        platformEncoder.close()
        val softwareEncoder = software(bitrate)
        if (softwareEncoder.available) return softwareEncoder
        softwareEncoder.close()
        return null
    }
}

/**
 * Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay
 * microphone uplink, using Android's MediaCodec encoder (Android 10 and later).
 */
internal class OpusEncoder(bitrate: Int) : MicrophoneOpusEncoder {
    override val implementation: String = "mediacodec"

    private val codec: MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            SAMPLE_RATE,
            CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also {
            it.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            it.start()
            Log.i(TAG, "Opus microphone encoder started bitrate=$bitrate")
        }
    } catch (error: Exception) {
        Log.w(TAG, "Opus microphone encoder unavailable", error)
        null
    }
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var closed = false
    private var outputPackets = 0

    override val available: Boolean get() = codec != null && !closed

    /**
     * Queues one 20 ms PCM frame and returns all Opus access units made available by the codec.
     */
    override fun encode(pcm: ByteArray): List<ByteArray> {
        val codec = codec ?: return emptyList()
        if (closed) return emptyList()
        val inputIndex = try {
            codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        } catch (error: Exception) {
            Log.w(TAG, "Opus microphone input dequeue failed", error)
            return emptyList()
        }
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)
            if (input == null || pcm.size > input.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
            } else {
                input.clear()
                input.put(pcm)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    pcm.size,
                    presentationTimeUs,
                    0,
                )
                presentationTimeUs += INPUT_DURATION_US
            }
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val codec = codec ?: return emptyList()
        val output = ArrayList<ByteArray>()
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, 0)
            } catch (error: Exception) {
                Log.w(TAG, "Opus microphone output dequeue failed", error)
                return output
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return output
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(bytes)
                        output.add(bytes)
                        outputPackets++
                        if (outputPackets <= FIRST_PACKET_LOG_COUNT) {
                            Log.i(
                                TAG,
                                "Opus microphone packet=$outputPackets bytes=${bytes.size} " +
                                    "head=${bytes.copyOf(minOf(bytes.size, 16)).toHexString()}",
                            )
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        return output
    }

    override fun close() {
        if (closed) return
        closed = true
        val codec = codec ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
            // Best effort.
        }
        try {
            codec.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_DURATION_US = 20_000L
        const val MAX_INPUT_BYTES = 4_096
        const val FIRST_PACKET_LOG_COUNT = 3
    }
}

/**
 * Opus encoder in pure Java (Concentus, a port of the Opus reference library) for devices whose
 * Android has no MediaCodec Opus encoder. Kept free of Android types so it stays testable on the JVM.
 */
internal class SoftwareOpusEncoder(
    bitrate: Int,
    complexity: Int = DEFAULT_COMPLEXITY,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    create: () -> org.concentus.OpusEncoder = {
        org.concentus.OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
    },
) : MicrophoneOpusEncoder {
    override val implementation: String = "software"

    private var encoder: org.concentus.OpusEncoder? = try {
        create().apply {
            setBitrate(bitrate)
            setComplexity(complexity)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
    } catch (error: Throwable) {
        onError("software Opus encoder unavailable", error)
        null
    }
    private val output = ByteArray(MAX_PACKET_BYTES)
    private var failures = 0

    override val available: Boolean get() = encoder != null

    override fun encode(pcm: ByteArray): List<ByteArray> {
        val encoder = encoder ?: return emptyList()
        if (pcm.size != FRAME_BYTES) return emptyList()
        return try {
            val length = encoder.encode(pcm, 0, FRAME_SAMPLES, output, 0, output.size)
            if (length > 0) listOf(output.copyOf(length)) else emptyList()
        } catch (error: Exception) {
            failed(encoder, error)
        } catch (error: AssertionError) {
            // Concentus checks the reference library's debug assertions in every build and throws an Error,
            // which would otherwise end the microphone thread (#865). A failed check costs this frame only.
            failed(encoder, error)
        }
    }

    private fun failed(encoder: org.concentus.OpusEncoder, error: Throwable): List<ByteArray> {
        if (failures++ < MAX_REPORTED_FAILURES) onError("software Opus encode failed", error)
        runCatching { encoder.resetState() }
        return emptyList()
    }

    override fun close() {
        encoder = null
    }

    internal companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2

        /** Speech matched complexity 10 in tests at about three quarters of its CPU time. */
        const val DEFAULT_COMPLEXITY = 5
        private const val MAX_PACKET_BYTES = 1_275
        private const val MAX_REPORTED_FAILURES = 3
    }
}

/**
 * Whether this device can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: the bundled software encoder also works on
 * devices without a platform Opus encoder. Probe it before advertising the microphone formats,
 * without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean = isAvailable(
        platformAvailable = ::platformAvailable,
        software = { SoftwareOpusEncoder(bitrate = 48_000) },
    )

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        if (runCatching(platformAvailable).getOrDefault(false)) return true
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
            }
        }
}

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val type = typeAndSource(config.audioType).first
    private val format = format(config)
    private var metadata = metadata(config)
    private var windowStart = nowNs()
    private var readStart = windowStart
    private var capturedBytes = 0L
    private var reads = 0L
    private var zeroReads = 0L
    private var readErrors = 0L
    private var readMaxNs = 0L
    private var encodedFrames = 0L
    private var emptyEncodedFrames = 0L
    private var udpSent = 0L
    private var sendErrors = 0L
    private var lastSentNs: Long? = null
    private var sendGapMaxNs = 0L
    private var peakSample = 0
    private var routedDeviceType: Int? = null

    fun started(routeType: Int?) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}")
    }

    fun useSource(source: Int) { metadata = "type=$type source=${sourceName(source)} $format" }

    fun reading() { readStart = nowNs() }

    fun read(count: Int) {
        reads++
        readMaxNs = maxOf(readMaxNs, (nowNs() - readStart).coerceAtLeast(0))
        when {
            count > 0 -> capturedBytes += count
            count == 0 -> zeroReads++
            else -> readErrors++
        }
    }

    /**
     * The loudest 16-bit little-endian sample sent in this window. Without it, a microphone that
     * delivers silence and an uplink the iPhone discards looked the same in a report (#647).
     */
    fun level(pcm: ByteArray) {
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            peakSample = maxOf(peakSample, kotlin.math.abs(sample))
            index += 2
        }
    }

    fun encoded(frameCount: Int, emptyCount: Int) {
        encodedFrames += frameCount.coerceAtLeast(0)
        emptyEncodedFrames += emptyCount.coerceAtLeast(0)
    }

    fun sent() {
        val now = nowNs()
        lastSentNs?.let { sendGapMaxNs = maxOf(sendGapMaxNs, (now - it).coerceAtLeast(0)) }
        lastSentNs = now
        udpSent++
    }

    fun sendFailed() { sendErrors++ }

    fun restarted(attempt: Int, reason: String, routeType: Int?) {
        emit(
            "Microphone: recording restarted $metadata attempt=$attempt reason=$reason " +
                "routedDeviceType=${routeType ?: "unknown"}",
        )
    }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null, silence: (() -> String)? = null) {
        val now = nowNs()
        if (!ended && now - windowStart < REPORT_INTERVAL_NS) return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        val silenceDetail = if (peakSample <= 0 && silence != null) runCatching { " " + silence() }.getOrDefault("") else ""
        emit(
            "Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
                "captureBytes=$capturedBytes reads=$reads zeroReads=$zeroReads readErrors=$readErrors " +
                "readMaxMs=${readMaxNs / 1_000_000} encodedFrames=$encodedFrames " +
                "emptyEncodedFrames=$emptyEncodedFrames udpSent=$udpSent sendErrors=$sendErrors " +
                "sendGapMaxMs=${sendGapMaxNs / 1_000_000} peakDbfs=${peakDbfs(peakSample)}$silenceDetail ended=$ended",
        )
        windowStart = now
        capturedBytes = 0
        reads = 0
        zeroReads = 0
        readErrors = 0
        readMaxNs = 0
        encodedFrames = 0
        emptyEncodedFrames = 0
        udpSent = 0
        sendErrors = 0
        sendGapMaxNs = 0
        peakSample = 0
    }

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        private const val REPORT_INTERVAL_NS = 5_000_000_000L

        /** Whole dBFS of a 16-bit peak; "silent" when every sample was zero. */
        internal fun peakDbfs(peak: Int): String =
            if (peak <= 0) "silent" else Math.round(20 * kotlin.math.log10(peak / 32768.0)).toString()

        private val SOURCE_NAMES = mapOf(
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_UPLINK to "VOICE_UPLINK",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK",
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.REMOTE_SUBMIX to "REMOTE_SUBMIX",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            1999 to "HOTWORD",
        )

        internal fun sourceName(source: Int): String = SOURCE_NAMES[source] ?: source.toString()

        private fun typeAndSource(audioType: String): Pair<String, String> = when (audioType) {
            "telephony" -> "telephony" to "VOICE_COMMUNICATION"
            "speechrecognition" -> "speechrecognition" to "VOICE_RECOGNITION"
            else -> "other" to "MIC"
        }

        private fun format(config: MicrophoneConfig): String = "codec=${config.codec.name} " +
            "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"

        private fun metadata(config: MicrophoneConfig): String {
            val (type, source) = typeAndSource(config.audioType)
            return "type=$type source=$source ${format(config)}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}

internal typealias MicrophoneUplink = VoiceMicRecorder

/**
 * Captures one PCM microphone stream via Android [AudioRecord] and sends it back to the iPhone as
 * sealed CarPlay RTP (LPCM or Opus).
 */
internal class VoiceMicRecorder(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    private val opusEncoderFactory: (Int) -> MicrophoneOpusEncoder? = { bitrate ->
        MicrophoneOpusEncoders.create(
            bitrate = bitrate,
            software = { value ->
                SoftwareOpusEncoder(value, onError = { message, error -> Log.w(TAG, message, error) })
            },
        )
    },
    private val noAudioRestartMillis: Long = NO_AUDIO_RESTART_MILLIS,
    private val audioManager: AudioManager? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: MicrophoneOpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        stats.useSource(source)
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoderFactory(config.bitrate ?: 48_000)
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        if (nextEncoder != null) {
            val message = "Microphone: encoder type=${config.audioType} codec=OPUS " +
                "implementation=${nextEncoder.implementation}"
            Log.i(TAG, message)
            runCatching { onDiagnostic(message) }
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // Some devices throw on VOICE_RECOGNITION at build(); VOICE_COMMUNICATION works there.
        val nextRecorder = createRecorder(source, channelMask, bufferSize)
            ?: if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                stats.useSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                createRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, channelMask, bufferSize)
            } else {
                null
            }
        if (nextRecorder == null) {
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                runCatching { bind(InetSocketAddress(InetAddress.getByName("::"), 0)) }
                    .recoverCatching { bind(InetSocketAddress(0)) }
                    .getOrThrow()
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") {
                effects = voiceEffects(nextRecorder.audioSessionId)
            }
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=$source", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize source=$source")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = recorder.state)
            recorder.release()
            return null
        }
        return recorder
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> {
        val aec = enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        }
        val ns = enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        }
        return listOfNotNull(aec, ns)
    }

    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val datagram = DatagramPacket(ByteArray(0), 0, config.host, config.port)
        val routeInfo = { routeType(recorder) }
        val ownSession = recorder.audioSessionId
        val silence = audioManager?.let { manager -> { silenceDetail(manager, ownSession) } }
        var filled = 0
        var readRestarts = 0
        var noAudioRestarts = 0
        var lastAudioNs = System.nanoTime()
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    if (running.get() && count != AudioRecord.ERROR_DEAD_OBJECT &&
                        readRestarts < MAX_READ_RESTARTS && restartRecording(recorder)
                    ) {
                        readRestarts++
                        stats.restarted(readRestarts, "read-error", routeType(recorder))
                        continue
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo, silence = silence)
                    val now = System.nanoTime()
                    if (running.get() && noAudioRestarts < MAX_NO_AUDIO_RESTARTS &&
                        now - lastAudioNs >= noAudioRestartMillis * 1_000_000L
                    ) {
                        noAudioRestarts++
                        lastAudioNs = now
                        if (restartRecording(recorder)) {
                            stats.restarted(noAudioRestarts, "no-audio", routeType(recorder))
                        } else {
                            stats.failure(MicrophoneFailureStage.RECORDING, code = recorder.recordingState)
                        }
                    } else {
                        Thread.sleep(EMPTY_READ_PAUSE_MILLIS)
                    }
                    continue
                }
                lastAudioNs = System.nanoTime()
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        stats.level(frame)
                        sendFrame(socket, datagram, counters, frame)
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo, silence = silence)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo, silence = silence)
            running.set(false)
            release()
        }
    }

    private fun restartRecording(recorder: AudioRecord): Boolean = try {
        runCatching { recorder.stop() }
        Thread.sleep(READ_RESTART_PAUSE_MILLIS)
        recorder.startRecording()
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (_: IllegalStateException) {
        false
    }

    private fun sendFrame(
        socket: DatagramSocket,
        datagram: DatagramPacket,
        counters: MicrophoneCounters,
        frame: ByteArray,
    ) {
        if (config.codec == AudioCodecKind.OPUS) {
            val bodies = opusEncoder?.encode(frame).orEmpty()
            stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
            for (body in bodies) {
                sendPacket(
                    socket = socket,
                    datagram = datagram,
                    counters = counters,
                    body = body,
                    samples = config.rtpSamplesPerPacket,
                )
            }
        } else {
            MicrophonePacketizer.toWirePcmInPlace(frame)
            stats.encoded(1, if (frame.isEmpty()) 1 else 0)
            sendPacket(
                socket = socket,
                datagram = datagram,
                counters = counters,
                body = frame,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        datagram: DatagramPacket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            datagram.setData(packet, 0, packet.size)
            socket.send(datagram)
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    private fun silenceDetail(manager: AudioManager, ownSession: Int): String {
        val others = manager.activeRecordingConfigurations
            .filter { it.clientAudioSessionId != ownSession }
            .map { MicrophoneCaptureStats.sourceName(it.clientAudioSource) }
        return "audioMode=${manager.mode} otherRecordings=" +
            if (others.isEmpty()) "none" else others.take(MAX_LISTED_RECORDINGS).joinToString(",", "[", "]")
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val MAX_READ_RESTARTS = 3
        const val READ_RESTART_PAUSE_MILLIS = 50L
        const val NO_AUDIO_RESTART_MILLIS = 1_000L
        const val MAX_NO_AUDIO_RESTARTS = 3
        const val EMPTY_READ_PAUSE_MILLIS = 5L
        const val MAX_LISTED_RECORDINGS = 4
    }
}
