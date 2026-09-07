package com.phonebridge

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

const val WORKSPACE_DB_VERSION = 2

@Entity(tableName = "workspace_sessions")
data class WorkspaceSessionEntity(
    @PrimaryKey val id: String = "",
    val title: String = "新会话",
    val providerId: String = "codex",
    val model: String = "",
    val systemPrompt: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val armedUntil: Long? = null
)

@Entity(
    tableName = "workspace_messages",
    indices = [Index(value = ["sessionId", "createdAt"])]
)
data class WorkspaceMessageEntity(
    @PrimaryKey val id: String = "",
    val sessionId: String = "",
    val role: String = "user",
    val text: String = "",
    val createdAt: Long = 0L,
    val streamId: String? = null
)

@Entity(tableName = "workspace_tasks", indices = [Index(value = ["updatedAt"])])
data class WorkspaceTaskEntity(
    @PrimaryKey val id: String = "",
    val source: String = "conversation",
    val title: String = "未命名任务",
    val state: String = "pending",
    val progress: Int = 0,
    val detail: String = "",
    val error: String? = null,
    val retryCount: Int = 0,
    val artifactRefsJson: String = "[]",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Entity(
    tableName = "workspace_attention",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["dedupeKey"], unique = true)
    ]
)
data class WorkspaceAttentionEntity(
    @PrimaryKey val id: String = "",
    val source: String = "",
    val severity: String = AttentionSeverity.MEDIUM,
    val status: String = AttentionStatus.OPEN,
    val title: String = "",
    val summary: String = "",
    val relatedSessionId: String? = null,
    val relatedTaskId: String? = null,
    val dedupeKey: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val snoozedUntil: Long? = null
)

@Entity(
    tableName = "workspace_policies",
    primaryKeys = ["scopeType", "scopeId"],
    indices = [Index(value = ["expiresAt"])]
)
data class WorkspaceAutonomyPolicyEntity(
    val scopeType: String = AutonomyScope.SESSION,
    val scopeId: String = "",
    val level: String = AutonomyLevel.OBSERVE,
    val allowedToolsJson: String = "[]",
    val expiresAt: Long? = null,
    val continuousMic: Boolean = false,
    val confirmationRulesJson: String = "[]"
)

@Entity(
    tableName = "workspace_action_runs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["state"])
    ]
)
data class WorkspaceActionRunEntity(
    @PrimaryKey val id: String = "",
    val origin: String = "",
    val sessionId: String? = null,
    val automationId: String? = null,
    val taskId: String? = null,
    val toolId: String = "",
    val argsSummary: String = "",
    val state: String = ActionRunState.QUEUED,
    val approval: String = ActionRunApproval.PENDING,
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val resultRef: String? = null,
    val error: String? = null
)

@Entity(
    tableName = "workspace_outbox",
    indices = [Index(value = ["origin", "sequence"], unique = true)]
)
data class WorkspaceOutboxEntity(
    @PrimaryKey val eventId: String = "",
    val origin: String = "phone",
    val sequence: Long = 0L,
    val type: String = "",
    val payload: String = "{}",
    val createdAt: Long = 0L,
    val ack: Boolean = false,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val localActionId: String? = null,
    val localActionState: String? = null
)

@Dao
abstract class WorkspaceDao {
    @Query("SELECT * FROM workspace_sessions ORDER BY updatedAt DESC")
    abstract suspend fun sessions(): List<WorkspaceSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSession(session: WorkspaceSessionEntity)

