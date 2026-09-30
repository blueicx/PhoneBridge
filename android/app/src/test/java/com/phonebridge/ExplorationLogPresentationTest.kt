package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationLogPresentationTest {
    @Test
    fun pendingAndAcknowledgedEntriesNeverDisplayRewardsOrRealityActions() {
        val pending = entry(ExplorationLogStatus.PENDING)
        val pendingPresentation = ExplorationLogPresentation.from(pending)
        assertEquals("待同步", pendingPresentation.statusLabel)
        assertNull(pendingPresentation.rewardLabel)
        assertFalse(pendingPresentation.canOpenReality)

        val acknowledged = ExplorationLogPresentation.from(pending.copy(acknowledged = true))
        assertEquals("节点已收到，奖励待确认", acknowledged.statusLabel)
        assertNull(acknowledged.rewardLabel)
        assertFalse(acknowledged.canOpenReality)
    }

    @Test
    fun confirmedReceiptShowsOnlyItsPersistedRewardAndEntersRealityReadOnly() {
        val confirmed = entry(ExplorationLogStatus.CONFIRMED).copy(
            coarseRegion = "camera",
            moteId = "sprite",
            reward = ExplorationLogReward(xp = 5, items = listOf(ExplorationLogItem("item_01", 2)))
        )

        val presentation = ExplorationLogPresentation.from(confirmed)

        assertEquals("已确认", presentation.statusLabel)
        assertEquals("经验 +5 · item_01 ×2", presentation.rewardLabel)
        assertEquals("仅镜头，未使用位置", presentation.regionLabel)
        assertTrue(presentation.detailText.contains(confirmed.eventId))
        assertTrue(presentation.detailText.contains("Mote sprite"))
        assertTrue(presentation.canOpenReality)
        assertTrue(presentation.isReadOnly)
    }

    @Test
    fun expiredRejectedEntryIsViewOnlyAndNeverShowsReward() {
        val expired = entry(ExplorationLogStatus.REJECTED).copy(
            reason = "offline_event_expired",
            reward = null
        )

        val presentation = ExplorationLogPresentation.from(expired)

        assertEquals("已过期 · 仅查看", presentation.statusLabel)
        assertNull(presentation.rewardLabel)
        assertFalse(presentation.canOpenReality)
        assertTrue(presentation.isReadOnly)
        assertFalse(presentation.detailText.contains("offline_event_expired"))
    }

    private fun entry(status: ExplorationLogStatus) = ExplorationLogEntry(
        eventId = "reality-lens:v2:2026-09-30:cell:1:2:object:entry_01",
        status = status,
        occurredAt = 1_790_000_000_000,
        coarseRegion = "cell:1:2",
        clueType = "object",
        observation = "一起留意可观察的物体线索。",
        reward = null
    )
}
