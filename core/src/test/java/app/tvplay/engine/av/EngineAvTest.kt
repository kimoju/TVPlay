package app.tvplay.engine.av

import app.tvplay.engine.protocol.rtsp.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAvTest {

    @Test
    fun toAnnexBInPlaceAndIsRandomAccessAndAdtsSliceOperateWithoutExtraCopies() {
        val rawAvcc = byteArrayOf(
            0, 0, 0, 4, 0x67, 0x42, 0x00, 0x1e, // SPS
            0, 0, 0, 5, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0xa0.toByte(), // IDR slice
        )
        val expectedAnnexB = MediaCodecSupport.toAnnexB(rawAvcc.copyOf())
        assertTrue(MediaCodecSupport.toAnnexBInPlace(rawAvcc))
        assertArrayEquals(expectedAnnexB, rawAvcc)
        assertTrue(MediaCodecSupport.isRandomAccess(rawAvcc, VideoCodec.H264))

        val nonIdr = byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0x10, 0x22, 0x33, 0x44)
        assertFalse(MediaCodecSupport.isRandomAccess(nonIdr, VideoCodec.H264))

        val rtpWithAac = ByteArray(12 + 6) { (it + 1).toByte() }
        val adtsFromCopy = MediaCodecSupport.adtsFrame(rtpWithAac.copyOfRange(12, rtpWithAac.size), 48_000, 2)
        val adtsFromSlice = MediaCodecSupport.adtsFrame(rtpWithAac, 12, 6, 48_000, 2)
        assertArrayEquals(adtsFromCopy, adtsFromSlice)
        val adtsHeader = ByteArray(7)
        MediaCodecSupport.writeAdtsHeader(adtsHeader, 6, 48_000, 2)
        assertArrayEquals(adtsFromCopy.copyOfRange(0, 7), adtsHeader)

        // Already-Annex-B IDR frame whose bytes 5..8 coincidentally equal remaining length (2) must not be corrupted
        val annexBWithCoincidentalLengthAtOffset5 = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x65,
            0x00, 0x00, 0x00, 0x02,
            0xaa.toByte(), 0xbb.toByte(),
        )
        val expectedUnchanged = annexBWithCoincidentalLengthAtOffset5.copyOf()
        assertTrue(MediaCodecSupport.toAnnexBInPlace(annexBWithCoincidentalLengthAtOffset5))
        assertArrayEquals(expectedUnchanged, annexBWithCoincidentalLengthAtOffset5)
    }

    @Test
    fun videoDecodeQueueTracksFrameCountAndBytesInO1AndTriggersResyncOnOverflow() {
        val queue = VideoDecodeQueue(maxFrames = 3, maxBytes = 100)
        queue.offer(VideoJob.Frame(ByteArray(30), receivedNs = 10L))
        queue.offer(VideoJob.Frame(ByteArray(30), receivedNs = 20L))
        assertEquals(2, queue.backlogAfterCurrent().pendingFrames)
        assertEquals(20L, queue.backlogAfterCurrent().newestPendingReceivedNs)

        val first = queue.poll(0)
        assertTrue(first is VideoJob.Frame)
        assertEquals(1, queue.backlogAfterCurrent().pendingFrames)

        // Offering 3 more frames exceeds maxFrames=3 -> discards older frames and inserts Resync + new frame
        queue.offer(VideoJob.Frame(ByteArray(30), receivedNs = 30L))
        queue.offer(VideoJob.Frame(ByteArray(30), receivedNs = 40L))
        queue.offer(VideoJob.Frame(ByteArray(30), receivedNs = 50L))
        assertEquals(VideoJob.Resync, queue.poll(0))
        val afterResync = queue.poll(0) as? VideoJob.Frame
        assertEquals(50L, afterResync?.receivedNs)

        // offerFirst preempts queued frames and requestResync discards stale frames before Resync
        queue.offer(VideoJob.Frame(ByteArray(20), receivedNs = 60L))
        queue.offer(VideoJob.Frame(ByteArray(20), receivedNs = 70L))
        val detach = SurfaceDetachRequest(Any(), park = false)
        queue.offerFirst(VideoJob.DetachSurface(detach))
        val preempted = queue.poll(0)
        assertTrue(preempted is VideoJob.DetachSurface)
        queue.requestResync()
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(0, queue.backlogAfterCurrent().pendingFrames)
    }

    @Test
    fun displayMetricMathSnapsFpsAndPhysicalMillimeters() {
        assertEquals(30, DisplayMetricMath.sanitizeFps(15))
        assertEquals(60, DisplayMetricMath.sanitizeFps(75))
        assertEquals(45, DisplayMetricMath.sanitizeFps(44))
        assertEquals(200, DisplayMetricMath.sanitizeWidthPhysicalMm(202))
        val size = DisplayMetricMath.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 320,
            basis = PhysicalSizeBasis.WIDTH,
        )
        assertEquals(320, size.widthMm)
        assertEquals(180, size.heightMm)
    }

    @Test
    fun displayScaleAndUiDensityKeepDimensionsEvenAndWithin4KUhdCap() {
        val base = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080)
        val scaled75 = DisplayScaleMath.applyPercent(base, 75)
        assertEquals(0, scaled75.widthPixels % 2)
        assertEquals(0, scaled75.heightPixels % 2)
        assertEquals(1440, scaled75.widthPixels)
        assertEquals(810, scaled75.heightPixels)

        val densitySmaller = UiDensityScale.apply(base, 75)
        assertTrue(densitySmaller.widthPixels > base.widthPixels)
        assertEquals(0, densitySmaller.widthPixels % 2)

        val uhd = TvDisplaySpec(widthPixels = 3840, heightPixels = 2160)
        val uhdSmaller = UiDensityScale.apply(uhd, 75)
        assertEquals(uhd.widthPixels, uhdSmaller.widthPixels)
        assertEquals(uhd.heightPixels, uhdSmaller.heightPixels)
    }

    @Test
    fun safeAreaClampsToDisplayBoundsAndDerivesSymmetricInsets() {
        val rect = DisplaySafeAreaRect(left = 40, top = 20, right = 1880, bottom = 1060)
        val insets = DisplaySafeAreaMath.toInsets(rect, 1920, 1080, 1920, 1080)
        assertEquals(40, insets.left)
        assertEquals(40, insets.right)
        assertEquals(20, insets.top)
        assertEquals(20, insets.bottom)
    }
}
