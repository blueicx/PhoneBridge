package com.phonebridge

import org.json.JSONArray

data class MoteStoryEntry(
    val id: String,
    val title: String,
    val description: String,
    val trigger: String,
    val rewardXp: Int,
    val completed: Boolean,
    val claimed: Boolean,
    val moteId: String = "",
    val moteName: String = "",
    val exclusive: Boolean = false,
    val completion: String = "",
)

object MoteStoryProtocol {
    fun parse(array: JSONArray?): List<MoteStoryEntry> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optJSONObject(index) ?: continue
                val reward = value.optJSONObject("reward")
                val entry = parseRow(
                    mapOf(
                        "id" to value.optString("id"),
                        "title" to value.optString("title"),
                        "description" to value.optString("description"),
                        "trigger" to value.optString("trigger"),
                        "rewardXp" to (reward?.optInt("xp", 0) ?: 0),
                        "completed" to value.optBoolean("completed", false),
                        "claimed" to value.optBoolean("claimed", false),
                        "moteId" to value.optString("moteId"),
                        "moteName" to value.optString("moteName"),
                        "exclusive" to value.optBoolean("exclusive", false),
                        "completion" to value.optString("completion"),
                    )
                )
                if (entry != null) add(entry)
            }
        }
    }

    fun parseRows(rows: List<Map<String, Any?>>): List<MoteStoryEntry> = rows.mapNotNull(::parseRow)

    private fun parseRow(value: Map<String, Any?>): MoteStoryEntry? {
        val id = value["id"].toString().trim().takeIf { it.isNotBlank() && it != "null" } ?: return null
        return MoteStoryEntry(
            id = id,
            title = value["title"].toString().takeIf { it.isNotBlank() && it != "null" } ?: id,
            description = value["description"].toString().takeUnless { it == "null" }.orEmpty(),
            trigger = value["trigger"].toString().takeUnless { it == "null" }.orEmpty(),
            rewardXp = (value["rewardXp"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
            completed = value["completed"] as? Boolean ?: false,
            claimed = value["claimed"] as? Boolean ?: false,
            moteId = value["moteId"].toString().takeUnless { it == "null" }.orEmpty(),
            moteName = value["moteName"].toString().takeUnless { it == "null" }.orEmpty(),
            exclusive = value["exclusive"] as? Boolean ?: false,
            completion = value["completion"].toString().takeUnless { it == "null" }.orEmpty(),
        )
    }

    fun summary(array: JSONArray?): String {
        return summary(parse(array))
    }

    fun summary(entries: List<MoteStoryEntry>): String {
        val completed = entries.count { it.completed }
        val claimed = entries.count { it.claimed }
        return "剧情 ${completed}/${entries.size} · 已领奖 $claimed"
    }

    fun fromJson(text: String): List<MoteStoryEntry> = runCatching { parse(JSONArray(text)) }.getOrDefault(emptyList())
}
