package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRoutineModelsTest {
    @Test
    fun routineTransitionsAllowInterruptionAndAnExplicitRestart() {
        assertEquals(setOf("start"), DailyRoutineProtocol.allowedActions(null))
        assertEquals(setOf("pause", "skip", "finish", "interrupt"), DailyRoutineProtocol.allowedActions("active"))
        assertEquals(setOf("resume", "skip", "finish", "interrupt"), DailyRoutineProtocol.allowedActions("paused"))
        assertEquals(setOf("start"), DailyRoutineProtocol.allowedActions("interrupted"))
        assertEquals(setOf("start"), DailyRoutineProtocol.allowedActions("finished"))
    }

    @Test
    fun elapsedTimeResetsOnRestartAndOnlyAccruesWhileActive() {
        val finished = DailyRoutineEntry(
            id = "routine-1", routineId = DailyRoutineProtocol.FOCUS_TIMER, title = "专注计时",
            status = "finished", startedAt = 1_000L, updatedAt = 8_000L, lastEventAt = 8_000L,
            elapsedSeconds = 700L
        )
        assertEquals(0L, DailyRoutineProtocol.elapsedForAction("start", finished, nowMs = 9_000L))

        val active = finished.copy(status = "active", lastEventAt = 8_000L, elapsedSeconds = 120L)
        assertEquals(180L, DailyRoutineProtocol.elapsedForAction("pause", active, nowMs = 68_000L))
        val paused = active.copy(status = "paused", lastEventAt = 68_000L, elapsedSeconds = 180L)
        assertEquals(180L, DailyRoutineProtocol.elapsedForAction("finish", paused, nowMs = 128_000L))
    }

    @Test
    fun pendingAndRejectedEventsNeverMasqueradeAsConfirmedBusinessState() {
        val start = RoutineActionRequest(
            eventId = "routine-event-001",
            routineId = "focus-timer",
            action = "start",
            occurredAt = "2026-09-30T08:00:00.000Z"
        )
        val pending = DailyRoutineProtocol.pendingEntry(null, start)
        assertEquals("pending", pending.syncState)
        assertEquals("pending", pending.status)
        assertEquals("start", pending.pendingAction)

        val confirmed = DailyRoutineProtocol.confirmedEntry(
            pending.copy(id = "routine_server_1", status = "active", revision = 1L)
        )
        assertEquals("confirmed", confirmed.syncState)
        assertNull(confirmed.pendingAction)

        val pause = start.copy(eventId = "routine-event-002", action = "pause")
        val rejected = requireNotNull(DailyRoutineProtocol.rejectedEntry(confirmed.copy(pendingAction = "pause", syncState = "pending"), "invalid_transition"))
        assertEquals("active", rejected.status)
        assertEquals("rejected", rejected.syncState)
        assertEquals("invalid_transition", rejected.syncReason)
        assertNull(rejected.pendingAction)
        assertEquals("routine-event-002", pause.eventId)
    }

    @Test
    fun snapshotParserIgnoresUnknownFieldsAndDefaultsMissingOptionalValues() {
        val snapshot = DailyRoutineProtocol.parseSnapshot(
            """{"catalog":[{"id":"walk-observation","title":"散步观察","description":"观察","kind":"observation","permissionRequired":false,"future":true}],"current":[{"id":"routine-1","routineId":"walk-observation","title":"散步观察","status":"paused","startedAt":"2026-09-30T08:00:00.000Z","updatedAt":"2026-09-30T08:05:00.000Z","elapsedSeconds":300,"revision":4,"futureField":"ignored"}],"history":[],"revision":4,"privacyRevision":2,"migrationRequired":false,"futureEnvelope":{}}"""
        )

        assertEquals(4L, snapshot.revision)
        assertEquals(2L, snapshot.privacyRevision)
        assertEquals("walk-observation", snapshot.catalog.single().id)
        assertFalse(snapshot.catalog.single().permissionRequired)
        assertEquals("paused", snapshot.current.single().status)
        assertEquals(300L, snapshot.current.single().elapsedSeconds)
        assertEquals("confirmed", snapshot.current.single().syncState)
        assertNull(snapshot.current.single().reflection)
    }

    @Test
    fun actionPayloadIsStableAndIncludesThePrivacyRevision() {
        val action = RoutineActionRequest(
            eventId = "routine-event-003",
            routineId = "bedtime-review",
            action = "finish",
            occurredAt = "2026-09-30T22:00:00.000Z",
            elapsedSeconds = 90L,
            reflection = "今天完成了自己的安排。"
        )

        val payload = DailyRoutineProtocol.actionPayload(action, privacyRevision = 7L)
        val values = parseWorkspaceJsonObject(payload)
        assertEquals(action.eventId, values["eventId"])
        assertEquals(action.routineId, values["routineId"])
        assertEquals(action.action, values["action"])
        assertEquals(7L, (values["privacyRevision"] as Number).toLong())
        assertEquals(action.reflection, values["reflection"])
        assertEquals(payload, DailyRoutineProtocol.actionPayload(action, privacyRevision = 7L))
        assertTrue(DailyRoutineProtocol.actionPayload(action.copy(reflection = null), 7L).contains("privacyRevision"))
        val parsed = requireNotNull(DailyRoutineProtocol.parseActionPayload(payload))
        assertEquals(action, parsed.request)
        assertEquals(7L, parsed.privacyRevision)
    }

    @Test
    fun routineHttpDeliverySeparatesAcceptedRetryableAndBusinessRejectedResponses() {
        val accepted = DailyRoutineProtocol.classifyHttpResponse(
            201,
            """{"ok":true,"action":"start","revision":3,"entry":{"id":"routine-1","routineId":"focus-timer","title":"专注计时","status":"active","startedAt":"2026-09-30T08:00:00.000Z","updatedAt":"2026-09-30T08:00:00.000Z","elapsedSeconds":0}}"""
        )
        assertEquals(RoutineDeliveryKind.ACCEPTED, accepted.kind)
        assertEquals("routine-1", accepted.entry?.id)
        assertEquals(3L, accepted.revision)
        assertEquals(RoutineDeliveryKind.RETRY, DailyRoutineProtocol.classifyHttpResponse(503, "{}" ).kind)
        val rejected = DailyRoutineProtocol.classifyHttpResponse(409, """{"ok":false,"code":"invalid_transition","error":"cannot pause"}""")
        assertEquals(RoutineDeliveryKind.REJECTED, rejected.kind)
        assertEquals("cannot pause", rejected.reason)
    }

    @Test
    fun sharedRoutineFixturesMatchTheOutboxAndHttpBusinessReceiptContracts() {
        val eventJson = fixture("daily-routine-event.json")
        val event = WorkspaceEvent.fromJson(eventJson)
        assertEquals("routine-fixture-001", event.eventId)
        assertEquals("routine.event", event.type)
        val envelope = requireNotNull(DailyRoutineProtocol.parseActionPayload(event.payload))
        assertEquals(4L, envelope.privacyRevision)
        assertEquals("focus-timer", envelope.request.routineId)
        assertEquals("start", envelope.request.action)
        assertEquals(17L, event.sequence)

        val receipt = DailyRoutineProtocol.classifyHttpResponse(201, fixture("daily-routine-response.json"))
        assertEquals(RoutineDeliveryKind.ACCEPTED, receipt.kind)
        assertEquals("routine_fixture_001", receipt.entry?.id)
        assertEquals(5L, receipt.revision)
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")?.bufferedReader()?.use { it.readText() }
            ?: java.io.File("../../protocol-fixtures/$name").readText()
}
