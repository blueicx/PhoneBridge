package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoteProfileTest {
    @Test fun registryContainsTenProfilesAndUnknownFallsBack() {
        assertEquals(10, MoteProfiles.all.size)
        assertEquals(6, MoteProfiles.initial.size)
        assertEquals(4, MoteProfiles.explorable.size)
        assertEquals(PetAppearance.MOTE, PetAppearance.fromWire("unknown"))
        assertEquals(PetAppearance.EMBER_SPRIG, PetAppearance.fromWire("ember_sprig"))
        assertNotNull(MoteProfiles.profile(PetAppearance.ORBIT_RAVEN))
    }

    @Test fun behaviorMappingIsDeterministicAndHealthAware() {
        val input = MoteBehaviorInput("running", "warning", "tap", 2, PetEmotion())
        val first = MoteBehaviorEngine.resolve(MoteProfiles.profile(PetAppearance.EMBER_SPRIG), input)
        val second = MoteBehaviorEngine.resolve(MoteProfiles.profile(PetAppearance.EMBER_SPRIG), input)
        assertEquals(first, second)
        assertTrue(first.motionIntensity > .5f)
        assertEquals("protective", first.gaze)
        assertEquals("embers", first.particleType)
        assertTrue(first.proactive)
    }
}
