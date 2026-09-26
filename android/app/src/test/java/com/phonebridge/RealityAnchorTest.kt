package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityAnchorTest {
    private val trackedPose = AnchorPose(120f, 180f, 1f, true, "arcore-session")

    @Test
    fun canvasAnchorMapsBearingAndDistanceDeterministically() {
        val provider = CanvasSensorAnchorProvider()
        val pose = provider.update(
            RealityFrame(
                timestampMs = 1000L,
                bearingDegrees = 10f,
                pitchDegrees = 0f,
                rollDegrees = 0f,
                targetBearingDegrees = 40f,
                distanceBand = "near",
                width = 1000,
                height = 600,
                fps = 30f,
                temperatureCelsius = 30f
            )
        )
        assertEquals(750f, pose.x, 0.01f)
        assertEquals(300f, pose.y, 0.01f)
        assertEquals(1f, pose.scale, 0.01f)
        assertTrue(pose.visible)
        assertEquals("canvas", pose.source)
    }

    @Test
    fun selectorFallsBackWhenArCoreUnavailableOrThermalBudgetIsExceeded() {
        val fallback = CanvasSensorAnchorProvider()
        val ar = ArCoreAnchorProvider()
        val selector = RealityAnchorSelector(ar, fallback)
        val frame = RealityFrame(1L, 0f, 0f, 0f, 20f, "mid", 800, 480, 30f, 43f)
        val pose = selector.update(frame)
        assertEquals("canvas", pose.source)
        assertFalse(selector.usingArCore)

        val coolFrame = frame.copy(temperatureCelsius = 30f, fps = 30f)
        assertEquals("canvas", selector.update(coolFrame).source)
        assertFalse(selector.usingArCore)
    }

    @Test
    fun selectorUsesOnlyARealArCorePoseSourceAndNeverRelabelsCanvas() {
        val fallback = CanvasSensorAnchorProvider()
        val ar = ArCoreAnchorProvider(ArCorePoseSource { frame ->
            fallback.update(frame).copy(source = "arcore-session")
        })
        val selector = RealityAnchorSelector(ar, fallback)
        val pose = selector.update(RealityFrame(1L, 0f, 0f, 0f, 20f, "mid", 800, 480, 30f, 30f))
        assertEquals("arcore-session", pose.source)
        assertTrue(selector.usingArCore)
    }

    @Test
    fun selectorDoesNotFallBackForAShortFrameRateSpike() {
        val selector = RealityAnchorSelector(ArCoreAnchorProvider { trackedPose }, CanvasSensorAnchorProvider())
        listOf(
            realityFrame(0L, 30f),
            realityFrame(500L, 10f),
            realityFrame(1_000L, 30f),
            realityFrame(1_500L, 30f),
            realityFrame(2_000L, 30f),
        ).forEach { frame ->
            assertEquals("arcore-session", selector.update(frame).source)
            assertTrue(selector.usingArCore)
        }

        assertTrue(selector.usingArCore)
    }

    @Test
    fun selectorFallsBackOnlyAfterThreeCompleteLowFrameRateWindows() {
        val selector = RealityAnchorSelector(ArCoreAnchorProvider { trackedPose }, CanvasSensorAnchorProvider())
        selector.update(realityFrame(0L, 30f))
        for (timestamp in 100L..5_900L step 100L) {
            selector.update(realityFrame(timestamp, 20f))
            if (timestamp < 6_000L) assertTrue("fallback before three low windows at $timestamp", selector.usingArCore)
        }
        selector.update(realityFrame(6_000L, 20f))
        assertFalse(selector.usingArCore)
    }

    @Test
    fun thermalFallbackStaysLatchedAfterTheDeviceCools() {
        val selector = RealityAnchorSelector(ArCoreAnchorProvider { trackedPose }, CanvasSensorAnchorProvider())
        assertEquals("canvas", selector.update(realityFrame(0L, 30f).copy(temperatureCelsius = 40f)).source)
        assertEquals("canvas", selector.update(realityFrame(61_000L, 30f).copy(temperatureCelsius = 37f)).source)
        assertFalse(selector.usingArCore)
    }

    private fun realityFrame(timestampMs: Long, fps: Float) = RealityFrame(
        timestampMs = timestampMs,
        bearingDegrees = 0f,
        pitchDegrees = 0f,
        rollDegrees = 0f,
        targetBearingDegrees = 0f,
        distanceBand = "mid",
        width = 800,
        height = 480,
        fps = fps,
        temperatureCelsius = 30f,
    )
}
