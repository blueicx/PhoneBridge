package com.phonebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RealityClueSubmission(
    val eventId: String,
    val clueType: String,
    val region: String,
    val offline: Boolean,
    val duplicate: Boolean = false
)

data class RealityExplorationState(
    val region: String? = null,
    val events: List<RealityEvent> = emptyList(),
    val pending: List<RealityClueSubmission> = emptyList(),
    val discoveredEventIds: Set<String> = emptySet()
)

/**
 * Offline-first coordinator for RealityLens. It never stores precise
 * coordinates and does not mark a clue as discovered until the server ACKs it.
 */
class RealityExplorationCoordinator(
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow(RealityExplorationState())
    val state: StateFlow<RealityExplorationState> = _state.asStateFlow()

    fun setRegion(value: String?) {
        val normalized = normalizeRegion(value)
        _state.value = _state.value.copy(
            region = normalized,
            events = _state.value.events.filter { it.region == normalized && it.expiresAt > now() }
        )
    }

    fun replaceEvents(events: List<RealityEvent>, at: Long = now()) {
        val region = _state.value.region
        _state.value = _state.value.copy(
            events = events
                .asSequence()
                .filter { region != null && it.region == region }
                .filter { it.expiresAt > at }
                .distinctBy { it.id }
                .sortedBy { it.bearing }
                .toList()
        )
    }

    fun submitClue(nodeId: String?, online: Boolean): RealityClueSubmission {
        val clueType = RealityClueProtocol.canonicalType(nodeId)
        val region = _state.value.region ?: "camera"
        val eventId = RealityClueProtocol.eventId(nodeId, region)
        val current = _state.value
        if (eventId in current.discoveredEventIds || current.pending.any { it.eventId == eventId }) {
            return RealityClueSubmission(eventId, clueType, region, offline = !online, duplicate = true)
        }
        val submission = RealityClueSubmission(eventId, clueType, region, offline = !online)
        _state.value = current.copy(pending = current.pending + submission)
        return submission
    }

    fun acknowledge(eventId: String, accepted: Boolean) {
        val current = _state.value
        val pending = current.pending.filterNot { it.eventId == eventId }
        val discovered = if (accepted && eventId.isNotBlank()) current.discoveredEventIds + eventId else current.discoveredEventIds
        _state.value = current.copy(pending = pending, discoveredEventIds = discovered)
    }

    fun pendingSubmissions(): List<RealityClueSubmission> = _state.value.pending

    fun restoreDiscovered(eventIds: Collection<String>) {
        _state.value = _state.value.copy(discoveredEventIds = _state.value.discoveredEventIds + eventIds.filter { it.isNotBlank() })
    }

    private fun normalizeRegion(value: String?): String? {
        val region = value?.trim().orEmpty()
        if (region.isBlank() || region == "camera") return region.ifBlank { null } ?: "camera"
        return region.takeIf { Regex("^cell:-?\\d+:-?\\d+$").matches(it) } ?: "camera"
    }
}
