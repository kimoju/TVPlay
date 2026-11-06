package app.tvplay.engine.av

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import app.tvplay.engine.protocol.rtsp.VideoCodec
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** CarPlay's main screen stream type. */
internal const val MAIN_SCREEN_TYPE = 110

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set

    fun reset() {
        needsKeyFrame = true
    }

    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)

    fun onQueued() {
        needsKeyFrame = false
    }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val lock = Object()
    private val jobs = ArrayDeque<VideoJob>()
    private var queuedFrames = 0
    private var queuedFrameBytes = 0L

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    fun backlogAfterCurrent(): Backlog = synchronized(lock) {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frames++
            newest = newest?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        Backlog(frames, newest)
    }

    fun offer(job: VideoJob) = synchronized(lock) {
        if (job is VideoJob.Frame) {
            if (queuedFrames >= maxFrames || queuedFrameBytes + job.nalus.size > maxBytes) {
                discardFramesLocked()
                jobs.addLast(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) {
                lock.notifyAll()
                return@synchronized
            }
            queuedFrames++
            queuedFrameBytes += job.nalus.size.toLong()
        }
        jobs.addLast(job)
        lock.notifyAll()
    }

    fun offerFirst(job: VideoJob) = synchronized(lock) {
        if (job is VideoJob.Frame) {
            if (job.nalus.size > maxBytes) return@synchronized
            queuedFrames++
            queuedFrameBytes += job.nalus.size.toLong()
        }
        jobs.addFirst(job)
        lock.notifyAll()
    }

    fun requestResync() = synchronized(lock) {
        discardFramesLocked()
        jobs.addLast(VideoJob.Resync)
        lock.notifyAll()
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    fun discardCurrentChain() = synchronized(lock) {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val next = iterator.next()) {
                is VideoJob.Frame -> {
                    iterator.remove()
                    queuedFrames--
                    queuedFrameBytes -= next.nalus.size.toLong()
                }
                VideoJob.Resync -> iterator.remove()
                else -> return@synchronized
            }
        }
    }

    fun discardFrames() = synchronized(lock) {
        discardFramesLocked()
    }

    private fun discardFramesLocked() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val next = iterator.next()) {
                is VideoJob.Frame -> {
                    iterator.remove()
                    queuedFrames--
                    queuedFrameBytes -= next.nalus.size.toLong()
                }
                VideoJob.Resync -> iterator.remove()
                else -> Unit
            }
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? = synchronized(lock) {
        if (jobs.isEmpty() && timeoutMillis > 0L) {
            val deadlineNs = System.nanoTime() + timeoutMillis * 1_000_000L
            while (jobs.isEmpty()) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) break
                lock.wait(
                    remainingNs / 1_000_000L,
                    (remainingNs % 1_000_000L).toInt(),
                )
            }
        }
        jobs.pollFirst()?.also { job ->
            if (job is VideoJob.Frame) {
                queuedFrames--
                queuedFrameBytes -= job.nalus.size.toLong()
            }
        }
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = synchronized(lock) {
        ArrayList<VideoJob>(jobs.size).also { drained ->
            drained.addAll(jobs)
            jobs.clear()
            queuedFrames = 0
            queuedFrameBytes = 0L
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

/**
 * A request that the decoder stop rendering to [surface] (a SurfaceHolder destroy callback must not
 * return before that). The decoder worker completes it once its codec no longer renders there: moved to
 * another surface, parked on its own offscreen consumer, or released. A worker that shuts down completes
 * its pending requests after releasing the codec.
 */
internal class SurfaceDetachRequest(val surface: Any, val park: Boolean) {
    private val done = CountDownLatch(1)
    @Volatile var outcome: DetachOutcome? = null
        private set

    fun complete(result: DetachOutcome) {
        if (outcome == null) outcome = result
        done.countDown()
    }

    /**
     * Waits until the request completes or [workerAlive] turns false (its codec is then released), up to
     * [timeoutNanos]. An interrupt does not end the wait early; it is restored before returning. Returns
     * false only when neither happened in time.
     */
    fun await(timeoutNanos: Long, workerAlive: () -> Boolean, nanoTime: () -> Long = System::nanoTime): Boolean {
        val deadline = nanoTime() + timeoutNanos
        var interrupted = false
        try {
            while (true) {
                if (done.count == 0L || !workerAlive()) return true
                val left = deadline - nanoTime()
                if (left <= 0) return false
                try {
                    if (done.await(minOf(left, POLL_NANOS), TimeUnit.NANOSECONDS)) return true
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        // Re-check that the worker is still alive this often while waiting.
        const val POLL_NANOS = 50_000_000L
    }
}

internal enum class DetachOutcome { NOT_RENDERING, PARKED, RELEASED }

/**
 * What the worker does for a detach request, from the surface its codec actually renders to (not the
 * sink's latest wish, which can run ahead of the queue): nothing when that is another surface already,
 * otherwise park the codec when asked and possible, else release it.
 */
internal fun detachAction(current: Any?, leaving: Any, park: Boolean, hasDecoder: Boolean): DetachOutcome = when {
    current !== leaving -> DetachOutcome.NOT_RENDERING
    park && hasDecoder -> DetachOutcome.PARKED
    else -> DetachOutcome.RELEASED
}

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = HandlerThread("carplay-video-parking").apply { start() }
    private val reader: ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: Looper get() = thread.looper

    init {
        try {
            reader = ImageReader.newInstance(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1),
                ImageFormat.PRIVATE,
                3,
            )
            reader.setOnImageAvailableListener(
                { source ->
                    synchronized(consumerLock) {
                        if (!closed) source.acquireLatestImage()?.close()
                    }
                },
                Handler(thread.looper),
            )
            surface = reader.surface
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

/**
 * Turns the iPhone's per-frame time into a local time on System.nanoTime, so frames can be shown at an
 * even pace instead of whenever the link and the decoder hand them over. The decoder adds the display
 * delay ([PacingDelay]) on top.
 */
internal class FramePacer(
    private val window: Int = 240,
    private val percentile: Int = 5,
    private val refreshEvery: Int = 30,
    private val slewPerSecond: Double = 0.002,
    private val resetNanos: Long = 500_000_000L,
    private val maxEarlyNanos: Long = 100_000_000L,
) {
    private val residuals = LongArray(window)
    private val scratch = LongArray(window)
    private var count = 0
    private var next = 0
    private var frames = 0
    private var candidate = 0L
    private var offset = Long.MIN_VALUE
    private var lastArrivalNanos = 0L

    /**
     * The local time of a frame stamped [senderNanos] that arrived at [arrivalNanos]: its sender time on
     * System.nanoTime plus the link's base delay. 0 when that is implausible (far behind or ahead of its
     * arrival), so the frame is shown as soon as it is decoded.
     */
    fun localTime(senderNanos: Long, arrivalNanos: Long): Long {
        residuals[next] = arrivalNanos - senderNanos
        next = (next + 1) % window
        if (count < window) count++
        val warmingUp = frames < refreshEvery
        if (warmingUp || frames % refreshEvery == 0) {
            residuals.copyInto(scratch, 0, 0, count)
            java.util.Arrays.sort(scratch, 0, count)
            candidate = scratch[(count - 1) * percentile / 100]
        }
        frames++
        if (offset == Long.MIN_VALUE || warmingUp || candidate - offset > resetNanos || offset - candidate > maxEarlyNanos) {
            offset = candidate
        } else {
            val step = ((arrivalNanos - lastArrivalNanos).coerceAtLeast(0) * slewPerSecond).toLong()
            offset += (candidate - offset).coerceIn(-step, step)
        }
        lastArrivalNanos = arrivalNanos
        val local = senderNanos + offset
        return if (local - arrivalNanos in -ONE_SECOND..maxEarlyNanos) local else 0L
    }

    private companion object {
        const val ONE_SECOND = 1_000_000_000L
    }
}

/**
 * Smooth video's display delay over a frame's local time ([FramePacer.localTime]).
 */
internal class PacingDelay(
    initialNanos: Long,
    private val minNanos: Long = 30_000_000L,
    private val maxNanos: Long = 200_000_000L,
    private val window: Int = 120,
    private val percentile: Int = 90,
    private val refreshEvery: Int = 15,
    private val marginNanos: Long = 20_000_000L,
    private val risePerFrame: Long = 1_000_000L,
    private val fallPerFrame: Long = 500_000L,
) {
    private val needs = LongArray(window)
    private val scratch = LongArray(window)
    private var count = 0
    private var next = 0
    private var frames = 0

    var nanos: Long = initialNanos.coerceIn(minNanos, maxNanos)
        private set
    private var goal = nanos

    /** Records that a frame came out of the decoder [neededNanos] after its local time. */
    fun onFrame(neededNanos: Long) {
        needs[next] = neededNanos.coerceIn(0L, maxNanos)
        next = (next + 1) % window
        if (count < window) count++
        if (++frames % refreshEvery == 0) {
            needs.copyInto(scratch, 0, 0, count)
            java.util.Arrays.sort(scratch, 0, count)
            goal = (scratch[(count - 1) * percentile / 100] + marginNanos).coerceIn(minNanos, maxNanos)
        }
        nanos += (goal - nanos).coerceIn(-fallPerFrame, risePerFrame)
    }
}

/** Running totals of one decoder: frames received and shown, and the queue-to-output time of shown frames. */
data class LiveVideoCounters(
    val received: Long,
    val rendered: Long,
    val decodeNanos: Long,
    val decodeSamples: Long,
)

/** What the live FPS counter shows for the last interval; [decodeMillis] is -1 when no frame was timed. */
data class LiveVideoReading(val shownFps: Int, val receivedFps: Int, val decodeMillis: Int)

/**
 * Turns successive [LiveVideoCounters] into per-interval rates. The first sample, and one taken after the
 * totals went backwards (a new decoder), only sets the baseline.
 */
class LiveVideoMeter {
    private var last: LiveVideoCounters? = null
    private var lastNanos = 0L

    fun update(counters: LiveVideoCounters?, nowNanos: Long): LiveVideoReading? {
        val previous = last
        val elapsed = nowNanos - lastNanos
        last = counters
        lastNanos = nowNanos
        if (counters == null || previous == null || elapsed <= 0 ||
            counters.received < previous.received || counters.rendered < previous.rendered
        ) return null
        val seconds = elapsed / 1e9
        val samples = counters.decodeSamples - previous.decodeSamples
        val decode = if (samples > 0) ((counters.decodeNanos - previous.decodeNanos) / samples / 1_000_000).toInt() else -1
        return LiveVideoReading(
            shownFps = Math.round((counters.rendered - previous.rendered) / seconds).toInt(),
            receivedFps = Math.round((counters.received - previous.received) / seconds).toInt(),
            decodeMillis = decode,
        )
    }

    fun reset() {
        last = null
        lastNanos = 0L
    }
}

/** Recover a growing input backlog, while allowing delayed bursts and static tail frames to finish. */
internal class VideoBacklogRecovery {
    private var episodeStartNs: Long? = null
    private var initialSpanNs = 0L
    private var initialPendingFrames = 0
    private var lastObservedNs: Long? = null

    fun reset() {
        episodeStartNs = null
        initialSpanNs = 0L
        initialPendingFrames = 0
        lastObservedNs = null
    }

    fun observe(
        nowNs: Long,
        currentReceivedNs: Long,
        pendingFrames: Int,
        newestPendingReceivedNs: Long?,
    ): Boolean {
        if (pendingFrames <= 0 || newestPendingReceivedNs == null ||
            currentReceivedNs > nowNs || newestPendingReceivedNs > nowNs ||
            newestPendingReceivedNs < currentReceivedNs ||
            lastObservedNs?.let { nowNs < it } == true
        ) {
            reset()
            return false
        }
        lastObservedNs = nowNs
        val ageNs = nowNs - currentReceivedNs
        val spanNs = newestPendingReceivedNs - currentReceivedNs
        if (ageNs <= LOW_AGE_NS || spanNs <= LOW_SPAN_NS) {
            reset()
            return false
        }
        if (ageNs >= HARD_AGE_NS && spanNs >= SOFT_SPAN_NS) {
            reset()
            return true
        }
        if (ageNs <= SOFT_AGE_NS || spanNs < SOFT_SPAN_NS) return false
        val start = episodeStartNs
        if (start == null) {
            episodeStartNs = nowNs
            initialSpanNs = spanNs
            initialPendingFrames = pendingFrames
            return false
        }
        val growing = spanNs - initialSpanNs >= SPAN_GROWTH_NS ||
            pendingFrames - initialPendingFrames >= FRAME_GROWTH
        if (nowNs - start >= GRACE_NS && growing) {
            reset()
            return true
        }
        return false
    }

    private companion object {
        const val SOFT_AGE_NS = 250_000_000L
        const val SOFT_SPAN_NS = 100_000_000L
        const val LOW_AGE_NS = 150_000_000L
        const val LOW_SPAN_NS = 50_000_000L
        const val HARD_AGE_NS = 1_500_000_000L
        const val GRACE_NS = 750_000_000L
        const val SPAN_GROWTH_NS = 100_000_000L
        const val FRAME_GROWTH = 4
    }
}

/**
 * Pure byte helpers that convert the CarPlay screen/audio payloads into the
 * records Android MediaCodec and AudioTrack expect. Kept free of Android types
 * so they stay testable on the JVM.
 */
object MediaCodecSupport {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** Splits an AVCDecoderConfigurationRecord into raw first SPS and first PPS. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> {
        if (codecData.size < 7) return emptySet()
        var cursor = 6
        val sps = readParameterSets(codecData, cursor, codecData[5].toInt() and 0x1f)
        cursor += sps.sumOf { it.size + 2 }
        if (cursor >= codecData.size) return emptySet()
        val pps = readParameterSets(codecData, cursor + 1, codecData[cursor].toInt() and 0xff)
        return (sps.firstOrNull() ?: ByteArray(0)) to (pps.firstOrNull() ?: ByteArray(0))
    }

    /**
     * Converts an HEVCDecoderConfigurationRecord (hvcC) into Annex B VPS/SPS/PPS CSD.
     *
     * Android video decoders expect the initialization data as NAL units with start codes,
     * not as the raw ISO-BMFF hvcC record.
     */
    fun hevcCodecSpecificData(codecData: ByteArray): ByteArray {
        if (codecData.size < HEVC_FIXED_RECORD_SIZE || codecData[0].toInt() != 1) {
            return ByteArray(0)
        }

        var cursor = HEVC_ARRAY_COUNT_OFFSET
        val arrayCount = codecData[cursor++].toInt() and 0xff
        val output = ByteArrayOutputStream()
        repeat(arrayCount) {
            if (cursor >= codecData.size) return ByteArray(0)
            cursor += 1 // array_completeness, reserved, and nal_unit_type
            if (cursor + 2 > codecData.size) return ByteArray(0)
            val nalUnitCount = readU16Be(codecData, cursor)
            cursor += 2
            repeat(nalUnitCount) {
                if (cursor + 2 > codecData.size) return ByteArray(0)
                val nalUnitLength = readU16Be(codecData, cursor)
                cursor += 2
                if (cursor + nalUnitLength > codecData.size) return ByteArray(0)
                output.write(START_CODE)
                output.write(codecData, cursor, nalUnitLength)
                cursor += nalUnitLength
            }
        }
        return output.toByteArray()
    }

    /** Converts CarPlay's length-prefixed NAL units into an Annex B byte stream. */
    fun toAnnexB(lengthPrefixed: ByteArray): ByteArray {
        if (lengthPrefixed.size >= 4 &&
            lengthPrefixed[0] == 0.toByte() &&
            lengthPrefixed[1] == 0.toByte() &&
            lengthPrefixed[2] == 0.toByte() &&
            lengthPrefixed[3] == 1.toByte()
        ) {
            return lengthPrefixed
        }

        var inputOffset = 0
        var outputSize = 0
        while (inputOffset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, inputOffset)
            inputOffset += 4
            if (length <= 0 || length > lengthPrefixed.size - inputOffset) return ByteArray(0)
            if (length > Int.MAX_VALUE - outputSize - START_CODE.size) return ByteArray(0)
            outputSize += START_CODE.size + length
            inputOffset += length
        }
        if (outputSize == 0 || inputOffset != lengthPrefixed.size) return ByteArray(0)

        val output = ByteArray(outputSize)
        var offset = 0
        var outputOffset = 0
        while (offset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, offset)
            offset += 4
            if (length <= 0 || offset + length > lengthPrefixed.size) break
            START_CODE.copyInto(output, outputOffset)
            outputOffset += START_CODE.size
            lengthPrefixed.copyInto(output, outputOffset, offset, offset + length)
            outputOffset += length
            offset += length
        }
        return output
    }

    /**
     * Converts CarPlay's length-prefixed NAL units into an Annex B byte stream in place,
     * avoiding a per-frame buffer allocation and full-frame copy on the decode worker.
     * Returns false when [lengthPrefixed] is empty or malformed.
     */
    fun toAnnexBInPlace(lengthPrefixed: ByteArray): Boolean {
        if (lengthPrefixed.size < 4) return false
        val startsWithStartCode = lengthPrefixed[0] == 0.toByte() &&
            lengthPrefixed[1] == 0.toByte() &&
            lengthPrefixed[2] == 0.toByte() &&
            lengthPrefixed[3] == 1.toByte()
        if (startsWithStartCode && (lengthPrefixed.size == 4 || (lengthPrefixed[4].toInt() and 0x1f) !in 9..12)) {
            return true
        }

        var inputOffset = 0
        while (inputOffset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, inputOffset)
            inputOffset += 4
            if (length <= 0 || length > lengthPrefixed.size - inputOffset) {
                inputOffset = -1
                break
            }
            inputOffset += length
        }
        if (inputOffset != lengthPrefixed.size) {
            return lengthPrefixed[0] == 0.toByte() &&
                lengthPrefixed[1] == 0.toByte() &&
                lengthPrefixed[2] == 0.toByte() &&
                lengthPrefixed[3] == 1.toByte()
        }

        var offset = 0
        while (offset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, offset)
            lengthPrefixed[offset] = 0
            lengthPrefixed[offset + 1] = 0
            lengthPrefixed[offset + 2] = 0
            lengthPrefixed[offset + 3] = 1
            offset += 4 + length
        }
        return true
    }

    /** IDR (AVC) or IRAP (HEVC) starts an independently decodable reference chain. */
    fun isRandomAccess(annexB: ByteArray, codec: VideoCodec): Boolean {
        var cursor = 0
        val limit = annexB.size - 3
        while (cursor < limit) {
            val third = annexB[cursor + 2].toInt()
            if (third > 1 || third < 0) {
                cursor += 3
                continue
            }
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                third == 1 -> 3
                third == 0 && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0 && cursor + prefix < annexB.size) {
                val header = annexB[cursor + prefix].toInt() and 255
                if (codec == VideoCodec.H264 && header and 31 == 5) return true
                if (codec == VideoCodec.H265 && cursor + prefix + 1 < annexB.size &&
                    (header shr 1) and 63 in 16..21
                ) return true
                cursor += prefix
            } else {
                cursor++
            }
        }
        return false
    }

    /** Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame. */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray =
        adtsFrame(accessUnit, 0, accessUnit.size, sampleRate, channels)

    /** Wraps one raw AAC-LC access unit slice `[offset, offset + length)` in an MPEG-4 ADTS frame in a single allocation. */
    fun adtsFrame(
        source: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val frameLength = length + 7
        val frame = ByteArray(frameLength)
        writeAdtsHeader(frame, length, sampleRate, channels)
        source.copyInto(frame, 7, offset, offset + length)
        return frame
    }

    /** Writes a 7-byte MPEG-4 ADTS header into [target] for an AAC-LC access unit of [length] bytes. */
    fun writeAdtsHeader(
        target: ByteArray,
        length: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = length + 7
        target[0] = 0xff.toByte()
        target[1] = 0xf1.toByte()
        target[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        target[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        target[4] = ((frameLength ushr 3) and 0xff).toByte()
        target[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        target[6] = 0xfc.toByte()
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun emptySet(): Pair<ByteArray, ByteArray> = ByteArray(0) to ByteArray(0)

    private fun readParameterSets(source: ByteArray, offset: Int, count: Int): List<ByteArray> {
        val sets = ArrayList<ByteArray>(count)
        var cursor = offset
        var index = 0
        while (index < count && cursor + 2 <= source.size) {
            index++
            val length = readU16Be(source, cursor)
            cursor += 2
            if (cursor + length > source.size) break
            sets.add(source.copyOfRange(cursor, cursor + length))
            cursor += length
        }
        return sets
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private const val HEVC_FIXED_RECORD_SIZE = 23
    private const val HEVC_ARRAY_COUNT_OFFSET = 22
}

/** A failed configure/start still owns the created codec and must release it before propagating. */
internal object MediaCodecStartup {
    fun <T> create(
        create: () -> T,
        configure: (T) -> Unit,
        start: (T) -> Unit,
        release: (T) -> Unit,
    ): T {
        val candidate = create()
        try {
            configure(candidate)
            start(candidate)
            return candidate
        } catch (failure: Throwable) {
            // Driver cleanup can fail too; preserve the original configure/start failure.
            runCatching { release(candidate) }
            throw failure
        }
    }
}

/** Optional MediaTek decoder modes exposed by old MTK ACodec implementations. */
internal enum class MtkDecoderMode(val diagnosticName: String) {
    NONE("off"),
    LOW_LATENCY("low-latency"),
    LOW_LATENCY_NO_REORDER("low-latency+no-reorder"),
    ;

    fun fallback(): MtkDecoderMode = when (this) {
        LOW_LATENCY_NO_REORDER -> LOW_LATENCY
        LOW_LATENCY -> NONE
        NONE -> NONE
    }

    fun attempts(): List<MtkDecoderMode> = when (this) {
        LOW_LATENCY_NO_REORDER -> listOf(LOW_LATENCY_NO_REORDER, LOW_LATENCY, NONE)
        LOW_LATENCY -> listOf(LOW_LATENCY, NONE)
        NONE -> listOf(NONE)
    }
}

/** Detection and tuning for legacy MTK OMX and dedicated Codec2 low-latency decoders. */
object MtkDecoderTuning {
    const val LOW_LATENCY_KEY = "vdec-lowlatency"

    // This is the spelling used by MTK's ACodec even though it maps to NoReorderMode.
    const val NO_REORDER_KEY = "vdec-no-record"

    fun isDecoderName(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true) ||
            name.startsWith("c2.mtk.", ignoreCase = true)

    /** The published vdec-* bridge is an ACodec/OMX extension, not a Codec2 vendor parameter. */
    fun supportsLegacyAcodecKeys(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true)

    fun isCodec2LowLatencyDecoder(name: String, advertisesLowLatency: Boolean): Boolean =
        name.startsWith("c2.mtk.", ignoreCase = true) &&
            (advertisesLowLatency || name.endsWith(".lowlatency", ignoreCase = true) ||
                name.endsWith(".low_latency", ignoreCase = true))

    /** Prefer a dedicated MTK Codec2 low-latency component; otherwise use the default legacy OMX decoder. */
    internal fun selectedDecoder(candidates: List<MtkDecoderCandidate>): MtkDecoderCandidate? =
        candidates.firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }
            ?: candidates.firstOrNull()?.takeIf { supportsLegacyAcodecKeys(it.name) }

    /** The MTK decoder path to expose for [mime], or null when neither safe path is available. */
    fun selectedDecoderName(mime: String): String? = candidates(mime).let(::selectedDecoder)?.name

    /** A dedicated Codec2 low-latency component for [mime], selected explicitly instead of using ACodec keys. */
    fun codec2LowLatencyDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }?.name

    /** The default decoder for [mime] only when it can consume MTK's legacy ACodec keys. */
    fun legacyAcodecDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull()?.name?.takeIf(::supportsLegacyAcodecKeys)

    private val candidatesByMime = ConcurrentHashMap<String, List<MtkDecoderCandidate>>()

    private fun candidates(mime: String): List<MtkDecoderCandidate> =
        candidatesByMime.getOrPut(mime.lowercase()) { listCandidates(mime) }

    private fun listCandidates(mime: String): List<MtkDecoderCandidate> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) ||
                info.supportedTypes.none { it.equals(mime, ignoreCase = true) }
            ) return@mapNotNull null
            val lowLatency = runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            }.getOrDefault(false)
            MtkDecoderCandidate(info.name, lowLatency)
        }
    }.getOrDefault(emptyList())
}

