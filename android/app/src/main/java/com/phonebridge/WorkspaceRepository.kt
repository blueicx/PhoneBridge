package com.phonebridge

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class WorkspaceRepository private constructor(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        WorkspaceDatabase::class.java,
        "phonebridge-workspace.db"
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
        dao.enqueue(
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
                localActionState = event.localActionState?.let(ActionRunState::fromWire)
            )
        ) != -1L
    }

    suspend fun readyOutbox(now: Long = System.currentTimeMillis()): List<WorkspaceEvent> = withContext(Dispatchers.IO) {
        dao.readyOutbox(now).map { it.toEvent() }
    }

    suspend fun acknowledge(eventId: String) = withContext(Dispatchers.IO) { dao.acknowledge(eventId) }

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
        localActionState = localActionState
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
