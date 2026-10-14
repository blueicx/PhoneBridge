package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityInventoryProtocolTest {
    @Test
    fun inventoryReadsServerEffectsQuantitiesAndEquipmentState() {
        val catalog = JSONArray()
            .put(JSONObject().put("id", "item_21").put("name", "晨露").put("kind", "boost")
                .put("effectLabel", "专注增益").put("effectDescription", "30 分钟内探索经验提高 50%。")
                .put("useEffect", JSONObject().put("kind", "xp_multiplier").put("durationMs", 1_800_000)))
            .put(JSONObject().put("id", "item_31").put("name", "蓝焰").put("kind", "equipment")
                .put("effectLabel", "巡游徽章").put("effectDescription", "每次有效探索额外获得 2 XP。")
                .put("equipmentEffect", JSONObject().put("key", "xpBonus").put("amount", 2)))
            .put(JSONObject().put("id", "unknown-kind").put("kind", "???"))
        val inventory = JSONObject().put("item_21", 2).put("item_31", 1).put("unknown", 9)
        val loadout = JSONArray().put("item_31")

        val items = RealityInventoryProtocol.parseItems(catalog, inventory, loadout)
        assertEquals(2, items.size)
        assertEquals(2, items.first { it.id == "item_21" }.quantity)
        assertTrue(items.first { it.id == "item_21" }.usable)
        assertEquals("专注增益", items.first { it.id == "item_21" }.effectLabel)
        assertTrue(items.first { it.id == "item_31" }.equipped)
        assertEquals("xpBonus", items.first { it.id == "item_31" }.equipmentKey)
        assertFalse(items.first { it.id == "item_31" }.usable)
    }

    @Test
    fun recipeAndGrowthPreviewsKeepRequirementsSourcesAndResults() {
        val recipes = JSONArray().put(JSONObject()
            .put("recipe", JSONObject().put("id", "recipe_01").put("name", "安定香"))
            .put("ingredients", JSONArray().put(JSONObject()
                .put("itemId", "item_01").put("name", "潮汐玻璃").put("owned", 0)
                .put("required", 1).put("missing", 1).put("sources", JSONArray().put("地点线索"))))
            .put("missing", JSONArray().put("item_01"))
            .put("canCraft", false)
            .put("output", JSONObject().put("name", "安定香")))
        val recipe = RealityInventoryProtocol.parseRecipes(recipes).single()
        assertEquals("recipe_01", recipe.id)
        assertFalse(recipe.canCraft)
        assertEquals("潮汐玻璃", recipe.ingredients.single().name)
        assertEquals(listOf("地点线索"), recipe.ingredients.single().sources)
        assertEquals(listOf("item_01"), recipe.missingItemIds)

        val records = JSONArray().put(JSONObject()
            .put("title", "专注计时").put("xpDelta", 4).put("relationshipDelta", 1)
            .put("source", "daily_activity").put("completedAt", 1_700_000_000_000L))
        val growth = RealityInventoryProtocol.parseGrowthRecords(records).single()
        assertEquals("专注计时", growth.title)
        assertEquals(4, growth.xpDelta)
        assertEquals(1, growth.relationshipDelta)
        assertEquals("daily_activity", growth.source)
    }

    @Test
    fun malformedOptionalFieldsUseSafeDefaultsAndUnknownEntriesAreIgnored() {
        val items = RealityInventoryProtocol.parseItems(
            JSONArray().put(JSONObject().put("id", "item_01").put("name", "材料").put("kind", "material")),
            JSONObject().put("item_01", -4),
            JSONArray()
        )
        assertEquals(0, items.single().quantity)
        assertFalse(items.single().usable)
        assertFalse(items.single().equipped)
        assertTrue(RealityInventoryProtocol.parseRecipes(JSONArray().put(JSONObject())).isEmpty())
        assertTrue(RealityInventoryProtocol.parseGrowthRecords(JSONArray().put(JSONObject())).isEmpty())
    }

    @Test
    fun pendingConsumableAndCraftReceiptsRemainActionableAfterTheServerChangesInventory() {
        assertTrue(RealityInventoryProtocol.shouldShowUseItem(quantity = 0, hasPendingReceipt = true))
        assertFalse(RealityInventoryProtocol.shouldShowUseItem(quantity = 0, hasPendingReceipt = false))
        assertEquals("重试确认", RealityInventoryProtocol.useActionLabel(hasPendingReceipt = true))
        assertTrue(RealityInventoryProtocol.canSubmitCraft(canCraft = false, hasPendingReceipt = true))
        assertFalse(RealityInventoryProtocol.canSubmitCraft(canCraft = false, hasPendingReceipt = false))
        assertEquals("重试确认", RealityInventoryProtocol.craftActionLabel(hasPendingReceipt = true))
        assertTrue(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = null))
        assertTrue(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = 408))
        assertTrue(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = 429))
        assertTrue(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = 503))
        assertFalse(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = 400))
        assertFalse(RealityInventoryProtocol.shouldKeepPendingReceipt(statusCode = 409))
    }
}
