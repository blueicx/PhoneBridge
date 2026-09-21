package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityAnchorTest {
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
        val ar = ArCoreAnchorProvider(available = true)
        val selector = RealityAnchorSelector(ar, fallback)
        val frame = RealityFrame(1L, 0f, 0f, 0f, 20f, "mid", 800, 480, 30f, 43f)
        val pose = selector.update(frame)
        assertEquals("canvas", pose.source)
        assertFalse(selector.usingArCore)

        val coolFrame = frame.copy(temperatureCelsius = 30f, fps = 30f)
        assertEquals("arcore", selector.update(coolFrame).source)
        assertTrue(selector.usingArCore)
    }
}
