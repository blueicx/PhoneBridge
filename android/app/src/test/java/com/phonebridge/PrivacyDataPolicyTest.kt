package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDataPolicyTest {
    @Test
    fun categoriesAreDeduplicatedAndReturnedInStableOrder() {
        assertEquals(
            listOf("memories", "tasks", "progress", "goals"),
            PrivacyDataPolicy.normalizeCategories(listOf("goals", "progress", "memories", "progress", "tasks"))
        )
        assertNull(PrivacyDataPolicy.normalizeCategories(listOf("memories", "unknown")))
        assertNull(PrivacyDataPolicy.normalizeCategories(emptyList()))
    }

    @Test
    fun workspaceEventsMapToKnownPrivacyCategoriesWithoutGuessingPayloadText() {
        assertEquals(
            listOf("conversations"),
            PrivacyDataPolicy.classifyEvent(WorkspaceEventTypes.MESSAGE, mapOf("text" to "private chat"))?.categories
        )
        assertEquals(
            listOf("progress"),
            PrivacyDataPolicy.classifyEvent(WorkspaceEventTypes.MOTE_EXPLORATION, mapOf("clueType" to "light"))?.categories
        )
        assertEquals(
            listOf("conversations", "tasks"),
            PrivacyDataPolicy.classifyEvent(
                WorkspaceEventTypes.TASK_PROGRESS,
                mapOf("task" to mapOf("metadata" to mapOf("sessionId" to "s-1")))
            )?.categories
        )
        assertEquals(
            listOf("conversations", "tasks"),
            PrivacyDataPolicy.classifyEvent(
                WorkspaceEventTypes.TASK_FINISHED,
                mapOf("source" to "conversation", "task" to mapOf("id" to "t-1"))
            )?.categories
        )
        assertEquals(emptyList<String>(), PrivacyDataPolicy.classifyEvent(WorkspaceEventTypes.DEVICE_STATE, emptyMap())?.categories)
        assertNull(PrivacyDataPolicy.classifyEvent("workspace.private_extension", mapOf("text" to "do not send")))
    }

    @Test
    fun routinesAndGoalsAreSelectablePrivacyCategories() {
        assertEquals(
            listOf("routines", "goals"),
            PrivacyDataPolicy.normalizeCategories(listOf("goals", "routines"))
        )
    }

    @Test
    fun goalLinkedTaskEventsAreFencedByBothGoalAndTaskPrivacy() {
        assertEquals(
            listOf("goals", "tasks"),
            PrivacyDataPolicy.classifyEvent(
                WorkspaceEventTypes.TASK_PROGRESS,
                mapOf("task" to mapOf("metadata" to mapOf("goalId" to "goal-1", "milestoneId" to "mile-1")))
            )?.categories
        )
    }

    @Test
    fun exportRequiresMatchingLongPassphrases() {
        assertTrue(PrivacyDataPolicy.isValidPassphrase("correct horse battery staple", "correct horse battery staple"))
        assertFalse(PrivacyDataPolicy.isValidPassphrase("short", "short"))
        assertFalse(PrivacyDataPolicy.isValidPassphrase("correct horse battery staple", "different passphrase"))
    }

    @Test
    fun deleteConfirmationMustBeExactAndRequestIdNonBlank() {
        assertTrue(PrivacyDataPolicy.isValidDeleteConfirmation("DELETE SELECTED DATA", "request-1"))
        assertFalse(PrivacyDataPolicy.isValidDeleteConfirmation("delete selected data", "request-1"))
        assertFalse(PrivacyDataPolicy.isValidDeleteConfirmation("DELETE SELECTED DATA", " "))
    }

    @Test
    fun missedDeletionReceiptsAreReconciledOnceAfterReconnect() {
        assertEquals(
            listOf("delete-2", "delete-3"),
            PrivacyDataPolicy.pendingDeletionIds(
                listOf("delete-1", "delete-2", "delete-2", " ", "delete-3"),
                setOf("delete-1")
            )
        )
    }
}
