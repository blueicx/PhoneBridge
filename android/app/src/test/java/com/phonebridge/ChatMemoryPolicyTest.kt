package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryPolicyTest {
    @Test
    fun explicitRememberRequestIsIgnoredWhenThisTurnOptedOut() {
        assertNull(ChatMemoryPolicy.explicitFact("记住：我喜欢安静", rememberThisTurn = false))
        assertEquals("我喜欢安静", ChatMemoryPolicy.explicitFact("记住：我喜欢安静", rememberThisTurn = true))
    }

    @Test
    fun inferredPersonalFactsBecomeUnconfirmedCandidates() {
        val candidate = ChatMemoryPolicy.inferCandidate("我喜欢清晨散步", rememberThisTurn = true)
        assertEquals("我喜欢清晨散步", candidate?.text)
        assertEquals("auto_extract", candidate?.source)
        assertEquals(false, candidate?.confirmed)
        assertNull(ChatMemoryPolicy.inferCandidate("明天帮我查天气", rememberThisTurn = true))
        assertNull(ChatMemoryPolicy.inferCandidate("我喜欢清晨散步", rememberThisTurn = false))
    }

    @Test
    fun onlyConfirmedAndNotExcludedMemoriesAreEligibleForRecall() {
        assertTrue(ChatMemoryPolicy.isRecallEligible(confirmed = true, excluded = false))
        assertEquals(false, ChatMemoryPolicy.isRecallEligible(confirmed = false, excluded = false))
        assertEquals(false, ChatMemoryPolicy.isRecallEligible(confirmed = true, excluded = true))
    }
}
