package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class RealityDiscoveryProgressTest {
    @Test
    fun onlyCluesFromTheCurrentShanghaiActivityDateAreLocked() {
        val saved = setOf("2026-09-27|location", "2026-09-28|object", "2026-09-28|light")

        assertEquals(setOf("object", "light"), RealityDiscoveryProgress.forDate(saved, "2026-09-28"))
    }

    @Test
    fun legacyPermanentClueNamesMigrateForTodayAndCanBeRewritten() {
        val today = "2026-09-28"
        val visible = RealityDiscoveryProgress.forDate(setOf("place", "object"), today)

        assertEquals(setOf("location", "object"), visible)
        assertEquals(setOf("2026-09-28|location", "2026-09-28|object"), RealityDiscoveryProgress.encode(visible, today))
    }
}
