package com.phonebridge

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class WorkspaceProtocolTest {
    @Test
    fun sharedFixtureMatchesAndroidEnvelope() {
        val resource = javaClass.getResourceAsStream("/workspace-event.json")
        val text = if (resource != null) {
            BufferedReader(InputStreamReader(resource)).use { it.readText() }
        } else {
            val candidates = listOf(
                File("../protocol-fixtures/workspace-event.json"),
                File("../../protocol-fixtures/workspace-event.json"),
                File("protocol-fixtures/workspace-event.json")
            )
            val fixture = candidates.firstOrNull { it.isFile }
            requireNotNull(fixture) { "shared workspace fixture missing; cwd=${System.getProperty("user.dir")}" }
            fixture.readText()
        }
        val event = WorkspaceEvent(
            eventId = "fixture-event-1",
            origin = "phone-fixture",
            sequence = 1L,
            type = "workspace.message",
            payload = "{\"role\":\"user\"}",
            createdAt = 0L,
            ack = false
        )
        assertTrue(text.contains("\"eventId\": \"fixture-event-1\""))
        assertTrue(text.contains("\"type\": \"workspace.message\""))
        assertEquals("fixture-event-1", event.eventId)
        assertEquals("workspace.message", event.type)
        assertEquals(1L, event.sequence)
    }

    @Test
    fun eventDeduperRejectsDuplicateOriginSequence() {
        val deduper = EventDeduper()
        val first = WorkspaceEvent(origin = "phone", sequence = 7, type = "chat.message", payload = "{}")
        val duplicate = first.copy(eventId = "another-id")
        assertTrue(deduper.accept(first))
        assertFalse(deduper.accept(duplicate))
        assertEquals(1, deduper.size())
    }

    @Test
    fun outboxRetriesWithBackoffAndRemovesOnlyAfterAck() {
        val queue = OutboxQueue()
        val event = WorkspaceEvent(
            origin = "phone",
            sequence = 1,
            type = WorkspaceEventTypes.ATTENTION,
            payload = AttentionItem(
                id = "attention-1",
                source = "session",
                severity = AttentionSeverity.HIGH,
                status = AttentionStatus.OPEN,
                title = "Need approval",
                summary = "确认本地自动恢复动作",
                relatedSessionId = "session-1",
                relatedTaskId = "task-1",
                dedupeKey = "session-1:approve",
                createdAt = 100L,
                updatedAt = 100L,
                snoozedUntil = null
            ).toJson().toString(),
            createdAt = 100,
            localActionId = "run-1",
            localActionState = ActionRunState.RUNNING
        )
        assertTrue(queue.enqueue(event))
        assertFalse(queue.enqueue(event.copy(eventId = "duplicate")))
        assertEquals(1, queue.ready(100).size)
        assertTrue(queue.retry(event.eventId, now = 100, backoffMs = 2_000))
        assertEquals(0, queue.ready(500).size)
        val retried = queue.ready(2_100).single()
        assertEquals(1, retried.retryCount)
        assertEquals("run-1", retried.event.localActionId)
        assertEquals(ActionRunState.RUNNING, retried.event.localActionState)
        assertTrue(queue.acknowledge(event.eventId))
        assertEquals(0, queue.size())
    }

    @Test
    fun privacyRevisionWireIsStableAndLegacyEventsDefaultToNoRevisions() {
        val revisions = linkedMapOf("conversations" to 2L, "tasks" to 4L)
        val wire = PrivacyRevisionWire.toJson(revisions)
        assertEquals("{\"conversations\":2,\"tasks\":4}", wire)
        assertEquals(revisions, PrivacyRevisionWire.fromJson(wire))
        assertEquals(emptyMap<String, Long>(), PrivacyRevisionWire.fromJson(null))
        assertEquals(emptyMap<String, Long>(), PrivacyRevisionWire.fromJson("{}"))

        val event = WorkspaceEvent(
            origin = "phone", sequence = 1, type = WorkspaceEventTypes.MESSAGE,
            payload = "{}", privacyRevisions = revisions
        )
        assertEquals(revisions, event.privacyRevisions)
        assertEquals(revisions, WorkspaceEvent.fromJson(event.toJson()).privacyRevisions)
    }

    @Test
    fun oldWorkspaceEnvelopeParsesWithoutRevisionAndInvalidRevisionFieldsAreDropped() {
        val legacy = WorkspaceEvent.fromJson(
            """{"eventId":"legacy","origin":"phone","sequence":3,"type":"workspace.message","payload":{"text":"hello"},"createdAt":5,"unknownField":"ignored"}"""
        )
        assertEquals("legacy", legacy.eventId)
        assertEquals("{\"text\":\"hello\"}", legacy.payload)
        assertEquals(emptyMap<String, Long>(), legacy.privacyRevisions)

        val parsed = PrivacyRevisionWire.fromJson(
            """{"conversations":2,"tasks":3.5,"progress":-1,"unknown":7,"memories":"4","goals":9007199254740992}"""
        )
        assertEquals(mapOf("conversations" to 2L), parsed)
    }

    @Test
    fun sharedPrivacyRevisionFixtureParsesAndClassifiesTheSameCrossRuntimeEvents() {
        val fixtureText = javaClass.getResourceAsStream("/privacy-event-revision.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("shared privacy-event-revision.json fixture is missing")
        val fixture = parseWorkspaceJsonObject(fixtureText)
        val cases = fixture["cases"] as? List<*> ?: error("fixture cases are missing")
        for (rawCase in cases) {
            val item = fixtureObject(rawCase)
            val eventJson = fixtureObject(item["event"])
            val payload = fixtureObject(eventJson["payload"])
            val event = WorkspaceEvent(
                eventId = eventJson["eventId"] as String,
                origin = eventJson["origin"] as String,
                sequence = (eventJson["sequence"] as Number).toLong(),
                type = eventJson["type"] as String,
                payload = "{}",
                createdAt = (eventJson["createdAt"] as Number).toLong(),
                privacyRevisions = PrivacyRevisionWire.fromValue(eventJson["privacyRevisions"])
            )
            val categories = PrivacyDataPolicy.classifyEvent(event.type, payload)?.categories
            val caseId = item["id"] as String
            val expectedCategories = (item["expectedCategories"] as List<*>).map { it as String }
            assertEquals(caseId, expectedCategories, categories)

            val revisionsJson = fixtureObject(item["categoryRevisions"])
            val expectedStatus = when {
                categories.orEmpty().any { category ->
                    val current = (revisionsJson[category] as? Number)?.toLong() ?: 0L
                    val supplied = event.privacyRevisions[category]
                    supplied == null && current > 0L
                } -> "privacy_revision_required"
                categories.orEmpty().any { category ->
                    event.privacyRevisions[category]?.let { it < ((revisionsJson[category] as? Number)?.toLong() ?: 0L) } == true
                } -> "privacy_revision_stale"
                else -> "accepted"
            }
            assertEquals(caseId, item["expectedStatus"], expectedStatus)
            val expectedRevisions = PrivacyRevisionWire.fromValue(eventJson["privacyRevisions"])
            assertEquals(caseId, expectedRevisions, event.privacyRevisions)
        }
    }

    @Test
    fun privacyRevisionPolicyRejectsMissingOrStaleVersionsAndWaitsForEveryLegacyDecision() {
        assertFalse(PrivacyRevisionPolicy.hasCompleteOverview(PrivacyDataPolicy.categories, emptySet()))
        assertFalse(PrivacyRevisionPolicy.hasCompleteOverview(PrivacyDataPolicy.categories, PrivacyDataPolicy.categories.dropLast(1).toSet()))
        assertTrue(PrivacyRevisionPolicy.hasCompleteOverview(PrivacyDataPolicy.categories, PrivacyDataPolicy.categories.toSet()))
        assertTrue(PrivacyRevisionPolicy.shouldHoldOutbox(privacyOverviewLoaded = false, migrationPending = false))
        assertTrue(PrivacyRevisionPolicy.shouldHoldOutbox(privacyOverviewLoaded = true, migrationPending = true))
        assertFalse(PrivacyRevisionPolicy.shouldHoldOutbox(privacyOverviewLoaded = true, migrationPending = false))
        assertEquals(PrivacyRevisionDisposition.ACCEPT, PrivacyRevisionPolicy.check(0L, null, false))
        assertEquals(PrivacyRevisionDisposition.REVISION_REQUIRED, PrivacyRevisionPolicy.check(2L, null, false))
        assertEquals(PrivacyRevisionDisposition.STALE, PrivacyRevisionPolicy.check(2L, 1L, false))
        assertEquals(PrivacyRevisionDisposition.ACCEPT, PrivacyRevisionPolicy.check(2L, 2L, false))
        assertEquals(PrivacyRevisionDisposition.ACCEPT, PrivacyRevisionPolicy.check(2L, 3L, false))
        assertEquals(PrivacyRevisionDisposition.MIGRATION_REQUIRED, PrivacyRevisionPolicy.check(2L, 2L, true))
        assertTrue(PrivacyRevisionPolicy.canApplyRemoteMigrationDecision(null, "clear"))
        assertTrue(PrivacyRevisionPolicy.canApplyRemoteMigrationDecision("keep", "keep"))
        assertFalse(PrivacyRevisionPolicy.canApplyRemoteMigrationDecision("keep", "clear"))
        assertFalse(PrivacyRevisionPolicy.canApplyRemoteMigrationDecision(null, "upload"))
        assertEquals(0L, PrivacyRevisionPolicy.revisionForNewEvent(0L, migrationRequired = false, localDecision = null))
        assertEquals(0L, PrivacyRevisionPolicy.revisionForNewEvent(0L, migrationRequired = true, localDecision = null))
        assertEquals(1L, PrivacyRevisionPolicy.revisionForNewEvent(0L, migrationRequired = true, localDecision = "keep"))
        assertEquals(5L, PrivacyRevisionPolicy.revisionForNewEvent(4L, migrationRequired = true, localDecision = "clear"))
        assertEquals(4L, PrivacyRevisionPolicy.revisionForNewEvent(4L, migrationRequired = true, localDecision = "other"))
        assertFalse(PrivacyRevisionPolicy.canResumeMigration(setOf("conversations", "tasks"), mapOf("tasks" to "keep")))
        assertTrue(PrivacyRevisionPolicy.canResumeMigration(setOf("conversations", "tasks"), mapOf("tasks" to "keep", "conversations" to "clear")))
        assertTrue(PrivacyRevisionPolicy.canResumeMigration(emptySet(), emptyMap()))
        val firstDecision = PrivacyRevisionPolicy.resolveMigrationDecision(setOf("conversations"), emptyMap(), "conversations", "keep")
        assertEquals(mapOf("conversations" to "keep"), firstDecision)
        assertNull(PrivacyRevisionPolicy.resolveMigrationDecision(setOf("conversations"), firstDecision.orEmpty(), "conversations", "clear"))
        assertNull(PrivacyRevisionPolicy.resolveMigrationDecision(setOf("conversations"), emptyMap(), "tasks", "keep"))
        assertNull(PrivacyRevisionPolicy.resolveMigrationDecision(setOf("conversations"), emptyMap(), "conversations", "upload"))
    }

    @Test
    fun outboxLeaseSeparatesTransportSendFromBusinessAckAndRecoversAfterTimeout() {
        val queue = OutboxQueue()
        val event = WorkspaceEvent(origin = "phone", sequence = 9, type = WorkspaceEventTypes.MOTE_EXPLORATION, payload = "{}", createdAt = 100L)
        assertTrue(queue.enqueue(event))
        val claimed = queue.claimReady(now = 100L, leaseMs = 1_000L).single()
        assertEquals(1_100L, claimed.leaseUntil)
        assertEquals(0, queue.ready(500L).size)
        assertEquals(listOf(event.eventId), queue.expireLeases(now = 1_100L))
        assertEquals(0, queue.ready(1_100L).size)
        val afterBackoff = queue.ready(2_100L).single()
        assertEquals(1, afterBackoff.retryCount)
        assertTrue(queue.claim(event.eventId, now = 2_100L, leaseMs = 500L))
        assertTrue(queue.acknowledge(
            event.eventId,
            accepted = false,
            businessStatus = "rejected",
            reason = "offline_event_expired",
            resultRevision = 42L
        ))
        assertEquals(0, queue.size())
        assertEquals("rejected", queue.outcome(event.eventId)?.businessStatus)
        assertEquals("offline_event_expired", queue.outcome(event.eventId)?.reason)
        assertEquals(42L, queue.outcome(event.eventId)?.resultRevision)
    }

    @Test
    fun moteExplorationEventUsesOutboxEventIdDeduplication() {
        val event = WorkspaceEvent(
            origin = "phone",
            sequence = 44,
            type = WorkspaceEventTypes.MOTE_EXPLORATION,
            payload = "{\"eventId\":\"clue-44\",\"clueType\":\"location\"}",
            createdAt = 10L
        )
        val queue = OutboxQueue()
        assertTrue(queue.enqueue(event))
        assertFalse(queue.enqueue(event.copy(eventId = "another", sequence = 44)))
        assertEquals(WorkspaceEventTypes.MOTE_EXPLORATION, queue.ready(10).single().event.type)
    }

    @Test
    fun stateMergeKeepsExistingModelWhenSnapshotOmitsIt() {
        val current = WorkspaceUiState(online = true, model = "small-cn", authorized = true)
        val merged = mergeWorkspaceUiState(current, WorkspaceUiState(online = false, activeTasks = 2))
        assertEquals("small-cn", merged.model)
        assertEquals(2, merged.activeTasks)
        assertFalse(merged.online)
    }

    @Test
    fun attentionItemRoundTripKeepsNullableFields() {
        val item = AttentionItem(
            id = "attention-42",
            source = "automation",
            severity = AttentionSeverity.MEDIUM,
            status = AttentionStatus.SNOOZED,
            title = "Review automation",
            summary = "Automation needs confirmation",
            relatedSessionId = "session-1",
            relatedTaskId = null,
            dedupeKey = "automation:session-1",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            snoozedUntil = 3_000L
        )

        val encoded = item.toJson().toString()
        val decoded = AttentionItem.fromJson(encoded)

        assertEquals(item, decoded)
    }

    @Test
    fun mergeAttentionItemsDedupesByDedupeKeyAndPrefersIncoming() {
        val existing = AttentionItem(
            id = "attention-old",
            source = "session",
            severity = AttentionSeverity.LOW,
            status = AttentionStatus.OPEN,
            title = "Old title",
            summary = "Old summary",
            relatedSessionId = "session-7",
            relatedTaskId = null,
            dedupeKey = "session-7:approval",
            createdAt = 10L,
            updatedAt = 10L,
            snoozedUntil = null
        )
        val incoming = existing.copy(
            id = "attention-new",
            severity = AttentionSeverity.HIGH,
            status = AttentionStatus.ACKNOWLEDGED,
            title = "New title",
            summary = "New summary",
            updatedAt = 20L
        )

        val merged = mergeAttentionItems(listOf(existing), listOf(incoming))

        assertEquals(1, merged.size)
        assertEquals("attention-new", merged.single().id)
        assertEquals("New title", merged.single().title)
        assertEquals(AttentionStatus.ACKNOWLEDGED, merged.single().status)
    }

    @Test
    fun actionRunStateNormalizesWireValues() {
        assertEquals(ActionRunState.RUNNING, ActionRunState.fromWire("Running"))
        assertEquals(ActionRunState.SUCCEEDED, ActionRunState.fromWire("succeeded"))
        assertEquals(ActionRunState.BLOCKED, ActionRunState.fromWire("blocked"))
        assertEquals(ActionRunState.QUEUED, ActionRunState.fromWire("unknown"))
        assertTrue(ActionRunState.isTerminal(ActionRunState.FAILED))
        assertTrue(ActionRunState.isTerminal(ActionRunState.BLOCKED))
        assertFalse(ActionRunState.isTerminal(ActionRunState.RUNNING))
    }

    @Test
    fun autonomyPolicyRoundTripPreservesContinuousMicAndAllowedTools() {
        val policy = AutonomyPolicy(
            scopeType = AutonomyScope.SESSION,
            scopeId = "session-9",
            level = AutonomyLevel.REVERSIBLE,
            allowedTools = listOf("camera.snapshot", "microphone.listen"),
            expiresAt = 9_000L,
            continuousMic = true,
            confirmationRules = listOf(
                AutonomyConfirmationRule("require-face", true),
                AutonomyConfirmationRule("deny-background", true)
            )
        )

        val encoded = policy.toJson().toString()
        val decoded = AutonomyPolicy.fromJson(encoded)

        assertEquals(policy, decoded)
        assertEquals(emptyList<AutonomyPolicy>(), AutonomyPolicy.listFromJson("[]"))
    }

    @Test
    fun whitelistAutonomyLevelRemainsWireCompatible() {
        val policy = AutonomyPolicy("global", "personal", AutonomyLevel.WHITELIST, listOf("device.telemetry"), null, false, emptyList())
        assertEquals(policy, AutonomyPolicy.fromJson(policy.toJson().toString()))
    }

    @Test
    fun syncCursorOnlyAdvancesAndKeepsLastErrorUntilRecovery() {
        val start = WorkspaceSyncCursor(5L)
        assertEquals(5L, WorkspaceSyncReducer.advance(start, 3L).revision)
        val failed = WorkspaceSyncReducer.fail(start, "temporary disconnect")
        assertEquals("temporary disconnect", failed.lastError)
        assertEquals(9L, WorkspaceSyncReducer.advance(failed, 9L).revision)
        assertNull(WorkspaceSyncReducer.advance(failed, 9L).lastError)
    }

    @Test
    fun workspaceEventGateDeduplicatesIdsAndFlagsRevisionGaps() {
        val gate = WorkspaceEventGate()
        assertTrue(gate.accept(1L, "event-1"))
        assertFalse(gate.accept(1L, "event-1"))
        assertTrue(gate.accept(3L, "event-3"))
        assertTrue(gate.revisionGapDetected)
        assertFalse(gate.accept(2L, "event-2"))
    }

    @Test
    fun actionRunRoundTripKeepsApprovalAndErrorFields() {
        val run = ActionRun(
            id = "run-9",
            origin = "phone",
            sessionId = "session-4",
            automationId = "automation-2",
            taskId = "task-3",
            toolId = "camera.snapshot",
            argsSummary = "{\"mode\":\"burst\"}",
            state = ActionRunState.fromWire("running"),
            approval = ActionRunApproval.APPROVED,
            createdAt = 111L,
            startedAt = 222L,
            endedAt = null,
            resultRef = null,
            error = null
        )

        val decoded = ActionRun.fromJson(run.toJson().toString())

        assertEquals(run, decoded)
        assertNull(decoded.error)
            assertNull(decoded.resultRef)
    }

    @Test
    fun nodeWireValuesRemainReadableAcrossStatusAndTimestampFormats() {
        val attention = AttentionItem.fromJson(
            """{"id":"attention-node","source":"automation","severity":"critical","status":"read","title":"Node alert","summary":"Recovered","relatedSessionId":"session-node","relatedTaskId":null,"dedupeKey":"node:alert","createdAt":"2026-08-31T00:00:00.000Z","updatedAt":"2026-08-31T00:00:01.000Z","snoozedUntil":null}"""
        )
        assertEquals(AttentionStatus.READ, attention.status)
        assertEquals(1_000L, attention.updatedAt - attention.createdAt)

        val policy = AutonomyPolicy.fromJson(
            """{"scopeType":"session","targetId":"session-node","level":"reversible","allowedTools":["device.camera"],"expiresAt":"2026-08-31T00:05:00.000Z","continuousMic":false,"confirmationRules":[{"toolId":"device.camera","requireConfirmation":true}]}"""
        )
        assertEquals("session-node", policy.scopeId)
        assertEquals(1, policy.confirmationRules.size)
        assertEquals("device.camera", policy.confirmationRules.single().toolId)
        assertTrue(policy.confirmationRules.single().requireConfirmation)

        val action = ActionRun.fromJson(
            """{"id":"action-node","origin":"automation","sessionId":null,"automationId":"automation-node","taskId":null,"toolId":"device.camera","argsSummary":"{}","state":"blocked","approval":"granted","createdAt":"2026-08-31T00:00:00.000Z","startedAt":null,"endedAt":"2026-08-31T00:00:02.000Z","resultRef":null,"error":"stopped"}"""
        )
        assertEquals(ActionRunState.BLOCKED, action.state)
        assertEquals(ActionRunApproval.GRANTED, action.approval)
        assertEquals(2_000L, action.endedAt!! - action.createdAt)
    }

    @Test
    fun workspaceDatabaseDeclaresPrivacyFenceMigration() {
        assertEquals(5, WORKSPACE_DB_VERSION)
        val migrations = WorkspaceRepository.MIGRATIONS.toList()
        assertTrue(migrations.any { it.startVersion == 1 && it.endVersion == 2 })
        assertTrue(migrations.any { it.startVersion == 3 && it.endVersion == 4 })
        assertTrue(migrations.any { it.startVersion == 4 && it.endVersion == 5 })
        assertTrue(migrations.all { it is Migration })
    }

    private fun fixtureObject(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.mapNotNull { (key, item) ->
            (key as? String)?.let { it to item }
        }?.toMap().orEmpty()

    @Test
    fun workspaceEventGateBehaviors() {
        val gate = WorkspaceEventGate()
        gate.markResynchronized(7L)
        assertFalse(gate.accept(6L, "event-6"))
        assertFalse(gate.revisionGapDetected)

        assertTrue(gate.accept(8L, "event-8"))
        assertFalse(gate.revisionGapDetected)

        gate.accept(11L, "event-11")
        assertTrue(gate.revisionGapDetected)
        assertTrue(gate.consumeGap())
        assertFalse(gate.revisionGapDetected)
        gate.markResynchronized(12L)
        assertFalse(gate.revisionGapDetected)
        assertFalse(gate.consumeGap())
        assertEquals(12L, gate.revision)

        assertTrue(gate.accept(13L, "event-13"))
        assertFalse(gate.accept(14L, "event-13"))
    }

}
