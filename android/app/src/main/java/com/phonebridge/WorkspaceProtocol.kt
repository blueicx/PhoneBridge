package com.phonebridge

import java.util.Locale
import java.util.UUID
import java.time.Instant

data class WorkspaceEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val origin: String,
    val sequence: Long,
    val type: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val ack: Boolean = false,
    val localActionId: String? = null,
    val localActionState: String? = null,
    val revision: Long = 0L
)

object WorkspaceEventTypes {
    const val MESSAGE = "workspace.message"
    const val ATTENTION = "workspace.attention"
    const val POLICY = "workspace.policy"
    const val ACTION_RUN = "workspace.action_run"
    const val DEVICE_STATE = "device.state"
    const val MOTE_ROSTER = "mote.roster"
    const val MOTE_PROFILE = "mote.profile"
    const val MOTE_EXPLORATION = "mote.exploration"
    const val SYNC_STATE = "workspace.sync_state"
    const val TASK_PROGRESS = "workspace.task.progress"
    const val TASK_FINISHED = "workspace.task.finished"
    const val AUTONOMY_APPROVAL = "autonomy.approval"
    const val MOTE_RELATIONSHIP = "mote.relationship"
    const val MOTE_QUEST = "mote.quest"
}

object AttentionSeverity {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val CRITICAL = "critical"

    fun fromWire(value: String?): String = when (value.normalized()) {
        LOW -> LOW
        HIGH -> HIGH
        CRITICAL -> CRITICAL
        else -> MEDIUM
    }
}

object AttentionStatus {
    const val OPEN = "open"
    const val READ = "read"
    const val ACKNOWLEDGED = READ
    const val RESOLVED = "resolved"
    const val SNOOZED = "snoozed"
    const val DISMISSED = "dismissed"

    fun fromWire(value: String?): String = when (value.normalized()) {
        READ, "acknowledged" -> READ
        RESOLVED -> RESOLVED
        SNOOZED -> SNOOZED
        DISMISSED -> DISMISSED
        else -> OPEN
    }
}

object AutonomyScope {
    const val GLOBAL = "global"
    const val SESSION = "session"
    const val AUTOMATION = "automation"

    fun fromWire(value: String?): String = when (value.normalized()) {
        GLOBAL -> GLOBAL
        AUTOMATION -> AUTOMATION
        else -> SESSION
    }
}

object AutonomyLevel {
    const val OBSERVE = "observe"
    const val REVERSIBLE = "reversible"
    const val WHITELIST = "whitelist"

    fun fromWire(value: String?): String = when (value.normalized()) {
        REVERSIBLE -> REVERSIBLE
        WHITELIST -> WHITELIST
        else -> OBSERVE
    }
}

object ActionRunState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val BLOCKED = "blocked"

    fun fromWire(value: String?): String = when (value.normalized()) {
        RUNNING -> RUNNING
        SUCCEEDED, "success", "done" -> SUCCEEDED
        FAILED, "error" -> FAILED
        CANCELLED -> CANCELLED
        BLOCKED -> BLOCKED
        else -> QUEUED
    }

    fun isTerminal(value: String?): Boolean = when (fromWire(value)) {
        SUCCEEDED, FAILED, CANCELLED, BLOCKED -> true
        else -> false
    }
}

object ActionRunApproval {
    const val PENDING = "pending"
    const val GRANTED = "granted"
    const val APPROVED = GRANTED
    const val DENIED = "denied"

    fun fromWire(value: String?): String = when (value.normalized()) {
        GRANTED, "approved" -> GRANTED
        DENIED -> DENIED
        else -> PENDING
    }
}

