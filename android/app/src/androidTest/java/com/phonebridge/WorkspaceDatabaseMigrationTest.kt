package com.phonebridge

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class WorkspaceDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WorkspaceDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun sharedPrivacyMigrationFixtureMapsToAndroidSyncGate() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("privacy-overview.json").use {
            JSONObject(BufferedReader(InputStreamReader(it)).readText())
        }
        val migration = fixture.getJSONObject("migration")
        val categories = fixture.getJSONObject("categories")
        val required = buildSet {
            val values = migration.getJSONArray("requiredCategories")
            for (index in 0 until values.length()) add(values.getString(index))
        }
        assertTrue(required.contains("conversations"))
        assertTrue(categories.has("routines"))
        assertTrue(categories.has("goals"))
        assertTrue(PrivacyRevisionPolicy.shouldHoldOutbox(
            privacyOverviewLoaded = true,
            migrationPending = categories.getJSONObject("conversations").getBoolean("migrationRequired")
        ))
        assertFalse(PrivacyRevisionPolicy.shouldHoldOutbox(
            privacyOverviewLoaded = true,
            migrationPending = categories.getJSONObject("progress").getBoolean("migrationRequired")
        ))
        assertEquals("keep", migration.getJSONObject("decisions").getString("progress"))
    }

    @Test
    fun localPrivacyQuarantineUsesDeviceEncryptionAndCanBeReviewed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PrivacyQuarantineStore.clearCategory(context, "conversations")
        try {
            PrivacyQuarantineStore.replaceCategory(context, "conversations", "{\"text\":\"private local draft\"}")
            val persisted = context.getSharedPreferences("phonebridge_privacy_quarantine", 0)
                .getString("conversations.ciphertext", "").orEmpty()
            assertFalse(persisted.contains("private local draft"))
            assertTrue(PrivacyQuarantineStore.hasCategory(context, "conversations"))
            assertEquals("{\"text\":\"private local draft\"}",
                PrivacyQuarantineStore.snapshot(context).getString("conversations"))
        } finally {
            PrivacyQuarantineStore.clearCategory(context, "conversations")
        }
        assertFalse(PrivacyQuarantineStore.hasCategory(context, "conversations"))
    }

    @Test
    fun androidDecryptsTheSharedCrossRuntimeLocalArchiveFixture() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = context.assets.open("privacy-local-archive.json").use {
            JSONObject(BufferedReader(InputStreamReader(it)).readText())
        }
        assertEquals("phonebridge-local-privacy", fixture.getString("format"))
        assertEquals("PBKDF2-HMAC-SHA256", fixture.getString("kdf"))
        assertEquals(210_000, fixture.getInt("iterations"))
        val envelope = PrivacyLocalArchiveEnvelope(
            salt = fixture.getString("salt"),
            nonce = fixture.getString("nonce"),
            sha256 = fixture.getString("sha256"),
            ciphertext = fixture.getString("ciphertext")
        )

        assertEquals(
            "{\"formatVersion\":1,\"test\":\"cross-runtime crypto fixture\"}",
            PrivacyLocalArchiveCrypto.decrypt(envelope, "PhoneBridge fixture test passphrase")
        )
    }

    @Test
    fun migratesV4ToV5WithoutLosingRowsAndQuarantinesLegacyOutbox() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
        try {
            helper.createDatabase(TEST_DATABASE, 4).apply {
                execSQL("INSERT INTO workspace_sessions (id,title,providerId,model,systemPrompt,createdAt,updatedAt,armedUntil) VALUES ('session-1','Saved session','codex','model-a','',10,20,NULL)")
                execSQL("INSERT INTO workspace_messages (id,sessionId,role,text,createdAt,streamId) VALUES ('message-1','session-1','user','Keep this row',15,NULL)")
                execSQL("INSERT INTO workspace_tasks (id,source,title,state,progress,detail,error,retryCount,artifactRefsJson,createdAt,updatedAt) VALUES ('task-1','conversation','Keep task','pending',0,'',NULL,0,'[]',10,20)")
                execSQL("INSERT INTO workspace_outbox (eventId,origin,sequence,type,payload,createdAt,ack,retryCount,nextAttemptAt) VALUES ('legacy-event','phone',1,'workspace.message','{\"text\":\"quarantine\"}',10,0,0,10)")
                close()
            }

            val migrated = helper.runMigrationsAndValidate(
                TEST_DATABASE,
                5,
                true,
                *WorkspaceRepository.MIGRATIONS
            )
            migrated.query("SELECT id FROM workspace_sessions WHERE id='session-1'").use { assertTrue(it.moveToFirst()) }
            migrated.query("SELECT id FROM workspace_messages WHERE id='message-1'").use { assertTrue(it.moveToFirst()) }
            migrated.query("SELECT id FROM workspace_tasks WHERE id='task-1'").use { assertTrue(it.moveToFirst()) }
            migrated.query("SELECT privacyCategory,privacyRevision,privacyRevisionsJson,quarantined FROM workspace_outbox WHERE eventId='legacy-event'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertEquals(0L, it.getLong(1))
                assertEquals("{}", it.getString(2))
                assertEquals(1, it.getInt(3))
            }
            migrated.query("SELECT COUNT(*) FROM workspace_privacy_state").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            migrated.close()

            val database = Room.databaseBuilder(context, WorkspaceDatabase::class.java, TEST_DATABASE)
                .addMigrations(*WorkspaceRepository.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            try {
            database.workspaceDao().enqueue(
                    WorkspaceOutboxEntity(
                        eventId = "revisioned-event",
                        origin = "phone",
                        sequence = 2L,
                        type = WorkspaceEventTypes.MESSAGE,
                        payload = "{\"text\":\"new event\"}",
                        createdAt = 20L,
                        nextAttemptAt = 20L,
                        privacyCategory = "conversations",
                        privacyRevision = 0L,
                        privacyRevisionsJson = "{\"conversations\":0}",
                        quarantined = false
                )
            )
            val ready = database.workspaceDao().readyOutbox(Long.MAX_VALUE)
            assertFalse(ready.any { it.eventId == "legacy-event" })
            assertEquals("revisioned-event", ready.single().eventId)
            database.workspaceDao().quarantineOutbox("revisioned-event")
            assertEquals(0, database.workspaceDao().claimOutbox("revisioned-event", 20L, 1_020L, 20L))
            assertFalse(database.workspaceDao().readyOutbox(Long.MAX_VALUE).any { it.eventId == "revisioned-event" })
            assertEquals("{\"text\":\"quarantine\"}", database.workspaceDao().outbox("legacy-event")?.payload)
            database.workspaceDao().savePrivacyState(
                WorkspacePrivacyStateEntity("conversations", revision = 2L, migrationRequired = true, decision = "keep", updatedAt = 30L)
            )
            assertEquals(2L, database.workspaceDao().privacyState("conversations")?.revision)
            assertTrue(database.workspaceDao().privacyState("conversations")?.migrationRequired == true)
            assertEquals("Saved session", database.workspaceDao().sessions().single().title)
                assertEquals("Keep this row", database.workspaceDao().messages("session-1").single().text)
                assertEquals("Keep task", database.workspaceDao().tasks().single().title)
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(TEST_DATABASE)
        }
    }

    @Test
    fun migratesV5ToV6WithoutDestructiveFallbackOrLosingTaskRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(V6_MIGRATION_DATABASE)
        try {
            helper.createDatabase(V6_MIGRATION_DATABASE, 5).apply {
                execSQL("INSERT INTO workspace_tasks (id,source,title,state,progress,detail,error,retryCount,artifactRefsJson,createdAt,updatedAt) VALUES ('v5-task','goal','Saved task','running',40,'keep',NULL,1,'[]',100,120)")
                close()
            }

            val migrated = helper.runMigrationsAndValidate(
                V6_MIGRATION_DATABASE,
                6,
                true,
                *WorkspaceRepository.MIGRATIONS
            )
            migrated.query("SELECT id,goalId,milestoneId FROM workspace_tasks WHERE id='v5-task'").use {
                assertTrue(it.moveToFirst())
                assertEquals("v5-task", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
            migrated.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('workspace_routine_entries','workspace_goals','workspace_milestones')").use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
            }
            migrated.close()
        } finally {
            context.deleteDatabase(V6_MIGRATION_DATABASE)
        }
    }

    @Test
    fun routineOutboxSeparatesPendingAcceptedAndRejectedBusinessState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(ROUTINE_REPOSITORY_DATABASE)
        val repository = WorkspaceRepository(context, ROUTINE_REPOSITORY_DATABASE)
        try {
            val start = RoutineActionRequest(
                eventId = "routine-room-start-001",
                routineId = DailyRoutineProtocol.FOCUS_TIMER,
                action = "start",
                occurredAt = "2026-09-30T08:00:00.000Z"
            )
            val startEvent = routineWorkspaceEvent(start, sequence = 1L)
            repository.observePrivacyStates(listOf(WorkspacePrivacyStateEntity("routines", revision = 2L)))
            assertFalse(repository.enqueueRoutineAction(startEvent, start).queued)
            repository.observePrivacyStates(completePrivacyStates(
                WorkspacePrivacyStateEntity("routines", revision = 2L)
            ))
            assertTrue(repository.enqueueRoutineAction(startEvent, start).queued)
            val pending = repository.routineEntries().single()
            assertEquals("pending", pending.status)
            assertEquals(RoutineSyncState.PENDING, pending.syncState)
            assertEquals(mapOf("routines" to 2L), repository.outboxEvent(start.eventId)?.privacyRevisions)

            val serverEntry = pending.copy(
                id = "routine-server-001",
                status = "active",
                syncState = RoutineSyncState.CONFIRMED,
                pendingEventId = null,
                pendingAction = null,
                revision = 1L
            )
            assertTrue(repository.completeRoutineAction(start.eventId, accepted = true, serverEntry = serverEntry, resultRevision = 1L))
            assertEquals("active", repository.routineEntries().single().status)
            assertEquals(RoutineSyncState.CONFIRMED, repository.routineEntries().single().syncState)

            val pause = start.copy(
                eventId = "routine-room-pause-001",
                action = "pause",
                occurredAt = "2026-09-30T08:05:00.000Z",
                elapsedSeconds = 300L
            )
            assertTrue(repository.enqueueRoutineAction(routineWorkspaceEvent(pause, sequence = 2L), pause).queued)
            val rejected = repository.routineEntries().single()
            assertEquals("active", rejected.status)
            assertEquals(RoutineSyncState.PENDING, rejected.syncState)
            assertTrue(repository.completeRoutineAction(pause.eventId, accepted = false, reason = "invalid_transition"))
            val restored = repository.routineEntries().single()
            assertEquals("active", restored.status)
            assertEquals(RoutineSyncState.REJECTED, restored.syncState)
            assertEquals("invalid_transition", restored.syncReason)
            assertTrue(repository.outboxEvent(pause.eventId)?.ack == true)
        } finally {
            repository.close()
            context.deleteDatabase(ROUTINE_REPOSITORY_DATABASE)
        }
    }

    @Test
    fun routineAndGoalPrivacyPurgesAreScopedAndTaskDeletionUnbindsMilestones() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(SPACES_REPOSITORY_DATABASE)
        val repository = WorkspaceRepository(context, SPACES_REPOSITORY_DATABASE)
        try {
            repository.observePrivacyStates(completePrivacyStates(
                WorkspacePrivacyStateEntity("routines", revision = 1L),
                WorkspacePrivacyStateEntity("goals", revision = 2L),
                WorkspacePrivacyStateEntity("tasks", revision = 3L)
            ))
            val goal = GoalBoardGoal(
                id = "goal-room-001",
                title = "完成离线样例",
                description = "仅用于数据库迁移测试",
                createdAt = "2026-09-30T08:00:00.000Z",
                updatedAt = "2026-09-30T08:00:00.000Z",
                milestones = listOf(GoalBoardMilestone("milestone-room-001", "goal-room-001", "整理需求", "", "running", "task-room-001"))
            )
            val task = WorkspaceTaskEntity(
                id = "task-room-001", source = "goal", title = "整理需求", state = "running",
                goalId = goal.id, milestoneId = goal.milestones.single().id
            )
            repository.saveAcceptedGoal(goal, listOf(task))
            assertEquals("in_progress", repository.goals().single().milestones.single().status)
            repository.saveTask(task.copy(state = "succeeded"))
            assertEquals("completed", repository.goals().single().milestones.single().status)
            val routineRequest = RoutineActionRequest(
                eventId = "routine-room-privacy-001", routineId = DailyRoutineProtocol.WALK_OBSERVATION,
                action = "start", occurredAt = "2026-09-30T08:10:00.000Z"
            )
            assertTrue(repository.enqueueRoutineAction(routineWorkspaceEvent(routineRequest, 1L), routineRequest).queued)

            repository.purgePrivacyCategories(listOf("tasks"))
            assertTrue(repository.tasks().isEmpty())
            val retained = repository.goals().single().milestones.single()
            assertNull(retained.taskId)
            assertEquals("pending", retained.status)
            assertEquals(1, repository.routineEntries().size)

            repository.saveAcceptedGoal(goal, listOf(task))
            repository.saveTask(task.copy(state = "succeeded"))
            assertEquals("completed", repository.goals().single().milestones.single().status)
            repository.deleteTask(task.id)
            assertTrue(repository.tasks().isEmpty())
            assertNull(repository.goals().single().milestones.single().taskId)
            assertEquals("pending", repository.goals().single().milestones.single().status)

            repository.saveAcceptedGoal(goal, listOf(task))
            repository.deleteGoal(goal.id)
            assertTrue(repository.goals().isEmpty())
            assertTrue(repository.tasks().isEmpty())

            repository.saveAcceptedGoal(goal, listOf(task))
            repository.purgePrivacyCategories(listOf("goals"))
            assertTrue(repository.goals().isEmpty())
            assertTrue(repository.tasks().isEmpty())
            assertEquals(1, repository.routineEntries().size)

            repository.purgePrivacyCategories(listOf("routines"))
            assertTrue(repository.routineEntries().isEmpty())
            assertNull(repository.outboxEvent(routineRequest.eventId))
        } finally {
            repository.close()
            context.deleteDatabase(SPACES_REPOSITORY_DATABASE)
        }
    }

    @Test
    fun legacyKeepIsReadOnlyAndClearPurgesItsCategoryOutbox() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(REPOSITORY_DATABASE)
        val repository = WorkspaceRepository(context, REPOSITORY_DATABASE)
        try {
            assertTrue(repository.privacySyncBlocked())
            repository.observePrivacyStates(completePrivacyStates(
                WorkspacePrivacyStateEntity("conversations", migrationRequired = true),
                WorkspacePrivacyStateEntity("tasks", migrationRequired = true)
            ))
            assertTrue(repository.privacySyncBlocked())
            val conversation = WorkspaceEvent(
                eventId = "legacy-conversation",
                origin = "phone",
                sequence = 1L,
                type = WorkspaceEventTypes.MESSAGE,
                payload = "{\"text\":\"private legacy text\"}",
                createdAt = 10L
            )
            val task = WorkspaceEvent(
                eventId = "legacy-task",
                origin = "phone",
                sequence = 2L,
                type = WorkspaceEventTypes.TASK_PROGRESS,
                payload = "{\"taskId\":\"task-legacy\"}",
                createdAt = 11L
            )
            assertTrue(repository.enqueue(conversation))
            assertTrue(repository.enqueue(task))
            assertTrue(repository.readyOutbox(Long.MAX_VALUE).isEmpty())

            assertTrue(repository.applyLegacyPrivacyDecision("conversations", "keep"))
            assertFalse(repository.applyLegacyPrivacyDecision("conversations", "clear"))
            assertEquals("legacy-conversation", repository.outboxEvent(conversation.eventId)?.eventId)
            val afterChoice = conversation.copy(eventId = "new-after-choice", sequence = 3L, createdAt = 12L)
            assertTrue(repository.enqueue(afterChoice))
            assertEquals(mapOf("conversations" to 1L), repository.outboxEvent(afterChoice.eventId)?.privacyRevisions)
            val conversationCounts = repository.localPrivacyCounts().getValue("conversations")
            assertEquals(2L, conversationCounts.pendingOutbox)
            assertEquals(0L, conversationCounts.quarantinedOutbox)
            repository.observePrivacyStates(
                completePrivacyStates(
                    WorkspacePrivacyStateEntity("conversations", revision = 1L, migrationRequired = false, decision = "keep"),
                    WorkspacePrivacyStateEntity("tasks", migrationRequired = true)
                )
            )
            assertEquals("keep", repository.privacyStates().single { it.category == "conversations" }.decision)
            assertFalse(repository.privacyStates().single { it.category == "conversations" }.migrationRequired)
            assertTrue(repository.privacySyncBlocked())
            assertTrue(repository.readyOutbox(Long.MAX_VALUE).isEmpty())

            assertTrue(repository.applyLegacyPrivacyDecision("tasks", "clear"))
            assertNull(repository.outboxEvent(task.eventId))
            repository.observePrivacyStates(completePrivacyStates(
                WorkspacePrivacyStateEntity("conversations", revision = 1L, migrationRequired = false, decision = "keep"),
                WorkspacePrivacyStateEntity("tasks", revision = 1L, migrationRequired = false, decision = "clear")
            ))
            assertFalse(repository.privacySyncBlocked())
            assertEquals(1L, repository.localPrivacyCounts().getValue("conversations").quarantinedOutbox)
            assertEquals("new-after-choice", repository.readyOutbox(Long.MAX_VALUE).single().eventId)
            assertEquals(mapOf("conversations" to 1L), repository.readyOutbox(Long.MAX_VALUE).single().privacyRevisions)
        } finally {
            repository.close()
            context.deleteDatabase(REPOSITORY_DATABASE)
        }
    }

    @Test
    fun privacyDeletionPurgesPreRevisionEventsAndNewEventsUseTheAcceptedRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(REVISION_DATABASE)
        val repository = WorkspaceRepository(context, REVISION_DATABASE)
        try {
            repository.observePrivacyStates(completePrivacyStates(WorkspacePrivacyStateEntity("conversations", revision = 0L)))
            assertFalse(repository.privacySyncBlocked())
            val beforeDeletion = WorkspaceEvent(
                eventId = "before-delete",
                origin = "phone",
                sequence = 1L,
                type = WorkspaceEventTypes.MESSAGE,
                payload = "{\"text\":\"old\"}",
                createdAt = 10L
            )
            assertTrue(repository.enqueue(beforeDeletion))
            assertEquals(mapOf("conversations" to 0L), repository.outboxEvent(beforeDeletion.eventId)?.privacyRevisions)

            repository.applyPrivacyDeletion(listOf("conversations"), mapOf("conversations" to 1L))
            assertNull(repository.outboxEvent(beforeDeletion.eventId))

            val afterDeletion = beforeDeletion.copy(eventId = "after-delete", sequence = 2L, payload = "{\"text\":\"new\"}")
            assertTrue(repository.enqueue(afterDeletion))
            assertEquals(mapOf("conversations" to 1L), repository.readyOutbox(Long.MAX_VALUE).single().privacyRevisions)
        } finally {
            repository.close()
            context.deleteDatabase(REVISION_DATABASE)
        }
    }

    @Test
    fun fetchingPrivacyOverviewNeverRebasesAnOlderPendingEventToANewerDeletionRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(OVERVIEW_DATABASE)
        val repository = WorkspaceRepository(context, OVERVIEW_DATABASE)
        try {
            val beforeOverview = WorkspaceEvent(
                eventId = "before-first-overview",
                origin = "phone",
                sequence = 1L,
                type = WorkspaceEventTypes.MESSAGE,
                payload = "{\"text\":\"must remain stale\"}",
                createdAt = 10L
            )
            assertTrue(repository.enqueue(beforeOverview))
            assertTrue(repository.privacySyncBlocked())

            repository.observePrivacyStates(completePrivacyStates(WorkspacePrivacyStateEntity("conversations", revision = 3L)))

            assertTrue(repository.readyOutbox(Long.MAX_VALUE).isEmpty())
            assertEquals(1L, repository.localPrivacyCounts().getValue("conversations").quarantinedOutbox)
            assertEquals(mapOf("conversations" to 0L), repository.outboxEvent(beforeOverview.eventId)?.privacyRevisions)
        } finally {
            repository.close()
            context.deleteDatabase(OVERVIEW_DATABASE)
        }
    }

    companion object {
        private const val TEST_DATABASE = "workspace-migration-v4-v5.db"
        private const val V6_MIGRATION_DATABASE = "workspace-migration-v5-v6.db"
        private const val ROUTINE_REPOSITORY_DATABASE = "workspace-routine-repository-v6.db"
        private const val SPACES_REPOSITORY_DATABASE = "workspace-spaces-repository-v6.db"
        private const val REPOSITORY_DATABASE = "workspace-privacy-decision-test.db"
        private const val REVISION_DATABASE = "workspace-privacy-revision-test.db"
        private const val OVERVIEW_DATABASE = "workspace-privacy-overview-test.db"
    }

    private fun completePrivacyStates(vararg overrides: WorkspacePrivacyStateEntity): List<WorkspacePrivacyStateEntity> {
        val byCategory = overrides.associateBy { it.category }
        return PrivacyDataPolicy.categories.map { category ->
            byCategory[category] ?: WorkspacePrivacyStateEntity(category)
        }
    }

    private fun routineWorkspaceEvent(request: RoutineActionRequest, sequence: Long) = WorkspaceEvent(
        eventId = request.eventId,
        origin = "phone-test",
        sequence = sequence,
        type = WorkspaceEventTypes.ROUTINE_EVENT,
        payload = DailyRoutineProtocol.actionPayload(request, privacyRevision = 0L),
        createdAt = 1_790_740_800_000L
    )
}
