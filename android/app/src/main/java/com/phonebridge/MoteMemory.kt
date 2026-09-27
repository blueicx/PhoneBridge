package com.phonebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

object MoteMemory {
    private const val PREFS = "mote_memory"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 50

    fun load(context: Context): MutableList<MemoryItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            val items = mutableListOf<MemoryItem>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) {
                    val created = item.optLong("createdAtMs", 0L)
                    items.add(
                        MemoryItem(
                            id = item.optString("id").ifBlank { stableId(text) },
                            text = text,
                            createdAtMs = created,
                            importance = (item.optInt("importance", 3)).coerceIn(1, 5),
                            lastUsedAtMs = item.optLong("lastUsedAtMs", 0L),
                            useCount = item.optInt("useCount", 0),
                            source = item.optString("source", "user"),
                            status = item.optString("status", "confirmed").takeIf { it in setOf("candidate", "confirmed") } ?: "confirmed",
                            excludedFromRecall = item.optBoolean("excludedFromRecall", false),
                            updatedAtMs = item.optLong("updatedAtMs", created)
                        )
                    )
                }
            }
            items
        }.getOrDefault(mutableListOf())
    }

    fun save(context: Context, items: List<MemoryItem>) {
        val array = JSONArray()
        items.take(MAX_ITEMS).forEach { item ->
            array.put(
                JSONObject()
                    .put("text", item.text)
                    .put("id", item.id)
                    .put("createdAtMs", item.createdAtMs)
                    .put("importance", item.importance)
                    .put("lastUsedAtMs", item.lastUsedAtMs)
                    .put("useCount", item.useCount)
                    .put("source", item.source)
                    .put("status", item.status)
                    .put("excludedFromRecall", item.excludedFromRecall)
                    .put("updatedAtMs", item.updatedAtMs)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    fun add(context: Context, text: String, source: String = "user", status: String = "confirmed"): List<MemoryItem> {
        val clean = text.trim().take(240)
        if (clean.isEmpty()) return load(context)
        val items = load(context)
        val id = stableId(clean)
        val existing = items.firstOrNull { it.text.equals(clean, ignoreCase = true) }
        if (status == "candidate" && existing?.status == "confirmed") return items
        val now = System.currentTimeMillis()
        if (items.none { it.text.equals(clean, ignoreCase = true) }) {
            items.add(
                0,
                MemoryItem(
                    id = id,
                    text = clean,
                    createdAtMs = now,
                    importance = detectImportance(clean),
                    source = source,
                    status = if (status == "candidate") "candidate" else "confirmed",
                    updatedAtMs = now
                )
            )
        } else {
            items.removeAll { it.text.equals(clean, ignoreCase = true) }
            items.add(
                0,
                MemoryItem(
                    id = id,
                    text = clean,
                    createdAtMs = now,
                    importance = detectImportance(clean),
                    source = source,
                    status = if (status == "candidate") "candidate" else "confirmed",
                    updatedAtMs = now
                )
            )
        }
        val saved = items.take(MAX_ITEMS)
        save(context, saved)
        return saved.toMutableList()
    }

    fun addCandidate(context: Context, text: String, source: String = "auto_extract"): List<MemoryItem> =
        add(context, text, source = source, status = "candidate")

    fun confirmById(context: Context, id: String): List<MemoryItem> = updateById(context, id) { it.copy(status = "confirmed") }

    fun setExcludedFromRecall(context: Context, id: String, excluded: Boolean): List<MemoryItem> =
        updateById(context, id) { it.copy(excludedFromRecall = excluded) }

    fun updateById(context: Context, id: String, text: String): List<MemoryItem> {
        val clean = text.trim().take(240)
        if (clean.isEmpty()) return load(context)
        return updateById(context, id) { it.copy(text = clean, importance = detectImportance(clean)) }
    }

    private fun updateById(context: Context, id: String, transform: (MemoryItem) -> MemoryItem): List<MemoryItem> {
        val now = System.currentTimeMillis()
        val items = load(context).map { item ->
            if (item.id == id) transform(item).copy(updatedAtMs = now) else item
        }
        save(context, items)
        return items.toMutableList()
    }

    fun removeAt(context: Context, index: Int): List<MemoryItem> {
        val items = load(context)
        if (index in items.indices) items.removeAt(index)
        save(context, items)
        return items
    }

    fun removeById(context: Context, id: String): List<MemoryItem> {
        val items = load(context).filterNot { it.id == id }
        save(context, items)
        return items.toMutableList()
    }

    fun relevant(context: Context, query: String, limit: Int = 12): List<MemoryItem> {
        val items = load(context)
        val recallable = items.filter { ChatMemoryPolicy.isRecallEligible(it.status == "confirmed", it.excludedFromRecall) }
        if (recallable.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val keys = keywords(query)
        return recallable.map { item ->
            val overlap = keys.count { key ->
                item.text.contains(key, ignoreCase = true) ||
                    key.length >= 2 && item.text.replace(" ", "").contains(key, ignoreCase = true)
            }.toFloat()
            val ageDays = ((now - maxOf(item.createdAtMs, 1L)) / 86_400_000f).coerceAtLeast(0f)
            val usedDays = if (item.lastUsedAtMs <= 0) 30f else ((now - item.lastUsedAtMs) / 86_400_000f).coerceAtLeast(0f)
            val score = overlap * 8f + item.importance * 2f + item.useCount * 0.4f - min(ageDays, 60f) * .03f - min(usedDays, 60f) * .02f
            item to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun touch(context: Context, items: List<MemoryItem>) {
        if (items.isEmpty()) return
        val all = load(context)
        val now = System.currentTimeMillis()
        val touchedIds = items.map { it.id }.toSet()
        val updated = all.map {
            if (it.id in touchedIds) it.copy(lastUsedAtMs = now, useCount = it.useCount + 1) else it
        }
        save(context, updated)
    }

    fun removeMatching(context: Context, query: String): List<MemoryItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return load(context)
        val items = load(context).filterNot { it.text.contains(needle, ignoreCase = true) }
        save(context, items)
        return items.toMutableList()
    }

    private fun stableId(text: String): String =
        abs(text.lowercase().hashCode()).toString(36) + "_" + text.length

    private fun detectImportance(text: String): Int = when {
        Regex("( allergy|过敏|药物|生日|纪念日|名字|喜欢|讨厌|害怕|重要)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 5
        Regex("(工作|项目|学校|课程|会议|截止|地址|电话)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 4
        else -> 3
    }

    private fun keywords(query: String): List<String> =
        query.split(Regex("[\\s,.，。！？；;:：]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(16)

    fun clear(context: Context): List<MemoryItem> {
        save(context, emptyList())
        return mutableListOf()
    }
}
