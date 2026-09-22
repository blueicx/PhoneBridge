package com.phonebridge

import kotlin.math.floor
import java.time.Instant
import java.time.ZoneId

data class RealityEvent(
    val id: String,
    val region: String,
    val kind: String,
    val clueType: String,
    val bearing: Int,
    val distanceBand: String,
    val expiresAt: Long,
    val seed: String,
)

data class RealityInventory(val items: Map<String, Int> = emptyMap())

data class RealityProgress(
    val xp: Int = 0,
    val level: Int = 1,
    val inventory: RealityInventory = RealityInventory(),
    val loadout: List<String> = emptyList(),
    val habitatComfort: Int = 0,
)

object RealityClueProtocol {
    private val aliases = mapOf("place" to "location")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    fun canonicalType(value: String?): String {
        val normalized = value?.trim()?.lowercase() ?: ""
        return aliases[normalized] ?: normalized.takeIf { it in setOf("location", "object", "light") } ?: "location"
    }

    fun eventId(nodeId: String?, coarseRegion: String? = null, activityAt: Long = System.currentTimeMillis()): String {
        val clue = canonicalType(nodeId)
        val region = coarseRegion?.trim()?.takeIf { it.startsWith("cell:") } ?: "camera"
        val date = Instant.ofEpochMilli(activityAt).atZone(shanghai).toLocalDate()
        return "reality-lens:v2:$date:$region:$clue"
    }

    fun booleanField(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in setOf("1", "true", "yes", "on", "y")
        else -> false
    }
}

object RealityRegion {
    fun fromCoordinates(latitude: Double, longitude: Double, cellSize: Double = .02): String {
        require(latitude.isFinite() && longitude.isFinite() && cellSize > 0)
        return "cell:${floor(latitude / cellSize).toInt()}:${floor(longitude / cellSize).toInt()}"
    }

    fun normalizeBearing(value: Int): Int = ((value % 360) + 360) % 360
    fun isUsableDistance(value: String): Boolean = value in setOf("near", "mid", "far")
}
