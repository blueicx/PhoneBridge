package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSessionRepositoryTest {
    @Test
    fun snapshotCombinesSummaryAndTimelineProjection() {
        val repository = CompanionSessionRepository()

        repository.applySummary(
            CompanionSummary(
                generatedAt = 100L,
                connectionOnline = true,
                totalTasks = 1,
                runningTasks = 1,
                needsConfirmation = 1,
                moteName = "星核"
            )
        )
        repository.applyTimelineSnapshot(
            TimelineSnapshot(
                revision = 12L,
                tasks = listOf(
                    TimelineTask(
                        id = "task_1",
                        title = "整理收件箱",
                        state = "needs_confirmation",
                        isPendingConfirmation = true,
                        updatedAt = 100L
                    )
                ),
                attention = listOf(
                    TimelineAttention(
                        id = "attention_1",
                        title = "等待确认",
                        relatedTaskId = "task_1",
                        status = "open"
                    )
                )
            )
        )

        val snapshot = repository.snapshot.value
        assertEquals(12L, snapshot.revision)
        assertEquals("星核", snapshot.summary.moteName)
        assertEquals("task_1", snapshot.currentTask()?.id)
        assertEquals(1, snapshot.pendingAttention().size)
        assertEquals("task_1", snapshot.pendingAttention().single().relatedTaskId)
    }

    @Test
    fun duplicateAndStaleEventsDoNotRegressTheSnapshot() {
        val repository = CompanionSessionRepository()
        val current = TimelineEvent(
            eventId = "evt_current",
            revision = 2L,
            timestamp = 200L,
            entityType = "task",
            entityId = "task_1",
            entityVersion = 2,
            payload = mapOf("title" to "新标题", "state" to "running", "progress" to 40)
        )
        assertTrue(repository.applyEvent(current))
        assertFalse(repository.applyEvent(current))
        assertFalse(
            repository.applyEvent(
                current.copy(
                    eventId = "evt_old",
                    revision = 1L,
                    entityVersion = 1,
                    payload = mapOf("title" to "旧标题", "state" to "pending")
                )
            )
        )

        assertEquals("新标题", repository.snapshot.value.tasks.single().title)
        assertEquals(40, repository.snapshot.value.tasks.single().progress)
    }

    @Test
    fun onlineAndOfflineTransitionsKeepLastProjection() {
        val repository = CompanionSessionRepository()
        repository.applyTimelineSnapshot(
            TimelineSnapshot(
                revision = 4L,
                tasks = listOf(TimelineTask(id = "task_1", title = "缓存任务"))
            )
        )

        repository.markOffline("节点暂时不可达")
        assertFalse(repository.snapshot.value.sync.online)
        assertEquals("节点暂时不可达", repository.snapshot.value.sync.lastError)
        assertEquals("缓存任务", repository.snapshot.value.tasks.single().title)

        repository.markOnline()
        assertTrue(repository.snapshot.value.sync.online)
        assertEquals(null, repository.snapshot.value.sync.lastError)
    }
}
