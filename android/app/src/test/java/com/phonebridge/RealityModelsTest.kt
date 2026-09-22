package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityModelsTest {
    @Test
    fun legacyLensNamesMapToSharedClueProtocol() {
        assertEquals("location", RealityClueProtocol.canonicalType("place"))
        assertEquals("object", RealityClueProtocol.canonicalType("object"))
        assertEquals("light", RealityClueProtocol.canonicalType("light"))
        assertEquals("location", RealityClueProtocol.canonicalType("unknown"))
    }

    @Test
    fun clueEventIdsAreStableAndDoNotContainPreciseLocation() {
        val activityAt = 1_767_264_000_000L
        val first = RealityClueProtocol.eventId("place", "cell:1561:6073", activityAt)
        val second = RealityClueProtocol.eventId("place", "cell:1561:6073", activityAt)
        assertEquals(first, second)
        assertTrue(first.startsWith("reality-lens:v2:2026-01-01:cell:1561:6073:location"))
        assertTrue(first.contains("location"))
        assertFalse(first.contains("31.2304"))
        assertFalse(first.contains("121.4737"))
    }

    @Test
    fun clueFlagsAcceptBooleanAndLegacyZeroOneWireValues() {
        assertTrue(RealityClueProtocol.booleanField(true))
        assertFalse(RealityClueProtocol.booleanField(false))
        assertTrue(RealityClueProtocol.booleanField(1))
        assertFalse(RealityClueProtocol.booleanField(0))
        assertTrue(RealityClueProtocol.booleanField("1"))
        assertFalse(RealityClueProtocol.booleanField("0"))
    }

    @Test
    fun coarseRegionNeverKeepsRawCoordinates() {
        val region = RealityRegion.fromCoordinates(31.2304, 121.4737)
        assertEquals("cell:1561:6073", region)
        assertFalse(region.contains("31.2304"))
    }

    @Test
    fun bearingAndDistanceAreNormalized() {
        assertEquals(359, RealityRegion.normalizeBearing(-1))
        assertTrue(RealityRegion.isUsableDistance("mid"))
        assertFalse(RealityRegion.isUsableDistance("unknown"))
    }
}