data class AttentionItem(
    val id: String,
    val source: String,
    val severity: String,
    val status: String,
    val title: String,
    val summary: String,
    val relatedSessionId: String?,
    val relatedTaskId: String?,
    val dedupeKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val snoozedUntil: Long?
) {
    fun toJson(): String = jsonObject(
        "id" to id,
        "source" to source,
        "severity" to AttentionSeverity.fromWire(severity),
        "status" to AttentionStatus.fromWire(status),
        "title" to title,
        "summary" to summary,
        "relatedSessionId" to relatedSessionId,
        "relatedTaskId" to relatedTaskId,
        "dedupeKey" to dedupeKey,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "snoozedUntil" to snoozedUntil
    )

    companion object {
        fun fromJson(text: String): AttentionItem = fromMap(parseJsonObject(text))

        fun listToJson(items: List<AttentionItem>): String = jsonObjectArray(items.map { it.toJson() })

        fun listFromJson(text: String?): List<AttentionItem> = parseJsonObjectArray(text).map(::fromMap)

        private fun fromMap(values: Map<String, Any?>): AttentionItem = AttentionItem(
            id = values.string("id"),
            source = values.string("source"),
            severity = AttentionSeverity.fromWire(values.string("severity")),
            status = AttentionStatus.fromWire(values.string("status")),
            title = values.string("title"),
            summary = values.string("summary"),
            relatedSessionId = values.nullableString("relatedSessionId"),
            relatedTaskId = values.nullableString("relatedTaskId"),
            dedupeKey = values.nullableString("dedupeKey"),
            createdAt = values.long("createdAt"),
            updatedAt = values.long("updatedAt"),
            snoozedUntil = values.nullableLong("snoozedUntil")
        )
    }
}

data class AutonomyConfirmationRule(
    val toolId: String,
    val requireConfirmation: Boolean
) {
    fun toJson(): String = jsonObject(
        "toolId" to toolId,
        "requireConfirmation" to requireConfirmation
    )

    companion object {
        fun fromMap(values: Map<String, Any?>): AutonomyConfirmationRule = AutonomyConfirmationRule(
            toolId = values.string("toolId"),
            requireConfirmation = values.boolean("requireConfirmation")
        )

        fun listToJson(items: List<AutonomyConfirmationRule>): String =
            items.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJson() }

        fun listFromJson(text: String?): List<AutonomyConfirmationRule> = when (val value = text?.trim()) {
            null, "", "[]" -> emptyList()
            else -> parseJsonObjectArray(value).map(::fromMap)
        }
    }
}

data class AutonomyPolicy(
    val scopeType: String,
    val scopeId: String,
    val level: String,
    val allowedTools: List<String>,
    val expiresAt: Long?,
    val continuousMic: Boolean,
    val confirmationRules: List<AutonomyConfirmationRule>,
    val revision: Int = 0,
    val usesRemaining: Int? = null
) {
    fun toJson(): String = jsonObject(
        "scopeType" to AutonomyScope.fromWire(scopeType),
        "scopeId" to scopeId,
        "level" to AutonomyLevel.fromWire(level),
        "allowedTools" to allowedTools,
        "expiresAt" to expiresAt,
        "continuousMic" to continuousMic,
        "confirmationRules" to RawJson(AutonomyConfirmationRule.listToJson(confirmationRules)),
        "revision" to revision,
        "usesRemaining" to usesRemaining
    )

    companion object {
        fun fromJson(text: String): AutonomyPolicy = fromMap(parseJsonObject(text))

        fun listToJson(items: List<AutonomyPolicy>): String = jsonObjectArray(items.map { it.toJson() })

        fun listFromJson(text: String?): List<AutonomyPolicy> = parseJsonObjectArray(text).map(::fromMap)

        private fun fromMap(values: Map<String, Any?>): AutonomyPolicy = AutonomyPolicy(
            scopeType = AutonomyScope.fromWire(values.string("scopeType")),
            scopeId = values.string("scopeId").ifBlank { values.string("targetId") },
            level = AutonomyLevel.fromWire(values.string("level")),
            allowedTools = values.stringList("allowedTools"),
            expiresAt = values.nullableLong("expiresAt"),
            continuousMic = values.boolean("continuousMic"),
            confirmationRules = values.confirmationRules(),
            revision = values.int("revision"),
            usesRemaining = values.nullableInt("usesRemaining")
        )
    }
}

