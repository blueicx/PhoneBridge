package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityModelsTest {
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
