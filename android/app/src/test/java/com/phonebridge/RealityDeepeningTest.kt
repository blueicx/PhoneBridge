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
        assertFalse("Reality entry must release CameraX before ARCore is attempted", first.cameraOwned)
        assertFalse("A camera started only for Reality must not be restored on exit", first.keepCameraOnExit)
        val exited = controller.exitReality()
        assertEquals(RealityCaptureSurface.COMPANION, exited.surface)
        assertFalse(exited.cameraOwned)
        assertFalse(exited.keepCameraOnExit)

        assertTrue(controller.claimCameraX())
        val shared = controller.enterReality(cameraAlreadyRunning = true)
        assertFalse(shared.cameraOwned)
        assertTrue(shared.keepCameraOnExit)
        val arEntry = controller.beginArCoreAttempt()!!
        assertTrue(controller.claimArCore(arEntry))
        val restored = controller.exitReality()
        assertTrue("Only a camera active before Reality entry is restored", restored.keepCameraOnExit)
        assertFalse(restored.cameraOwned)
    }

    @Test fun arCoreOwnerRejectsCameraXAndStaleCallbacksAndAllowsOnlyNormalFallback() {
        val controller = RealityCaptureController()
        val entry = controller.enterReality(cameraAlreadyRunning = false).entryId
        val attempt = controller.beginArCoreAttempt()!!
        assertTrue(controller.claimArCore(attempt))
        assertFalse(controller.claimCameraX(attempt))

        controller.enterReality(cameraAlreadyRunning = false)
        assertFalse(controller.claimArCore(attempt))
        val nextAttempt = controller.beginArCoreAttempt()!!
        assertFalse(controller.claimCameraX(entry))
        assertTrue(controller.markFallback(nextAttempt, "初始化失败"))
        assertTrue(controller.claimCameraX(nextAttempt))
        assertFalse(controller.claimArCore(nextAttempt))
    }

    @Test fun anExplicitCameraXStartCancelsPendingArOwnership() {
        val controller = RealityCaptureController()
        val entry = controller.enterReality(cameraAlreadyRunning = false).entryId
        val attempt = controller.beginArCoreAttempt()!!
        assertTrue(controller.markFallback(attempt, "已切换普通镜头"))
        assertTrue(controller.claimCameraX(entry))
        assertFalse(controller.claimArCore(attempt))
        assertEquals(null, controller.beginArCoreAttempt())
        assertEquals(RealityCameraOwner.CAMERAX, controller.snapshot().owner)
        assertEquals("已切换普通镜头", controller.snapshot().fallbackReason)
    }

    @Test fun thermalFallbackCannotBeClearedWithoutCoolingEvidence() {
        val controller = RealityCaptureController()
        val entry = controller.enterReality(cameraAlreadyRunning = true).entryId
        val attempt = controller.beginArCoreAttempt()!!
        assertTrue(controller.markFallback(attempt, "设备偏热", thermal = true))
        assertFalse(controller.clearThermalLockoutAfterExplicitStart(entry))
        val exited = controller.exitReality()
        assertFalse(exited.keepCameraOnExit)
        assertTrue(exited.thermalLockout)
    }

    @Test fun thermalLockoutRequiresOneMinuteBelowThirtyEightAndExplicitCameraStart() {
        val controller = RealityCaptureController()
        val entry = controller.enterReality(cameraAlreadyRunning = false).entryId
        val attempt = controller.beginArCoreAttempt()!!
        assertTrue(controller.markFallback(attempt, "设备偏热", thermal = true))
        controller.observeTemperature(nowMs = 1_000L, temperatureCelsius = 37.5f)
        controller.observeTemperature(nowMs = 60_999L, temperatureCelsius = 37.5f)
        assertFalse(controller.clearThermalLockoutAfterExplicitStart(60_999L, 37.5f, entry))
        controller.observeTemperature(nowMs = 61_000L, temperatureCelsius = 37.5f)
        assertTrue(controller.clearThermalLockoutAfterExplicitStart(61_000L, 37.5f, entry))
        assertFalse(controller.snapshot().thermalLockout)
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
