package com.phonebridge

import java.time.Instant

data class DailyRoutineDefinition(
    val id: String,
    val title: String,
    val description: String = "",
    val kind: String = "",
    val input: String? = null,
    val permissionRequired: Boolean = false
)

data class DailyRoutineEntry(
    val id: String,
    val routineId: String,
    val title: String,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val lastEventAt: Long,
    val elapsedSeconds: Long = 0L,
    val revision: Long = 0L,
    val reflection: String? = null,
    val syncState: String = RoutineSyncState.CONFIRMED,
    val pendingEventId: String? = null,
    val pendingAction: String? = null,
    val syncReason: String? = null
)

data class DailyRoutineSnapshot(
    val catalog: List<DailyRoutineDefinition> = DailyRoutineProtocol.defaultCatalog,
    val current: List<DailyRoutineEntry> = emptyList(),
    val history: List<DailyRoutineEntry> = emptyList(),
    val nextCursor: Int? = null,
    val revision: Long = 0L,
    val privacyRevision: Long = 0L,
    val migrationRequired: Boolean = false
)

data class RoutineActionRequest(
    val eventId: String,
    val routineId: String,
    val action: String,
    val occurredAt: String,
    val elapsedSeconds: Long? = null,
    val reflection: String? = null
)

data class RoutineActionEnvelope(val request: RoutineActionRequest, val privacyRevision: Long)

data class RoutineActionQueueResult(val queued: Boolean, val reason: String? = null)

enum class RoutineDeliveryKind { ACCEPTED, RETRY, REJECTED }

data class RoutineDeliveryDecision(
    val kind: RoutineDeliveryKind,
    val entry: DailyRoutineEntry? = null,
    val reason: String? = null,
    val revision: Long? = null
)

object RoutineSyncState {
    const val CONFIRMED = "confirmed"
    const val PENDING = "pending"
    const val REJECTED = "rejected"
}

object DailyRoutineProtocol {
    const val FOCUS_TIMER = "focus-timer"
    const val WALK_OBSERVATION = "walk-observation"
    const val BEDTIME_REVIEW = "bedtime-review"
    const val EVENT_TYPE = "routine.event"

    val defaultCatalog = listOf(
        DailyRoutineDefinition(FOCUS_TIMER, "专注计时", "按自己的节奏专注；可随时暂停或结束。", "focus"),
        DailyRoutineDefinition(WALK_OBSERVATION, "散步观察", "无需定位或相机，留意身边值得记录的一件小事。", "observation"),
        DailyRoutineDefinition(BEDTIME_REVIEW, "睡前回顾", "可选的纯文本回顾，不会自动写入长期记忆。", "reflection", input = "text")
    )

    private val validActions = setOf("start", "pause", "resume", "skip", "finish", "interrupt")
    private val unfinishedStates = setOf("active", "paused")

    fun allowedActions(status: String?): Set<String> = when (status?.trim()?.lowercase()) {
        "active" -> setOf("pause", "skip", "finish", "interrupt")
        "paused" -> setOf("resume", "skip", "finish", "interrupt")
        "pending" -> emptySet()
        "finished", "skipped", "interrupted", null, "" -> setOf("start")
        else -> emptySet()
    }

    fun elapsedForAction(action: String, current: DailyRoutineEntry?, nowMs: Long): Long? {
        if (action == "start") return 0L
        current ?: return null
        if (current.status == "active" && action in setOf("pause", "finish", "skip", "interrupt")) {
            val added = ((nowMs - current.lastEventAt).coerceAtLeast(0L) / 1000L)
            return (current.elapsedSeconds + added).coerceAtMost(86_400L)
        }
        return current.elapsedSeconds.coerceIn(0L, 86_400L)
    }

