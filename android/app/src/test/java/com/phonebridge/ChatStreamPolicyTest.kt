package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStreamPolicyTest {
    @Test
    fun rejectsCancelledAndStaleDeltasWithoutBlockingOtherStreamsWhenIdle() {
        val cancelled = setOf("chat_cancelled")
        assertEquals(false, ChatStreamPolicy.shouldRenderDelta("chat_cancelled", "", cancelled))
        assertEquals(false, ChatStreamPolicy.shouldRenderDelta("chat_old", "chat_current", cancelled))
        assertEquals(true, ChatStreamPolicy.shouldRenderDelta("chat_current", "chat_current", cancelled))
        assertEquals(true, ChatStreamPolicy.shouldRenderDelta("remote_web", "", cancelled))
        assertEquals(false, ChatStreamPolicy.shouldRenderDelta("", "", cancelled))
    }
}
