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

    @Test fun parsesMoteExclusiveCompletionAndClaimMetadataFromWire() {
        val story = MoteStoryProtocol.parseRows(
            listOf(mapOf(
                "id" to "exclusive-ember_sprig",
                "title" to "焰芽先行一步",
                "description" to "行动",
                "trigger" to "task_success",
                "completion" to "激活焰芽并成功完成一项任务",
                "moteId" to "ember_sprig",
                "moteName" to "焰芽",
                "exclusive" to true,
                "rewardXp" to 11,
                "completed" to true,
                "claimed" to false,
            ))
        ).single()
        assertEquals("ember_sprig", story.moteId)
        assertEquals("焰芽", story.moteName)
        assertTrue(story.exclusive)
        assertEquals("激活焰芽并成功完成一项任务", story.completion)
        assertEquals(11, story.rewardXp)
    }
}
