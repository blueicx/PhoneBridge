package com.phonebridge

import org.json.JSONArray
import org.json.JSONObject

object HabitatLayoutProtocol {
    private val knownVisuals = setOf(
        "star-lamp", "moss-stone", "cloud-platform", "wind-chime", "moon-pool",
        "flower-rack", "crystal-cluster", "warm-hearth", "sand-dune", "feather-curtain",
    )

    fun parse(catalog: JSONArray?, habitat: JSONObject?): List<StageDecoration> {
        val definitions = buildMap {
            val source = catalog ?: return@buildMap
            for (index in 0 until minOf(source.length(), 100)) {
                val value = source.optJSONObject(index) ?: continue
                val id = value.optString("id").trim()
                if (id.isBlank()) continue
                put(id, value)
            }
        }
        val ids = distinctIds(habitat?.optJSONArray("decorations")).filter { it in definitions }.take(12)
        val savedPositions = buildMap {
            val source = habitat?.optJSONArray("layout") ?: return@buildMap
            for (index in 0 until minOf(source.length(), 100)) {
                val value = source.optJSONObject(index) ?: continue
                val id = value.optString("id").trim()
                if (id.isNotBlank()) put(id, value)
            }
        }
        return ids.mapIndexed { slot, id ->
            val definition = definitions.getValue(id)
            val position = savedPositions[id]
            val defaultX = (0.16 + (slot % 4) * 0.225).toFloat()
            val defaultY = (0.79 - (slot / 4) * 0.16).toFloat()
            val x = position?.optDouble("x", Double.NaN)?.toFloat()
                ?.takeIf { it.isFinite() && it in 0f..1f } ?: defaultX
            val y = position?.optDouble("y", Double.NaN)?.toFloat()
                ?.takeIf { it.isFinite() && it in 0f..1f } ?: defaultY
            val rotation = position?.optDouble("rotation", 0.0)?.toFloat()
                ?.takeIf { it.isFinite() && it in -180f..180f } ?: 0f
            val visual = definition.optString("visualType", "star-lamp").takeIf { it in knownVisuals } ?: "star-lamp"
            val interaction = definition.optString("interaction", interactionFor(visual))
                .takeIf { it.matches(Regex("^tap-[a-z-]{2,24}$")) } ?: interactionFor(visual)
            StageDecoration(
                id = id,
                name = definition.optString("name", "家园摆件").ifBlank { "家园摆件" },
                visualType = visual,
                interaction = interaction,
                x = x,
                y = y,
                rotation = rotation,
            )
        }
    }

    fun parseCached(json: JSONArray?): List<StageDecoration> {
        val source = json ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(source.length(), 12)) {
                val value = source.optJSONObject(index) ?: continue
                val id = value.optString("id").trim()
                val name = value.optString("name").trim()
                if (id.isBlank() || name.isBlank()) continue
                val visual = value.optString("visualType", "star-lamp").takeIf { it in knownVisuals } ?: "star-lamp"
                add(StageDecoration(
                    id = id,
                    name = name,
                    visualType = visual,
                    interaction = value.optString("interaction", interactionFor(visual)),
                    x = value.optDouble("x", (0.16 + (size % 4) * 0.225)).toFloat().coerceIn(0f, 1f),
                    y = value.optDouble("y", (0.79 - (size / 4) * 0.16)).toFloat().coerceIn(0f, 1f),
                    rotation = value.optDouble("rotation", 0.0).toFloat().coerceIn(-180f, 180f),
                ))
            }
        }
    }

    private fun distinctIds(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(values.length(), 100)) {
                val id = values.optString(index).trim()
                if (id.isNotBlank() && id !in this) add(id)
            }
        }
    }

    private fun interactionFor(visual: String): String = when (visual) {
        "star-lamp" -> "tap-glow"
        "moss-stone" -> "tap-soothe"
        "cloud-platform" -> "tap-sit"
        "wind-chime" -> "tap-chime"
        "moon-pool" -> "tap-ripple"
        "flower-rack" -> "tap-bloom"
        "crystal-cluster" -> "tap-pulse"
        "warm-hearth" -> "tap-warm"
        "sand-dune" -> "tap-rest"
        else -> "tap-wave"
    }
}
