package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ExplorationLogStatus { CONFIRMED, PENDING, REJECTED }

data class ExplorationLogItem(val id: String, val amount: Int)

data class ExplorationLogBoost(
    val id: String,
    val expiresAt: Long,
    val multiplier: Double
)

data class ExplorationLogReward(
    val xp: Int,
    val items: List<ExplorationLogItem> = emptyList(),
    val boost: ExplorationLogBoost? = null
)

data class ExplorationLogEntry(
    val eventId: String,
    val status: ExplorationLogStatus,
    val occurredAt: Long,
    val coarseRegion: String,
    val clueType: String,
    val moteId: String? = null,
    val observation: String? = null,
    val reward: ExplorationLogReward? = null,
    val reason: String? = null,
    val acknowledged: Boolean = false
) {
    companion object {
        val SERVER_FIELDS = setOf(
            "eventId",
            "status",
            "occurredAt",
            "coarseRegion",
            "clueType",
            "moteId",
            "observation",
            "reward"
        )
    }
}

data class ExplorationLogPage(val entries: List<ExplorationLogEntry>, val nextCursor: String?)

data class ExplorationLogSnapshot(
    val entries: List<ExplorationLogEntry> = emptyList(),
    val nextCursor: String? = null,
    val privacyRevision: Long? = null,
    val revisionChanged: Boolean = false,
    val requiresRefreshFromStart: Boolean = false
)

class ExplorationLogRequestGate {
    private var generation = 0L

    @Synchronized
    fun begin(cursor: String?): Long {
        if (cursor == null) generation += 1L
        return generation
    }

    @Synchronized
    fun reset(): Long {
        generation += 1L
        return generation
    }

    @Synchronized
    fun isCurrent(requestGeneration: Long): Boolean = requestGeneration == generation
}

object ExplorationLogParser {
    private const val MAX_PAGE_SIZE = 100
    private const val MAX_EVENT_ID_LENGTH = 180
    private const val MAX_CURSOR_LENGTH = 512
    private const val MAX_TEXT_LENGTH = 240
    private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L
    private val safeEventId = Regex("^[A-Za-z0-9._~:-]{1,$MAX_EVENT_ID_LENGTH}$")
    private val safeRegion = Regex("^(?:camera|cell:-?\\d{1,8}:-?\\d{1,8})$")
    private val safeId = Regex("^[A-Za-z0-9._-]{1,80}$")
    private val safeReason = Regex("^[a-z0-9_.:-]{1,80}$")
    private val safeCursor = Regex("^[A-Za-z0-9_-]{1,$MAX_CURSOR_LENGTH}$")
    private val clueTypes = setOf("location", "object", "light")

