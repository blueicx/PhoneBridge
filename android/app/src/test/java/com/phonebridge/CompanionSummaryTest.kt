package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSummaryTest {
    @Test
    fun parsesCrossClientSummaryAndFormatsCompactStatus() {
        val summary = CompanionSummaryParser.parseValues(mapOf(
            "version" to 1,
            "generatedAt" to 123L,
            "connection" to mapOf("online" to true, "battery" to 76, "temperature" to 32.5),
            "tasks" to mapOf("total" to 4, "running" to 1, "needsConfirmation" to 1, "activeId" to "task_1"),
            "attention" to mapOf("open" to 2, "highestSeverity" to "high"),
            "mote" to mapOf("id" to "ember_sprig", "name" to "焰芽", "level" to 3, "xp" to 18, "gaze" to "focused", "reminderStrength" to 0.7),
            "reality" to mapOf("region" to "cell:1:2", "eventCount" to 8, "level" to 2, "xp" to 33, "inventoryCount" to 2, "seenEventCount" to 1),
            "ai" to mapOf("providerId" to "local", "providerName" to "本地离线规则", "status" to "ready", "budgetRemaining" to 1200, "degradationCount" to 2, "memoryCount" to 7, "memoryRevision" to 4),
            "safety" to mapOf("autonomyLevel" to "whitelist", "emergencyStop" to false)
        ))

        assertEquals("ember_sprig", summary.moteId)
        assertEquals(3, summary.moteLevel)
        assertEquals(1, summary.runningTasks)
        assertEquals(2, summary.openAttention)
        assertEquals(8, summary.realityEvents)
        assertEquals("local", summary.providerId)
        assertFalse(summary.emergencyStop)
        assertTrue(summary.compactStatus().contains("焰芽 Lv.3"))
        assertTrue(summary.compactStatus().contains("现实 8"))
        assertEquals("在线 · 任务 1/4 · 提醒 2 · 现实 8", summary.widgetMeta())
    }

    @Test
    fun unknownOrMalformedSummaryFallsBackToSafeDefaults() {
        val summary = CompanionSummaryParser.parseValues(emptyMap())
        assertEquals("rimuru", summary.moteId)
        assertEquals(1, summary.moteLevel)
        assertEquals("local", summary.providerId)
        assertFalse(summary.connectionOnline)
    }
}
