package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealitySessionLifecycleTest {
    @Test
    fun sessionResumePauseAndCloseAreIdempotentAndOrdered() {
        val calls = mutableListOf<String>()
        val lifecycle = RealitySessionLifecycle(object : RealitySessionLifecyclePort {
            override fun resume() { calls += "resume" }
            override fun pause() { calls += "pause" }
            override fun close() { calls += "close" }
        })

        assertTrue(lifecycle.resume())
        assertTrue(lifecycle.resume())
        lifecycle.pause()
        assertTrue(lifecycle.resume())
        lifecycle.close()
        lifecycle.close()

        assertEquals(listOf("resume", "pause", "resume", "pause", "close"), calls)
        assertFalse(lifecycle.resume())
        assertEquals(RealitySessionLifecycleState.CLOSED, lifecycle.state)
    }

    @Test
    fun failedSessionResumeCanBeClosedWithoutLeakingAnActiveState() {
        var closeCount = 0
        val lifecycle = RealitySessionLifecycle(object : RealitySessionLifecyclePort {
            override fun resume() { error("camera unavailable") }
            override fun pause() { error("pause must not run after failed resume") }
            override fun close() { closeCount += 1 }
        })
        assertFalse(lifecycle.resume())
        lifecycle.close()
        assertEquals(1, closeCount)
        assertEquals(RealitySessionLifecycleState.CLOSED, lifecycle.state)
    }
}
