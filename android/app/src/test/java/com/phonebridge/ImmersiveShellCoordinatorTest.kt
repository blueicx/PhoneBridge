package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveShellCoordinatorTest {
    @Test
    fun drawerIsClosedByBackBeforeLeavingImmersiveSurface() {
        val coordinator = ImmersiveShellCoordinator(ImmersiveSurface.REALITY)

        coordinator.openDrawer()
        assertTrue(coordinator.onBack())
        assertFalse(coordinator.state.value.drawerOpen)
        assertEquals(ImmersiveSurface.REALITY, coordinator.state.value.surface)

        assertTrue(coordinator.onBack())
        assertEquals(ImmersiveSurface.COMPANION, coordinator.state.value.surface)
        assertFalse(coordinator.onBack())
    }

    @Test
    fun deepLinksSelectTheEntityAndOpenTheDrawer() {
        val coordinator = ImmersiveShellCoordinator()

        assertTrue(coordinator.openDeepLink("phonebridge://task/task_42"))
        assertEquals("task_42", coordinator.state.value.selectedTaskId)
        assertTrue(coordinator.state.value.drawerOpen)

        assertTrue(coordinator.openDeepLink("phonebridge://attention/attn_7"))
        assertEquals("attn_7", coordinator.state.value.selectedAttentionId)
        assertEquals(null, coordinator.state.value.selectedTaskId)
        assertFalse(coordinator.openDeepLink("https://example.invalid/task_42"))
    }

    @Test
    fun taskActionProtocolRejectsUnknownActionsAndKeepsIdempotencyKey() {
        val request = CompanionTaskActionProtocol.create("task_42", "retry", "idem_42")
        assertEquals("/api/tasks/task_42/actions", request?.path)
        assertEquals("idem_42", request?.idempotencyKey)
        assertEquals(null, CompanionTaskActionProtocol.create("task_42", "delete", "idem_bad"))
    }
}
