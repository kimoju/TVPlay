package app.tvplay.remote

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import app.tvplay.engine.av.ViewportVideoLayout
import java.io.Closeable
import kotlin.math.roundToInt

typealias CarPlayPicture = TvVideoColorFilter

/**
 * Pixel adjustments for CarPlay video textures, not Android or OEM UI.
 */
object TvVideoColorFilter {
    const val BRIGHTNESS = "brightness"
    const val CONTRAST = "contrast"
    const val SATURATION = "saturation"
    const val WARMTH = "warmth"
    val keys = listOf(BRIGHTNESS, CONTRAST, SATURATION, WARMTH)
    private val previewListeners = mutableSetOf<() -> Unit>()
    private var originalPreview = false

    /** Transient A/B comparison; never written to preferences or left on after panel closure. */
    fun showOriginal(show: Boolean) {
        originalPreview = show
        previewListeners.toList().forEach { it() }
    }

    class Binding(private val view: TextureView) : Closeable {
        private var closed = false
        private val prefs = preferences(view.context)
        private val update: () -> Unit = { if (!closed) apply(view, prefs) }
        private val updateRunnable = Runnable { update() }
        private val handler = Handler(Looper.getMainLooper())
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            handler.removeCallbacks(updateRunnable)
            handler.post(updateRunnable)
        }

        init {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            previewListeners.add(update)
            update()
        }

        override fun close() {
            closed = true
            handler.removeCallbacks(updateRunnable)
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            previewListeners.remove(update)
        }
    }

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("carplay_picture", Context.MODE_PRIVATE)

    fun reset(prefs: SharedPreferences) {
        // clear() does not notify listeners on Android 10. Explicit values update live views.
        val editor = prefs.edit()
        keys.forEach { editor.putInt(it, defaultValue(it)) }
        editor.apply()
    }

    fun defaultValue(key: String): Int = if (key == CONTRAST || key == SATURATION) 100 else 0

    fun range(key: String): IntRange = when (key) {
        BRIGHTNESS -> -50..50
        WARMTH -> -100..100
        else -> 0..200
    }

    fun value(prefs: SharedPreferences, key: String): Int =
        prefs.getInt(key, defaultValue(key)).coerceIn(range(key))

    fun matrix(prefs: SharedPreferences): ColorMatrix {
        val contrast = value(prefs, CONTRAST) / 100f
        val brightness = value(prefs, BRIGHTNESS) * 255f / 100f
        val offset = 127.5f * (1f - contrast) + brightness
        val warmth = value(prefs, WARMTH) / 100f * 0.2f
        return ColorMatrix().apply {
            setSaturation(value(prefs, SATURATION) / 100f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            postConcat(ColorMatrix().apply { setScale(1f + warmth, 1f, 1f - warmth, 1f) })
        }
    }

    fun apply(view: TextureView, prefs: SharedPreferences) {
        val neutral = originalPreview || keys.all { value(prefs, it) == defaultValue(it) }
        view.setLayerPaint(
            if (neutral) {
                null
            } else {
                Paint().apply {
                    colorFilter = ColorMatrixColorFilter(matrix(prefs))
                }
            },
        )
    }
}

enum class CarPlayVideoSurfaceMode { TEXTURE, SURFACE }

/**
 * Video surface selection and decoder compatibility helpers for CarPlay projection.
 */
object CarPlayVideoSurface {
    fun mode(
        hardwareAccelerated: Boolean,
        smoothVideo: Boolean = false,
        directVideoOutput: Boolean = false,
        textureUnsafeDecoder: Boolean = false,
    ): CarPlayVideoSurfaceMode = carPlayVideoSurfaceMode(
        hardwareAccelerated = hardwareAccelerated,
        smoothVideo = smoothVideo,
        directVideoOutput = directVideoOutput,
        textureUnsafeDecoder = textureUnsafeDecoder,
    )

    fun findTextureUnsafeDecoder(decoders: List<VideoDecoderInfo>, mimes: List<String>): String? =
        textureUnsafeDecoder(decoders, mimes)

    fun boundsFrom(content: ViewportVideoLayout): CarPlaySurfaceBounds =
        CarPlaySurfaceBounds.from(content)

    fun smoothDelayMillis(fps: Int): Int = smoothVideoDelayMillis(fps)

    fun isLastPictureDone(
        rendered: Long,
        baseline: Long,
        elapsedMillis: Long,
        maxMillis: Long,
    ): Boolean = lastPictureDone(rendered, baseline, elapsedMillis, maxMillis)