data class ActionRun(
    val id: String,
    val origin: String,
    val sessionId: String?,
    val automationId: String?,
    val taskId: String?,
    val toolId: String,
    val argsSummary: String,
    val state: String,
    val approval: String,
    val createdAt: Long,
    val startedAt: Long?,
    val endedAt: Long?,
    val resultRef: String?,
    val error: String?
) {
    fun toJson(): String = jsonObject(
        "id" to id,
        "origin" to origin,
        "sessionId" to sessionId,
        "automationId" to automationId,
        "taskId" to taskId,
        "toolId" to toolId,
        "argsSummary" to argsSummary,
        "state" to ActionRunState.fromWire(state),
        "approval" to ActionRunApproval.fromWire(approval),
        "createdAt" to createdAt,
        "startedAt" to startedAt,
        "endedAt" to endedAt,
        "resultRef" to resultRef,
        "error" to error
    )

    companion object {
        fun fromJson(text: String): ActionRun = fromMap(parseJsonObject(text))

        fun listToJson(items: List<ActionRun>): String = jsonObjectArray(items.map { it.toJson() })

        fun listFromJson(text: String?): List<ActionRun> = parseJsonObjectArray(text).map(::fromMap)

        private fun fromMap(values: Map<String, Any?>): ActionRun = ActionRun(
            id = values.string("id"),
            origin = values.string("origin"),
            sessionId = values.nullableString("sessionId"),
            automationId = values.nullableString("automationId"),
            taskId = values.nullableString("taskId"),
            toolId = values.string("toolId"),
            argsSummary = values.string("argsSummary"),
            state = ActionRunState.fromWire(values.string("state")),
            approval = ActionRunApproval.fromWire(values.string("approval")),
            createdAt = values.long("createdAt"),
            startedAt = values.nullableLong("startedAt"),
            endedAt = values.nullableLong("endedAt"),
            resultRef = values.nullableString("resultRef"),
            error = values.nullableString("error")
        )
    }
}

fun mergeAttentionItems(existing: List<AttentionItem>, incoming: List<AttentionItem>): List<AttentionItem> {
    val merged = mutableListOf<AttentionItem>()
    (existing + incoming).forEach { candidate ->
        val dedupeKey = candidate.dedupeKey?.takeIf { it.isNotBlank() }
        merged.removeAll { current ->
            current.id == candidate.id || (dedupeKey != null && current.dedupeKey == dedupeKey)
        }
        merged += candidate
    }
    return merged.sortedByDescending { it.updatedAt }
}

class EventDeduper {
    private val acceptedIds = mutableSetOf<String>()
    private val acceptedSequences = mutableSetOf<String>()

    fun accept(event: WorkspaceEvent): Boolean {
        val sequenceKey = "${event.origin}:${event.sequence}"
        if (!acceptedIds.add(event.eventId)) return false
        if (!acceptedSequences.add(sequenceKey)) {
            acceptedIds.remove(event.eventId)
            return false
        }
        return true
    }

    fun size(): Int = acceptedSequences.size
}

data class OutboxItem(
    val event: WorkspaceEvent,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = event.createdAt
)

class OutboxQueue {
    private val items = linkedMapOf<String, OutboxItem>()

    fun enqueue(event: WorkspaceEvent): Boolean {
        val duplicate = items.values.any {
            it.event.eventId == event.eventId ||
                (it.event.origin == event.origin && it.event.sequence == event.sequence)
        }
        if (duplicate) return false
        items[event.eventId] = OutboxItem(event)
        return true
    }

    fun ready(now: Long): List<OutboxItem> = items.values.filter { it.nextAttemptAt <= now }

    fun retry(eventId: String, now: Long, backoffMs: Long = 1_000L): Boolean {
        val current = items[eventId] ?: return false
        items[eventId] = current.copy(
            retryCount = current.retryCount + 1,
            nextAttemptAt = now + backoffMs.coerceAtMost(60_000L)
        )
        return true
    }

    fun acknowledge(eventId: String): Boolean = items.remove(eventId) != null

    fun size(): Int = items.size
}

