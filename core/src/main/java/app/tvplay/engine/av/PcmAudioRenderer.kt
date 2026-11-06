package app.tvplay.engine.av

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.tvplay.engine.auth.toHexString
import app.tvplay.engine.protocol.iap2.Iap2BodyReader
import app.tvplay.engine.protocol.iap2.Iap2Frame
import app.tvplay.engine.protocol.rtsp.AudioCodecKind
import app.tvplay.engine.protocol.rtsp.AudioFormat
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Logical TV audio output channels corresponding to Android [AudioAttributes] usages.
 */
enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
}

/**
 * Content classification for an audio stream.
 */
enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal fun channelForAudioType(audioType: String): AudioChannel = when (audioType) {
    "telephony" -> AudioChannel.PHONE
    "speechrecognition" -> AudioChannel.ASSISTANT
    "alert", "AltAudio", "navigation" -> AudioChannel.NAVIGATION
    else -> AudioChannel.MEDIA
}

internal fun contentTypeForAudioType(audioType: String): AudioContentType = when (audioType) {
    "media", "default" -> AudioContentType.MUSIC
    else -> AudioContentType.SPEECH
}

internal fun usageForAudioChannel(channel: AudioChannel): Int = when (channel) {
    AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
    AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    AudioChannel.ASSISTANT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioAttributes.USAGE_ASSISTANT
    } else {
        AudioAttributes.USAGE_MEDIA
    }
    AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
}

internal fun contentTypeForAudioContent(contentType: AudioContentType): Int = when (contentType) {
    AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
    AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
}

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/**
 * One audio focus request that works on every supported Android version.
 * API 26+ uses [AudioFocusRequest]. Android 7.x uses the stream-based calls, whose callbacks are
 * moved to [handler] so that both paths deliver them on the same thread.
 */
