package com.phonebridge

import org.junit.Assert.*
import org.junit.Test

class StageWorkPolicyTest {
    @Test fun hiddenStageDoesNotRenderAndIdleBackgroundSamplesSlowly() {
        assertFalse(StageWorkPolicy.renderStage(foreground = false, shown = true))
        assertFalse(StageWorkPolicy.renderStage(foreground = true, shown = false))
        assertTrue(StageWorkPolicy.renderStage(foreground = true, shown = true))
        assertEquals(15000L, StageWorkPolicy.telemetryInterval(false, false))
    }
    @Test fun activeAudioOrCameraKeepsHealthSamplingWithoutRequiringStageRendering() {
        assertEquals(2500L, StageWorkPolicy.telemetryInterval(false, true))
        assertEquals(2500L, StageWorkPolicy.telemetryInterval(true, false))
    }
}