internal data class MtkDecoderCandidate(val name: String, val lowLatency: Boolean = false)

internal fun appliedMtkDecoderMode(requested: MtkDecoderMode, codecName: String): MtkDecoderMode =
    requested.takeIf { MtkDecoderTuning.supportsLegacyAcodecKeys(codecName) } ?: MtkDecoderMode.NONE

internal fun MtkDecoderMode.formatKeys(): List<String> = when (this) {
    MtkDecoderMode.NONE -> emptyList()
    MtkDecoderMode.LOW_LATENCY -> listOf(MtkDecoderTuning.LOW_LATENCY_KEY)
    MtkDecoderMode.LOW_LATENCY_NO_REORDER ->
        listOf(MtkDecoderTuning.LOW_LATENCY_KEY, MtkDecoderTuning.NO_REORDER_KEY)
}

/**
 * The operating rate requested from a stream's decoder, 0 for none: the main screen asks for the frame
 * rate the iPhone is asked for ([frameRate], the frame-rate setting).
 */
internal fun videoOperatingRate(type: Int, statsLabel: String?, frameRate: Int): Int =
    if (type == MAIN_SCREEN_TYPE && statsLabel == null && frameRate > 0) frameRate else 0

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/**
 * Configure attempts in order. Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
 * parameters with BAD_VALUE, so a minimal format and then software follow.
 */
internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> = buildList {
    if (operatingRate > 0) lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true, operatingRate)) }
    lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true)) }
    val mtkModes = maximumMtkMode.attempts()
    if (operatingRate > 0) {
        mtkModes.forEach { add(DecoderAttempt(null, tuned = true, operatingRate, it)) }
    }
    mtkModes.forEach { add(DecoderAttempt(null, tuned = true, mtkMode = it)) }
    add(DecoderAttempt(codecName = null, tuned = false))
    softwareDecoder?.let { add(DecoderAttempt(it, tuned = false)) }
}

/**
 * The operating rate to ask for at the next configure: none once a later attempt ([used]) worked after
 * the rate was refused, so the refusal is not paid again; unchanged when every attempt failed.
 */
internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int =
    if (requested > 0 && used != null && used.operatingRate == 0) 0 else requested

/**
 * Qualcomm's vendor parameters that make its decoders output each frame as soon as it is decoded, in
 * decode order.
 */
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (codecName.startsWith("c2.qti.") || codecName.startsWith("OMX.qcom.")) {
        listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
    } else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(
    val name: String,
    val hardware: Boolean,
    val alias: Boolean,
    val lowLatency: Boolean,
)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.firstOrNull { it.lowLatency && it.hardware && !it.alias }?.name

