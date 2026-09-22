package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoteProfileTest {
    @Test fun registryContainsTwentyProfilesAndUnknownFallsBack() {
        assertEquals(20, MoteProfiles.all.size)
        assertEquals(6, MoteProfiles.initial.size)
        assertEquals(14, MoteProfiles.explorable.size)
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

    @Test fun everyExplorableMoteHasItsOwnVisualPresetAndBodyKind() {
        val profiles = MoteProfiles.explorable.map { MoteVisualProfiles.profile(it.id) }
        assertEquals(14, profiles.size)
        assertTrue(profiles.all { it.bodyKind != MoteBodyKind.CORE })
        assertEquals(14, profiles.map { it.bodyKind }.toSet().size)
        assertEquals(MoteBodyKind.CORE, MoteVisualProfiles.profile(PetAppearance.MOTE).bodyKind)
        assertEquals(MoteBodyKind.CORE, MoteVisualProfiles.profile(PetAppearance.fromWire("future_mote")).bodyKind)
    }
}