    fun createRequest(
        eventId: String,
        routineId: String,
        action: String,
        occurredAt: String,
        elapsedSeconds: Long? = null,
        reflection: String? = null
    ): RoutineActionRequest? {
        val id = eventId.trim()
        val routine = routineId.trim()
        val normalizedAction = action.trim().lowercase()
        if (!id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"))) return null
        if (defaultCatalog.none { it.id == routine } || normalizedAction !in validActions) return null
        if (runCatching { Instant.parse(occurredAt).toEpochMilli() }.getOrNull() == null) return null
        if (elapsedSeconds != null && elapsedSeconds !in 0L..86_400L) return null
        if (reflection != null && (routine != BEDTIME_REVIEW || normalizedAction != "finish" || reflection.codePointCount(0, reflection.length) > 1000)) return null
        return RoutineActionRequest(id, routine, normalizedAction, occurredAt, elapsedSeconds, reflection)
    }

    fun pendingEntry(current: DailyRoutineEntry?, request: RoutineActionRequest): DailyRoutineEntry {
        val occurredAt = parseTime(request.occurredAt)
        val currentState = current?.takeIf { it.routineId == request.routineId }
        if (request.action == "start" || currentState == null) {
            val definition = defaultCatalog.first { it.id == request.routineId }
            return DailyRoutineEntry(
                id = "pending:${request.eventId}",
                routineId = request.routineId,
                title = definition.title,
                status = "pending",
                startedAt = occurredAt,
                updatedAt = occurredAt,
                lastEventAt = occurredAt,
                syncState = RoutineSyncState.PENDING,
                pendingEventId = request.eventId,
                pendingAction = request.action
            )
        }
        return currentState.copy(
            updatedAt = occurredAt,
            syncState = RoutineSyncState.PENDING,
            pendingEventId = request.eventId,
            pendingAction = request.action,
            syncReason = null
        )
    }

    fun confirmedEntry(entry: DailyRoutineEntry): DailyRoutineEntry = entry.copy(
        syncState = RoutineSyncState.CONFIRMED,
        pendingEventId = null,
        pendingAction = null,
        syncReason = null
    )

    fun rejectedEntry(current: DailyRoutineEntry?, reason: String): DailyRoutineEntry? = current?.copy(
        syncState = RoutineSyncState.REJECTED,
        pendingEventId = null,
        pendingAction = null,
        syncReason = reason.trim().take(180).ifBlank { "服务端未接受该操作" }
    )

    fun parseSnapshot(json: String): DailyRoutineSnapshot {
        val root = parseWorkspaceJsonObject(json)
        val parsedCatalog = root.list("catalog").mapNotNull { raw ->
            val value = raw.asMap() ?: return@mapNotNull null
            val id = value.string("id")
            if (id.isBlank()) return@mapNotNull null
            DailyRoutineDefinition(
                id = id,
                title = value.string("title").ifBlank { id },
                description = value.string("description"),
                kind = value.string("kind"),
                input = value.string("input").ifBlank { null },
                permissionRequired = value.boolean("permissionRequired")
            )
        }.takeIf { it.isNotEmpty() } ?: defaultCatalog
        return DailyRoutineSnapshot(
            catalog = parsedCatalog,
            current = root.list("current").mapNotNull(::entry),
            history = root.list("history").mapNotNull(::entry),
            nextCursor = root.intOrNull("nextCursor"),
            revision = root.long("revision").coerceAtLeast(0L),
            privacyRevision = root.long("privacyRevision").coerceAtLeast(0L),
            migrationRequired = root.boolean("migrationRequired")
        )
    }

    fun parseEntry(json: String): DailyRoutineEntry? = runCatching { entry(parseWorkspaceJsonObject(json)) }.getOrNull()

    fun parseActionPayload(json: String): RoutineActionEnvelope? = runCatching {
        val root = parseWorkspaceJsonObject(json)
        val request = createRequest(
            eventId = root.string("eventId"),
            routineId = root.string("routineId"),
            action = root.string("action"),
            occurredAt = root.string("occurredAt"),
            elapsedSeconds = root["elapsedSeconds"].toLongOrNull(),
            reflection = root.string("reflection").takeIf { root.containsKey("reflection") }
        ) ?: return null
        RoutineActionEnvelope(request, root.long("privacyRevision").coerceAtLeast(0L))
    }.getOrNull()

    fun classifyHttpResponse(statusCode: Int, body: String): RoutineDeliveryDecision {
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return RoutineDeliveryDecision(RoutineDeliveryKind.RETRY, reason = "HTTP $statusCode")
        }
        val root = runCatching { parseWorkspaceJsonObject(body) }.getOrNull()
        if (statusCode in 200..299 && root?.get("ok") == true) {
            val serverEntry = entry(root["entry"])
            return if (serverEntry != null) {
                RoutineDeliveryDecision(
                    kind = RoutineDeliveryKind.ACCEPTED,
                    entry = serverEntry,
                    revision = root.long("revision").coerceAtLeast(0L)
                )
            } else {
                RoutineDeliveryDecision(RoutineDeliveryKind.REJECTED, reason = "服务端未返回有效的日常记录")
            }
        }
        val reason = root?.string("error")?.ifBlank { root.string("code") }
            ?.take(180) ?: "服务端拒绝了日常操作（HTTP $statusCode）"
        return RoutineDeliveryDecision(RoutineDeliveryKind.REJECTED, reason = reason)
    }