/** The low-latency decoder to try at the next configure: none once another decoder ([used]) worked after it. */
internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? =
    if (requested != null && used != null && used.codecName != requested) null else requested

/** Qualcomm's decoder parameter for output in decoding order. */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val decodeMicros = IntArray(1024)
    private var decodeSamples = 0
    private var late = 0
    private var pacingDelayNanos = 0L

    @Synchronized
    fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized
    fun onRendered() {
        rendered++
    }

    @Synchronized
    fun onRecovery() {
        recoveries++
    }

    /** Time from queueing a frame into the decoder to dequeueing its output. */
    @Synchronized
    fun onDecodeLatency(ns: Long) {
        if (decodeSamples < decodeMicros.size) {
            decodeMicros[decodeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized
    fun onLate() {
        late++
    }

    /** Smooth video's current display delay ([PacingDelay]). */
    @Synchronized
    fun onPacingDelay(ns: Long) {
        pacingDelayNanos = ns
    }

    private fun decodeSummary(): String {
        if (decodeSamples == 0) return ""
        val sorted = decodeMicros.copyOf(decodeSamples).also { it.sort() }
        fun percentileMs(p: Int) = sorted[(decodeSamples - 1) * p / 100] / 1000
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        return " decode p50=%dms p90=%dms late=%d".format(percentileMs(50), percentileMs(90), late) + delay
    }

    @Synchronized
    fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) {
            windowStartNs = now
            return null
        }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds,
            rendered / seconds,
            maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(),
            recoveries,
            touchAvgMs,
            maxTouchLatencyNs / 1_000_000,
            touchSamples,
            TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + decodeSummary()
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0
        rendered = 0
        recoveries = 0
        bytes = 0
        maxArrivalGapNs = 0
        touchSamples = 0
        touchLatencySumNs = 0
        maxTouchLatencyNs = 0
        decodeSamples = 0
        late = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
    }
}

