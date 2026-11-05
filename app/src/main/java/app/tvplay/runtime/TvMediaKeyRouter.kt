package app.tvplay.runtime

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.KeyEvent
import androidx.annotation.StringRes
import app.tvplay.R
import app.tvplay.config.TvPreferencesStore
import app.tvplay.engine.av.AudioFocusRequestCompat
import app.tvplay.engine.av.CarPlayNowPlaying
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.network.WirelessStartupPolicy
import app.tvplay.engine.protocol.rtsp.CarPlayMediaButton
import app.tvplay.engine.session.CarPlayController
import app.tvplay.engine.session.CarPlayStatus
import app.tvplay.engine.session.TvFailureReason
import app.tvplay.engine.session.TvNowPlayingMetadata
import app.tvplay.engine.session.TvSessionState
import app.tvplay.engine.session.TvWirelessMode
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

typealias CarPlayMediaKeys = TvMediaKeyRouter

/**
 * Publishes an active Android [MediaSession] while CarPlay is running so TV remote / HDMI-CEC /
 * Bluetooth media keys and system Now Playing surfaces route to CarPlay.
 */
object TvMediaKeyRouter {
    private var session: MediaSession? = null
    private var attachedController: java.lang.ref.WeakReference<CarPlayController>? = null
    private var audioFocus: AudioFocusRequest? = null
    private val silentTrack = AtomicReference<AudioTrack?>()
    private val silentTrackRunning = AtomicBoolean(false)
    private var silentThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue<Bitmap>()
    private var lastMetadata = CarPlayNowPlaying()

    data class MediaKeyAudioFocusSpec(
        val focusGain: Int,
        val usage: Int,
        val contentType: Int,
        val focusChangeListener: ((Int) -> Unit)?,
    )

    fun shouldTakeMediaKeyAudioFocus(disableMediaKeyAudioFocus: Boolean): Boolean =
        !disableMediaKeyAudioFocus

    internal fun mediaKeyAudioFocusRequest(
        audioFocusEnabled: Boolean,
        audioFocusAutoYield: Boolean,
        listener: (Int) -> Unit,
    ): MediaKeyAudioFocusSpec = MediaKeyAudioFocusSpec(
        AudioManager.AUDIOFOCUS_GAIN,
        AudioAttributes.USAGE_MEDIA,
        AudioAttributes.CONTENT_TYPE_MUSIC,
        if (audioFocusEnabled && audioFocusAutoYield) listener else null,
    )

    fun attach(
        context: Context,
        controller: CarPlayController,
        onAudioFocusChange: (Int) -> Unit = {},
    ) {
        if (!Looper.getMainLooper().isCurrentThread) {
            main.post { attach(context, controller, onAudioFocusChange) }
            return
        }
        release(context)
        val app = context.applicationContext
        val takeAudioFocus = shouldTakeMediaKeyAudioFocus(
            TvPreferencesStore.loadDisableMediaKeyAudioFocus(app),
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (takeAudioFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusCompat = mediaKeyAudioFocusRequest(
                audioFocusEnabled = TvPreferencesStore.loadAudioFocusEnabled(app),
                audioFocusAutoYield = TvPreferencesStore.loadAudioFocusAutoYield(app),
                listener = onAudioFocusChange,
            )
            audioFocus = AudioFocusRequest.Builder(focusCompat.focusGain)
                .setAudioAttributes(audioAttributes)
                .apply {
                    focusCompat.focusChangeListener?.let { forward ->
                        setOnAudioFocusChangeListener(
                            AudioManager.OnAudioFocusChangeListener(forward),
                            main,
                        )
                    }
                }
                .build().also {
                    app.getSystemService(AudioManager::class.java).requestAudioFocus(it)
                }
        }
        session = MediaSession(app, "TVPlayCarPlay").apply {
            setPlaybackToLocal(audioAttributes)
            @Suppress("DEPRECATION")
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                CarPlayMediaCallback(
                    sendMediaButton = { index -> controller.sendMedia(index) },
                    toggleMediaButton = { index -> controller.sendMedia(index) },
                ),
                main,
            )
            isActive = true
        }
        attachedController = java.lang.ref.WeakReference(controller)
        updateMetadata(controller.nowPlayingSnapshot().toAvNowPlaying())
        controller.nowPlayingListener = { metadata ->
            main.post { updateMetadata(metadata.toAvNowPlaying()) }
        }
        controller.artworkListener = { transferId, jpeg ->
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            main.post { applyArtworkResult(artworkQueue.onArtworkDecoded(transferId, bitmap)) }
        }
        if (takeAudioFocus) startSilentAudio()
    }

