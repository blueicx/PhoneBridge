package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoteCharacterizationTest {
    @Test
    fun everyMoteHasDistinctObservableCuesForEachMoment() {
        val moments = MoteMoment.entries
        assertEquals(20, MoteProfiles.all.size)
        for (moment in moments) {
            val cues = MoteProfiles.all.map { profile ->
                MoteCharacterizationEngine.resolve(profile.id, moment, relationshipLevel = 2)
            }
            assertTrue(cues.all { it.line.isNotBlank() && it.gesture.isNotBlank() })
            assertEquals(20, cues.map { it.line }.toSet().size)
            assertEquals(20, cues.map { it.gesture }.toSet().size)
        }
    }

    @Test
    fun relationshipStageChangesAddressLineAndGestureIntensity() {
        val first = MoteCharacterizationEngine.resolve(PetAppearance.MOTE, MoteMoment.TOUCH, 1)
        val familiar = MoteCharacterizationEngine.resolve(PetAppearance.MOTE, MoteMoment.TOUCH, 2)
        val trusted = MoteCharacterizationEngine.resolve(PetAppearance.MOTE, MoteMoment.TOUCH, 4)
        val bonded = MoteCharacterizationEngine.resolve(PetAppearance.MOTE, MoteMoment.TOUCH, 8)

        assertEquals(MoteRelationshipStage.FIRST_MEETING, first.relationshipStage)
        assertEquals(MoteRelationshipStage.FAMILIAR, familiar.relationshipStage)
        assertEquals(MoteRelationshipStage.TRUSTED, trusted.relationshipStage)
        assertEquals(MoteRelationshipStage.BONDED, bonded.relationshipStage)
        assertNotEquals(first.line, bonded.line)
        assertTrue(first.movementIntensity < familiar.movementIntensity)
        assertTrue(familiar.movementIntensity < trusted.movementIntensity)
        assertTrue(trusted.movementIntensity < bonded.movementIntensity)
    }

    @Test
    fun storyBranchesAreParsedAndUnknownFieldsRemainCompatible() {
        val entry = MoteStoryProtocol.parseRows(
            listOf(mapOf(
                "id" to "exclusive-mote",
                "title" to "星核的第一份推演",
                "exclusive" to true,
                "branches" to listOf(
                    mapOf("id" to "go-further", "title" to "继续行动", "outcome" to "获得前进奖励", "bonusXp" to 3),
                    mapOf("id" to "keep-at-home", "title" to "留在家园", "outcome" to "留下舞台记忆", "bonusXp" to 0),
                ),
                "branchChoiceId" to "go-further",
                "branchOutcome" to "获得前进奖励",
                "completedAtMs" to 1_700_000_000_000L,
                "futureField" to "ignored",
            ))).single()

        assertEquals(2, entry.branches.size)
        assertEquals("go-further", entry.branchChoiceId)
        assertEquals("获得前进奖励", entry.branchOutcome)
        assertEquals(1_700_000_000_000L, entry.completedAtMs)
    }
}
