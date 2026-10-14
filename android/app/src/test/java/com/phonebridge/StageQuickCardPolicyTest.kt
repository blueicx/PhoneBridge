package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageQuickCardPolicyTest {
    @Test fun approvalAndCurrentTaskAreTheOnlyTwoStageCards() {
        val snapshot = CompanionSnapshot(
            tasks = listOf(
                TimelineTask(id = "running", title = "巡检节点", state = "running", progress = 42),
                TimelineTask(id = "approval-task", title = "整理文件", state = "needs_confirmation"),
                TimelineTask(id = "pending", title = "下一项", state = "pending")
            )
        )
        val cards = StageQuickCardPolicy.select(
            snapshot,
            listOf(StageToolApproval("approval-1", "approval-task", "files.organize", "needs_confirmation"))
        )
        assertEquals(2, cards.size)
        assertEquals(StageQuickCardKind.APPROVAL, cards[0].kind)
        assertEquals("approval-1", cards[0].approvalId)
        assertEquals("running", cards[1].taskId)
        assertEquals("pause", cards[1].action)
    }

    @Test fun offlineSnapshotKeepsTaskAndReminderAndLimitsLongHistory() {
        val snapshot = CompanionSnapshot(
            sync = CompanionSyncState(online = false),
            tasks = listOf(TimelineTask(id = "paused", title = "继续整理", state = "paused", progress = 20)),
            attention = listOf(TimelineAttention(id = "attention", title = "需要查看", status = "open", relatedTaskId = "paused")),
            messages = List(20) { TimelineChatMessage(id = "m$it", sessionId = "s", text = "message $it") }
        )
        val cards = StageQuickCardPolicy.select(snapshot, emptyList())
        assertEquals(2, cards.size)
        assertEquals(listOf(StageQuickCardKind.TASK, StageQuickCardKind.ATTENTION), cards.map { it.kind })
        assertEquals("continue", cards.first().action)
        assertTrue(cards.none { it.detail.contains("message") })
    }

    @Test fun completedTaskAppearsOnlyWhenThereIsRoom() {
        val snapshot = CompanionSnapshot(
            tasks = listOf(TimelineTask(id = "done", title = "最近结果", state = "succeeded", recentResult = "已完成"))
        )
        val card = StageQuickCardPolicy.select(snapshot, emptyList()).single()
        assertEquals(StageQuickCardKind.RESULT, card.kind)
        assertEquals("done", card.taskId)
    }

    @Test fun activeRoutineProgressFillsTheSecondStageCardWithoutPrivateReflection() {
        val cards = StageQuickCardPolicy.select(
            CompanionSnapshot(tasks = listOf(TimelineTask(id = "task", title = "进行中任务", state = "running", progress = 40))),
            emptyList(),
            listOf(StageActivityProgress("focus-timer", "专注计时", "active", 754L))
        )
        assertEquals(listOf(StageQuickCardKind.TASK, StageQuickCardKind.ACTIVITY), cards.map { it.kind })
        assertEquals("focus-timer", cards[1].routineId)
        assertTrue(cards[1].detail.contains("12 分钟"))
        assertTrue(cards.size <= 2)
    }
}
