package com.phonebridge

object RealityDiscoveryProgress {
    private val clueTypes = setOf("location", "object", "light")

    fun forDate(saved: Set<String>, activityDate: String): Set<String> {
        val prefix = "$activityDate|"
        return saved.mapNotNull { value ->
            val candidate = when {
                value.startsWith(prefix) -> value.substringAfter('|')
                '|' !in value -> value
                else -> return@mapNotNull null
            }
            val normalized = if (candidate == "place") "location" else candidate
            normalized.takeIf(clueTypes::contains)
        }.toSet()
    }

    fun encode(clueTypes: Set<String>, activityDate: String): Set<String> = clueTypes
        .mapNotNull { value ->
            val normalized = if (value == "place") "location" else value
            normalized.takeIf(this.clueTypes::contains)?.let { "$activityDate|$it" }
        }
        .toSet()
}
