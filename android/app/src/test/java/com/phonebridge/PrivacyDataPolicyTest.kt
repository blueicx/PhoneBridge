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
            listOf("memories", "tasks", "progress"),
            PrivacyDataPolicy.normalizeCategories(listOf("progress", "memories", "progress", "tasks"))
        )
        assertNull(PrivacyDataPolicy.normalizeCategories(listOf("memories", "unknown")))
        assertNull(PrivacyDataPolicy.normalizeCategories(emptyList()))
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