data class AutonomyApproval(
    val id: String,
    val toolId: String,
    val taskId: String? = null,
    val state: String = "needs_confirmation",
    val expiresAt: Long? = null
) {
    companion object {
        fun fromJson(text: String): AutonomyApproval {
            val values = parseJsonObject(text)
            return AutonomyApproval(
                id = values.string("id"),
                toolId = values.string("toolId"),
                taskId = values.nullableString("taskId"),
                state = values.string("state").ifBlank { "needs_confirmation" },
                expiresAt = values.nullableLong("expiresAt")
            )
        }
    }
}

data class MoteRelationshipSummary(
    val level: Int = 1,
    val xp: Int = 0,
    val interactions: Int = 0
) {
    companion object {
        fun fromJson(text: String): MoteRelationshipSummary {
            val values = parseJsonObject(text)
            return MoteRelationshipSummary(values.int("level").coerceAtLeast(1), values.int("xp").coerceAtLeast(0), values.int("interactions").coerceAtLeast(0))
        }
    }
}

data class WorkspaceSyncCursor(val revision: Long = 0L, val lastError: String? = null)

object WorkspaceSyncReducer {
    fun advance(cursor: WorkspaceSyncCursor, revision: Long): WorkspaceSyncCursor =
        if (revision > cursor.revision) cursor.copy(revision = revision, lastError = null) else cursor

    fun fail(cursor: WorkspaceSyncCursor, error: String): WorkspaceSyncCursor = cursor.copy(lastError = error.take(240))
}

class WorkspaceEventGate(private val maxEventIds: Int = 512) {
    private val eventIds = LinkedHashSet<String>()
    var revision: Long = 0L
        private set
    var revisionGapDetected: Boolean = false
        private set

    fun accept(nextRevision: Long, eventId: String): Boolean {
        val id = eventId.trim()
        if (id.isBlank() || id in eventIds || (revision > 0L && nextRevision <= revision)) return false
        if (revision > 0L && nextRevision > revision + 1L) revisionGapDetected = true
        revision = maxOf(revision, nextRevision)
        eventIds += id
        while (eventIds.size > maxEventIds) eventIds.remove(eventIds.first())
        return true
    }

    fun consumeGap(): Boolean {
        val gap = revisionGapDetected
        revisionGapDetected = false
        return gap
    }

    fun markResynchronized(snapshotRevision: Long) {
        revision = maxOf(revision, snapshotRevision)
        revisionGapDetected = false
    }
}

data class WorkspaceUiState(
    val online: Boolean = false,
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val model: String = "",
    val activeTasks: Int = 0,
    val authorized: Boolean = false,
    val emergencyStop: Boolean = false
)

fun mergeWorkspaceUiState(current: WorkspaceUiState, incoming: WorkspaceUiState): WorkspaceUiState = WorkspaceUiState(
    online = incoming.online,
    camera = incoming.camera,
    microphone = incoming.microphone,
    model = incoming.model.ifBlank { current.model },
    activeTasks = incoming.activeTasks,
    authorized = incoming.authorized,
    emergencyStop = incoming.emergencyStop
)

private fun String?.normalized(): String = this.orEmpty().trim().lowercase(Locale.US)

private fun jsonObject(vararg entries: Pair<String, Any?>): String =
    entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${quoteJson(key)}:${value.toJsonLiteral()}"
    }

private fun jsonObjectArray(items: List<String>): String =
    items.joinToString(prefix = "[", postfix = "]", separator = ",")

private fun Any?.toJsonLiteral(): String = when (this) {
    null -> "null"
    is RawJson -> value
    is String -> quoteJson(this)
    is Boolean, is Int, is Long, is Double, is Float -> toString()
    is List<*> -> joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJsonLiteral() }
    else -> quoteJson(toString())
}

private data class RawJson(val value: String)

private fun quoteJson(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
    append('"')
}

private fun parseJsonObject(text: String): Map<String, Any?> = when (val value = JsonParser(text).parseValue()) {
    is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to item }
    else -> emptyMap()
}

private fun parseJsonObjectArray(text: String?): List<Map<String, Any?>> {
    val raw = text?.trim().orEmpty()
    if (raw.isBlank()) return emptyList()
    return when (val value = JsonParser(raw).parseValue()) {
        is List<*> -> value.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            (item as? Map<String, Any?>)
        }
        else -> emptyList()
    }
}

