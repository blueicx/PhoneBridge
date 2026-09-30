package com.phonebridge

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspaceClientTest {
    @Test
    fun reusesHttpClientsForTheSameCertificatePinAndSeparatesDifferentPins() {
        val workspaceClient = WorkspaceClient()
        val firstPin = "sha256:" + "a".repeat(64)
        val secondPin = "sha256:" + "b".repeat(64)

        assertSame(workspaceClient.clientForFingerprint(null), workspaceClient.clientForFingerprint(null))
        assertSame(workspaceClient.clientForFingerprint(firstPin), workspaceClient.clientForFingerprint(firstPin))
        assertNotSame(workspaceClient.clientForFingerprint(firstPin), workspaceClient.clientForFingerprint(secondPin))
    }
}
