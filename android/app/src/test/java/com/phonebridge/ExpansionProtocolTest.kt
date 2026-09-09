package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpansionProtocolTest {
    @Test
    fun parsesApprovalAndRelationshipPayloadsWithSafeDefaults() {
        val approval = AutonomyApproval.fromJson("{\"id\":\"a1\",\"toolId\":\"device.safe\",\"state\":\"needs_confirmation\"}")
        assertEquals("a1", approval.id)
        assertEquals("device.safe", approval.toolId)
        assertEquals("needs_confirmation", approval.state)

        val relationship = MoteRelationshipSummary.fromJson("{\"level\":2,\"xp\":105,\"interactions\":7}")
        assertEquals(2, relationship.level)
        assertEquals(105, relationship.xp)
        assertEquals(7, relationship.interactions)
    }

    @Test
    fun exposesExpansionEventNames() {
        assertEquals("workspace.sync_state", WorkspaceEventTypes.SYNC_STATE)
        assertEquals("autonomy.approval", WorkspaceEventTypes.AUTONOMY_APPROVAL)
        assertEquals("mote.quest", WorkspaceEventTypes.MOTE_QUEST)
    }

    @Test
    fun relationshipLevelChangesMoteBehaviorDeterministically() {
        val profile = MoteProfiles.profile(PetAppearance.MOTE)
        val quiet = MoteBehaviorEngine.resolve(profile, MoteBehaviorInput(relationshipLevel = 1))
        val bonded = MoteBehaviorEngine.resolve(profile, MoteBehaviorInput(relationshipLevel = 5))
        assertTrue(bonded.motionIntensity > quiet.motionIntensity)
    }
}