    fun release(context: Context) {
        if (!Looper.getMainLooper().isCurrentThread) {
            main.post { release(context) }
            return
        }
        attachedController?.get()?.let { controller ->
            controller.nowPlayingListener = null
            controller.artworkListener = null
        }
        attachedController = null
        stopSilentAudio()
        artworkQueue.clear()
        lastMetadata = CarPlayNowPlaying()
        session?.run {
            isActive = false
            release()
        }
        session = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocus?.let {
                context.applicationContext
                    .getSystemService(AudioManager::class.java)
                    .abandonAudioFocusRequest(it)
            }
        }
        audioFocus = null
    }

    internal fun playbackStateCode(playing: Boolean?): Int =
        if (playing == false) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING

    internal fun playbackActions(): Long =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND

    internal fun androidMetadata(
        metadata: CarPlayNowPlaying,
        putString: (String, String?) -> Unit,
        putLong: (String, Long) -> Unit,
    ) {
        putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
        putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
        putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
        putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, metadata.artist)
        putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, metadata.title)
        putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, metadata.artist)
        putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, metadata.album)
        putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.durationMillis ?: -1L)
    }

    internal fun metadataChanged(previous: CarPlayNowPlaying, current: CarPlayNowPlaying): Boolean =
        previous.title != current.title ||
            previous.album != current.album ||
            previous.artist != current.artist ||
            previous.durationMillis != current.durationMillis ||
            previous.artworkTransferId != current.artworkTransferId

    private fun updateMetadata(metadata: CarPlayNowPlaying) {
        val active = session ?: return
        val metadataDirty = metadataChanged(lastMetadata, metadata)
        lastMetadata = metadata
        val artwork = artworkQueue.onMetadata(metadata.artworkTransferId)
        if (metadataDirty) {
            active.setMetadata(buildMediaMetadata(metadata, artwork.currentArtwork))
        }
        active.setPlaybackState(
            PlaybackState.Builder()
                .setActions(playbackActions())
                .setState(
                    playbackStateCode(metadata.playing),
                    metadata.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (metadata.playing == false) 0f else 1f,
                )
                .build(),
        )
    }

    private fun applyArtworkResult(result: NowPlayingArtworkQueue.Result<Bitmap>) {
        val active = session ?: return
        if (result.currentArtwork != null) {
            active.setMetadata(buildMediaMetadata(lastMetadata, result.currentArtwork))
        }
    }

    private fun buildMediaMetadata(metadata: CarPlayNowPlaying, artwork: Bitmap?): MediaMetadata =
        MediaMetadata.Builder().apply {
            androidMetadata(metadata, ::putString, ::putLong)
            if (artwork != null && !artwork.isRecycled) {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            }
        }.build()

    private fun TvNowPlayingMetadata.toAvNowPlaying(): CarPlayNowPlaying = CarPlayNowPlaying(
        title = title,
        album = album,
        artist = artist,
        artworkTransferId = artworkFileTransferId,
        sourceApp = appName ?: appBundleId,
        playing = isPlaying ?: false,
    )

    /**
     * Plays a silent 8 kHz mono stream so platforms that inspect active [AudioTrack] playback
     * before routing physical media buttons treat TVPlay as the active media receiver.
     */
    private fun startSilentAudio() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(8_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val bufferBytes = AudioTrack.getMinBufferSize(
            8_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1_600)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        silentTrack.set(track)
        silentTrackRunning.set(true)
        silentThread = Thread({
            val silence = ByteArray(1_600)
            runCatching {
                track.play()
                while (silentTrackRunning.get() && !Thread.currentThread().isInterrupted) {
                    if (track.write(silence, 0, silence.size) <= 0) break
                }
            }
        }, "tvplay-media-key-claim").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSilentAudio() {
        silentTrackRunning.set(false)
        silentThread?.interrupt()
        silentThread = null
        silentTrack.getAndSet(null)?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }
}

/**
 * [MediaSession.Callback] that maps Android media key events and transport controls to
 * [CarPlayMediaButton] HID consumer reports.
 */
internal class CarPlayMediaCallback(
    private val sendMediaButton: (Int) -> Unit,
    private val toggleMediaButton: (Int) -> Unit = sendMediaButton,
    private val keyEventFromIntent: (Intent) -> KeyEvent? = ::extractMediaKeyEvent,
) : MediaSession.Callback() {
    override fun onPlay() = sendMediaButton(CarPlayMediaButton.PLAY)
    override fun onPause() = sendMediaButton(CarPlayMediaButton.PAUSE)
    override fun onSkipToNext() = sendMediaButton(CarPlayMediaButton.NEXT)
    override fun onSkipToPrevious() = sendMediaButton(CarPlayMediaButton.PREVIOUS)
    override fun onFastForward() = toggleMediaButton(CarPlayMediaButton.NEXT)
    override fun onRewind() = toggleMediaButton(CarPlayMediaButton.PREVIOUS)
    override fun onStop() = sendMediaButton(CarPlayMediaButton.PAUSE)

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        val event = keyEventFromIntent(mediaButtonIntent) ?: return false
        val button = CarPlayMediaButton.forKeyCode(event.keyCode)
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            sendMediaButton(button)
        }
        return true
    }
}