internal typealias VideoSnapshotRecorder = VideoStats

/** Immutable snapshot of video decoder state for UI/session inspection. */
data class VideoRenderDiagnostics(
    val codecName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val receivedFrames: Long = 0L,
    val renderedFrames: Long = 0L,
    val decodeNanos: Long = 0L,
    val decodeSamples: Long = 0L,
    val operatingRate: Int = 0,
    val dedicatedOutputThread: Boolean = false,
)

/** Bounded failure metadata for exported reports; exception messages can contain private inputs. */
internal object MediaFailureSummary {
    fun describe(error: Throwable): String {
        val failures = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (failures.size < 3) {
            val next = current ?: break
            if (failures.any { it === next }) break
            failures += next
            current = next.cause
        }
        val types = failures.joinToString("/") { identifier(it.javaClass.simpleName, 64) }
        // Keep one code location, never the complete stack, source filename or arbitrary caller.
        val frame = failures.asSequence().flatMap { it.stackTrace.asSequence() }.firstOrNull {
            it.className.startsWith("android.media.") ||
                it.className.startsWith("app.tvplay.media.") ||
                it.className.startsWith("app.tvplay.engine.av.")
        }
        val location = frame?.let {
            "${identifier(it.className, 128)}.${identifier(it.methodName, 64)}:${it.lineNumber}"
        } ?: "unavailable"
        return "error=${identifier(error.javaClass.simpleName, 64)} causes=$types at=$location"
    }