    fun sessionMatchesView(sinkPaces: Boolean, viewSmoothVideo: Boolean): Boolean =
        backgroundSessionMatchesView(sinkPaces, viewSmoothVideo)
}

/**
 * Decide from the attached window, not the declared manifest flag or device model. Smooth video also
 * needs a SurfaceView: only the compositor honours the frame timestamps it releases with. Direct video
 * output (a setting) picks one too: frames go straight to the compositor instead of through the app's
 * own drawing, which can save about a refresh. So does a decoder whose pictures the app's drawing
 * cannot take as a texture ([textureUnsafeDecoder]).
 */
fun carPlayVideoSurfaceMode(
    hardwareAccelerated: Boolean,
    smoothVideo: Boolean = false,
    directVideoOutput: Boolean = false,
    textureUnsafeDecoder: Boolean = false,
): CarPlayVideoSurfaceMode =
    if (hardwareAccelerated && !smoothVideo && !directVideoOutput && !textureUnsafeDecoder) {
        CarPlayVideoSurfaceMode.TEXTURE
    } else {
        CarPlayVideoSurfaceMode.SURFACE
    }

/** An installed video decoder, in MediaCodecList order. */
data class VideoDecoderInfo(val name: String, val types: List<String>)

/**
 * The decoder MediaCodec.createDecoderByType picks for one of [mimes] (the first that lists it) when its
 * pictures cannot be shown through a TextureView, otherwise null. Emteria's Android 17 for Raspberry Pi 5
 * decodes with c2.ffmpeg.h264.decoder, which reports itself as hardware accelerated: the app's renderer
 * aborts with "Invalid GrBackendTexture" right after the first frame drawn as a texture, while a
 * SurfaceView shows the same pictures, composited by the system. Other FFmpeg-backed Codec2 decoders
 * share its output, so the family is matched by name.
 */
fun textureUnsafeDecoder(decoders: List<VideoDecoderInfo>, mimes: List<String>): String? =
    mimes.asSequence()
        .mapNotNull { mime ->
            decoders.firstOrNull { decoder ->
                decoder.types.any { it.equals(mime, ignoreCase = true) }
            }
        }
        .firstOrNull { it.name.startsWith(FFMPEG_CODEC2_PREFIX, ignoreCase = true) }
        ?.name

private const val FFMPEG_CODEC2_PREFIX = "c2.ffmpeg."

/** Integer compositor bounds retain overscan when a CarPlay view area crops the canvas. */
data class CarPlaySurfaceBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun from(content: ViewportVideoLayout): CarPlaySurfaceBounds {
            val left = content.left.roundToInt()
            val top = content.top.roundToInt()
            return CarPlaySurfaceBounds(
                left,
                top,
                ((content.left + content.width).roundToInt() - left).coerceAtLeast(1),
                ((content.top + content.height).roundToInt() - top).coerceAtLeast(1),
            )
        }
    }
}

/**
 * Smooth video's starting delay over the link's base delay; it then adjusts to the frames the decoder
 * releases.
 */
fun smoothVideoDelayMillis(fps: Int): Int = 40 + 3_000 / fps.coerceIn(30, 60)

/**
 * Whether the last picture shown over a SurfaceView that came back can go: a frame was rendered since it came
 * back ([rendered] moved on from [baseline], also from 0 on a new decoder), or [maxMillis] passed.
 */
fun lastPictureDone(rendered: Long, baseline: Long, elapsedMillis: Long, maxMillis: Long): Boolean =
    (rendered > 0 && rendered != baseline) || elapsedMillis > maxMillis

/**
 * A retained background session can be adopted only when its sink's pacing matches the video view this
 * host built: the sink fixes pacing at creation and the host picks its view once.
 */
fun backgroundSessionMatchesView(sinkPaces: Boolean, viewSmoothVideo: Boolean): Boolean =
    sinkPaces == viewSmoothVideo

/** Texture wrappers are ours to release; SurfaceHolder surfaces belong to the framework. */
class CarPlayVideoSurfaceOwner<T : Any>(
    private val detach: (T) -> Unit,
    private val release: (T) -> Unit,
) {
    var current: T? = null
        private set
    private var owned = false

    fun replace(surface: T, releaseOnDetach: Boolean) {
        if (current === surface) return
        clear()
        current = surface
        owned = releaseOnDetach
    }

    /** Ignore a late callback from a surface replaced by a newer output. */
    fun clear(surface: T) {
        if (current === surface) clear()
    }

    fun clear() {
        val previous = current ?: return
        val releasePrevious = owned
        current = null
        owned = false
        try {
            detach(previous)
        } finally {
            if (releasePrevious) release(previous)
        }
    }
}
