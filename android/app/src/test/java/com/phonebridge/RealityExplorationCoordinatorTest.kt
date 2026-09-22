package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityExplorationCoordinatorTest {
    @Test
    fun eventRefreshFiltersExpiredAndCrossRegionEvents() {
        val coordinator = RealityExplorationCoordinator(now = { 1_000L })
        coordinator.setRegion("cell:1:2")
        coordinator.replaceEvents(
            listOf(
                RealityEvent("near", "cell:1:2", "object", "object", 20, "near", 2_000L, "seed"),
                RealityEvent("old", "cell:1:2", "light", "light", 30, "mid", 900L, "seed"),
                RealityEvent("other", "cell:2:3", "location", "location", 40, "far", 2_000L, "seed")
            )
        )

        assertEquals(listOf("near"), coordinator.state.value.events.map { it.id })
    }

    @Test
    fun offlineClueSubmissionIsQueuedOnceAndAcknowledgementIsIdempotent() {
        val coordinator = RealityExplorationCoordinator(now = { 1_000L })
        coordinator.setRegion("cell:1:2")

        val first = coordinator.submitClue("place", online = false)
        val duplicate = coordinator.submitClue("place", online = false)
        assertFalse(first.duplicate)
        assertTrue(first.offline)
        assertTrue(duplicate.duplicate)
        assertEquals(1, coordinator.pendingSubmissions().size)

        coordinator.acknowledge(first.eventId, accepted = true)
        coordinator.acknowledge(first.eventId, accepted = true)
        assertEquals(0, coordinator.pendingSubmissions().size)
        assertTrue(coordinator.state.value.discoveredEventIds.contains(first.eventId))
    }

    @Test
    fun locationPermissionFallbackUsesCameraRegionWithoutPreciseCoordinates() {
        val coordinator = RealityExplorationCoordinator(now = { 1_000L })
        coordinator.setRegion("31.2304,121.4737")

        val submission = coordinator.submitClue("light", online = true)
        assertEquals("camera", submission.region)
        assertFalse(submission.eventId.contains("31.2304"))
        assertFalse(submission.offline)
    }
}
