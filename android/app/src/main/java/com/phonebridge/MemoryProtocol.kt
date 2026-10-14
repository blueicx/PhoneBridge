package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object MemoryProtocol {
    fun parseList(response: JSONObject): List<MemoryItem> {
        return parseItems(response.optJSONArray("memories"))
    }

    fun parseItems(values: JSONArray?): List<MemoryItem> {
        val items = values ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val value = items.optJSONObject(index) ?: continue
                val text = value.optString("text").trim()
                if (text.isEmpty()) continue
                val source = value.optString("source", "user").ifBlank { "user" }
                val createdAt = epochMillis(value.opt("createdAt") ?: value.opt("createdAtMs"))
                val provenance = value.optJSONArray("provenance").toStringList().ifEmpty { listOf(source) }
                add(
                    MemoryItem(
                        id = value.optString("id").ifBlank { fallbackId(text) },
                        text = text,
                        createdAtMs = createdAt,
                        importance = value.optInt("importance", 3).coerceIn(1, 5),
                        lastUsedAtMs = epochMillis(value.opt("lastUsedAt") ?: value.opt("lastUsedAtMs")),
                        useCount = value.optInt("useCount", 0).coerceAtLeast(0),
                        source = source,
                        status = value.optString("status", "confirmed").takeIf { it == "candidate" || it == "confirmed" } ?: "confirmed",
                        excludedFromRecall = value.optBoolean("excludedFromRecall", false),
                        updatedAtMs = epochMillis(value.opt("updatedAt") ?: value.opt("updatedAtMs")).takeIf { it > 0 } ?: createdAt,
                        lastUsedReason = value.optString("lastUsedReason", ""),
                        provenance = provenance,
                        mergedFrom = value.optJSONArray("mergedFrom").toStringList(),
                        possibleConflicts = value.optJSONArray("possibleConflicts").toMemoryConflicts(),
                        pendingSync = false
                    )
                )
            }
        }
    }

    fun mergeRemote(remote: List<MemoryItem>, local: List<MemoryItem>): List<MemoryItem> {
        val localById = local.associateBy { it.id }
        val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
        val synchronized = remote.map { item ->
            val cached = localById[item.id] ?: return@map item.copy(pendingSync = false)
            item.copy(
                importance = cached.importance,
                useCount = maxOf(item.useCount, cached.useCount),
                lastUsedAtMs = maxOf(item.lastUsedAtMs, cached.lastUsedAtMs),
                lastUsedReason = item.lastUsedReason.ifBlank { cached.lastUsedReason },
                pendingSync = false
            )
        }
        val pending = local.filter { it.pendingSync && it.id !in remoteIds }
            .sortedByDescending { it.updatedAtMs }
        return (pending + synchronized).take(50)
    }

    private fun epochMillis(value: Any?): Long = when (value) {
        is Number -> value.toLong().coerceAtLeast(0L)
        is String -> value.toLongOrNull()?.coerceAtLeast(0L)
            ?: runCatching { Instant.parse(value).toEpochMilli().coerceAtLeast(0L) }.getOrDefault(0L)
        else -> 0L
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }.distinct().take(100)
    }

    private fun JSONArray?.toMemoryConflicts(): List<MemoryConflict> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optJSONObject(index) ?: continue
                val text = value.optString("text").trim()
                if (text.isEmpty()) continue
                add(
                    MemoryConflict(
                        id = value.optString("id"),
                        text = text,
                        source = value.optString("source", "user"),
                        similarity = value.optDouble("similarity", 0.0).toFloat().coerceIn(0f, 1f),
                        reason = value.optString("reason", "")
                    )
                )
            }
        }.take(20)
    }

    private fun fallbackId(text: String) = "remote_${text.lowercase().hashCode().toUInt().toString(36)}"
}