private fun extractMediaKeyEvent(intent: Intent): KeyEvent? =
    if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
    }

/**
 * Keeps Now Playing album art matched to the active iAP2 file transfer ID.
 *
 * Artwork is decoded off the main thread while track metadata can advance on the main thread.
 * Late decode results for an older transfer ID are returned in [Result.discard] so the caller
 * can recycle their bitmaps immediately.
 */
class NowPlayingArtworkQueue<Image : Any> {
    data class Result<Image : Any>(
        val currentArtwork: Image?,
        val discard: List<Image> = emptyList(),
    )

    private var currentTransferId: Int? = null
    private var currentArtwork: Image? = null

    @Synchronized
    fun onMetadata(transferId: Int?): Result<Image> {
        if (transferId == currentTransferId) {
            return Result(currentArtwork = currentArtwork)
        }
        val old = currentArtwork
        currentTransferId = transferId
        currentArtwork = null
        return Result(
            currentArtwork = null,
            discard = listOfNotNull(old),
        )
    }

    @Synchronized
    fun onArtworkDecoded(transferId: Int, image: Image?): Result<Image> {
        if (image == null) return Result(currentArtwork = null)
        if (transferId != currentTransferId) {
            return Result(currentArtwork = null, discard = listOf(image))
        }
        val replaced = currentArtwork?.takeIf { it !== image }
        currentArtwork = image
        return Result(
            currentArtwork = image,
            discard = listOfNotNull(replaced),
        )
    }

    @Synchronized
    fun clear(): List<Image> {
        val old = currentArtwork
        currentTransferId = null
        currentArtwork = null
        return listOfNotNull(old)
    }
}

/**
 * Suggested user action for CarPlay connection failures, never inferred from untrusted text.
 */
object ConnectionFailureGuidance {
    @StringRes
    fun message(
        failure: CarPlayStatus.Failed,
        wireless: Boolean,
        mode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
        wifiResetRequired: Boolean = failure.wifiResetRequired,
        startupFailure: WirelessStartupFailure? = failure.startupFailure,
    ): Int = message(
        detail = failure.message,
        wireless = wireless,
        mode = mode,
        wifiResetRequired = wifiResetRequired,
        startupFailure = startupFailure,
        knownCause = failure.knownCause,
    )

    @StringRes
    fun message(
        detail: String,
        wireless: Boolean,
        mode: TvWirelessMode = TvWirelessMode.LAN_WIFI,
        wifiResetRequired: Boolean = false,
        startupFailure: WirelessStartupFailure? = null,
        knownCause: TvFailureReason? = null,
    ): Int {
        if (wifiResetRequired) return R.string.connection_help_reset_wifi
        when (knownCause) {
            TvFailureReason.CHOSEN_IPHONE_NOT_PAIRED -> return R.string.connection_help_chosen_iphone_not_paired
            TvFailureReason.NO_PAIRED_IPHONE -> return R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c
            TvFailureReason.IPHONE_NOT_CHOSEN -> return R.string.connection_help_choose_iphone
            TvFailureReason.WIFI_DIRECT_CHANNEL_UNAVAILABLE -> return R.string.connection_help_wifi_direct_channel
            TvFailureReason.LAN_NETWORK_NOT_READY -> return R.string.connection_help_existing_wifi
            TvFailureReason.WIFI_DIRECT_START_FAILED -> return R.string.connection_help_wifi_direct
            null -> Unit
        }
        when (startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> return R.string.connection_help_wifi_direct_beside_hotspot
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return network(mode)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return network(mode)
            null -> Unit
        }

        val error = detail.lowercase(Locale.ROOT)
        if ("securityexception" in error || "lacks permission" in error) return R.string.connection_help_general
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error || "creategroup" in error) {
            return network(mode)
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return network(mode)
        }
        return R.string.connection_help_general
    }

    @StringRes
    fun stage(cause: TvFailureReason): Int = when (cause) {
        TvFailureReason.CHOSEN_IPHONE_NOT_PAIRED -> R.string.chosen_iphone_not_paired
        TvFailureReason.NO_PAIRED_IPHONE -> R.string.pair_your_iphone
        TvFailureReason.IPHONE_NOT_CHOSEN -> R.string.choose_your_iphone
        TvFailureReason.WIFI_DIRECT_CHANNEL_UNAVAILABLE -> R.string.wifi_direct_channel_unavailable
        TvFailureReason.LAN_NETWORK_NOT_READY -> R.string.hotspot_network_not_ready
        TvFailureReason.WIFI_DIRECT_START_FAILED -> R.string.hotspot_control_failed
    }

    @StringRes
    private fun network(mode: TvWirelessMode): Int = when (mode) {
        TvWirelessMode.LAN_WIFI -> R.string.connection_help_existing_wifi
        TvWirelessMode.WIFI_DIRECT -> R.string.connection_help_wifi_direct
    }
}

