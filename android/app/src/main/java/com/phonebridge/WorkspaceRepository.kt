package com.phonebridge

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PrivacyLocalCounts(
    val roomRecords: Long = 0L,
    val preferencesRecords: Long = 0L,
    val pendingOutbox: Long = 0L,
    val quarantinedOutbox: Long = 0L
)

class WorkspaceRepository internal constructor(
    context: Context,
    databaseName: String = "phonebridge-workspace.db"
) {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(
        appContext,
        WorkspaceDatabase::class.java,
        databaseName
    )
        .addMigrations(*MIGRATIONS)
        .build()
    private val dao = database.workspaceDao()

    suspend fun saveSession(session: WorkspaceSessionEntity) = withContext(Dispatchers.IO) { dao.saveSession(session) }

    suspend fun sessions(): List<WorkspaceSessionEntity> = withContext(Dispatchers.IO) { dao.sessions() }

    suspend fun saveMessage(message: WorkspaceMessageEntity) = withContext(Dispatchers.IO) { dao.saveMessage(message) }

    suspend fun messages(sessionId: String): List<WorkspaceMessageEntity> = withContext(Dispatchers.IO) { dao.messages(sessionId) }

    suspend fun saveTask(task: WorkspaceTaskEntity) = withContext(Dispatchers.IO) { dao.saveTask(task) }

    suspend fun tasks(): List<WorkspaceTaskEntity> = withContext(Dispatchers.IO) { dao.tasks() }

    suspend fun saveAttentionItem(item: AttentionItem) = withContext(Dispatchers.IO) {
        // SQLite's primary/unique keys make this a single atomic upsert. A
        // read-merge-clear-write cycle could lose a concurrent WS event.
        dao.saveAttentionItem(item.toEntity())
    }

    suspend fun saveAttentionItems(items: List<AttentionItem>) = withContext(Dispatchers.IO) {
        if (items.isNotEmpty()) dao.saveAttentionItems(items.map { it.toEntity() })
    }

    suspend fun replaceAttentionItems(items: List<AttentionItem>) = withContext(Dispatchers.IO) {
        dao.replaceAttention(mergeAttentionItems(emptyList(), items).map { it.toEntity() })
    }

    suspend fun attentionItems(): List<AttentionItem> = withContext(Dispatchers.IO) {
        dao.attention().map { it.toModel() }
    }

    suspend fun updateAttentionState(
        id: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
        snoozedUntil: Long? = null
    ) = withContext(Dispatchers.IO) {
        dao.updateAttentionState(id, AttentionStatus.fromWire(status), updatedAt, snoozedUntil)
    }

    suspend fun savePolicy(policy: AutonomyPolicy) = withContext(Dispatchers.IO) {
        dao.savePolicy(policy.toEntity())
    }

    suspend fun savePolicies(items: List<AutonomyPolicy>) = withContext(Dispatchers.IO) {
        dao.replacePolicies(items.map { it.toEntity() })
    }

    suspend fun policies(): List<AutonomyPolicy> = withContext(Dispatchers.IO) {
        dao.policies().map { it.toModel() }
    }

    suspend fun updatePolicy(policy: AutonomyPolicy) = withContext(Dispatchers.IO) {
        dao.updatePolicy(
            scopeType = AutonomyScope.fromWire(policy.scopeType),
            scopeId = policy.scopeId,
            level = AutonomyLevel.fromWire(policy.level),
            allowedToolsJson = policy.allowedTools.toStableJson(),
            expiresAt = policy.expiresAt,
            continuousMic = policy.continuousMic,
            confirmationRulesJson = AutonomyConfirmationRule.listToJson(policy.confirmationRules)
        )
    }

    suspend fun saveActionRun(run: ActionRun) = withContext(Dispatchers.IO) {
        dao.saveActionRun(run.toEntity())
    }

    suspend fun saveActionRuns(items: List<ActionRun>) = withContext(Dispatchers.IO) {
        dao.replaceActionRuns(items.map { it.toEntity() })
    }

    suspend fun actionRuns(): List<ActionRun> = withContext(Dispatchers.IO) {
        dao.actionRuns().map { it.toModel() }
    }

    suspend fun purgePrivacyCategories(categories: List<String>, linkedConversationTaskIds: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        val normalized = PrivacyDataPolicy.normalizeCategories(categories)
            ?: throw IllegalArgumentException("invalid privacy categories")
        database.withTransaction {
            normalized.forEach { category -> clearRoomPrivacyCategory(category, linkedConversationTaskIds) }
            purgeClassifiedOutbox(normalized.toSet())
        }
    }

    /** Applies a remote deletion fence and local purge atomically with respect to outbox enqueue. */
    suspend fun applyPrivacyDeletion(
        categories: List<String>,
        revisions: Map<String, Long>,
        linkedConversationTaskIds: List<String> = emptyList(),
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val normalized = PrivacyDataPolicy.normalizeCategories(categories)
            ?: throw IllegalArgumentException("invalid privacy categories")
        val safeRevisions = PrivacyRevisionWire.fromValue(revisions)
        database.withTransaction {
            persistPrivacyRevisions(safeRevisions, updatedAt)
            normalized.forEach { category -> clearRoomPrivacyCategory(category, linkedConversationTaskIds) }
            val currentStates = dao.privacyStates().associateBy { it.category }
            dao.allOutbox().forEach { row ->
                val eventCategories = PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload))
                    ?.categories.orEmpty()
                val touchedCategories = eventCategories.filter(normalized::contains)
                val stamped = PrivacyRevisionWire.fromJson(row.privacyRevisionsJson)
                val stale = touchedCategories.any { category ->
                    val receiptRevision = safeRevisions[category]
                    val deletionRevision = receiptRevision?.let {
                        maxOf(it, currentStates[category]?.revision ?: 0L)
                    }
                    val eventRevision = stamped[category]
                    deletionRevision == null || eventRevision == null || eventRevision < deletionRevision
                }
                if (stale) dao.deleteOutboxEvent(row.eventId)
            }
            quarantineInvalidOutbox(dao.privacyStates().associateBy { it.category })
        }
    }

    suspend fun localPrivacyCounts(): Map<String, PrivacyLocalCounts> = withContext(Dispatchers.IO) {
        val allEvents = dao.allOutbox()
        val roomCounts = mapOf(
            "memories" to 0L,
            "conversations" to (
                dao.countSessions() + dao.countMessages() + dao.countConversationTasks() +
                    dao.countConversationAttention() + dao.countConversationActionRuns()
                ),
            "tasks" to (dao.countTasks() + dao.countAttention() + dao.countActionRuns()),
            "progress" to 0L,
            "routines" to 0L,
            "goals" to 0L
        )
        PrivacyDataPolicy.categories.associateWith { category ->
            val matching = allEvents.filter { row ->
                PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload))
                    ?.categories?.contains(category) == true
            }
            PrivacyLocalCounts(
                roomRecords = roomCounts[category] ?: 0L,
                preferencesRecords = when (category) {
                    "memories" -> MoteMemory.load(appContext).size.toLong() +
                        if (PrivacyQuarantineStore.hasCategory(appContext, category)) 1L else 0L
                    "conversations" -> ChatOutbox.load(appContext).size.toLong() +
                        (if (MoteHandoff.load(appContext).isEmpty()) 0L else 1L) +
                        (if (PrivacyQuarantineStore.hasCategory(appContext, category)) 1L else 0L)
                    "progress" -> localProgressPreferenceRecords() +
                        if (PrivacyQuarantineStore.hasCategory(appContext, category)) 1L else 0L
                    else -> 0L
                },
                pendingOutbox = matching.count { !it.ack }.toLong(),
                quarantinedOutbox = matching.count { !it.ack && it.quarantined }.toLong()
            )
        }
    }

    suspend fun quarantinedOutboxForCategory(category: String): List<WorkspaceEvent> = withContext(Dispatchers.IO) {
        require(category in PrivacyDataPolicy.categories) { "invalid privacy category" }
        dao.allOutbox().asSequence()
            .filter { it.quarantined }
            .filter { row ->
                PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload))
                    ?.categories?.contains(category) == true
            }
            .map { it.toEvent() }
            .toList()
    }

    suspend fun unclassifiedQuarantinedOutboxCount(): Long = withContext(Dispatchers.IO) {
        dao.allOutbox().count { row ->
            row.quarantined && PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload)) == null
        }.toLong()
    }

    suspend fun unclassifiedQuarantinedOutbox(): List<WorkspaceEvent> = withContext(Dispatchers.IO) {
        dao.allOutbox().asSequence()
            .filter { it.quarantined }
            .filter { PrivacyDataPolicy.classifyEvent(it.type, parseWorkspaceJsonObject(it.payload)) == null }
            .map { it.toEvent() }
            .toList()
    }

    private fun localProgressPreferenceRecords(): Long {
        val roster = appContext.getSharedPreferences("mote_roster", Context.MODE_PRIVATE)
        fun arraySize(key: String): Long = runCatching {
            val raw = roster.getString(key, null) ?: return@runCatching 0L
            JSONArray(raw).length().toLong()
        }.getOrDefault(0L)
        fun objectSize(key: String): Long = runCatching {
            val raw = roster.getString(key, null) ?: return@runCatching 0L
            JSONObject(raw).length().toLong()
        }.getOrDefault(0L)
        val hasPetSnapshot = appContext.getSharedPreferences("mote_pet", Context.MODE_PRIVATE)
            .let { prefs -> listOf("level", "experience", "appearance").any(prefs::contains) }
        return arraySize("roster") + objectSize("state") + arraySize("story") + if (hasPetSnapshot) 1L else 0L
    }

    /** Applies the local half of a legacy-data decision before the caller confirms it with the server. */
    suspend fun applyLegacyPrivacyDecision(
        category: String,
        decision: String,
        linkedConversationTaskIds: List<String> = emptyList(),
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        if (category !in PrivacyDataPolicy.categories || decision !in setOf("clear", "keep")) return@withContext false
        database.withTransaction {
            val existing = dao.privacyState(category)
            if (existing?.decision != null && existing.decision != decision) return@withTransaction false
            if (existing?.decision == decision) return@withTransaction true
            if (decision == "clear") {
                clearRoomPrivacyCategory(category, linkedConversationTaskIds)
                purgeClassifiedOutbox(setOf(category))
            }
            dao.savePrivacyState((existing ?: WorkspacePrivacyStateEntity(category = category, migrationRequired = true)).copy(
                decision = decision,
                updatedAt = updatedAt
            ))
            true
        }
    }

    private suspend fun clearRoomPrivacyCategory(category: String, linkedConversationTaskIds: List<String>) {
        when (category) {
            "conversations" -> dao.purgeConversationData(linkedConversationTaskIds)
            "tasks" -> dao.purgeTaskData()
            "progress" -> dao.purgeProgressData()
            "memories", "routines", "goals" -> Unit
        }
    }

    private suspend fun purgeClassifiedOutbox(categories: Set<String>) {
        dao.allOutbox().forEach { row ->
            val classification = PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload))
            if (classification?.categories.orEmpty().any(categories::contains)) dao.deleteOutboxEvent(row.eventId)
        }
    }

    suspend fun privacyStates(): List<WorkspacePrivacyStateEntity> = withContext(Dispatchers.IO) {
        dao.privacyStates()
    }

    suspend fun privacySyncBlocked(): Boolean = withContext(Dispatchers.IO) {
        val states = dao.privacyStates()
        PrivacyRevisionPolicy.shouldHoldOutbox(
            privacyOverviewLoaded = PrivacyRevisionPolicy.hasCompleteOverview(
                PrivacyDataPolicy.categories,
                states.mapTo(mutableSetOf()) { it.category }
            ),
            migrationPending = states.any { it.migrationRequired }
        )
    }

    suspend fun observePrivacyStates(incoming: List<WorkspacePrivacyStateEntity>) = withContext(Dispatchers.IO) {
        val safe = incoming.filter { it.category in PrivacyDataPolicy.categories && it.revision >= 0L }
        if (safe.isEmpty()) return@withContext
        database.withTransaction {
            safe.forEach { next ->
                val previous = dao.privacyState(next.category)
                if (previous == null || next.revision >= previous.revision) {
                    val pendingLocalDecision = previous?.decision?.takeIf { next.migrationRequired && next.decision == null }
                    dao.savePrivacyState(next.copy(
                        decision = next.decision ?: pendingLocalDecision,
                        updatedAt = if (next.migrationRequired && previous?.migrationRequired == true) previous.updatedAt else next.updatedAt
                    ))
                }
            }
            // Never rewrite an existing event's fence from the newly fetched snapshot:
            // an event queued before this overview may predate a remote deletion.
            quarantineInvalidOutbox(dao.privacyStates().associateBy { it.category })
        }
    }

    /** Applies monotonic server revisions and quarantines any local event stamped before a deletion. */
    suspend fun observePrivacyRevisions(revisions: Map<String, Long>, updatedAt: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val safe = PrivacyRevisionWire.fromValue(revisions)
        if (safe.isEmpty()) return@withContext
        database.withTransaction {
            persistPrivacyRevisions(safe, updatedAt)
            val current = dao.privacyStates().associateBy { it.category }
            quarantineInvalidOutbox(current)
        }
    }

    private suspend fun persistPrivacyRevisions(revisions: Map<String, Long>, updatedAt: Long) {
        revisions.forEach { (category, revision) ->
            val previous = dao.privacyState(category)
            if (revision >= (previous?.revision ?: 0L)) {
                dao.savePrivacyState(
                    WorkspacePrivacyStateEntity(
                        category = category,
                        revision = revision,
                        migrationRequired = previous?.migrationRequired ?: false,
                        decision = previous?.decision,
                        updatedAt = updatedAt
                    )
                )
            }
        }
    }

    private suspend fun quarantineInvalidOutbox(states: Map<String, WorkspacePrivacyStateEntity>) {
        dao.pendingOutbox().forEach { row ->
            val categories = PrivacyDataPolicy.classifyEvent(row.type, parseWorkspaceJsonObject(row.payload))?.categories
                ?: run { dao.quarantineOutbox(row.eventId); return@forEach }
            val stamped = PrivacyRevisionWire.fromJson(row.privacyRevisionsJson)
            val invalid = categories.any { category ->
                val state = states[category]
                if (state?.migrationRequired == true) return@any false
                PrivacyRevisionPolicy.check(
                    currentRevision = state?.revision ?: 0L,
                    clientRevision = stamped[category],
                    migrationRequired = state?.migrationRequired == true
                ) != PrivacyRevisionDisposition.ACCEPT
            }
            if (invalid) dao.quarantineOutbox(row.eventId)
        }
    }

    suspend fun updateActionRun(run: ActionRun) = withContext(Dispatchers.IO) {
        dao.updateActionRunState(
            id = run.id,
            state = ActionRunState.fromWire(run.state),
            approval = ActionRunApproval.fromWire(run.approval),
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            resultRef = run.resultRef,
            error = run.error
        )
    }

    suspend fun enqueue(event: WorkspaceEvent): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val classification = PrivacyDataPolicy.classifyEvent(event.type, parseWorkspaceJsonObject(event.payload))
            val states = dao.privacyStates().associateBy { it.category }
            val categories = classification?.categories.orEmpty()
            val revisions = categories.associateWith { category ->
                val state = states[category]
                PrivacyRevisionPolicy.revisionForNewEvent(
                    currentRevision = state?.revision ?: 0L,
                    migrationRequired = state?.migrationRequired == true,
                    localDecision = state?.decision
                )
            }
            val quarantined = classification == null
            val primaryCategory = categories.singleOrNull()
            val inserted = dao.enqueue(
                WorkspaceOutboxEntity(
                    eventId = event.eventId,
                    origin = event.origin,
                    sequence = event.sequence,
                    type = event.type,
                    payload = event.payload,
                    createdAt = event.createdAt,
                    ack = event.ack,
                    nextAttemptAt = event.createdAt,
                    localActionId = event.localActionId,
                    localActionState = event.localActionState?.let(ActionRunState::fromWire),
                    privacyCategory = primaryCategory,
                    privacyRevision = primaryCategory?.let { revisions[it] } ?: 0L,
                    privacyRevisionsJson = PrivacyRevisionWire.toJson(revisions),
                    quarantined = quarantined
                )
            )
            inserted != -1L
        }
    }

    suspend fun readyOutbox(now: Long = System.currentTimeMillis()): List<WorkspaceEvent> = withContext(Dispatchers.IO) {
        if (privacySyncBlocked()) emptyList() else dao.readyOutbox(now).map { it.toEvent() }
    }

    suspend fun claimOutbox(
        eventId: String,
        now: Long = System.currentTimeMillis(),
        leaseMs: Long = 15_000L
    ): Boolean = withContext(Dispatchers.IO) {
        dao.claimOutbox(eventId, now, now + leaseMs.coerceAtLeast(1L), now) > 0
    }

    suspend fun outboxEvent(eventId: String): WorkspaceEvent? = withContext(Dispatchers.IO) {
        dao.outbox(eventId)?.toEvent()
    }

    suspend fun expireOutboxLeases(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val expired = dao.expiredOutbox(now)
        expired.forEach { eventId -> dao.retry(eventId, now + 1_000L) }
        expired.size
    }

    suspend fun acknowledge(
        eventId: String,
        accepted: Boolean = true,
        businessStatus: String? = null,
        reason: String? = null,
        resultRevision: Long? = null
    ) = withContext(Dispatchers.IO) {
        dao.acknowledge(
            eventId = eventId,
            businessStatus = businessStatus ?: if (accepted) "accepted" else "rejected",
            businessReason = reason,
            resultRevision = resultRevision
        )
    }

    suspend fun retry(
        eventId: String,
        now: Long = System.currentTimeMillis(),
        retryCount: Int = 0,
        localActionState: String? = null
    ) = withContext(Dispatchers.IO) {
        val backoff = (1_000L shl retryCount.coerceIn(0, 5)).coerceAtMost(60_000L)
        dao.retry(eventId, now + backoff, localActionState?.let(ActionRunState::fromWire))
    }

    fun close() = database.close()

    private fun WorkspaceAttentionEntity.toModel() = AttentionItem(
        id = id,
        source = source,
        severity = AttentionSeverity.fromWire(severity),
        status = AttentionStatus.fromWire(status),
        title = title,
        summary = summary,
        relatedSessionId = relatedSessionId,
        relatedTaskId = relatedTaskId,
        dedupeKey = dedupeKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
        snoozedUntil = snoozedUntil
    )

    private fun AttentionItem.toEntity() = WorkspaceAttentionEntity(
        id = id,
        source = source,
        severity = AttentionSeverity.fromWire(severity),
        status = AttentionStatus.fromWire(status),
        title = title,
        summary = summary,
        relatedSessionId = relatedSessionId,
        relatedTaskId = relatedTaskId,
        dedupeKey = dedupeKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
        snoozedUntil = snoozedUntil
    )

    private fun WorkspaceAutonomyPolicyEntity.toModel() = AutonomyPolicy(
        scopeType = AutonomyScope.fromWire(scopeType),
        scopeId = scopeId,
        level = AutonomyLevel.fromWire(level),
        allowedTools = parseStableStringList(allowedToolsJson),
        expiresAt = expiresAt,
        continuousMic = continuousMic,
        confirmationRules = parseConfirmationRules(confirmationRulesJson),
        revision = revision,
        usesRemaining = usesRemaining
    )

    private fun AutonomyPolicy.toEntity() = WorkspaceAutonomyPolicyEntity(
        scopeType = AutonomyScope.fromWire(scopeType),
        scopeId = scopeId,
        level = AutonomyLevel.fromWire(level),
        allowedToolsJson = allowedTools.toStableJson(),
        expiresAt = expiresAt,
        continuousMic = continuousMic,
        confirmationRulesJson = AutonomyConfirmationRule.listToJson(confirmationRules),
        revision = revision,
        usesRemaining = usesRemaining
    )

    private fun WorkspaceActionRunEntity.toModel() = ActionRun(
        id = id,
        origin = origin,
        sessionId = sessionId,
        automationId = automationId,
        taskId = taskId,
        toolId = toolId,
        argsSummary = argsSummary,
        state = ActionRunState.fromWire(state),
        approval = ActionRunApproval.fromWire(approval),
        createdAt = createdAt,
        startedAt = startedAt,
        endedAt = endedAt,
        resultRef = resultRef,
        error = error
    )

    private fun ActionRun.toEntity() = WorkspaceActionRunEntity(
        id = id,
        origin = origin,
        sessionId = sessionId,
        automationId = automationId,
        taskId = taskId,
        toolId = toolId,
        argsSummary = argsSummary,
        state = ActionRunState.fromWire(state),
        approval = ActionRunApproval.fromWire(approval),
        createdAt = createdAt,
        startedAt = startedAt,
        endedAt = endedAt,
        resultRef = resultRef,
        error = error
    )

    private fun WorkspaceOutboxEntity.toEvent() = WorkspaceEvent(
        eventId = eventId,
        origin = origin,
        sequence = sequence,
        type = type,
        payload = payload,
        createdAt = createdAt,
        ack = ack,
        localActionId = localActionId,
        localActionState = localActionState,
        privacyRevisions = PrivacyRevisionWire.fromJson(privacyRevisionsJson)
    )

    companion object {
        internal val MIGRATIONS: Array<Migration> = arrayOf(
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workspace_attention` (
                            `id` TEXT NOT NULL,
                            `source` TEXT NOT NULL,
                            `severity` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `summary` TEXT NOT NULL,
                            `relatedSessionId` TEXT,
                            `relatedTaskId` TEXT,
                            `dedupeKey` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `snoozedUntil` INTEGER,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_attention_updatedAt` ON `workspace_attention` (`updatedAt`)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_attention_dedupeKey` ON `workspace_attention` (`dedupeKey`)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workspace_policies` (
                            `scopeType` TEXT NOT NULL,
                            `scopeId` TEXT NOT NULL,
                            `level` TEXT NOT NULL,
                            `allowedToolsJson` TEXT NOT NULL,
                            `expiresAt` INTEGER,
                            `continuousMic` INTEGER NOT NULL,
                            `confirmationRulesJson` TEXT NOT NULL,
                            PRIMARY KEY(`scopeType`, `scopeId`)
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_policies_expiresAt` ON `workspace_policies` (`expiresAt`)")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workspace_action_runs` (
                            `id` TEXT NOT NULL,
                            `origin` TEXT NOT NULL,
                            `sessionId` TEXT,
                            `automationId` TEXT,
                            `taskId` TEXT,
                            `toolId` TEXT NOT NULL,
                            `argsSummary` TEXT NOT NULL,
                            `state` TEXT NOT NULL,
                            `approval` TEXT NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `startedAt` INTEGER,
                            `endedAt` INTEGER,
                            `resultRef` TEXT,
                            `error` TEXT,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    database.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_action_runs_createdAt` ON `workspace_action_runs` (`createdAt`)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_action_runs_state` ON `workspace_action_runs` (`state`)")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `localActionId` TEXT")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `localActionState` TEXT")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE `workspace_policies` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE `workspace_policies` ADD COLUMN `usesRemaining` INTEGER")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `leaseUntil` INTEGER")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `lastSentAt` INTEGER")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `businessStatus` TEXT")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `businessReason` TEXT")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `resultRevision` INTEGER")
                }
            },
            object : Migration(4, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `privacyCategory` TEXT")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `privacyRevision` INTEGER NOT NULL DEFAULT 0")
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `privacyRevisionsJson` TEXT NOT NULL DEFAULT '{}'")
                    // Pre-fence outbox rows have no trustworthy category revision; retain but never replay them.
                    database.execSQL("ALTER TABLE `workspace_outbox` ADD COLUMN `quarantined` INTEGER NOT NULL DEFAULT 1")
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `workspace_privacy_state` (
                            `category` TEXT NOT NULL,
                            `revision` INTEGER NOT NULL DEFAULT 0,
                            `migrationRequired` INTEGER NOT NULL DEFAULT 0,
                            `decision` TEXT,
                            `updatedAt` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`category`)
                        )
                        """.trimIndent()
                    )
                }
            }
        )

        @Volatile
        private var instance: WorkspaceRepository? = null

        fun get(context: Context): WorkspaceRepository = instance ?: synchronized(this) {
            instance ?: WorkspaceRepository(context).also { instance = it }
        }

        fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"

        private fun List<String>.toStableJson(): String = if (isEmpty()) "[]" else buildString {
            append("[")
            this@toStableJson.forEachIndexed { index, value ->
                if (index > 0) append(",")
                append(quoteJson(value))
            }
            append("]")
        }

        private fun parseConfirmationRules(raw: String?): List<AutonomyConfirmationRule> {
            val parsed = AutonomyConfirmationRule.listFromJson(raw)
            if (parsed.isNotEmpty() || raw?.trim() == "[]") return parsed
            return parseStableStringList(raw).map { AutonomyConfirmationRule(it, true) }
        }

        private fun parseStableStringList(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val trimmed = raw.trim()
            if (trimmed == "[]") return emptyList()
            return trimmed
                .removePrefix("[")
                .removeSuffix("]")
                .split(',')
                .filter { it.isNotBlank() }
                .map { token ->
                    token.trim().removePrefix("\"").removeSuffix("\"")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\b", "\b")
                        .replace("\\f", "\u000C")
                }
        }

        private fun quoteJson(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                    }
                }
            }
            append('"')
        }
    }
}
