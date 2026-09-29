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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
}
