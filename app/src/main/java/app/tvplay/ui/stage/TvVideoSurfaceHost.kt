package app.tvplay.ui.stage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import app.tvplay.engine.av.ViewportVideoLayout
import app.tvplay.remote.CarPlaySurfaceBounds
import app.tvplay.remote.VideoDecoderInfo
import app.tvplay.remote.textureUnsafeDecoder
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

typealias VideoSurfaceController = TvVideoSurfaceHost

data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val windowWidth: Int,
    val windowHeight: Int,
)

/**
 * Manages projection video layout fitting, aspect ratio transforms, and `TextureView` / `SurfaceView`
 * fallback helpers for [TvStageActivity].
 */
object TvVideoSurfaceHost {
    fun computeContentRect(
        display: CarPlaySessionDisplay?,
        viewWidth: Int,
        viewHeight: Int,
    ): ViewportVideoLayout {
        if (display == null) {
            return ViewportVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        return ViewportVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    fun applyVideoLayout(
        videoView: View?,
        fallbackVideoView: SurfaceView?,
        currentFallbackBounds: CarPlaySurfaceBounds?,
        display: CarPlaySessionDisplay?,
        viewWidth: Int,
        viewHeight: Int,
    ): CarPlaySurfaceBounds? {
        val view = videoView ?: return currentFallbackBounds
        if (viewWidth <= 0 || viewHeight <= 0) return currentFallbackBounds
        val content = computeContentRect(display, viewWidth, viewHeight)
        if (fallbackVideoView != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (currentFallbackBounds != bounds) {
                fallbackVideoView.layoutParams = FrameLayout.LayoutParams(
                    bounds.width,
                    bounds.height,
                    Gravity.TOP or Gravity.LEFT,
                ).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
            return bounds
        }
        (view as? TextureView)?.setTransform(
            Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            },
        )
        return currentFallbackBounds
    }

    fun findTextureUnsafeVideoDecoder(hevcEnabled: Boolean): String? = try {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .map { VideoDecoderInfo(it.name, it.supportedTypes.toList()) }
        val mimes = listOfNotNull(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC.takeIf { hevcEnabled },
        )
        textureUnsafeDecoder(decoders, mimes)
    } catch (_: RuntimeException) {
        null
    }

    fun captureLastSurfacePicture(
        surfaceView: SurfaceView?,
        bounds: CarPlaySurfaceBounds?,
        copyLooper: Looper,
        waitMillis: Long,
    ): Bitmap? {
        if (surfaceView == null || bounds == null || !surfaceView.holder.surface.isValid) return null
        val picture = Bitmap.createBitmap(
            (bounds.width / 2).coerceAtLeast(1),
            (bounds.height / 2).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val done = CountDownLatch(1)
        val copied = AtomicBoolean(false)
        val requested = runCatching {
            PixelCopy.request(
                surfaceView,
                picture,
                { result ->
                    copied.set(result == PixelCopy.SUCCESS)
                    done.countDown()
                },
                Handler(copyLooper),
            )
        }.isSuccess
        if (requested) done.await(waitMillis, TimeUnit.MILLISECONDS)
        return picture.takeIf { copied.get() }
    }
}

/**
 * Tracks the active [TvStageActivity] fullscreen CarPlay projection window.
 */
object CarPlayScreen {
    @Volatile private var open: WeakReference<TvStageActivity>? = null

    fun target(@Suppress("UNUSED_PARAMETER") context: Context): Class<out TvStageActivity> =
        TvStageActivity::class.java

    fun intent(context: Context): Intent = Intent(context, TvStageActivity::class.java)

    /** The open CarPlay screen, if it is not closing. */
    fun current(): TvStageActivity? = open?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun forwarded(screen: TvStageActivity): Boolean {
        open = WeakReference(screen)
        return false
    }

    fun destroyed(screen: TvStageActivity) {
        if (open?.get() === screen) open = null
    }

    /** One diagnostic-report line: where the open screen runs. */
    fun report(@Suppress("UNUSED_PARAMETER") context: Context): String = buildString {
        val screen = current()
        if (screen == null) {
            append("open=none")
        } else {
            append("open=${screen.javaClass.simpleName} task=${screen.taskId}")
            append(" taskRoot=${runCatching { screen.isTaskRoot }.getOrNull()}")
            append(" multiWindow=${screen.isInMultiWindowMode}")
            append(" display=${screen.window?.decorView?.display?.displayId}")
        }
    }
}
