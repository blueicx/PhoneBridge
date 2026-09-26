package com.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityCallbackEpochTest {
    @Test
    fun invalidatedSessionCannotDeliverCallbacksIntoANewerEntry() {
        val epoch = RealityCallbackEpoch()
        val firstSession = epoch.advance()
        assertTrue(epoch.accepts(firstSession))

        epoch.advance()
        val secondSession = epoch.advance()

        assertFalse(epoch.accepts(firstSession))
        assertTrue(epoch.accepts(secondSession))
    }
}