    fun actionPayload(request: RoutineActionRequest, privacyRevision: Long): String = buildString {
        append('{')
        append("\"eventId\":").append(request.eventId.jsonQuoted())
        append(",\"routineId\":").append(request.routineId.jsonQuoted())
        append(",\"action\":").append(request.action.jsonQuoted())
        append(",\"occurredAt\":").append(request.occurredAt.jsonQuoted())
        request.elapsedSeconds?.let { append(",\"elapsedSeconds\":").append(it) }
        request.reflection?.let { append(",\"reflection\":").append(it.jsonQuoted()) }
        append(",\"privacyRevision\":").append(privacyRevision.coerceAtLeast(0L))
        append('}')
    }

    private fun entry(raw: Any?): DailyRoutineEntry? {
        val value = raw.asMap() ?: return null
        val id = value.string("id")
        val routineId = value.string("routineId")
        if (id.isBlank() || defaultCatalog.none { it.id == routineId }) return null
        val status = value.string("status").lowercase().takeIf {
            it in setOf("active", "paused", "finished", "skipped", "interrupted", "pending")
        } ?: "unknown"
        val startedAt = value.longTime("startedAt")
        val updatedAt = value.longTime("updatedAt").takeIf { it > 0L } ?: startedAt
        return DailyRoutineEntry(
            id = id,
            routineId = routineId,
            title = value.string("title").ifBlank { defaultCatalog.first { it.id == routineId }.title },
            status = status,
            startedAt = startedAt,
            updatedAt = updatedAt,
            lastEventAt = value.longTime("lastEventAt").takeIf { it > 0L } ?: updatedAt,
            elapsedSeconds = value.long("elapsedSeconds").coerceIn(0L, 86_400L),
            revision = value.long("revision").coerceAtLeast(0L),
            reflection = value.string("reflection").takeIf { it.isNotEmpty() },
            syncState = RoutineSyncState.CONFIRMED
        )
    }

    private fun parseTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<*> ?: emptyList()
    private fun Map<String, Any?>.string(key: String): String = (this[key] as? String).orEmpty()
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: false
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: 0L
    private fun Any?.toLongOrNull(): Long? = (this as? Number)?.toLong() ?: this?.toString()?.toLongOrNull()
    private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull()
    private fun Map<String, Any?>.longTime(key: String): Long = when (val value = this[key]) {
        is Number -> value.toLong()
        is String -> runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
        else -> 0L
    }
    private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
        ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()
    private fun String.jsonQuoted(): String = buildString(length + 2) {
        append('"')
        this@jsonQuoted.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
