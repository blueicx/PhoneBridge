package com.phonebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageAudioPolicyTest {
    @Test
    fun ambienceRequiresExplicitOptInAVisibleResumedStageAndNoQuietMode() {
        assertFalse(StageAudioPolicy.shouldPlayAmbient(false, false, true, true))
        assertFalse(StageAudioPolicy.shouldPlayAmbient(true, true, true, true))
        assertFalse(StageAudioPolicy.shouldPlayAmbient(true, false, false, true))
        assertFalse(StageAudioPolicy.shouldPlayAmbient(true, false, true, false))
        assertTrue(StageAudioPolicy.shouldPlayAmbient(true, false, true, true))
    }

    @Test
    fun quietModeSuppressesVoicePlaybackButDoesNotChangeTextDelivery() {
        assertFalse(StageAudioPolicy.shouldPlayVoice(quietMode = true))
        assertTrue(StageAudioPolicy.shouldPlayVoice(quietMode = false))
    }
}
