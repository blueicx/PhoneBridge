package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitatLayoutProtocolTest {
    @Test
    fun parsesVersionedLayoutUsingExplicitCatalogPresentation() {
        val catalog = JSONArray()
            .put(JSONObject().put("id", "decor_01").put("name", "星灯").put("visualType", "star-lamp").put("interaction", "tap-glow"))
            .put(JSONObject().put("id", "decor_02").put("name", "苔石").put("visualType", "moss-stone").put("interaction", "tap-soothe"))
        val habitat = JSONObject()
            .put("layoutVersion", 2)
            .put("decorations", JSONArray().put("decor_02").put("decor_01"))
            .put("layout", JSONArray()
                .put(JSONObject().put("id", "decor_02").put("x", 0.61).put("y", 0.44).put("rotation", -15))
                .put(JSONObject().put("id", "decor_01").put("x", 0.25).put("y", 0.78)))

        val parsed = HabitatLayoutProtocol.parse(catalog, habitat)

        assertEquals(2, parsed.size)
        assertEquals("moss-stone", parsed[0].visualType)
        assertEquals("tap-soothe", parsed[0].interaction)
        assertEquals(0.61f, parsed[0].x, 0.001f)
        assertEquals(0.44f, parsed[0].y, 0.001f)
        assertEquals(-15f, parsed[0].rotation, 0.001f)
    }

    @Test
    fun legacyDecorationIdsReceiveStableDefaultPositionsAndUnknownEntriesAreSkipped() {
        val catalog = JSONArray()
            .put(JSONObject().put("id", "decor_01").put("name", "星灯").put("visualType", "star-lamp").put("interaction", "tap-glow"))
            .put(JSONObject().put("id", "decor_02").put("name", "苔石"))
        val habitat = JSONObject().put("decorations", JSONArray().put("decor_01").put("missing").put("decor_02"))

        val parsed = HabitatLayoutProtocol.parse(catalog, habitat)

        assertEquals(listOf("decor_01", "decor_02"), parsed.map { it.id })
        assertEquals(0.16f, parsed[0].x, 0.001f)
        assertEquals(0.79f, parsed[0].y, 0.001f)
        assertEquals("star-lamp", parsed[0].visualType)
        assertEquals("star-lamp", parsed[1].visualType)
    }

    @Test
    fun parserBoundsLayoutToTwelveAndRejectsUnsafeCoordinatesByFallingBack() {
        val catalog = JSONArray()
        val ids = JSONArray()
        val layouts = JSONArray()
        repeat(14) { index ->
            val id = "decor_${(index + 1).toString().padStart(2, '0')}"
            catalog.put(JSONObject().put("id", id).put("name", "摆件$index").put("visualType", "crystal-cluster"))
            ids.put(id)
            layouts.put(JSONObject().put("id", id).put("x", 4.0).put("y", -1.0))
        }

        val parsed = HabitatLayoutProtocol.parse(catalog, JSONObject().put("decorations", ids).put("layout", layouts))

        assertEquals(12, parsed.size)
        assertTrue(parsed.all { it.x in 0f..1f && it.y in 0f..1f })
        assertEquals("tap-pulse", parsed.first().interaction)
    }
}
