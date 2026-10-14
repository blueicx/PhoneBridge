package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityEncounterPolicyTest {
    @Test
    fun observationPromptReflectsActiveMoteAndClueType() {
        val starCore = RealityEncounterPolicy.observationPrompt(PetAppearance.MOTE, "object", relationshipLevel = 1)
        val leafFox = RealityEncounterPolicy.observationPrompt(PetAppearance.SPRITE, "object", relationshipLevel = 1)
        val light = RealityEncounterPolicy.observationPrompt(PetAppearance.MOTE, "light", relationshipLevel = 1)

        assertNotEquals(starCore, leafFox)
        assertNotEquals(starCore, light)
        assertTrue(starCore.contains("物体"))
    }

    @Test
    fun selectedActionsAreRestrictedToEncounterProtocol() {
        assertEquals("observe", RealityEncounterPolicy.wireAction("observe"))
        assertEquals("skill", RealityEncounterPolicy.wireAction("skill"))
        assertNull(RealityEncounterPolicy.wireAction("delete"))
    }

    @Test
    fun encounterPromptCombinesMoteObservationWithAuthoredScenario() {
        val prompt = RealityEncounterPolicy.encounterPrompt(
            "回声信标",
            "断续的脉冲正从边缘传来，先判断它在回应什么。",
            "叶狐：我听见了细小的变化。"
        )

        assertEquals(
            "叶狐：我听见了细小的变化。\n\n回声信标\n断续的脉冲正从边缘传来，先判断它在回应什么。",
            prompt
        )
    }

    @Test
    fun trackingCopyExplainsLostAnchorAndRepositionMode() {
        val lost = RealityTrackingSnapshot(
            status = RealityTrackingStatus.TRACKING,
            anchorPlaced = true,
            motePose = null,
        )

        assertTrue(RealityEncounterPolicy.trackingHint(lost, repositioning = false).contains("锚点暂时丢失"))
        assertTrue(RealityEncounterPolicy.trackingHint(lost, repositioning = true).contains("轻触新位置"))
        assertTrue(RealityEncounterPolicy.trackingHint(RealityTrackingSnapshot(RealityTrackingStatus.FALLBACK, fallbackReason = "手动探索"), false).contains("手动探索"))
    }
}
