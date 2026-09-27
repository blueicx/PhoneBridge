package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnStateTest {
    @Test
    fun delayedSpeechCallbacksCannotMutateANewerTurn() {
        assertEquals(
            true,
            VoiceTurnCallbackPolicy.isCurrent("turn_4", "turn_4", callbackGeneration = 4, activeGeneration = 4)
        )
        assertEquals(
            false,
            VoiceTurnCallbackPolicy.isCurrent("turn_3", "", callbackGeneration = 3, activeGeneration = 4)
        )
        assertEquals(
            false,
            VoiceTurnCallbackPolicy.isCurrent("turn_3", "turn_3", callbackGeneration = 3, activeGeneration = 4)
        )
    }

    @Test
    fun stateSeparatesRecognitionGenerationPlaybackAndFailureRecovery() {
        val initial = VoiceForegroundState().listening()
        val recognized = initial.recognizing("帮我整理一下任务")
        val generating = recognized.processing("帮我整理一下任务")
        val speaking = generating.speaking("已经整理好了")
        val failed = generating.failed("连接超时")

        assertEquals(VoiceForegroundPhase.RECOGNIZING, recognized.phase)
        assertEquals(VoiceForegroundPhase.PROCESSING, generating.phase)
        assertEquals(VoiceForegroundPhase.SPEAKING, speaking.phase)
        assertTrue(failed.retryAvailable)
        assertEquals("连接超时", failed.detail)
    }

    @Test
    fun interruptReturnsToListeningAndClearsTransientReply() {
        val interrupted = VoiceForegroundState().listening()
            .processing("继续")
            .speaking("正在回答")
            .interrupt()

        assertEquals(VoiceForegroundPhase.LISTENING, interrupted.phase)
        assertEquals("", interrupted.detail)
        assertEquals(false, interrupted.retryAvailable)
    }
}