private fun Map<String, Any?>.string(key: String): String = when (val value = this[key]) {
    null -> ""
    is String -> value
    else -> value.toString()
}

private fun Map<String, Any?>.nullableString(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value
    else -> value.toString()
}

private fun Map<String, Any?>.long(key: String): Long = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: value.toInstantMillis()
    else -> 0L
}

private fun Map<String, Any?>.int(key: String): Int = when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

private fun Map<String, Any?>.nullableInt(key: String): Int? = when (val value = this[key]) {
    null -> null
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

private fun Map<String, Any?>.nullableLong(key: String): Long? = when (val value = this[key]) {
    null -> null
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: value.toInstantMillisOrNull()
    else -> null
}

private fun Map<String, Any?>.boolean(key: String): Boolean = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.equals("true", ignoreCase = true)
    else -> false
}

private fun Map<String, Any?>.stringList(key: String): List<String> = when (val value = this[key]) {
    is List<*> -> value.mapNotNull { item ->
        when (item) {
            null -> null
            is String -> item
            else -> item.toString()
        }
    }
    else -> emptyList()
}

private fun Map<String, Any?>.confirmationRules(): List<AutonomyConfirmationRule> = when (val value = this["confirmationRules"]) {
    is List<*> -> value.mapNotNull { item ->
        when (item) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                AutonomyConfirmationRule.fromMap(item as Map<String, Any?>)
            }
            is String -> AutonomyConfirmationRule(item, true)
            else -> null
        }
    }
    else -> emptyList()
}

private fun String.toInstantMillis(): Long = toInstantMillisOrNull() ?: 0L

private fun String.toInstantMillisOrNull(): Long? = try {
    Instant.parse(this).toEpochMilli()
} catch (_: Exception) {
    null
}

private class JsonParser(private val source: String) {
    private var index = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (index >= source.length) return null
        return when (val token = source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected JSON token '$token' at $index")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        if (peek('}')) {
            index++
            return emptyMap()
        }
        val values = linkedMapOf<String, Any?>()
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when {
                peek(',') -> {
                    index++
                    skipWhitespace()
                }
                peek('}') -> {
                    index++
                    return values
                }
                else -> error("Expected ',' or '}' at $index")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        if (peek(']')) {
            index++
            return emptyList()
        }
        val values = mutableListOf<Any?>()
        while (true) {
            values += parseValue()
            skipWhitespace()
            when {
                peek(',') -> {
                    index++
                    skipWhitespace()
                }
                peek(']') -> {
                    index++
                    return values
                }
                else -> error("Expected ',' or ']' at $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            when (ch) {
                '"' -> return result.toString()
                '\\' -> {
                    val escaped = source[index++]
                    result.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = source.substring(index, index + 4)
                                index += 4
                                hex.toInt(16).toChar()
                            }
                            else -> error("Unsupported escape '\\$escaped' at $index")
                        }
                    )
                }
                else -> result.append(ch)
            }
        }
        error("Unterminated string literal")
    }

    private fun parseNumber(): Number {
        val start = index
        if (source[index] == '-') index++
        while (index < source.length && source[index].isDigit()) index++
        val hasFraction = if (index < source.length && source[index] == '.') {
            index++
            while (index < source.length && source[index].isDigit()) index++
            true
        } else false
        val hasExponent = if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && source[index].isDigit()) index++
            true
        } else false
        val raw = source.substring(start, index)
        return if (hasFraction || hasExponent) raw.toDouble() else raw.toLong()
    }

    private fun parseLiteral(token: String, value: Any?): Any? {
        require(source.startsWith(token, index)) { "Expected '$token' at $index" }
        index += token.length
        return value
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        require(index < source.length && source[index] == expected) {
            "Expected '$expected' at $index"
        }
        index++
    }

    private fun peek(expected: Char): Boolean {
        skipWhitespace()
        return index < source.length && source[index] == expected
    }
}
