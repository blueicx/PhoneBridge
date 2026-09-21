package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityLocationPolicyTest {
    @Test
    fun permissionAndLocationSwitchesFallBackToCameraOnly() {
        val sample = RealityLocationSample(31.2304, 121.4737, accuracyMeters = 12f, isMock = false, timestampMs = 1000L)
        assertEquals(RealityLocationMode.CAMERA_ONLY, RealityLocationPolicy.resolve(false, true, sample, 1000L).mode)
        assertEquals(RealityLocationMode.CAMERA_ONLY, RealityLocationPolicy.resolve(true, false, sample, 1000L).mode)
    }

    @Test
    fun validLocationUsesCoarseCellAndRejectsMockOrStaleSamples() {
        val sample = RealityLocationSample(31.2304, 121.4737, accuracyMeters = 12f, isMock = false, timestampMs = 1000L)
        val result = RealityLocationPolicy.resolve(true, true, sample, 1000L)
        assertEquals(RealityLocationMode.COARSE_REGION, result.mode)
        assertEquals("cell:1561:6073", result.region)
        assertTrue(result.region!!.startsWith("cell:"))

        val mocked = sample.copy(isMock = true)
        assertEquals(RealityLocationMode.CAMERA_ONLY, RealityLocationPolicy.resolve(true, true, mocked, 1000L).mode)
        val stale = sample.copy(timestampMs = 0L)
        assertFalse(RealityLocationPolicy.resolve(true, true, stale, 1000L).region != null)

        val invalidCoordinates = sample.copy(latitude = Double.NaN)
        assertEquals(RealityLocationMode.CAMERA_ONLY, RealityLocationPolicy.resolve(true, true, invalidCoordinates, 1000L).mode)

        val outOfRange = sample.copy(longitude = 181.0)
        assertEquals(RealityLocationMode.CAMERA_ONLY, RealityLocationPolicy.resolve(true, true, outOfRange, 1000L).mode)
    }
}
