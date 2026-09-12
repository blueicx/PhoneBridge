package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspaceTimelineTest {

    private fun loadTimelineFixture(): String {
        val candidates = listOf(
            File("../protocol-fixtures/workspace-timeline.json"),
            File("../../protocol-fixtures/workspace-timeline.json"),
            File("protocol-fixtures/workspace-timeline.json")
        )
        val fixture = candidates.firstOrNull { it.isFile }
        requireNotNull(fixture) { "workspace-timeline fixture missing; cwd=${System.getProperty("user.dir")}" }
        return fixture.readText()
    }

    @Test
    fun sharedTimelineFixtureParsesAccurately() {
        val text = loadTimelineFixture()
        val timeline = TimelineParser.parse(text)

        assertEquals(7L, timeline.revision)
        assertEquals(7L, timeline.cursor)
        assertFalse(timeline.hasMore)
        assertEquals(7, timeline.events.size)

        val taskCreate = timeline.events.first { it.entityType == "task" && it.operation == "create" }
        assertEquals("task_fixture_1", taskCreate.entityId)
        assertEquals(1, taskCreate.entityVersion)

        val taskUpdate = timeline.events.first { it.entityType == "task" && it.operation == "update" }
        assertEquals("needs_confirmation", taskUpdate.payload["state"])
        assertEquals(2, taskUpdate.entityVersion)

        val deleteEvent = timeline.events.first { it.operation == "delete" }
        assertTrue(deleteEvent.deleted)
        assertEquals("task_temp_obsolete", deleteEvent.entityId)

        assertNotNull(timeline.snapshot)
        assertEquals(1, timeline.snapshot.tasks.size)
        assertEquals("needs_confirmation", timeline.snapshot.tasks[0].state)

        assertNotNull(timeline.mockInput.gps)
        assertEquals(31.2304, timeline.mockInput.gps?.latitude ?: 0.0, 0.0001)
        assertEquals(121.4737, timeline.mockInput.gps?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun timelineProjectionMaintainsStateAcrossEvents() {
        val projection = TimelineProjection()

        projection.applyEvent(
            TimelineEvent(
                eventId = "e1",
                revision = 1L,
                timestamp = 1000L,
                entityType = "task",
                entityId = "task_1",
                entityVersion = 1,
                operation = "create",
                payload = mapOf("id" to "task_1", "title" to "Task 1", "state" to "pending")
            )
        )

        assertEquals(1, projection.tasks.value.size)
        assertEquals("pending", projection.tasks.value["task_1"]?.state)

        projection.applyEvent(
            TimelineEvent(
                eventId = "e2",
                revision = 2L,
                timestamp = 2000L,
                entityType = "task",
                entityId = "task_1",
                entityVersion = 2,
                operation = "update",
                payload = mapOf("id" to "task_1", "title" to "Task 1", "state" to "needs_confirmation", "isPendingConfirmation" to true)
            )
        )

        assertEquals("needs_confirmation", projection.tasks.value["task_1"]?.state)
        assertEquals(2, projection.tasks.value["task_1"]?.entityVersion)
        assertTrue(projection.tasks.value["task_1"]?.isPendingConfirmation == true)

        projection.applyEvent(
            TimelineEvent(
                eventId = "e3",
                revision = 3L,
                timestamp = 3000L,
                entityType = "task",
                entityId = "task_1",
                entityVersion = 3,
                operation = "delete",
                deleted = true,
                payload = mapOf("id" to "task_1")
            )
        )

        assertNull(projection.tasks.value["task_1"])
    }

    @Test
    fun taskCardProtocolAndDeepLinksFormatCorrectly() {
        val task = TimelineTask(
            id = "task_99",
            title = "数据同步任务",
            state = "needs_confirmation",
            progress = 60,
            detail = "等待用户确认上传权限",
            relatedSessionId = "session_42",
            isPendingConfirmation = true,
            recentResult = "已准备 5 个包"
        )

        val card = TaskCardProtocol.createCard(task, chatSnippet = "请同步最新指标")
        assertEquals("task_99", card.id)
        assertEquals("数据同步任务", card.title)
        assertTrue(card.isPendingConfirmation)
        assertEquals("已准备 5 个包", card.recentResult)
        assertEquals("请同步最新指标", card.chatContext)
        assertEquals("phonebridge://task/task_99", card.deepLink)

        val parsedLink = TimelineDeepLink.parse("phonebridge://task/task_99")
        assertEquals(DeepLinkTarget.TASK, parsedLink.target)
        assertEquals("task_99", parsedLink.targetId)

        val attentionLink = TimelineDeepLink.parse("phonebridge://attention/attn_123")
        assertEquals(DeepLinkTarget.ATTENTION, attentionLink.target)
        assertEquals("attn_123", attentionLink.targetId)
    }

    @Test
    fun disconnectCacheRecoveryDetectsGapAndRestores() {
        val cache = TimelineCacheRecovery()

        cache.recordRevision(10L)
        assertEquals(10L, cache.lastKnownRevision)
        assertFalse(cache.hasGap)

        // Normal sequential event
        cache.onEventReceived(11L)
        assertFalse(cache.hasGap)
        assertEquals(11L, cache.lastKnownRevision)

        // Gap detected: skipped to 15L
        cache.onEventReceived(15L)
        assertTrue(cache.hasGap)

        // Reset with snapshot
        cache.resetFromSnapshot(15L)
        assertFalse(cache.hasGap)
        assertEquals(15L, cache.lastKnownRevision)
    }

    @Test
    fun gpsPlaceholderStructureMaintainsBoundary() {
        val placeholder = GpsPlaceholder(
            latitude = 39.9042,
            longitude = 116.4074,
            accuracy = 10f,
            timestamp = 1757650000000L
        )
        assertEquals(39.9042, placeholder.latitude, 0.0001)
        assertEquals(116.4074, placeholder.longitude, 0.0001)
        assertEquals(10f, placeholder.accuracy, 0.01f)
    }

    @Test
    fun parserAcceptsCanonicalEntityAndCreatedAtFields() {
        val text = """
            {
              "revision": 4,
              "cursor": 4,
              "events": [{
                "eventId": "canonical-1",
                "revision": 4,
                "createdAt": "2026-09-12T10:00:00.000Z",
                "entity": "task",
                "entityId": "task-canonical",
                "entityVersion": 1,
                "operation": "upsert",
                "payload": {"title": "兼容字段"}
              }]
            }
        """.trimIndent()

        val event = TimelineParser.parse(text).events.single()

        assertEquals("task", event.entityType)
        assertEquals("task-canonical", event.entityId)
        assertEquals(1789207200000L, event.timestamp)
    }
}
