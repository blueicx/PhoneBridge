package com.phonebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TimelineEvent(
    val eventId: String,
    val revision: Long,
    val timestamp: Long,
    val entityType: String,
    val entityId: String,
    val entityVersion: Int = 1,
    val operation: String = "update",
    val payload: Map<String, Any?> = emptyMap(),
    val deleted: Boolean = false
)

data class TimelineTask(
    val id: String,
    val title: String = "",
    val state: String = "pending",
    val progress: Int = 0,
    val detail: String = "",
    val source: String = "conversation",
    val relatedSessionId: String? = null,
    val isPendingConfirmation: Boolean = false,
    val recentResult: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineChatMessage(
    val id: String,
    val sessionId: String,
    val role: String = "user",
    val text: String = "",
    val relatedTaskId: String? = null,
    val createdAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineAttention(
    val id: String,
    val source: String = "task",
    val severity: String = "medium",
    val status: String = "open",
    val title: String = "",
    val summary: String = "",
    val relatedSessionId: String? = null,
    val relatedTaskId: String? = null,
    val dedupeKey: String? = null,
    val deepLink: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineMote(
    val profileId: String = "rimuru",
    val name: String = "利姆鲁",
    val active: Boolean = true,
    val level: Int = 1,
    val xp: Int = 0,
    val interactions: Int = 0,
    val mood: Int = 80,
    val gaze: String = "ambient",
    val reminderStrength: Float = 0f,
    val updatedAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineHealth(
    val connected: Boolean = false,
    val battery: Int = 100,
    val memory: Int = 0,
    val temperature: Float = 25f,
    val networkRx: Long = 0L,
    val networkTx: Long = 0L,
    val sensors: Map<String, Boolean> = emptyMap(),
    val updatedAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineAutonomy(
    val level: String = "whitelist",
    val allowedTools: List<String> = emptyList(),
    val emergencyStop: Boolean = false,
    val pendingApprovals: Int = 0,
    val updatedAt: Long = 0L,
    val entityVersion: Int = 1
)

data class TimelineSnapshot(
    val revision: Long = 0L,
    val tasks: List<TimelineTask> = emptyList(),
    val messages: List<TimelineChatMessage> = emptyList(),
    val attention: List<TimelineAttention> = emptyList(),
    val mote: TimelineMote? = null,
    val health: TimelineHealth? = null,
    val autonomy: TimelineAutonomy? = null
)

data class GpsPlaceholder(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L,
    val description: String = "Round 1 placeholder base"
)

data class TimelineMockInput(
    val gps: GpsPlaceholder? = null,
    val network: Map<String, Any?> = emptyMap(),
    val provider: Map<String, Any?> = emptyMap()
)

data class WorkspaceTimelineModel(
    val revision: Long = 0L,
    val cursor: Long = 0L,
    val headRevision: Long = 0L,
    val hasMore: Boolean = false,
    val events: List<TimelineEvent> = emptyList(),
    val snapshot: TimelineSnapshot = TimelineSnapshot(),
    val mockInput: TimelineMockInput = TimelineMockInput()
)

object TimelineParser {
    fun parse(text: String): WorkspaceTimelineModel {
        val root = TimelineJsonParser(text).parseValue() as? Map<*, *> ?: return WorkspaceTimelineModel()

        val revision = root.long("revision")
        val cursor = root.long("cursor")
        val headRevision = root.long("headRevision").takeIf { it > 0 } ?: revision
        val hasMore = root.boolean("hasMore")

        val rawEvents = root.list("events")
        val events = rawEvents.mapNotNull { it as? Map<*, *> }.map { e ->
            val payload = (e["payload"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
            TimelineEvent(
                eventId = e.string("eventId"),
                revision = e.long("revision"),
                timestamp = e.long("createdAt").takeIf { it > 0 } ?: e.long("timestamp"),
                entityType = e.string("entity").ifBlank { e.string("entityType") },
                entityId = e.string("entityId"),
                entityVersion = e.int("entityVersion").coerceAtLeast(1),
                operation = e.string("operation").ifBlank { "update" },
                payload = payload,
                deleted = e.boolean("deleted")
            )
        }

        val rawSnapshot = root["snapshot"] as? Map<*, *>
        val snapshot = if (rawSnapshot != null) {
            val tasks = rawSnapshot.list("tasks").mapNotNull { it as? Map<*, *> }.map { t ->
                TimelineTask(
                    id = t.string("id"),
                    title = t.string("title"),
                    state = t.string("state").ifBlank { "pending" },
                    progress = t.int("progress"),
                    detail = t.string("detail"),
                    source = t.string("source").ifBlank { "conversation" },
                    relatedSessionId = t.nullableString("relatedSessionId"),
                    isPendingConfirmation = t.boolean("isPendingConfirmation"),
                    recentResult = t.nullableString("recentResult"),
                    createdAt = t.long("createdAt"),
                    updatedAt = t.long("updatedAt"),
                    entityVersion = t.int("entityVersion").coerceAtLeast(1)
                )
            }
            val messages = rawSnapshot.list("messages").mapNotNull { it as? Map<*, *> }.map { m ->
                TimelineChatMessage(
                    id = m.string("id"),
                    sessionId = m.string("sessionId"),
                    role = m.string("role").ifBlank { "user" },
                    text = m.string("text"),
                    relatedTaskId = m.nullableString("relatedTaskId"),
                    createdAt = m.long("createdAt"),
                    entityVersion = m.int("entityVersion").coerceAtLeast(1)
                )
            }
            val attention = rawSnapshot.list("attention").mapNotNull { it as? Map<*, *> }.map { a ->
                TimelineAttention(
                    id = a.string("id"),
                    source = a.string("source").ifBlank { "task" },
                    severity = a.string("severity").ifBlank { "medium" },
                    status = a.string("status").ifBlank { "open" },
                    title = a.string("title"),
                    summary = a.string("summary"),
                    relatedSessionId = a.nullableString("relatedSessionId"),
                    relatedTaskId = a.nullableString("relatedTaskId"),
                    dedupeKey = a.nullableString("dedupeKey"),
                    deepLink = a.nullableString("deepLink"),
                    createdAt = a.long("createdAt"),
                    updatedAt = a.long("updatedAt"),
                    entityVersion = a.int("entityVersion").coerceAtLeast(1)
                )
            }
            val moteMap = rawSnapshot["mote"] as? Map<*, *>
            val mote = moteMap?.let {
                TimelineMote(
                    profileId = it.string("profileId").ifBlank { "rimuru" },
                    name = it.string("name"),
                    active = it.boolean("active"),
                    level = it.int("level").coerceAtLeast(1),
                    xp = it.int("xp"),
                    interactions = it.int("interactions"),
                    mood = it.int("mood").coerceAtLeast(0),
                    gaze = it.string("gaze").ifBlank { "ambient" },
                    reminderStrength = it.float("reminderStrength"),
                    updatedAt = it.long("updatedAt")
                )
            }
            val healthMap = rawSnapshot["health"] as? Map<*, *>
            val health = healthMap?.let {
                val sensors = (it["sensors"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                    k.toString() to (v == true || v.toString().equals("true", ignoreCase = true))
                } ?: emptyMap()
                TimelineHealth(
                    connected = it.boolean("connected"),
                    battery = it.int("battery").coerceIn(0, 100),
                    memory = it.int("memory"),
                    temperature = it.float("temperature"),
                    networkRx = it.long("networkRx"),
                    networkTx = it.long("networkTx"),
                    sensors = sensors,
                updatedAt = it.long("updatedAt")
                )
            }
            val autonomyMap = rawSnapshot["autonomy"] as? Map<*, *>
            val autonomy = autonomyMap?.let {
                TimelineAutonomy(
                    level = it.string("level").ifBlank { "whitelist" },
                    allowedTools = (it["allowedTools"] as? List<*>)?.mapNotNull { value -> value?.toString() } ?: emptyList(),
                    emergencyStop = it.boolean("emergencyStop"),
                    pendingApprovals = it.int("pendingApprovals"),
                    updatedAt = it.long("updatedAt")
                )
            }
            TimelineSnapshot(
                revision = rawSnapshot.long("revision"),
                tasks = tasks,
                messages = messages,
                attention = attention,
                mote = mote,
                health = health,
                autonomy = autonomy
            )
        } else {
            TimelineSnapshot()
        }

        val rawMock = root["mockInput"] as? Map<*, *>
        val gpsMap = rawMock?.get("gps") as? Map<*, *>
        val gps = gpsMap?.let {
            GpsPlaceholder(
                latitude = it.double("latitude"),
                longitude = it.double("longitude"),
                accuracy = it.float("accuracy"),
                timestamp = it.long("timestamp"),
                description = it.string("description")
            )
        }
        val mockInput = TimelineMockInput(
            gps = gps,
            network = (rawMock?.get("network") as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap(),
            provider = (rawMock?.get("provider") as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()
        )

        return WorkspaceTimelineModel(
            revision = revision,
            cursor = cursor,
            headRevision = headRevision,
            hasMore = hasMore,
            events = events,
            snapshot = snapshot,
            mockInput = mockInput
        )
    }
}

class TimelineProjection {
    private val _tasks = MutableStateFlow<Map<String, TimelineTask>>(emptyMap())
    val tasks: StateFlow<Map<String, TimelineTask>> = _tasks.asStateFlow()

    private val _messages = MutableStateFlow<List<TimelineChatMessage>>(emptyList())
    val messages: StateFlow<List<TimelineChatMessage>> = _messages.asStateFlow()

    private val _attention = MutableStateFlow<Map<String, TimelineAttention>>(emptyMap())
    val attention: StateFlow<Map<String, TimelineAttention>> = _attention.asStateFlow()

    private val _mote = MutableStateFlow<TimelineMote?>(null)
    val mote: StateFlow<TimelineMote?> = _mote.asStateFlow()

    private val _health = MutableStateFlow<TimelineHealth?>(null)
    val health: StateFlow<TimelineHealth?> = _health.asStateFlow()

    private val _autonomy = MutableStateFlow<TimelineAutonomy?>(null)
    val autonomy: StateFlow<TimelineAutonomy?> = _autonomy.asStateFlow()
    private val seenEventIds = LinkedHashSet<String>()
    private val entityVersions = mutableMapOf<String, Int>()

    fun applyEvent(event: TimelineEvent) {
        if (event.eventId.isNotBlank() && !seenEventIds.add(event.eventId)) return
        val previousVersion = entityVersions[event.entityKey]
        if (previousVersion != null && event.entityVersion < previousVersion) return
        entityVersions[event.entityKey] = maxOf(previousVersion ?: 0, event.entityVersion)
        val payload = event.payload
        if (event.deleted || event.operation == "delete") {
            when (event.entityType) {
                "task" -> _tasks.value = _tasks.value - event.entityId
                "chat" -> _messages.value = _messages.value.filter { it.id != event.entityId }
                "attention" -> _attention.value = _attention.value - event.entityId
            }
            return
        }

        when (event.entityType) {
            "task" -> {
                val existing = _tasks.value[event.entityId]
                val updated = TimelineTask(
                    id = event.entityId,
                    title = payload.string("title").ifBlank { existing?.title.orEmpty() },
                    state = payload.string("state").ifBlank { existing?.state ?: "pending" },
                    progress = if (payload.containsKey("progress")) payload.int("progress") else existing?.progress ?: 0,
                    detail = payload.string("detail").ifBlank { existing?.detail.orEmpty() },
                    source = payload.string("source").ifBlank { existing?.source ?: "conversation" },
                    relatedSessionId = payload.nullableString("relatedSessionId") ?: existing?.relatedSessionId,
                    isPendingConfirmation = if (payload.containsKey("isPendingConfirmation")) payload.boolean("isPendingConfirmation") else existing?.isPendingConfirmation ?: false,
                    recentResult = payload.nullableString("recentResult") ?: existing?.recentResult,
                    createdAt = payload.long("createdAt").takeIf { it > 0 } ?: existing?.createdAt ?: event.timestamp,
                    updatedAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
                _tasks.value = _tasks.value + (event.entityId to updated)
            }
            "chat" -> {
                val msg = TimelineChatMessage(
                    id = event.entityId,
                    sessionId = payload.string("sessionId"),
                    role = payload.string("role").ifBlank { "user" },
                    text = payload.string("text"),
                    relatedTaskId = payload.nullableString("relatedTaskId"),
                    createdAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
                _messages.value = _messages.value.filter { it.id != msg.id } + msg
            }
            "attention" -> {
                val existing = _attention.value[event.entityId]
                val item = TimelineAttention(
                    id = event.entityId,
                    source = payload.string("source").ifBlank { existing?.source ?: "task" },
                    severity = payload.string("severity").ifBlank { existing?.severity ?: "medium" },
                    status = payload.string("status").ifBlank { existing?.status ?: "open" },
                    title = payload.string("title").ifBlank { existing?.title.orEmpty() },
                    summary = payload.string("summary").ifBlank { existing?.summary.orEmpty() },
                    relatedSessionId = payload.nullableString("relatedSessionId") ?: existing?.relatedSessionId,
                    relatedTaskId = payload.nullableString("relatedTaskId") ?: existing?.relatedTaskId,
                    dedupeKey = payload.nullableString("dedupeKey") ?: existing?.dedupeKey,
                    deepLink = payload.nullableString("deepLink") ?: existing?.deepLink,
                    createdAt = payload.long("createdAt").takeIf { it > 0 } ?: existing?.createdAt ?: event.timestamp,
                    updatedAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
                _attention.value = _attention.value + (event.entityId to item)
            }
            "mote" -> {
                _mote.value = TimelineMote(
                    profileId = payload.string("profileId").ifBlank { _mote.value?.profileId ?: "rimuru" },
                    name = payload.string("name").ifBlank { _mote.value?.name ?: "利姆鲁" },
                    active = if (payload.containsKey("active")) payload.boolean("active") else _mote.value?.active ?: true,
                    level = payload.int("level").coerceAtLeast(1),
                    xp = payload.int("xp"),
                    interactions = payload.int("interactions"),
                    mood = payload.int("mood").coerceAtLeast(0),
                    gaze = payload.string("gaze").ifBlank { _mote.value?.gaze ?: "ambient" },
                    reminderStrength = payload.float("reminderStrength"),
                    updatedAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
            }
            "health" -> {
                _health.value = TimelineHealth(
                    connected = payload.boolean("connected"),
                    battery = payload.int("battery").coerceIn(0, 100),
                    memory = payload.int("memory"),
                    temperature = payload.float("temperature"),
                    networkRx = payload.long("networkRx"),
                    networkTx = payload.long("networkTx"),
                    sensors = (payload["sensors"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                        k.toString() to (v == true || v.toString().equals("true", ignoreCase = true))
                    } ?: emptyMap(),
                    updatedAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
            }
            "autonomy" -> {
                _autonomy.value = TimelineAutonomy(
                    level = payload.string("level").ifBlank { "whitelist" },
                    allowedTools = (payload["allowedTools"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                    emergencyStop = payload.boolean("emergencyStop"),
                    pendingApprovals = payload.int("pendingApprovals"),
                    updatedAt = event.timestamp,
                    entityVersion = event.entityVersion
                )
            }
        }
    }

    fun applySnapshot(snapshot: TimelineSnapshot) {
        _tasks.value = snapshot.tasks.associateBy { it.id }
        _messages.value = snapshot.messages
        _attention.value = snapshot.attention.associateBy { it.id }
        if (snapshot.mote != null) _mote.value = snapshot.mote
        if (snapshot.health != null) _health.value = snapshot.health
        if (snapshot.autonomy != null) _autonomy.value = snapshot.autonomy
        entityVersions.clear()
        snapshot.tasks.forEach { entityVersions["task:${it.id}"] = it.entityVersion }
        snapshot.messages.forEach { entityVersions["chat:${it.id}"] = it.entityVersion }
        snapshot.attention.forEach { entityVersions["attention:${it.id}"] = it.entityVersion }
        snapshot.mote?.let { entityVersions["mote:active"] = it.entityVersion }
        snapshot.health?.let { entityVersions["health:device"] = it.entityVersion }
        snapshot.autonomy?.let { entityVersions["autonomy:global"] = it.entityVersion }
    }

    private val TimelineEvent.entityKey: String
        get() = "$entityType:$entityId"
}

data class TaskCardModel(
    val id: String,
    val title: String,
    val state: String,
    val progress: Int,
    val isPendingConfirmation: Boolean,
    val recentResult: String?,
    val chatContext: String?,
    val deepLink: String
)

object TaskCardProtocol {
    fun createCard(task: TimelineTask, chatSnippet: String? = null): TaskCardModel {
        val link = TimelineDeepLink.buildTaskLink(task.id)
        return TaskCardModel(
            id = task.id,
            title = task.title,
            state = task.state,
            progress = task.progress,
            isPendingConfirmation = task.isPendingConfirmation || task.state == "needs_confirmation",
            recentResult = task.recentResult,
            chatContext = chatSnippet,
            deepLink = link
        )
    }
}

enum class DeepLinkTarget {
    TASK,
    ATTENTION,
    CHAT,
    UNKNOWN
}

data class TimelineDeepLink(
    val target: DeepLinkTarget,
    val targetId: String
) {
    companion object {
        private const val SCHEME = "phonebridge"

        fun parse(uri: String): TimelineDeepLink {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("$SCHEME://")) {
                return TimelineDeepLink(DeepLinkTarget.UNKNOWN, "")
            }
            val path = trimmed.removePrefix("$SCHEME://")
            val parts = path.split("/")
            if (parts.size < 2) return TimelineDeepLink(DeepLinkTarget.UNKNOWN, "")

            val target = when (parts[0].lowercase(Locale.US)) {
                "task" -> DeepLinkTarget.TASK
                "attention" -> DeepLinkTarget.ATTENTION
                "chat" -> DeepLinkTarget.CHAT
                else -> DeepLinkTarget.UNKNOWN
            }
            return TimelineDeepLink(target, parts[1])
        }

        fun buildTaskLink(taskId: String): String = "$SCHEME://task/$taskId"
        fun buildAttentionLink(attentionId: String): String = "$SCHEME://attention/$attentionId"
        fun buildChatLink(sessionId: String): String = "$SCHEME://chat/$sessionId"
    }
}

class TimelineCacheRecovery {
    var lastKnownRevision: Long = 0L
        private set
    var hasGap: Boolean = false
        private set

    fun recordRevision(rev: Long) {
        lastKnownRevision = maxOf(lastKnownRevision, rev)
    }

    fun onEventReceived(rev: Long) {
        if (lastKnownRevision > 0L && rev > lastKnownRevision + 1L) {
            hasGap = true
        }
        lastKnownRevision = maxOf(lastKnownRevision, rev)
    }

    fun resetFromSnapshot(snapshotRev: Long) {
        lastKnownRevision = maxOf(lastKnownRevision, snapshotRev)
        hasGap = false
    }
}

// ----------------- Lightweight Internal JSON Parser -----------------

private fun Map<*, *>.string(key: String): String = (this[key]?.toString() ?: "").trim()
private fun Map<*, *>.nullableString(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }
private fun Map<*, *>.int(key: String): Int = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull() ?: 0
    else -> 0
}
private fun Map<*, *>.long(key: String): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: parseIsoTimestamp(v)
    else -> 0L
}
private fun Map<*, *>.float(key: String): Float = when (val v = this[key]) {
    is Number -> v.toFloat()
    is String -> v.toFloatOrNull() ?: 0f
    else -> 0f
}
private fun Map<*, *>.double(key: String): Double = when (val v = this[key]) {
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull() ?: 0.0
    else -> 0.0
}
private fun Map<*, *>.boolean(key: String): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.equals("true", ignoreCase = true)
    else -> false
}
private fun Map<*, *>.list(key: String): List<*> = (this[key] as? List<*>) ?: emptyList<Any>()

private fun parseIsoTimestamp(iso: String): Long = try {
    java.time.Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    0L
}

private class TimelineJsonParser(private val source: String) {
    private var index = 0

    fun parseValue(): Any? {
        skipWhitespace()
        if (index >= source.length) return null
        return when (source[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> null
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        if (peek('}')) {
            index++
            return emptyMap()
        }
        val result = linkedMapOf<String, Any?>()
        while (index < source.length) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            result[key] = value
            skipWhitespace()
            if (peek(',')) {
                index++
                skipWhitespace()
            } else if (peek('}')) {
                index++
                return result
            } else {
                break
            }
        }
        return result
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        if (peek(']')) {
            index++
            return emptyList()
        }
        val result = mutableListOf<Any?>()
        while (index < source.length) {
            result += parseValue()
            skipWhitespace()
            if (peek(',')) {
                index++
                skipWhitespace()
            } else if (peek(']')) {
                index++
                return result
            } else {
                break
            }
        }
        return result
    }

    private fun parseString(): String {
        skipWhitespace()
        if (!peek('"')) return ""
        expect('"')
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (index < source.length) {
                        val esc = source[index++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 <= source.length) {
                                    val hex = source.substring(index, index + 4)
                                    index += 4
                                    sb.append(hex.toInt(16).toChar())
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val start = index
        while (index < source.length && (source[index] in '0'..'9' || source[index] in ".-+eE")) {
            index++
        }
        val numStr = source.substring(start, index)
        return if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E')) {
            numStr.toDoubleOrNull() ?: 0.0
        } else {
            numStr.toLongOrNull() ?: 0L
        }
    }

    private fun parseLiteral(expected: String, value: Any?): Any? {
        if (source.startsWith(expected, index)) {
            index += expected.length
            return value
        }
        return null
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) {
            index++
        }
    }

    private fun peek(char: Char): Boolean = index < source.length && source[index] == char

    private fun expect(char: Char) {
        skipWhitespace()
        if (index < source.length && source[index] == char) {
            index++
        }
    }
}
