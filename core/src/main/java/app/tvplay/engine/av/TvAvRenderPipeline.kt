package app.tvplay.engine.av

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.Surface
import app.tvplay.engine.protocol.rtsp.AudioFormat
import app.tvplay.engine.protocol.rtsp.AudioStreamId
import app.tvplay.engine.protocol.rtsp.MediaSink
import app.tvplay.engine.protocol.rtsp.MicrophoneConfig
import app.tvplay.engine.protocol.rtsp.VideoCodec
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

typealias AndroidMediaSink = TvAvRenderPipeline

/**
 * Android TV audio/video/microphone rendering pipeline for the CarPlay media engine.
 *
 * - Hardware-accelerated H.264/HEVC video decoding onto the TV [Surface] using [HardwareVideoDecoder],
 *   [VideoDecodeQueue], [FramePacer], [LiveVideoMeter], and [VideoBacklogRecovery].
 * - Low-latency and buffered 16-bit PCM [android.media.AudioTrack] output using [PcmAudioRenderer],
 *   [MediaClockTrim], [MediaPacketLagGuard], and [AudioFocusRequestCompat].
 * - Voice/Siri microphone uplink using [VoiceMicRecorder].
 */
class TvAvRenderPipeline(
    surface: Surface? = null,
    @Volatile private var videoWidth: Int = 1280,
    @Volatile private var videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    context: Context? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    @Volatile private var videoPacingDelayMillis: Int = 0,
    /** The main screen's frame rate, requested from its decoder as the operating rate; 0 for none. */
    @Volatile private var mainVideoFrameRate: Int = 0,
    /**
     * Low-latency decoding: ask Qualcomm decoders to output each frame as soon as it is decoded,
     * and hand decoded frames to the screen from a thread of their own.
     */
    private val vendorLowLatencyDecoder: Boolean = false,
    /** Opt-in MTK ACodec low-latency/no-reorder modes, with per-session automatic fallback. */
    private val mtkDecoderTuningEnabled: Boolean = false,
) : MediaSink, Closeable {
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )
    private val audioOutputDisabled = AtomicBoolean(false)

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, HardwareVideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    private val closingVideoDecoders = ConcurrentHashMap.newKeySet<HardwareVideoDecoder>()
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    internal var detachGraceNanos = 500_000_000L

    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, PcmAudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, VoiceMicRecorder>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null

    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val liveVideoMeter = LiveVideoMeter()

    @Volatile private var firstVideoFrameListener: (() -> Unit)? = null
    @Volatile private var videoStreamDimensionsListener: ((Int, Int) -> Unit)? = null
    @Volatile private var videoRenderStatusListener: ((VideoRenderDiagnostics) -> Unit)? = null

    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    fun setAudioFocusChangeListener(listener: ((Int) -> Unit)?) {
        audioFocusCoordinator.setFocusChangeListener(listener)
    }

    fun setAudioOutputDisabled(disabled: Boolean) {
        if (audioOutputDisabled.getAndSet(disabled) == disabled) return
        audioRenderers.values.forEach { it.onOutputDisabledChanged(disabled) }
    }

    fun isAudioOutputDisabled(): Boolean = audioOutputDisabled.get()

    fun setFirstVideoFrameListener(listener: (() -> Unit)?) {
        firstVideoFrameListener = listener
    }

    fun setVideoStreamDimensionsListener(listener: ((Int, Int) -> Unit)?) {
        videoStreamDimensionsListener = listener
    }

    fun setVideoRenderStatusListener(listener: ((VideoRenderDiagnostics) -> Unit)?) {
        videoRenderStatusListener = listener
    }

    fun setVideoSyncEnabled(enabled: Boolean) {
        val delayMillis = if (enabled) DEFAULT_PACING_DELAY_MILLIS else 0
        videoPacingDelayMillis = delayMillis
        val delayNanos = delayMillis * 1_000_000L
        synchronized(videoOwnershipLock) {
            videoDecoders[MAIN_SCREEN_TYPE]?.setVideoSyncEnabled(enabled, delayNanos)
        }
    }

    fun setVideoFrameRate(fps: Int) {
        mainVideoFrameRate = fps.coerceAtLeast(0)
        synchronized(videoOwnershipLock) {
            videoDecoders[MAIN_SCREEN_TYPE]?.setVideoFrameRate(mainVideoFrameRate)
        }
    }

    /**
     * Attaches or detaches the main TV [Surface] and updates the target decode dimensions.
     */
    fun setMainSurface(surface: Surface?, width: Int = videoWidth, height: Int = videoHeight) {
        if (width > 0) videoWidth = width
        if (height > 0) videoHeight = height
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            defaultSurface = surface
            if (surface != null) {
                surfaces[MAIN_SCREEN_TYPE] = surface
            } else {
                surfaces.remove(MAIN_SCREEN_TYPE)
            }
            videoDecoders[MAIN_SCREEN_TYPE]?.setSurface(surface)
        }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try {
                    videoRecoveryHandlers[type]?.invoke()
                } catch (error: Exception) {
                    Log.w(TAG, "Video keyframe request failed", error)
                } finally {
                    recoveryPending.set(false)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recoveryPending.set(false)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. With [parkMain] the main-screen decoder moves to an offscreen consumer it owns
     * and keeps its state; other decoders are released. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            surfaces.entries.removeIf { it.value === surface }
            if (defaultSurface === surface) defaultSurface = null
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            closingVideoDecoders.removeIf { it.exited }
            closingVideoDecoders.toList().forEach { closing ->
                steps += { closing.awaitExitUntil(deadline + detachGraceNanos) }
            }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(
        type: Int,
        decoder: HardwareVideoDecoder,
        request: SurfaceDetachRequest,
        deadline: Long,
    ): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w(TAG, "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: HardwareVideoDecoder) {
        synchronized(videoOwnershipLock) {
            if (videoDecoders[type] !== decoder && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: HardwareVideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            videoDecoders.entries.removeIf { it.value === decoder }
            if (videoClosed && videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else {
                emptyList()
            }
        }
        listeners.forEach { runCatching(it) }
    }

    /** True when this sink paces main-screen frames (smooth video). */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0

    /** False until the main screen's first video frame. */
    @Volatile var mainVideoReceived = false
        private set

    /** Running totals of the main screen's decoder for a live FPS counter; null while it has none. */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[MAIN_SCREEN_TYPE]?.liveCounters()

    /** Samples the current live video FPS and decode latency from the main screen decoder. */
    fun liveVideoStats(nowNanos: Long = System.nanoTime()): LiveVideoReading? =
        liveVideoMeter.update(liveVideoCounters(), nowNanos)

    /** Snapshot of the main screen decoder's current render diagnostics. */
    fun videoRenderSnapshot(): VideoRenderDiagnostics? =
        videoDecoders[MAIN_SCREEN_TYPE]?.diagnosticsSnapshot()

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    fun refreshPicture(type: Int = MAIN_SCREEN_TYPE) {
        videoDecoders[type]?.refreshPicture()
    }

    /** Resets the video decode pipeline and requests a fresh keyframe from the iPhone. */
    fun resetVideoPipeline() {
        synchronized(videoOwnershipLock) {
            videoDecoders.values.forEach { it.resync() }
        }
        requestVideoRecovery(MAIN_SCREEN_TYPE)
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            videoDecoder(type).configure(codec, codecData)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos)
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(this) {
            audioRenderers.remove(id)?.close()
        }
        updateMediaAudio(id, false)
    }

    override fun onAudioDuck(ducked: Boolean) {
        // Navigation prompt audio overlays media on TV without car-bus attenuation hacks.
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        try {
            if (config.audioType == TELEPHONY_AUDIO_TYPE) enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                VoiceMicRecorder(
                    config = config,
                    onDiagnostic = onAudioDiagnostic,
                    audioManager = audioManager,
                )
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            val before = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i(TAG, "audio mode $before -> ${manager.mode} for telephony stream=$id")
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$before now=${manager.mode} stream=$id")
            }
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            val line = try {
                manager.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=${AudioManager.MODE_NORMAL} now=${manager.mode} stream=$active"
            } catch (error: RuntimeException) {
                Log.w(TAG, "could not restore audio mode", error)
                val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
                "Audio: mode restore failed requested=${AudioManager.MODE_NORMAL} now=$currentMode" +
                    " error=${error.javaClass.simpleName}"
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    override fun close() {
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioFocusCoordinator.close()
        audioRenderers.values.forEach(PcmAudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) {
            mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() }
        }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(VoiceMicRecorder::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    /** Alias for [close] to release all active video decoders, audio tracks, and microphone uplinks. */
    fun release() = close()

    private fun videoDecoder(type: Int): HardwareVideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    private fun newVideoDecoder(
        type: Int,
        surface: Surface?,
        statsLabel: String? = null,
        startImmediately: Boolean = true,
    ) = HardwareVideoDecoder(
        streamType = type,
        surface = surface,
        width = videoWidth,
        height = videoHeight,
        preferSoftwareHevcDecoder = preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        pacingDelayNanos = if (type == MAIN_SCREEN_TYPE && statsLabel == null) {
            videoPacingDelayMillis * 1_000_000L
        } else {
            0L
        },
        onExit = ::onVideoDecoderExit,
        operatingRate = videoOperatingRate(type, statsLabel, mainVideoFrameRate),
        vendorLowLatency = vendorLowLatencyDecoder,
        dedicatedOutputThread = vendorLowLatencyDecoder,
        framesAtOnce = type == MAIN_SCREEN_TYPE && statsLabel == null && videoPacingDelayMillis > 0,
        mtkDecoderTuningEnabled = mtkDecoderTuningEnabled && type == MAIN_SCREEN_TYPE && statsLabel == null,
        onFirstFrameRendered = {
            if (type == MAIN_SCREEN_TYPE) firstVideoFrameListener?.invoke()
        },
        onStreamDimensionsChanged = { width, height ->
            if (type == MAIN_SCREEN_TYPE) videoStreamDimensionsListener?.invoke(width, height)
        },
        onRenderStatusChanged = { diagnostics ->
            if (type == MAIN_SCREEN_TYPE) videoRenderStatusListener?.invoke(diagnostics)
        },
    ).also { if (startImmediately) it.start() }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): PcmAudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return PcmAudioRenderer(
            format = format,
            audioFocusEnabled = audioFocusEnabled,
            audioFocusCoordinator = audioFocusCoordinator,
            mediaBufferMillis = mediaBufferMillis,
            report = onAudioDiagnostic,
            outputDisabled = audioOutputDisabled::get,
        ).also {
            audioRenderers[id] = it
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val TELEPHONY_AUDIO_TYPE = "telephony"
        const val DEFAULT_PACING_DELAY_MILLIS = 34
    }
}
