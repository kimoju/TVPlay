package app.tvplay.projection

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
import app.tvplay.CarPlaySessionDisplay
import app.tvplay.CarPlaySurfaceBounds
import app.tvplay.VideoDecoderInfo
import app.tvplay.media.CarPlayVideoLayout
import app.tvplay.textureUnsafeDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages projection video layout fitting, aspect ratio transforms, and `TextureView` / `SurfaceView`
 * fallback helpers for [app.tvplay.TvPlayProjectionActivity].
 */
internal object VideoSurfaceController {
    fun computeContentRect(
        display: CarPlaySessionDisplay?,
        viewWidth: Int,
        viewHeight: Int,
    ): CarPlayVideoLayout {
        if (display == null) {
            return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
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