    @Query("SELECT * FROM workspace_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    abstract suspend fun messages(sessionId: String): List<WorkspaceMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveMessage(message: WorkspaceMessageEntity)

    @Query("SELECT * FROM workspace_tasks ORDER BY updatedAt DESC")
    abstract suspend fun tasks(): List<WorkspaceTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveTask(task: WorkspaceTaskEntity)

    @Query("SELECT * FROM workspace_attention ORDER BY updatedAt DESC")
    abstract suspend fun attention(): List<WorkspaceAttentionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveAttentionItem(item: WorkspaceAttentionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveAttentionItems(items: List<WorkspaceAttentionEntity>)

    @Query("DELETE FROM workspace_attention")
    abstract suspend fun clearAttention()

    @Query("UPDATE workspace_attention SET status = :status, updatedAt = :updatedAt, snoozedUntil = :snoozedUntil WHERE id = :id")
    abstract suspend fun updateAttentionState(id: String, status: String, updatedAt: Long, snoozedUntil: Long?)

    @Transaction
    open suspend fun replaceAttention(items: List<WorkspaceAttentionEntity>) {
        clearAttention()
        if (items.isNotEmpty()) saveAttentionItems(items)
    }

    @Query("SELECT * FROM workspace_policies ORDER BY scopeType ASC, scopeId ASC")
    abstract suspend fun policies(): List<WorkspaceAutonomyPolicyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun savePolicy(policy: WorkspaceAutonomyPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun savePolicies(items: List<WorkspaceAutonomyPolicyEntity>)

    @Query("DELETE FROM workspace_policies")
    abstract suspend fun clearPolicies()

    @Query("UPDATE workspace_policies SET level = :level, allowedToolsJson = :allowedToolsJson, expiresAt = :expiresAt, continuousMic = :continuousMic, confirmationRulesJson = :confirmationRulesJson WHERE scopeType = :scopeType AND scopeId = :scopeId")
    abstract suspend fun updatePolicy(
        scopeType: String,
        scopeId: String,
        level: String,
        allowedToolsJson: String,
        expiresAt: Long?,
        continuousMic: Boolean,
        confirmationRulesJson: String
    )

    @Transaction
    open suspend fun replacePolicies(items: List<WorkspaceAutonomyPolicyEntity>) {
        clearPolicies()
        if (items.isNotEmpty()) savePolicies(items)
    }

    @Query("SELECT * FROM workspace_action_runs ORDER BY createdAt DESC")
    abstract suspend fun actionRuns(): List<WorkspaceActionRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveActionRun(run: WorkspaceActionRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveActionRuns(items: List<WorkspaceActionRunEntity>)

    @Query("DELETE FROM workspace_action_runs")
    abstract suspend fun clearActionRuns()

    @Query("UPDATE workspace_action_runs SET state = :state, approval = :approval, startedAt = :startedAt, endedAt = :endedAt, resultRef = :resultRef, error = :error WHERE id = :id")
    abstract suspend fun updateActionRunState(
        id: String,
        state: String,
        approval: String,
        startedAt: Long?,
        endedAt: Long?,
        resultRef: String?,
        error: String?
    )

    @Transaction
    open suspend fun replaceActionRuns(items: List<WorkspaceActionRunEntity>) {
        clearActionRuns()
        if (items.isNotEmpty()) saveActionRuns(items)
    }

    @Query("SELECT * FROM workspace_outbox WHERE ack = 0 AND nextAttemptAt <= :now ORDER BY createdAt ASC")
    abstract suspend fun readyOutbox(now: Long): List<WorkspaceOutboxEntity>

    @Query("SELECT * FROM workspace_outbox WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun outbox(eventId: String): WorkspaceOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueue(event: WorkspaceOutboxEntity): Long

    @Query("UPDATE workspace_outbox SET ack = 1 WHERE eventId = :eventId")
    abstract suspend fun acknowledge(eventId: String)

    @Query("UPDATE workspace_outbox SET retryCount = retryCount + 1, nextAttemptAt = :nextAttemptAt, localActionState = COALESCE(:localActionState, localActionState) WHERE eventId = :eventId")
    abstract suspend fun retry(eventId: String, nextAttemptAt: Long, localActionState: String? = null)
}

@Database(
    entities = [
        WorkspaceSessionEntity::class,
        WorkspaceMessageEntity::class,
        WorkspaceTaskEntity::class,
        WorkspaceAttentionEntity::class,
        WorkspaceAutonomyPolicyEntity::class,
        WorkspaceActionRunEntity::class,
        WorkspaceOutboxEntity::class
    ],
    version = WORKSPACE_DB_VERSION,
    exportSchema = false
)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
}
