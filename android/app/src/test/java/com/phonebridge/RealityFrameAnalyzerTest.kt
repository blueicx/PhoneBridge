package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

class RealityFrameAnalyzerTest {
    @Test
    fun frameLeaseClosesOnSuccessAndWhenAnalysisThrows() {
        val successFrame = FakeFrame()
        assertEquals("ok", RealityFrameAnalyzer.withFrameClosed(successFrame) { "ok" })
        assertTrue(successFrame.closed)

        val failingFrame = FakeFrame()
        runCatching {
            RealityFrameAnalyzer.withFrameClosed(failingFrame) { error("bad frame") }
        }
        assertTrue(failingFrame.closed)
    }

    @Test
    fun localLumaSamplingAndNv21CopyRespectPlaneStrides() {
        val y = RealityImagePlane(byteArrayOf(10, 20, 0, 0, 30, 40), rowStride = 4, pixelStride = 1)
        val u = RealityImagePlane(byteArrayOf(80), rowStride = 1, pixelStride = 1)
        val v = RealityImagePlane(byteArrayOf(120), rowStride = 1, pixelStride = 1)
        val planes = RealityImagePlanes(width = 2, height = 2, y = y, u = u, v = v)

        val signal = RealityFrameAnalyzer.sampleLuminance(planes)
        assertEquals(25f / 255f, signal?.averageLuma ?: 0f, .001f)
        assertEquals(listOf(10, 20, 30, 40, 120, 80), RealityFrameAnalyzer.toNv21(planes)?.map { it.toInt() and 0xff })
    }

    @Test
    fun optedInRemoteFrameIsDownsampledBeforeNv21Allocation() {
        val width = 1280
        val height = 720
        val planes = RealityImagePlanes(
            width = width,
            height = height,
            y = RealityImagePlane(ByteArray(width * height), rowStride = width, pixelStride = 1),
            u = RealityImagePlane(ByteArray(width * height / 4), rowStride = width / 2, pixelStride = 1),
            v = RealityImagePlane(ByteArray(width * height / 4), rowStride = width / 2, pixelStride = 1),
        )

        val frame = RealityFrameAnalyzer.toBoundedNv21(planes)
        assertEquals(640, frame?.width)
        assertEquals(360, frame?.height)
        assertEquals(640 * 360 * 3 / 2, frame?.data?.size)
    }

    private class FakeFrame : Closeable {
        var closed = false
        override fun close() { closed = true }
    }
}