class AudioFocusRequestCompat(
    val gain: Int,
    val attributes: AudioAttributes,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    private val handler: Handler,
) {
    private val platformRequest: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
    } else {
        null
    }

    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (Looper.myLooper() == handler.looper) listener.onAudioFocusChange(change)
        else handler.post { listener.onAudioFocusChange(change) }
    }

    fun request(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestAudioFocus(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(legacyListener, legacyStreamType(attributes.usage), gain)
        }

    fun abandon(manager: AudioManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(platformRequest as AudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(legacyListener)
        }

    internal companion object {
        /** The stream Android 7.x derives from these usages for its own focus bookkeeping. */
        fun legacyStreamType(usage: Int): Int = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
            AudioAttributes.USAGE_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
    }
}

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(
        val channel: AudioChannel,
        val attributes: AudioAttributes,
        var appliedVolume: Float = FULL_VOLUME,
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    private var focusVolume = FULL_VOLUME
    private var carPlayDucked = false
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    @Volatile private var focusChangeListener: ((Int) -> Unit)? = null
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    fun setFocusChangeListener(listener: ((Int) -> Unit)?) {
        focusChangeListener = listener
    }

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                runCatching {
                    report(
                        "Audio: focus change=$change dropped" +
                            " stale=${generation != focusGeneration} noRequest=${request == null}" +
                            " activeTracks=${active.size}",
                    )
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
            }
        }
        focusChangeListener?.invoke(change)
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        carPlayDucked = false
        active.clear()
        refreshRequest()
    }

    @Synchronized
    fun onAudioDuck(ducked: Boolean) {
        if (closed) return
        carPlayDucked = ducked
        applyEffectiveMediaVolume()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            focusVolume = FULL_VOLUME
            mediaVolume = if (carPlayDucked) DUCKED_VOLUME else FULL_VOLUME
            manager?.let { abandoned?.abandon(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        focusVolume = volume
        applyEffectiveMediaVolume()
    }

    private fun applyEffectiveMediaVolume() {
        mediaVolume = if (carPlayDucked) minOf(focusVolume, DUCKED_VOLUME) else focusVolume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        @Suppress("DEPRECATION")
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Keeps the TV's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the TV plays it at its own. After each write the
 * renderer reports how many frames are queued; a slow average of that level over time, as the
 * track drains it between writes, drives a PI controller that, once a second, trims the track's
 * playback rate so the level stays at its target.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private var targetFrames: Long,
    private var integral: Double = learnedTrim,
    private val measuresTarget: Boolean = false,
) {
    private class Measuring(val halfStartNs: Long? = null, val earlierHalfMean: Double? = null) {
        var levelFrameNs = 0.0
    }

    private var level: Double? = null
    /** When the level was last sampled. */
    var lastSampleNs = 0L
        private set
    private var lastBuffered = 0L
    private var lastUpdateNs: Long? = null
    private var compressedBacklog = false
    private var measuring: Measuring? = if (measuresTarget) Measuring() else null

    fun measureAgain() {
        if (measuresTarget) measuring = Measuring()
    }

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /**
     * Include compressed packets waiting for decode: those frames are already queued audio. [arrivedFrameNs] sums,
     * over each frame that arrived since [lastSampleNs], how long it has been queued.
     */
    fun sample(
        queuedFrames: Long,
        nowNs: Long,
        compressedFrames: Long = 0L,
        headMoving: Boolean = true,
        arrivedFrameNs: Double = 0.0,
    ) {
        if (targetFrames <= 0L) return
        compressedBacklog = compressedFrames > targetFrames
        val buffered = queuedFrames.coerceAtLeast(0L) +
            compressedFrames.coerceIn(0L, sampleRate.toLong() * 16L)
        val elapsedNs = nowNs - lastSampleNs
        val meanSinceLastWrite = drainedMean(lastBuffered.toDouble(), rate * elapsedNs / 1e9) +
            if (elapsedNs > 0) arrivedFrameNs / elapsedNs else 0.0
        val measure = measuring
        if (measure != null) {
            if (!headMoving) return
            addToMeasurement(measure, nowNs, elapsedNs, meanSinceLastWrite)
        } else {
            val previous = level
            level = if (previous == null || elapsedNs > STALE_NS) error(buffered.toDouble())
            else previous + (error(meanSinceLastWrite) - previous) * (1.0 - exp(-elapsedNs.toDouble() / AVERAGE_NS))
        }
        lastBuffered = buffered
        lastSampleNs = nowNs
    }

    private fun addToMeasurement(measure: Measuring, nowNs: Long, elapsedNs: Long, meanSinceLastWrite: Double) {
        val halfStartNs = measure.halfStartNs
        if (halfStartNs == null) {
            measuring = Measuring(nowNs)
            return
        }
        measure.levelFrameNs += meanSinceLastWrite * elapsedNs
        if (nowNs - halfStartNs < MEASURE_HALF_NS) return
        val halfMean = measure.levelFrameNs / (nowNs - halfStartNs)
        val earlierHalfMean = measure.earlierHalfMean
        if (earlierHalfMean == null || abs(halfMean - earlierHalfMean) > sampleRate * HALVES_AGREE_SECONDS) {
            measuring = Measuring(nowNs, halfMean)
            return
        }
        targetFrames = earlierHalfMean.roundToLong()
        level = 0.0
        measuring = null
    }

    private fun error(frames: Double): Double = (frames - targetFrames) / targetFrames

    /** The mean of a level that starts at [start] and drains [drained] frames at a steady pace, stopping at 0. */
    private fun drainedMean(start: Double, drained: Double): Double = when {
        drained <= 0.0 -> start
        drained <= start -> start - drained / 2
        else -> start * start / (2 * drained)
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        if (measuring != null) {
            val learned = (sampleRate * (1.0 + integral)).roundToInt()
            if (learned == rate) return null
            rate = learned
            return learned
        }
        val error = level ?: return null
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        if (!compressedBacklog) {
            integral = (integral + KI * error * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
            learnedTrim = integral
        }
        val next = (sampleRate * (1.0 + (KP * error + integral).coerceIn(-MAX_TRIM, MAX_TRIM))).roundToInt()
        if (next == rate) return null
        rate = next
        return next
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        /** Longer than the 0.4 s between the iPhone's bursts over Wi-Fi: a longer silence is a pause or a stall. */
        const val STALE_NS = 1_000_000_000L
        const val MAX_STEP_SECONDS = 2.0
        const val MEASURE_HALF_NS = 1_500_000_000L
        const val HALVES_AGREE_SECONDS = 0.2

        /** The drift the last music stream settled on. */
        @Volatile var learnedTrim = 0.0
    }
}

/**
 * Detects a *sustained* compressed-media backlog using RTP timestamps, independently of
 * packet-count or codec frame size. A short Wi-Fi catch-up burst must not be discarded.
 */
internal class MediaPacketLagGuard(private val clockRate: Int) {
    private var congestionSinceNs = 0L
    private var recovering = false

    /** Time between first undelivered RTP packet and the latest packet, respecting 32-bit wrap. */
    fun <T> pendingFrames(queue: LinkedBlockingQueue<T>, newestSample: Int, timestampOf: (T) -> Int): Long {
        if (clockRate <= 0) return 0
        val first = queue.peek() ?: return 0
        val frames = (newestSample.toLong() - timestampOf(first).toLong()) and 0xffff_ffffL
        return if (frames <= clockRate * MAX_VALID_SPAN_SECONDS.toLong()) frames else 0
    }

    fun <T> recover(
        queue: LinkedBlockingQueue<T>,
        newestSample: Int,
        timestampOf: (T) -> Int,
        nowNs: Long,
    ): Int {
        val durationFrames = pendingFrames(queue, newestSample, timestampOf)
        val delayNs = durationFrames * 1_000_000_000L / clockRate.coerceAtLeast(1)
        if (!recovering) {
            if (delayNs <= CONGESTION_THRESHOLD_NS) {
                congestionSinceNs = 0
                return 0
            }
            if (congestionSinceNs == 0L) {
                congestionSinceNs = nowNs
                return 0
            }
            if (nowNs - congestionSinceNs < CONGESTION_GRACE_NS) return 0
            recovering = true
        }
        if (delayNs <= RECOVERY_END_NS) {
            recovering = false
            congestionSinceNs = 0
            return 0
        }
        var removed = 0
        while (queue.isNotEmpty() &&
            pendingFrames(queue, newestSample, timestampOf) * 1_000_000_000L /
            clockRate.coerceAtLeast(1) > RECOVERY_TARGET_NS) {
            if (queue.poll() == null) break
            removed++
        }
        return removed
    }

    internal companion object {
        const val MAX_VALID_SPAN_SECONDS = 16
        const val CONGESTION_THRESHOLD_NS = 1_500_000_000L
        const val CONGESTION_GRACE_NS = 1_500_000_000L
        const val RECOVERY_TARGET_NS = 500_000_000L
        const val RECOVERY_END_NS = 200_000_000L
    }
}

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the
 * same Wi-Fi link as video; radio gaps of several hundred milliseconds are normal, so music needs a
 * buffer that outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    const val MOST_STABLE_MILLIS = 1000
    val wirelessPresets = listOf(DEFAULT_MILLIS, 500, MOST_STABLE_MILLIS)
    val wiredPresets = listOf(50, 100, 150, 200) + (DEFAULT_MILLIS..MOST_STABLE_MILLIS step 100)
    /** Every delay a session can use; [plan] accepts any of them. */
    val presets = (wiredPresets + wirelessPresets).distinct().sorted()

    private const val HEADROOM_MILLIS = 200
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS
    fun sanitizeWireless(millis: Int): Int = millis.takeIf { it in wirelessPresets } ?: DEFAULT_MILLIS
    fun sanitizeWired(millis: Int): Int = millis.takeIf { it in wiredPresets } ?: DEFAULT_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) return lowLatency
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * sanitize(mediaMillis) / 1000).toInt()
        val capacity = (bytesPerSecond * (sanitize(mediaMillis) + HEADROOM_MILLIS) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(
        isMedia: Boolean,
        playing: Boolean,
        underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean,
        rawHead: Int,
        floorBytes: Long,
    ): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
            queuedBytes(rawHead) <= floorBytes
}

/** The retained subset of the iPhone's incremental iAP2 NowPlayingUpdate state. */
data class CarPlayNowPlaying(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val artworkTransferId: Int? = null,
    val sourceApp: String? = null,
    val durationMillis: Long? = null,
    val elapsedMillis: Long? = null,
    val playing: Boolean = false,
)

/**
 * Retains NowPlayingUpdate (0x5001) fields. Updates carry only what changed, so an omitted field
 * preserves its previous value while a present empty string clears it.
 */
class CarPlayPlaybackStatus {
    @Volatile var nowPlaying = CarPlayNowPlaying()
        private set

    val playing: Boolean get() = nowPlaying.playing

    /** The complete new retained state when this frame changed it, otherwise null. */
    fun acceptUpdate(frame: Iap2Frame): CarPlayNowPlaying? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        val media = runCatching { body.optionalGroup(MEDIA_ITEM) }.getOrNull()
        val playback = runCatching { body.optionalGroup(PLAYBACK) }.getOrNull()
        val previous = nowPlaying
        val next = previous.copy(
            title = media.updatedString(TITLE, previous.title),
            album = media.updatedString(ALBUM, previous.album),
            artist = media.updatedString(ARTIST, previous.artist),
            artworkTransferId = media.updatedU8(ARTWORK_TRANSFER_ID, previous.artworkTransferId),
            sourceApp = playback.updatedString(SOURCE_APP, previous.sourceApp),
            durationMillis = media.updatedU32(DURATION, previous.durationMillis),
            elapsedMillis = playback.updatedU32(ELAPSED, previous.elapsedMillis),
            playing = playback.updatedStatus(previous.playing),
        )
        if (next == previous) return null
        nowPlaying = next
        return next
    }

    /** The new state when [frame] changed it, otherwise null. */
    fun accept(frame: Iap2Frame): Boolean? {
        val previous = playing
        val next = acceptUpdate(frame)?.playing ?: return null
        return next.takeIf { it != previous }
    }

    /** The session ended: nothing plays any more. */
    fun clear(): Boolean? {
        if (!playing) return null
        nowPlaying = nowPlaying.copy(playing = false)
        return false
    }

    /** Clears all retained metadata at the end of a CarPlay session. */
    fun clearAll(): CarPlayNowPlaying? {
        if (nowPlaying == CarPlayNowPlaying()) return null
        nowPlaying = CarPlayNowPlaying()
        return nowPlaying
    }

    private fun Iap2BodyReader?.updatedString(id: Int, previous: String?): String? {
        if (this == null || !has(id)) return previous
        return runCatching { string(id).trim().ifEmpty { null } }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU32(id: Int, previous: Long?): Long? {
        if (this == null || !has(id)) return previous
        return runCatching { u32(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedU8(id: Int, previous: Int?): Int? {
        if (this == null || !has(id)) return previous
        return runCatching { u8(id) }.getOrElse { previous }
    }

    private fun Iap2BodyReader?.updatedStatus(previous: Boolean): Boolean {
        if (this == null || !has(STATUS)) return previous
        return runCatching { u8(STATUS) == STATUS_PLAYING }.getOrElse { previous }
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_ITEM = 0
        private const val PLAYBACK = 1

        private const val TITLE = 1
        private const val DURATION = 4
        private const val ALBUM = 6
        private const val ARTIST = 12
        private const val ARTWORK_TRANSFER_ID = 26

        private const val STATUS = 0
        private const val ELAPSED = 1
        private const val SOURCE_APP = 7
        private const val STATUS_PLAYING = 1
    }
}

internal typealias AudioRenderer = PcmAudioRenderer

/**
 * Decodes CarPlay RTP audio packets (LPCM, AAC-LC, OPUS) and renders 16-bit PCM to an Android [AudioTrack].
 */
internal class PcmAudioRenderer(
    val format: AudioFormat,
    private val audioFocusEnabled: Boolean,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val outputDisabled: () -> Boolean = { false },
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int, val arrivedNs: Long)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    /** Music with sustained decoder backpressure must not keep playing minutes-old packets. */
    private val mediaLagGuard = if (format.audioType == "media") MediaPacketLagGuard(format.rtpClockRate) else null
    @Volatile private var newestRtpSample = 0
    private var lagRecoveryReports = 0
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val packetsDroppedTotal = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var headAtPlaybackStart = 0
    private var droppedAtLastTrim = 0
    private var clockTrim: MediaClockTrim? = null
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var rebufferPauseReportCount = 0
    private var rebufferRecoverReportCount = 0
    private var queueFullReportCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        val now = System.nanoTime()
        if (started) {
            packetsReceived.incrementAndGet()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
            newestRtpSample = sample
            val removed = mediaLagGuard?.recover(queue, sample, AudioPacket::sample, now) ?: 0
            if (removed > 0) {
                packetsDropped.addAndGet(removed)
                packetsDroppedTotal.addAndGet(removed)
                if (lagRecoveryReports < MAX_REBUFFER_REPORTS) {
                    lagRecoveryReports++
                    report(
                        "Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                            "packetsDiscarded=$removed remaining=${queue.size}",
                    )
                }
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample, now))) {
            if (started) {
                packetsDropped.incrementAndGet()
                packetsDroppedTotal.incrementAndGet()
            }
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                if (queueFullReportCount < MAX_QUEUE_FULL_REPORTS) {
                    queueFullReportCount++
                    report("Audio: queue full audioType=${format.audioType} count=$queueFullReportCount")
                }
            }
        }
    }

    fun onOutputDisabledChanged(disabled: Boolean) {
        if (disabled) {
            abandonAudioFocus()
        } else {
            requestAudioFocus()
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        try {
            runCatching {
                report(
                    "Audio: starting api=${Build.VERSION.SDK_INT} " +
                        "audioType=${format.audioType} codec=${format.codec} " +
                        "rate=${format.sampleRate} channels=${format.channels} focus=$audioFocusEnabled",
                )
            }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching {
                report(
                    "Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                        "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                        "channels=${format.channels} minBufferResult=$minBuffer",
                )
            }
            return
        }
        val channel = channelForAudioType(format.audioType)
        val contentType = contentTypeForAudioType(format.audioType)
        mappedChannel = channel
        val attributes = AudioAttributes.Builder()
            .setUsage(usageForAudioChannel(channel))
            .setContentType(contentTypeForAudioContent(contentType))
            .build()
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(
            isMedia = channel == AudioChannel.MEDIA,
            sampleRate = format.sampleRate,
            channels = format.channels,
            minBufferBytes = minBuffer,
            mediaMillis = mediaBufferMillis,
        )
        bytesPerSecond = format.sampleRate * frameBytes
        diagnosticStage = "track-build"
        val built = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(pcmFormat(encoding, channelMask))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()
        track = built
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        clockTrim = if (mappedChannel == AudioChannel.MEDIA) {
            MediaClockTrim(format.sampleRate, (startThresholdBytes / frameBytes).toLong(), measuresTarget = true)
        } else {
            null
        }
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report(
            "Audio: ready audioType=${format.audioType} codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=usage " +
                "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
                trackMetadata,
        )
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
    }

    private fun requestAudioFocus() {
        if (outputDisabled()) return
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private val outputBufferInfo = MediaCodec.BufferInfo()

    private fun handle(packet: AudioPacket) {
        if (queue.isEmpty()) {
            droppedPacketsLogged = false
        }
        val rtp = packet.rtp
        val payloadLength = rtp.size - 12
        if (payloadLength <= 0) return
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> {
                byteSwapS16(rtp, 12, payloadLength)
                writePcm(rtp, 12, payloadLength)
            }
            AudioCodecKind.AAC_LC -> {
                if (!firstAacPayloadLogged) {
                    firstAacPayloadLogged = true
                    Log.i(TAG, "audio AAC access unit bytes=$payloadLength")
                }
                val adts = MediaCodecSupport.adtsFrame(rtp, 12, payloadLength, format.sampleRate, format.channels)
                feedCodec(adts, 0, adts.size, timestampUs)
            }
            AudioCodecKind.OPUS -> {
                if (payloadLength < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(TAG, "audio Opus skipping short packet bytes=$payloadLength")
                    }
                    return
                }
                feedCodec(rtp, 12, payloadLength, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, offset: Int, length: Int, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        val index = DecoderInputPump.acquire(
            running = { running },
            drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            timeoutNs = AUDIO_INPUT_WAIT_NS,
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (length <= input.remaining()) {
            input.put(payload, offset, length)
            codec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(TAG, "audio decoder first input codec=${format.codec} bytes=$length")
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = outputBufferInfo
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    var copied = false
                    try {
                        if (size > 0) {
                            val output = codec.getOutputBuffer(index)
                            if (output != null) {
                                if (size > pcm.size) pcm = ByteArray(size)
                                output.position(info.offset)
                                output.limit(info.offset + size)
                                output.get(pcm, 0, size)
                                copied = true
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (copied) writePcm(pcm, 0, size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (outputDisabled()) {
            if (playbackStarted) {
                runCatching { track.pause() }
                playbackStarted = false
            }
            return
        }
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(TAG, "audio first PCM type=${format.payloadType} bytes=$length")
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes
            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (playbackStarted) trimClock(track)
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        headAtPlaybackStart = track.playbackHeadPosition
        clockTrim?.measureAgain()
        track.play()
        playbackStarted = true
        if (rebufferCount > 0 && rebufferRecoverReportCount < MAX_REBUFFER_REPORTS) {
            rebufferRecoverReportCount++
            report("Audio: rebuffer recovered audioType=${format.audioType} rebufferCount=$rebufferCount")
        }
    }

    private fun trimClock(track: AudioTrack) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val compressedRtpFrames = mediaLagGuard?.pendingFrames(queue, newestRtpSample, AudioPacket::sample)
            ?: 0L
        val compressedPcmFrames = compressedRtpFrames * format.sampleRate /
            format.rtpClockRate.coerceAtLeast(1)
        val dropped = packetsDroppedTotal.get() + inputDropped
        if (dropped != droppedAtLastTrim) {
            droppedAtLastTrim = dropped
            trim.measureAgain()
        }
        val head = track.playbackHeadPosition
        trim.sample(
            bufferProgress.queuedBytes(head) / frameBytes,
            now,
            compressedPcmFrames,
            headMoving = head != headAtPlaybackStart,
            arrivedFrameNs = queuedFrameNsSince(trim.lastSampleNs, now),
        )
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    /** For the packets queued since [sinceNs], the frames each adds to the queue times how long it has waited. */
    private fun queuedFrameNsSince(sinceNs: Long, nowNs: Long): Double {
        if (queue.size <= 1) return 0.0
        val clockRate = format.rtpClockRate.coerceAtLeast(1)
        var frameNs = 0.0
        var previousSample: Int? = null
        for (packet in queue) {
            val previous = previousSample
            previousSample = packet.sample
            if (previous == null || packet.arrivedNs <= sinceNs) continue
            val rtpFrames = (packet.sample.toLong() - previous) and 0xffff_ffffL
            if (rtpFrames > clockRate) continue
            frameNs += rtpFrames.toDouble() * format.sampleRate / clockRate * (nowNs - packet.arrivedNs)
        }
        return frameNs
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(
                mappedChannel == AudioChannel.MEDIA,
                playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart,
                queue.isEmpty(),
                track.playbackHeadPosition,
                startThresholdBytes / 2L,
            )
        ) {
            track.pause()
            playbackStarted = false
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
            if (rebufferPauseReportCount < MAX_REBUFFER_REPORTS) {
                rebufferPauseReportCount++
                val retainedMs = if (bytesPerSecond > 0) prebufferBytes * 1000L / bytesPerSecond else 0L
                report("Audio: rebuffer paused audioType=${format.audioType} rebufferCount=$rebufferCount retainedMs=$retainedMs")
            }
        }
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val channelName = if (mappedChannel == AudioChannel.PHONE) "CALL" else mappedChannel?.name
        val line = "audio stats audioType=${format.audioType} channel=$channelName " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "playbackRate=${clockTrim?.rate ?: -1} ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching {
            report(
                "Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
                    "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
                    MediaFailureSummary.describe(error),
            )
        }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray, offset: Int = 0, length: Int = source.size - offset): ByteArray {
        val end = offset + length - 1
        var index = offset
        while (index < end) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
            index += 2
        }
        return source
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
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
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_INPUT_WAIT_NS = 100_000_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val MAX_REBUFFER_REPORTS = 5
        const val MAX_QUEUE_FULL_REPORTS = 5
    }
}