    private fun identifier(value: String, limit: Int): String =
        value.take(limit).filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
            .ifEmpty { "unknown" }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
class HardwareVideoDecoder internal constructor(
    private val streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (HardwareVideoDecoder) -> Unit = {},
    operatingRate: Int = 0,
    /** Set [vendorLowLatencyKeys] on the tuned configure attempts. */
    private val vendorLowLatency: Boolean = false,
    /**
     * Release decoded frames from a thread that waits on the codec's output, instead of when the worker
     * next comes back from its queue or from feeding: a frame is shown as soon as it is decoded.
     */
    dedicatedOutputThread: Boolean = false,
    /**
     * The main screen with smooth video: for H.264, try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask decoders that list it for [PICTURE_ORDER_PARAMETER], so each frame leaves the decoder as soon as it can.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
    private val onFirstFrameRendered: () -> Unit = {},
    private val onStreamDimensionsChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onRenderStatusChanged: (VideoRenderDiagnostics) -> Unit = {},
) : Closeable {
    private val pacer = FramePacer()
    @Volatile private var pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var activeCodecName: String? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    @Volatile private var renderedFrameLogged = false
    val hasRenderedFirstFrame: Boolean get() = renderedFrameLogged && running
    // The operating rate still asked for, dropped for this stream once a decoder refuses it; and the rate
    // the current codec was configured with.
    @Volatile private var operatingRate = operatingRate
    @Volatile private var configuredRate = 0
    private var maximumMtkMode = if (mtkDecoderTuningEnabled) {
        MtkDecoderMode.LOW_LATENCY_NO_REORDER
    } else MtkDecoderMode.NONE
    private var configuredMtkMode = MtkDecoderMode.NONE
    private var configuredMtkCodec2LowLatency = false
    // Whether the vendor low-latency parameters were set on the codec being configured.
    private var configuredVendorLowLatency = false
    // Likewise the low-latency decoder, not tried again once another decoder had to take over; and what the
    // codec that tryConfigure started last was given.
    private var lowLatencyDecoderRefused = false
    private var startedLowLatency = false
    private var startedPictureOrder = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    // Running totals for the live FPS counter, read from the UI thread.
    private val liveReceived = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()

    fun liveCounters() = LiveVideoCounters(
        received = liveReceived.get(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
    )

    fun diagnosticsSnapshot(): VideoRenderDiagnostics = VideoRenderDiagnostics(
        codecName = activeCodecName,
        width = width,
        height = height,
        receivedFrames = liveReceived.get(),
        renderedFrames = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
        operatingRate = configuredRate,
        dedicatedOutputThread = outputThread != null,
    )

    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    private val outputThread = if (dedicatedOutputThread) {
        Thread(::runOutput, "carplay-video-output").apply { isDaemon = true }
    } else null
    // Guards the codec handoff between the worker and the output thread: [decoder] changes, [outputSurface]
    // and [parking] changes, the per-frame bookkeeping, and the output thread's handling of one buffer.
    // The output thread waits inside dequeueOutputBuffer without it.
    private val outputLock = Object()
    // The codec the output thread is waiting on; the worker waits for it to return before releasing that codec.
    private var dequeuing: MediaCodec? = null
    // A failure on the output thread, handled by the worker as if its own job had failed.
    @Volatile private var outputFailure: Exception? = null

    fun start() {
        thread.start()
        outputThread?.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    fun setVideoSyncEnabled(enabled: Boolean, defaultDelayNanos: Long) {
        pacingDelay = if (enabled && defaultDelayNanos > 0L) PacingDelay(defaultDelayNanos) else null
    }

    fun setVideoFrameRate(fps: Int) {
        operatingRate = videoOperatingRate(streamType, null, fps)
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    internal fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offerFirst(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    internal fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try {
                    thread.join(leftMillis)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    fun resync() {
        queue.requestResync()
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    outputFailure?.let { failure ->
                        outputFailure = null
                        onJobFailure("output", failure)
                    }
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val backlog = queue.backlogAfterCurrent()
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(),
                                job.receivedNs,
                                backlog.pendingFrames,
                                backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recover("video backlog kept growing or exceeded hard limit")
                            } else {
                                feed(job.nalus, job.receivedNs, localTimeOf(job))
                            }
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.RefreshPicture -> if (decoder != null) {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    if (outputThread == null) decoder?.let(::drainOutput)
                    stats.logIfDue()?.let { line ->
                        report(line)
                        onRenderStatusChanged(diagnosticsSnapshot())
                    }
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) {
                        requestKeyFrameIfDue()
                    }
                } catch (error: Exception) {
                    onJobFailure(job?.javaClass?.simpleName ?: "drain", error)
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            outputThread?.let { output ->
                output.interrupt()
                joinUninterruptibly(output, OUTPUT_EXIT_WAIT_MS)
            }
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
            runCatching { onExit(this) }
        }
    }

    private fun onJobFailure(stage: String, error: Exception) {
        if (running) Log.e(TAG, "video decoder job failed: $stage", error)
        if (running) reportFailure("stage=$stage", error)
        val surfaceValid = outputSurface?.isValid == true
        if (surfaceValid && error is IllegalStateException) {
            dropMtkTuning("codec failed during $stage")
            dropMtkCodec2LowLatency("codec failed during $stage")
        }
        if (surfaceValid && configuredRate > 0 && !renderedFrameLogged && error is MediaCodec.CodecException) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** The output thread: waits on the current codec's output and releases each frame as it comes. */
    private fun runOutput() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = synchronized(outputLock) {
                val current = decoder
                if (current == null || outputFailure != null) {
                    try {
                        outputLock.wait(OUTPUT_IDLE_WAIT_MS)
                    } catch (_: InterruptedException) {
                        return
                    }
                    null
                } else {
                    dequeuing = current
                    current
                }
            } ?: continue
            var index = MediaCodec.INFO_TRY_AGAIN_LATER
            var failure: Exception? = null
            try {
                index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (error: Exception) {
                failure = error
            }
            synchronized(outputLock) {
                dequeuing = null
                outputLock.notifyAll()
                // A codec the worker released or replaced meanwhile has no buffers left to hand out.
                if (decoder === codec) {
                    try {
                        if (failure != null) throw failure
                        handleOutput(codec, index, info)
                    } catch (error: Exception) {
                        if (running) outputFailure = error
                    }
                }
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface?.takeIf { it.isValid } ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val requestedRate = operatingRate
        val atOnce = framesAtOnce && codec == VideoCodec.H264
        val requestedMtkCodec2 = if (mtkDecoderTuningEnabled && !lowLatencyDecoderRefused) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (atOnce && !lowLatencyDecoderRefused) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = if (mtkDecoderTuningEnabled &&
            MtkDecoderTuning.legacyAcodecDecoderName(mime) != null
        ) maximumMtkMode else MtkDecoderMode.NONE
        var configured: ConfiguredVideoDecoder? = null
        var used: DecoderAttempt? = null
        for (attempt in videoDecoderAttempts(
            requestedRate,
            softwareDecoderName(mime),
            requestedLowLatency,
            requestedMtkMode,
        )) {
            if (!surface.isValid) return
            configured = tryConfigure(mime, csd, surface, attempt, atOnce)
            if (configured != null) {
                used = attempt
                break
            }
        }
        if (!surface.isValid) return
        if (configured == null) {
            queue.discardCurrentChain()
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        if (nextOperatingRate(requestedRate, used) != requestedRate) dropOperatingRate("refused at configure")
        if (nextLowLatencyDecoder(requestedLowLatency, used) != requestedLowLatency) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            lowLatencyDecoderRefused = true
        }
        renderedFrameLogged = false
        submittedFrameLogged = false
        synchronized(outputLock) {
            decoder = configured?.codec
            activeCodecName = configured?.codecName
            outputLock.notifyAll()
        }
        configuredRate = used?.operatingRate ?: 0
        configuredMtkMode = configured?.mtkMode ?: MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = configured != null && requestedMtkCodec2 != null &&
            configured.codecName.equals(requestedMtkCodec2, ignoreCase = true)
        if (configured != null && MtkDecoderTuning.supportsLegacyAcodecKeys(configured.codecName) &&
            configuredMtkMode.ordinal < requestedMtkMode.ordinal
        ) {
            report(
                "MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                    "to=${configuredMtkMode.diagnosticName}",
            )
            maximumMtkMode = configuredMtkMode
        }
        if (configured != null) {
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = if (vendorLowLatency) " vendorLowLatency=$configuredVendorLowLatency" else ""
            val output = if (outputThread != null) " outputThread=true" else ""
            val atOnceReport = if (atOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report(
                "decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                    "$rate$lowLatency$output$atOnceReport$mtk",
            )
            onRenderStatusChanged(diagnosticsSnapshot())
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        operatingRate = 0
    }

    private fun dropMtkTuning(reason: String) {
        val previous = configuredMtkMode
        if (previous == MtkDecoderMode.NONE) return
        val fallback = previous.fallback()
        if (fallback.ordinal < maximumMtkMode.ordinal) maximumMtkMode = fallback
        report(
            "MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
                "next=${fallback.diagnosticName}",
        )
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuredMtkCodec2LowLatency) return
        configuredMtkCodec2LowLatency = false
        lowLatencyDecoderRefused = true
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private fun buildFormat(
        mime: String,
        csd: List<ByteArray>,
        attempt: DecoderAttempt,
        mtkMode: MtkDecoderMode,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (attempt.operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
            }
            mtkMode.formatKeys().forEach { setInteger(it, 1) }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private data class ConfiguredVideoDecoder(
        val codec: MediaCodec,
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        /** Ask for [PICTURE_ORDER_PARAMETER] when the decoder lists it ([framesAtOnce], H.264). */
        atOnce: Boolean = false,
    ): ConfiguredVideoDecoder? {
        var candidate: MediaCodec? = null
        var mtkMode = MtkDecoderMode.NONE
        var codecName = attempt.codecName ?: "default"
        var phase = "create"
        var phaseStartNs = System.nanoTime()
        val startedNs = phaseStartNs
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val createdName = runCatching { codec.name }.getOrNull().orEmpty()
            codecName = createdName.ifEmpty { codecName }
            mtkMode = appliedMtkDecoderMode(attempt.mtkMode, createdName)
            val format = buildFormat(mime, csd, attempt, mtkMode)
            phase = "configure"
            phaseStartNs = System.nanoTime()
            val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val vendorKeys = if (attempt.tuned && vendorLowLatency) vendorLowLatencyKeys(codec.name.orEmpty()) else emptyList()
            vendorKeys.forEach { format.setInteger(it, 1) }
            configuredVendorLowLatency = vendorKeys.isNotEmpty()
            val pictureOrder = attempt.tuned && atOnce && listsPictureOrder(codec)
            if (pictureOrder) format.setInteger(PICTURE_ORDER_PARAMETER, 1)
            codec.configure(format, surface, null, 0)
            phase = "start"
            phaseStartNs = System.nanoTime()
            codec.start()
            startedLowLatency = lowLatency
            startedPictureOrder = pictureOrder
            val configuredName = runCatching { codec.name }.getOrNull().orEmpty()
                .ifEmpty { createdName }
                .ifEmpty { attempt.codecName ?: "default" }
            ConfiguredVideoDecoder(codec, configuredName, mtkMode)
        } catch (error: Exception) {
            val phaseMs = (System.nanoTime() - phaseStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            reportFailure(
                "stage=configure phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime",
                error,
            )
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /** Android 11 added the low-latency feature; aliases and software-only flags exist from Android 10. */
    private fun findLowLatencyDecoder(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            lowLatencyDecoderName(
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filter { !it.isEncoder && mime in it.supportedTypes }
                    .map { info ->
                        DecoderCandidate(
                            name = info.name,
                            hardware = info.isHardwareAccelerated,
                            alias = info.isAlias,
                            lowLatency = info.getCapabilitiesForType(mime).isFeatureSupported("low-latency"),
                        )
                    },
            )
        }.getOrNull()
    }

    /** Android 12 lets a codec list its vendor parameters. */
    private fun listsPictureOrder(codec: MediaCodec): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { PICTURE_ORDER_PARAMETER in codec.supportedVendorParameters }.getOrDefault(false)

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit = synchronized(outputLock) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                    try {
                        checkNotNull(decoder).setOutputSurface(consumer.surface)
                        outputSurface = consumer.surface
                        Log.i(TAG, "video decoder parked off screen")
                    } catch (error: Exception) {
                        Log.w(TAG, "video decoder could not park; releasing it", error)
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?): Unit = synchronized(outputLock) {
        if (outputSurface === surface) return
        if (surface != null && !surface.isValid) {
            Log.w(TAG, "video decoder ignoring invalid output surface")
            return
        }
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            parking?.close()
            parking = null
            queue.discardCurrentChain()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                parking?.close()
                parking = null
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        parking?.close()
        parking = null
        queue.discardCurrentChain()
        referenceChain.reset()
        lastKeyFrameRequestNs = 0L
        lastConfig?.let(::configureDecoder)
        requestKeyFrameIfDue()
    }

    private val workerBufferInfo = MediaCodec.BufferInfo()

    private fun feed(nalus: ByteArray, receivedNs: Long, presentNs: Long = 0L) {
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (!MediaCodecSupport.toAnnexBInPlace(nalus)) {
            recover("invalid video access unit")
            return
        }
        val annexB = nalus
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild must not immediately restart on its obsolete triggering IDR.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(),
                receivedNs,
                queue.backlogAfterCurrent(),
            )
        ) {
            queue.discardCurrentChain()
            backlogRecovery.reset()
            requestKeyFrameIfDue()
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = DecoderInputPump.acquire(
            running = { running && outputFailure == null },
            drain = { if (outputThread == null) drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            outputFailure?.let { failure ->
                outputFailure = null
                throw failure
            }
            dropMtkTuning("video decoder input stalled")
            dropMtkCodec2LowLatency("video decoder input stalled")
            recover("video decoder input stalled")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            // Recorded before queueing: the output thread may dequeue the frame as soon as it is queued.
            synchronized(outputLock) {
                val queuedNow = System.nanoTime()
                if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
                lastQueuedNanos = queuedNow
                queuedPresentationUs[queuedSlot] = presentationUs
                queuedAtNanos[queuedSlot] = queuedNow
                queuedPaced[queuedSlot] = presentNs > 0
                queuedBeforePause[queuedSlot] = false
                if (presentNs > 0) {
                    // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                    if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                        for (slotIndex in recentPacedSlots) {
                            if (slotIndex >= 0) queuedBeforePause[slotIndex] = true
                        }
                    }
                    lastQueuedLocalNanos = presentNs
                    recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                    recentPacedSlots[0] = queuedSlot
                }
                queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationUs, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        if (outputThread == null) drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int {
        for (index in queuedPresentationUs.indices) {
            if (queuedPresentationUs[index] == presentationUs) return index
        }
        return -1
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = workerBufferInfo
        while (running) {
            if (!handleOutput(codec, codec.dequeueOutputBuffer(info, 0), info)) return
        }
    }

    /** Handles one dequeueOutputBuffer result; false when there is nothing more to drain for now. */
    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
            index >= 0 -> {
                val render = outputSurface != null
                // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                // not shown, so it is neither paced nor counted.
                val shown = render && outputSurface !== parking?.surface
                val now = System.nanoTime()
                val slot = queuedSlotOf(info.presentationTimeUs)
                val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                val slotPaced = slot >= 0 && queuedPaced[slot]
                val slotBeforePause = slot >= 0 && queuedBeforePause[slot]
                if (slot >= 0) {
                    queuedPresentationUs[slot] = 0L
                }
                val heldOverGap = queued in 1 until resumedAtNanos
                if (queued > 0 && !heldOverGap) {
                    stats.onDecodeLatency(now - queued)
                    liveDecodeNanos.addAndGet(now - queued)
                    liveDecodeSamples.incrementAndGet()
                }
                val delay = pacingDelay
                val paced = shown && delay != null && slotPaced
                val localNs = info.presentationTimeUs * 1000
                val pauseHeld = paced && slotBeforePause
                val targetNs = if (paced) {
                    if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                    stats.onPacingDelay(delay.nanos)
                    localNs + delay.nanos
                } else 0L
                if (targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                    codec.releaseOutputBuffer(index, targetNs)
                } else {
                    if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                    codec.releaseOutputBuffer(index, render)
                }
                if (shown) {
                    stats.onRendered()
                    liveRendered.incrementAndGet()
                }
                if (shown && !renderedFrameLogged) {
                    renderedFrameLogged = true
                    report("first frame rendered")
                    onFirstFrameRendered()
                    Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
            else -> return false
        }
        return true
    }

    private fun logOutputFormat(format: MediaFormat) {
        val codedWidth = format.intOrNull(MediaFormat.KEY_WIDTH)
        val codedHeight = format.intOrNull(MediaFormat.KEY_HEIGHT)
        val cropLeft = format.intOrNull("crop-left")
        val cropTop = format.intOrNull("crop-top")
        val cropRight = format.intOrNull("crop-right")
        val cropBottom = format.intOrNull("crop-bottom")
        val visibleWidth = if (cropLeft != null && cropRight != null && cropRight >= cropLeft) {
            cropRight - cropLeft + 1
        } else {
            codedWidth
        }
        val visibleHeight = if (cropTop != null && cropBottom != null && cropBottom >= cropTop) {
            cropBottom - cropTop + 1
        } else {
            codedHeight
        }
        if (visibleWidth != null && visibleHeight != null && visibleWidth > 0 && visibleHeight > 0) {
            onStreamDimensionsChanged(visibleWidth, visibleHeight)
        }
        report(
            "output format requested=${width}x${height} " +
                "coded=${codedWidth}x${codedHeight} " +
                "crop=${cropLeft},${cropTop},${cropRight},${cropBottom} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${codedWidth}x${codedHeight} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching {
            report(
                "decoder failed api=${Build.VERSION.SDK_INT} $context " +
                    MediaFailureSummary.describe(error),
            )
        }
    }

    // Worker only; [outputLock] orders it against the output thread.
    private fun releaseDecoder() {
        backlogRecovery.reset()
        val codec: MediaCodec?
        synchronized(outputLock) {
            codec = decoder
            decoder = null
            activeCodecName = null
            queuedPresentationUs.fill(0L)
            queuedAtNanos.fill(0L)
            queuedPaced.fill(false)
            queuedBeforePause.fill(false)
            recentPacedSlots.fill(-1)
            queuedSlot = 0
            lastQueuedNanos = 0L
            resumedAtNanos = 0L
            lastQueuedLocalNanos = 0L
            // The output thread returns from dequeueOutputBuffer within its timeout and then sees no codec.
            awaitNotDequeuing(codec)
        }
        configuredRate = 0
        configuredMtkMode = MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = false
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
    }

    /** Waits, holding [outputLock], until the output thread no longer waits on [codec]; keeps an interrupt. */
    private fun awaitNotDequeuing(codec: MediaCodec?) {
        if (codec == null) return
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_EXIT_WAIT_MS)
        while (dequeuing === codec) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try {
                outputLock.wait(leftMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMillis: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (thread.isAlive) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try {
                thread.join(leftMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_IDLE_WAIT_MS = 10L
        const val OUTPUT_EXIT_WAIT_MS = 500L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        const val STILL_GAP_NS = 500_000_000L
        const val SENDER_PAUSE_NS = 120_000_000L
        const val PAUSE_HELD_FRAMES = 3
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

typealias VideoDecoder = HardwareVideoDecoder
