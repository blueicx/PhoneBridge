package com.phonebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompanionSyncState(
    val online: Boolean = false,
    val lastError: String? = null,
    val recovering: Boolean = false
)

data class CompanionSnapshot(
    val revision: Long = 0L,
    val summary: CompanionSummary = CompanionSummary(),
    val tasks: List<TimelineTask> = emptyList(),
    val messages: List<TimelineChatMessage> = emptyList(),
    val attention: List<TimelineAttention> = emptyList(),
    val mote: TimelineMote? = null,
    val health: TimelineHealth? = null,
    val autonomy: TimelineAutonomy? = null,
    val sync: CompanionSyncState = CompanionSyncState()
) {
    fun currentTask(): TimelineTask? = tasks.firstOrNull { task ->
        task.state in setOf("pending", "running", "paused", "needs_confirmation")
    }

    fun pendingAttention(): List<TimelineAttention> = attention.filter {
        it.status in setOf("open", "pending", "needs_confirmation")
    }

    fun recentResults(limit: Int = 3): List<TimelineTask> = tasks
        .filter { it.state in setOf("succeeded", "failed", "cancelled", "archived") }
        .sortedByDescending { it.updatedAt }
        .take(limit.coerceAtLeast(0))
}

data class CompanionTaskActionRequest(
    val taskId: String,
    val action: String,
    val idempotencyKey: String
) {
    val path: String get() = "/api/tasks/${taskId.trim()}/actions"
}

object CompanionTaskActionProtocol {
    private val allowedActions = setOf("start", "pause", "continue", "retry", "cancel", "archive")

    fun create(taskId: String, action: String, idempotencyKey: String): CompanionTaskActionRequest? {
        val normalizedTaskId = taskId.trim()
        val normalizedAction = action.trim().lowercase()
        val normalizedKey = idempotencyKey.trim()
        if (normalizedTaskId.isBlank() || normalizedAction !in allowedActions || normalizedKey.isBlank()) return null
        return CompanionTaskActionRequest(normalizedTaskId, normalizedAction, normalizedKey)
    }
}

/**
 * Single client-side projection for the immersive shell, the task drawer and
 * offline widgets. The server remains authoritative; this class only merges
 * a summary and the idempotent timeline projection.
 */
class CompanionSessionRepository(
    private val projection: TimelineProjection = TimelineProjection()
) {
    private val _snapshot = MutableStateFlow(CompanionSnapshot())
    val snapshot: StateFlow<CompanionSnapshot> = _snapshot.asStateFlow()

    private var summary = CompanionSummary()
    private var sync = CompanionSyncState()
    private var revision = 0L

    fun applySummary(value: CompanionSummary) {
        summary = value
        sync = sync.copy(online = value.connectionOnline, lastError = null, recovering = false)
        rebuild()
    }

    fun applyTimelineSnapshot(value: TimelineSnapshot, snapshotRevision: Long = value.revision) {
        projection.applySnapshot(value)
        revision = maxOf(revision, snapshotRevision, value.revision)
        sync = sync.copy(recovering = false, lastError = null)
        rebuild()
    }

    fun applyEvent(event: TimelineEvent): Boolean {
        val accepted = projection.applyEvent(event)
        if (!accepted) return false
        revision = maxOf(revision, event.revision)
        rebuild()
        return true
    }

    /** Applies a websocket burst and publishes one public snapshot update. */
    fun applyEvents(events: Iterable<TimelineEvent>): Int {
        var acceptedCount = 0
        var newestRevision = revision
        events.forEach { event ->
            if (projection.applyEvent(event)) {
                acceptedCount++
                newestRevision = maxOf(newestRevision, event.revision)
            }
        }
        if (acceptedCount > 0) {
            revision = newestRevision
            rebuild()
        }
        return acceptedCount
    }

    fun markOnline() {
        sync = sync.copy(online = true, lastError = null, recovering = false)
        rebuild()
    }

    fun markOffline(error: String? = null) {
        sync = sync.copy(online = false, lastError = error?.trim()?.takeIf { it.isNotBlank() }, recovering = false)
        rebuild()
    }

    fun markRecovering() {
        sync = sync.copy(recovering = true)
        rebuild()
    }

    private fun rebuild() {
        val taskOrder = compareByDescending<TimelineTask> { taskPriority(it.state) }
            .thenByDescending { it.updatedAt }
        val tasks = projection.tasks.value.values.sortedWith(taskOrder)
        val attention = projection.attention.value.values.sortedByDescending { it.updatedAt }
        _snapshot.value = CompanionSnapshot(
            revision = revision,
            summary = summary,
            tasks = tasks,
            messages = projection.messages.value.sortedBy { it.createdAt },
            attention = attention,
            mote = projection.mote.value,
            health = projection.health.value,
            autonomy = projection.autonomy.value,
            sync = sync
        )
    }

    private fun taskPriority(state: String): Int = when (state.lowercase()) {
        "needs_confirmation" -> 5
        "running" -> 4
        "paused" -> 3
        "pending" -> 2
        else -> 1
    }
}
