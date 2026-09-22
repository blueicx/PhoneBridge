package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityDeepeningTest {
    @Test fun localCueAnalysisIsDeterministicAndPrivacyNeutral() {
        val brightEdges = RealityCueAnalyzer.analyze(RealityImageSignal(0.88f, 0.42f))
        assertTrue("light" in brightEdges.types)
        assertTrue("object" in brightEdges.types)
        assertEquals(brightEdges, RealityCueAnalyzer.analyze(RealityImageSignal(0.88f, 0.42f)))
        assertFalse(RealityCueAnalyzer.shouldUploadOriginalFrame(defaultAllowUpload = false))
        assertTrue(RealityCueAnalyzer.shouldUploadOriginalFrame(defaultAllowUpload = true))
    }

    @Test fun captureControllerKeepsCameraOwnershipExclusive() {
        val controller = RealityCaptureController()
        val first = controller.enterReality(cameraAlreadyRunning = false)
        assertEquals(RealityCaptureSurface.REALITY, first.surface)
        assertTrue(first.cameraOwned)
        val shared = controller.enterReality(cameraAlreadyRunning = true)
        assertFalse(shared.cameraOwned)
        assertTrue(shared.keepCameraOnExit)
        val exited = controller.exitReality()
        assertEquals(RealityCaptureSurface.COMPANION, exited.surface)
        assertFalse(exited.cameraOwned)
    }

    @Test fun eventProjectionUsesNearestUnexpiredEventForEachClue() {
        val coordinator = RealityExplorationCoordinator(now = { 1000L })
        coordinator.setRegion("cell:1:2")
        coordinator.replaceEvents(
            listOf(
                RealityEvent("far", "cell:1:2", "object", "object", 240, "far", 2000, "a"),
                RealityEvent("near", "cell:1:2", "object", "object", 30, "near", 2000, "b"),
                RealityEvent("expired", "cell:1:2", "light", "light", 10, "near", 900, "c"),
            )
        )
        assertEquals("near", coordinator.eventForClue("object")?.id)
        assertEquals(null, coordinator.eventForClue("light"))
    }
}
