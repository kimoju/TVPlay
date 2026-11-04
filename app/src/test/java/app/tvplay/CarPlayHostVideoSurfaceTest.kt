package app.tvplay

import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import app.tvplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostVideoSurfaceTest {
    private class AttachedTexture(activity: TvPlayProjectionActivity, private val accelerated: Boolean) : TextureView(activity) {
        override fun isAttachedToWindow() = true
        override fun isHardwareAccelerated() = accelerated
    }

    @Test fun acceleratedWindowKeepsItsTextureAndTheOverlayOrder() {
        val activity = Robolectric.buildActivity(TvPlayProjectionActivity::class.java).get()
        val texture = AttachedTexture(activity, true)
        val root = FrameLayout(activity).apply { addView(texture) }
        set(activity, "videoView", texture)
        observe(activity, texture)
        val probe = field(activity, "videoSurfaceProbe") as ViewTreeObserver.OnPreDrawListener
        assertTrue(probe.onPreDraw())
        assertSame(texture, field(activity, "videoView"))
        assertSame(texture, root.getChildAt(0))
        assertNull(field(activity, "fallbackVideoView"))
        assertNull(field(activity, "videoSurfaceProbe"))
    }

    @Test fun softwareWindowReplacesTextureWithoutShrinkingTheNegotiatedViewport() {
        val activity = Robolectric.buildActivity(TvPlayProjectionActivity::class.java).get()
        val texture = AttachedTexture(activity, false)
        val root = FrameLayout(activity).apply {
            addView(texture, FrameLayout.LayoutParams(-1, -1))
        }
        set(activity, "videoView", texture)
        set(activity, "sessionDisplay", CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, 1920, 990))
        observe(activity, texture)
        val probe = field(activity, "videoSurfaceProbe") as ViewTreeObserver.OnPreDrawListener
        assertFalse(probe.onPreDraw())
        val viewport = field(activity, "videoView") as FrameLayout
        val fallback = field(activity, "fallbackVideoView") as SurfaceView
        assertNull(texture.parent)
        assertSame(viewport, root.getChildAt(0))
        assertSame(fallback, viewport.getChildAt(0))
        viewport.layout(0, 0, 1920, 942)
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        val bounds = CarPlaySurfaceBounds.from(content)
        val params = fallback.layoutParams as FrameLayout.LayoutParams
        assertEquals(bounds.width, params.width)
        assertEquals(bounds.height, params.height)
        assertEquals(bounds.left, params.leftMargin)
        assertEquals(bounds.top, params.topMargin)
        assertEquals(1920, viewport.width)
        assertEquals(942, viewport.height)
        assertNull(field(activity, "pictureBinding"))
    }

    private fun observe(activity: TvPlayProjectionActivity, texture: TextureView) {
        activity.javaClass.getDeclaredMethod("observeVideoWindow", TextureView::class.java)
            .apply { isAccessible = true }.invoke(activity, texture)
    }

    private fun field(activity: TvPlayProjectionActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(activity: TvPlayProjectionActivity, name: String, value: Any) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
