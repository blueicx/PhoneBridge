package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoteStoryProtocolTest {
    @Test fun parsesStoryProgressAndIgnoresMalformedRows() {
        val entries = MoteStoryProtocol.parseRows(
            listOf(
                mapOf("id" to "first-awakening", "title" to "星火初醒", "description" to "第一次", "trigger" to "activation", "rewardXp" to 5, "completed" to true, "claimed" to false),
                mapOf("title" to "bad")
            )
        )
        assertEquals(1, entries.size)
        assertEquals("first-awakening", entries.first().id)
        assertEquals(5, entries.first().rewardXp)
        assertTrue(entries.first().completed)
        assertEquals("剧情 1/1 · 已领奖 0", MoteStoryProtocol.summary(entries))
    }
}