    fun parsePage(json: JSONObject): ExplorationLogPage {
        val source = json.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until source.length().coerceAtMost(MAX_PAGE_SIZE)) {
                parseServerEntry(source.optJSONObject(index))?.let(::add)
            }
        }.distinctBy { it.eventId }
        val cursor = json.optString("nextCursor")
            .takeIf { it.isNotBlank() && safeCursor.matches(it) }
            .takeUnless { json.isNull("nextCursor") }
        return ExplorationLogPage(entries, cursor)
    }

    fun fromOutbox(entity: WorkspaceOutboxEntity, progressRevision: Long): ExplorationLogEntry? {
        if (progressRevision < 0L || entity.type != WorkspaceEventTypes.MOTE_EXPLORATION || entity.quarantined) return null
        if (entity.privacyCategory != "progress" || entity.privacyRevision != progressRevision) return null
        val revisions = runCatching { JSONObject(entity.privacyRevisionsJson) }.getOrNull() ?: return null
        if (!revisions.has("progress") || revisions.optLong("progress", Long.MIN_VALUE) != progressRevision) return null

        val payload = runCatching { JSONObject(entity.payload) }.getOrNull() ?: return null
        val eventId = payload.optString("eventId")
        val clueType = normalizeClueType(payload.optString("clueType")) ?: return null
        val region = payload.optString("region")
        if (!safeEventId.matches(eventId) || !safeRegion.matches(region)) return null
        val occurredAt = payload.optLong("activityAt", entity.createdAt)
            .takeIf { it >= 0L } ?: return null

        if (entity.ack && entity.businessStatus.equals("rejected", ignoreCase = true)) {
            val reason = entity.businessReason?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf(safeReason::matches) ?: "rejected"
            return ExplorationLogEntry(
                eventId = eventId,
                status = ExplorationLogStatus.REJECTED,
                occurredAt = occurredAt,
                coarseRegion = region,
                clueType = clueType,
                reason = reason,
                acknowledged = true
            )
        }

        // A transport ACK only confirms receipt. Rewards remain hidden until a server receipt arrives.
        return ExplorationLogEntry(
            eventId = eventId,
            status = ExplorationLogStatus.PENDING,
            occurredAt = occurredAt,
            coarseRegion = region,
            clueType = clueType,
            acknowledged = entity.ack
        )
    }

    private fun parseServerEntry(json: JSONObject?): ExplorationLogEntry? {
        json ?: return null
        val eventId = json.optString("eventId")
        val status = json.optString("status")
        val occurredAt = integralLong(json.opt("occurredAt")) ?: return null
        val region = json.optString("coarseRegion")
        val clueType = normalizeClueType(json.optString("clueType")) ?: return null
        if (!safeEventId.matches(eventId) || !safeRegion.matches(region) || status != "confirmed") return null
        val reward = parseReward(json.optJSONObject("reward")) ?: return null
        val moteId = json.optString("moteId").takeIf(safeId::matches)
        val observation = json.optString("observation")
            .filterNot(Char::isISOControl)
            .trim()
            .take(MAX_TEXT_LENGTH)
            .takeIf(String::isNotBlank)
        return ExplorationLogEntry(
            eventId = eventId,
            status = ExplorationLogStatus.CONFIRMED,
            occurredAt = occurredAt,
            coarseRegion = region,
            clueType = clueType,
            moteId = moteId,
            observation = observation,
            reward = reward
        )
    }

    private fun parseReward(json: JSONObject?): ExplorationLogReward? {
        json ?: return null
        val xp = integralInt(json.opt("xp"))?.takeIf { it in 0..1_000_000 } ?: return null
        val itemsJson = json.optJSONArray("items") ?: JSONArray()
        if (itemsJson.length() > 32) return null
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val amount = integralInt(item.opt("amount")) ?: continue
                if (safeId.matches(id) && amount in 1..1_000_000) add(ExplorationLogItem(id, amount))
            }
        }
        val boostJson = json.optJSONObject("boost")
        val boost = if (boostJson?.optString("id") == "field-focus") {
            val expiresAt = integralLong(boostJson.opt("expiresAt"))
            val multiplier = boostJson.optDouble("multiplier", Double.NaN)
            if (expiresAt != null && expiresAt >= 0L && multiplier.isFinite() && multiplier in 1.0..2.0) {
                ExplorationLogBoost("field-focus", expiresAt, multiplier)
            } else null
        } else null
        return ExplorationLogReward(xp = xp, items = items, boost = boost)
    }

    private fun normalizeClueType(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
        "location", "place" -> "location"
        "object" -> "object"
        "light" -> "light"
        else -> null
    }?.takeIf(clueTypes::contains)

    private fun integralLong(value: Any?): Long? {
        val number = value as? Number ?: return null
        val doubleValue = number.toDouble()
        if (!doubleValue.isFinite() || doubleValue < 0.0 || doubleValue > MAX_SAFE_JSON_INTEGER.toDouble()) return null
        val longValue = number.toLong()
        return longValue.takeIf { it.toDouble() == doubleValue }
    }

    private fun integralInt(value: Any?): Int? {
        val longValue = integralLong(value) ?: return null
        return longValue.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}

class ExplorationLogStore {
    private companion object {
        const val MAX_CACHED_CONFIRMED_ENTRIES = 500
    }

    private val confirmedById = linkedMapOf<String, ExplorationLogEntry>()
    private var currentRevision: Long? = null
    private var cursor: String? = null

    @Synchronized
    fun applyPage(
        page: ExplorationLogPage,
        outbox: List<WorkspaceOutboxEntity>,
        progressRevision: Long
    ): ExplorationLogSnapshot {
        require(progressRevision >= 0L) { "progress revision must be non-negative" }
        val storedRevision = currentRevision
        if (storedRevision != null && progressRevision < storedRevision) {
            return ExplorationLogSnapshot(
                entries = confirmedById.values
                    .sortedWith(compareByDescending<ExplorationLogEntry> { it.occurredAt }.thenByDescending { it.eventId }),
                nextCursor = cursor,
                privacyRevision = storedRevision,
                requiresRefreshFromStart = true
            )
        }
        if (storedRevision != null && storedRevision != progressRevision) {
            confirmedById.clear()
            currentRevision = progressRevision
            cursor = null
            return ExplorationLogSnapshot(
                privacyRevision = progressRevision,
                revisionChanged = true,
                requiresRefreshFromStart = true
            )
        }
        currentRevision = progressRevision
        page.entries
            .filter { it.status == ExplorationLogStatus.CONFIRMED }
            .forEach { confirmedById[it.eventId] = it }
        if (confirmedById.size > MAX_CACHED_CONFIRMED_ENTRIES) {
            confirmedById.values
                .sortedWith(compareBy<ExplorationLogEntry> { it.occurredAt }.thenBy { it.eventId })
                .take(confirmedById.size - MAX_CACHED_CONFIRMED_ENTRIES)
                .forEach { confirmedById.remove(it.eventId) }
        }
        cursor = page.nextCursor

        val local = outbox.asSequence()
            .mapNotNull { ExplorationLogParser.fromOutbox(it, progressRevision) }
            .filterNot { it.eventId in confirmedById }
            .distinctBy { it.eventId }
            .toList()
        val merged = (confirmedById.values + local)
            .distinctBy { it.eventId }
            .sortedWith(compareByDescending<ExplorationLogEntry> { it.occurredAt }.thenByDescending { it.eventId })
        return ExplorationLogSnapshot(
            entries = merged,
            nextCursor = cursor,
            privacyRevision = progressRevision
        )
    }

    @Synchronized
    fun reset() {
        confirmedById.clear()
        currentRevision = null
        cursor = null
    }
}
