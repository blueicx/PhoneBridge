package com.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBusinessAckPolicyTest {
    @Test
    fun explicitBusinessRejectionOverridesTransportDuplicate() {
        assertFalse(WorkspaceBusinessAckPolicy.isAccepted(false, "duplicate", "rejected"))
    }

    @Test
    fun acceptedAndDuplicateBusinessReceiptsRemainSuccessful() {
        assertTrue(WorkspaceBusinessAckPolicy.isAccepted(true, "accepted", "accepted"))
        assertTrue(WorkspaceBusinessAckPolicy.isAccepted(false, "duplicate", "duplicate"))
    }
}
