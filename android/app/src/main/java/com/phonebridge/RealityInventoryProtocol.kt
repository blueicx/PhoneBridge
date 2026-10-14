package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject

data class RealityInventoryItem(
    val id: String,
    val name: String,
    val kind: String,
    val quantity: Int,
    val effectLabel: String = "",
    val effectDescription: String = "",
    val useEffectKind: String = "",
    val useDurationMs: Long = 0L,
    val equipmentKey: String = "",
    val equipmentAmount: Int = 0,
    val usable: Boolean = false,
    val equipped: Boolean = false,
)

data class RealityRecipeIngredient(
    val itemId: String,
    val name: String,
    val owned: Int,
    val required: Int,
    val missing: Int,
    val sources: List<String>,
)

data class RealityRecipePreview(
    val id: String,
    val name: String,
    val canCraft: Boolean,
    val ingredients: List<RealityRecipeIngredient>,
    val missingItemIds: List<String>,
    val outputName: String,
)

data class RealityGrowthRecord(
    val title: String,
    val xpDelta: Int,
    val relationshipDelta: Int,
    val source: String,
    val completedAt: Long,
)

object RealityInventoryProtocol {
    private val itemKinds = setOf("material", "boost", "equipment")

    fun shouldShowUseItem(quantity: Int, hasPendingReceipt: Boolean): Boolean =
        quantity > 0 || hasPendingReceipt

    fun useActionLabel(hasPendingReceipt: Boolean): String =
        if (hasPendingReceipt) "重试确认" else "使用"

    fun canSubmitCraft(canCraft: Boolean, hasPendingReceipt: Boolean): Boolean =
        canCraft || hasPendingReceipt

    fun craftActionLabel(hasPendingReceipt: Boolean): String =
        if (hasPendingReceipt) "重试确认" else "制作"

    fun shouldKeepPendingReceipt(statusCode: Int?): Boolean =
        statusCode == null || statusCode == 408 || statusCode == 429 || statusCode >= 500

    fun parseItems(catalog: JSONArray?, inventory: JSONObject?, loadout: JSONArray?): List<RealityInventoryItem> {
        val equippedIds = buildSet {
            for (index in 0 until (loadout?.length() ?: 0)) {
                loadout?.optString(index)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }
        return buildList {
            for (index in 0 until (catalog?.length() ?: 0)) {
                val item = catalog?.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val kind = item.optString("kind").trim().lowercase()
                if (id.isEmpty() || name.isEmpty() || kind !in itemKinds) continue
                val useEffect = item.optJSONObject("useEffect")
                val equipmentEffect = item.optJSONObject("equipmentEffect")
                add(RealityInventoryItem(
                    id = id,
                    name = name,
                    kind = kind,
                    quantity = inventory?.optInt(id, 0)?.coerceAtLeast(0) ?: 0,
                    effectLabel = item.optString("effectLabel").trim(),
                    effectDescription = item.optString("effectDescription").trim(),
                    useEffectKind = useEffect?.optString("kind")?.trim().orEmpty(),
                    useDurationMs = useEffect?.optLong("durationMs", 0L)?.coerceAtLeast(0L) ?: 0L,
                    equipmentKey = equipmentEffect?.optString("key")?.trim().orEmpty(),
                    equipmentAmount = equipmentEffect?.optInt("amount", 0)?.coerceAtLeast(0) ?: 0,
                    usable = kind == "boost" && useEffect != null,
                    equipped = id in equippedIds,
                ))
            }
        }
    }

    fun parseRecipes(payload: JSONArray?): List<RealityRecipePreview> = buildList {
        for (index in 0 until (payload?.length() ?: 0)) {
            val value = payload?.optJSONObject(index) ?: continue
            val recipe = value.optJSONObject("recipe") ?: continue
            val id = recipe.optString("id").trim()
            val name = recipe.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            val ingredients = buildList {
                val source = value.optJSONArray("ingredients")
                for (itemIndex in 0 until (source?.length() ?: 0)) {
                    val item = source?.optJSONObject(itemIndex) ?: continue
                    val itemId = item.optString("itemId").trim()
                    val itemName = item.optString("name").trim()
                    if (itemId.isEmpty() || itemName.isEmpty()) continue
                    val required = item.optInt("required", 1).coerceAtLeast(1)
                    val owned = item.optInt("owned", 0).coerceAtLeast(0)
                    val missing = item.optInt("missing", (required - owned).coerceAtLeast(0)).coerceAtLeast(0)
                    val sources = buildList {
                        val sourceNames = item.optJSONArray("sources")
                        for (sourceIndex in 0 until (sourceNames?.length() ?: 0)) {
                            sourceNames?.optString(sourceIndex)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                        }
                    }.distinct()
                    add(RealityRecipeIngredient(itemId, itemName, owned, required, missing, sources))
                }
            }
            val missingIds = buildList {
                val source = value.optJSONArray("missing")
                for (missingIndex in 0 until (source?.length() ?: 0)) {
                    source?.optString(missingIndex)?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                }
            }.distinct()
            add(RealityRecipePreview(
                id = id,
                name = name,
                canCraft = value.optBoolean("canCraft", missingIds.isEmpty() && ingredients.all { it.missing == 0 }),
                ingredients = ingredients,
                missingItemIds = missingIds,
                outputName = value.optJSONObject("output")?.optString("name")?.trim().orEmpty(),
            ))
        }
    }

    fun parseGrowthRecords(payload: JSONArray?): List<RealityGrowthRecord> = buildList {
        for (index in 0 until (payload?.length() ?: 0)) {
            val item = payload?.optJSONObject(index) ?: continue
            val title = item.optString("title").trim()
            if (title.isEmpty()) continue
            add(RealityGrowthRecord(
                title = title,
                xpDelta = item.optInt("xpDelta", 0).coerceAtLeast(0),
                relationshipDelta = item.optInt("relationshipDelta", 0).coerceAtLeast(0),
                source = item.optString("source").trim(),
                completedAt = item.optLong("completedAt", 0L).coerceAtLeast(0L),
            ))
        }
    }
}