/**
 * Structured classification of the most recent connection failure for workbench/stage guidance.
 */
sealed interface ConnectionProblem {
    data class Cause(val cause: TvFailureReason) : ConnectionProblem
    data class Startup(val failure: WirelessStartupFailure) : ConnectionProblem
    data class UsbNotListed(val otherDevice: Boolean) : ConnectionProblem

    @StringRes
    fun line(): Int = when (this) {
        is Cause -> ConnectionFailureGuidance.stage(cause)
        is Startup -> when (failure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> R.string.hotspot_network_not_ready
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> R.string.first_tcp_timeout
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> R.string.wifi_direct_refused_beside_hotspot
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> R.string.connection_help_bluetooth_unusable
        }
        is UsbNotListed -> if (otherDevice) R.string.car_sees_usb_device_not_iphone else R.string.car_sees_no_usb_device
    }

    val token: String
        get() = when (this) {
            is Cause -> "cause:${cause.name}"
            is Startup -> "startup:${failure.name}"
            is UsbNotListed -> if (otherDevice) "usb:other" else "usb:none"
        }
}

/**
 * Persists the most recent actionable connection failure so the workbench and diagnostic report
 * can show what happened on the last attempt.
 */
object LastConnectionProblem {
    data class Record(val problem: ConnectionProblem, val atMillis: Long)

    private const val PREFS = "diplay_last_problem"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_AT = "at"

    fun follow(
        context: Context,
        status: CarPlayStatus,
        startupFailure: WirelessStartupFailure? = null,
    ) {
        val problem = when (status) {
            is CarPlayStatus.Failed -> status.knownCause?.let(ConnectionProblem::Cause)
                ?: (startupFailure ?: status.startupFailure)?.let(ConnectionProblem::Startup)
                ?: return clear(context)
            else -> return
        }
        save(context, problem)
    }

    fun follow(
        context: Context,
        state: TvSessionState,
        startupFailure: WirelessStartupFailure? = null,
    ) {
        val problem = when (state) {
            is TvSessionState.Failed -> state.cause?.let(ConnectionProblem::Cause)
                ?: startupFailure?.let(ConnectionProblem::Startup)
                ?: return clear(context)
            else -> return
        }
        save(context, problem)
    }

    fun save(context: Context, problem: ConnectionProblem, atMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_PROBLEM, problem.token)
            .putLong(KEY_AT, atMillis)
            .apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    fun load(context: Context): Record? {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_PROBLEM, null) ?: return null
        val problem = parse(token)
        val at = prefs.getLong(KEY_AT, -1)
        if (problem == null || at < 0) {
            clear(context)
            return null
        }
        return Record(problem, at)
    }

    fun homeLine(context: Context): String? {
        val record = load(context) ?: return null
        val time = DateUtils.formatSameDayTime(
            record.atMillis,
            System.currentTimeMillis(),
            DateFormat.SHORT,
            DateFormat.SHORT,
        )
        return context.getString(R.string.last_connection_attempt, time, context.getString(record.problem.line()))
    }

    fun report(context: Context): String {
        val record = load(context) ?: return "Last problem: none recorded"
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(record.atMillis))
        return "Last problem: ${english.getString(record.problem.line())} (${record.problem.token}) at $at"
    }

    private fun parse(token: String): ConnectionProblem? {
        val value = token.substringAfter(':')
        return when (token.substringBefore(':')) {
            "cause" -> TvFailureReason.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Cause)
            "startup" -> WirelessStartupFailure.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Startup)
            "usb" -> when (value) {
                "none" -> ConnectionProblem.UsbNotListed(false)
                "other" -> ConnectionProblem.UsbNotListed(true)
                else -> null
            }
            else -> null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Caps automatic wireless CarPlay startup retries with exponential backoff (`2s`, `4s`, `8s`, `16s`, `30s`)
 * and resets the retry count once a continuous video session runs for at least [WirelessStartupPolicy.STABLE_SESSION_MILLIS].
 */
class WirelessStartupRetryBudget {
    var retries = 0
        private set
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    fun nextDelayMillis(): Long? {
        if (retries >= WirelessStartupPolicy.MAX_STARTUP_RETRIES) return null
        return (2_000L * (1L shl retries++)).coerceAtMost(30_000L)
    }

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || nowMillis - firstFrameMillis < WirelessStartupPolicy.STABLE_SESSION_MILLIS) {
            return false
        }
        retries = 0
        return true
    }

    fun disconnected() {
        frameSession = null
    }

    fun manualRetry() {
        retries = 0
        disconnected()
    }
}
