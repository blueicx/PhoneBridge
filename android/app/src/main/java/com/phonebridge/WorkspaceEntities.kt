package com.phonebridge

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

const val WORKSPACE_DB_VERSION = 6

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
    val updatedAt: Long = 0L,
    val goalId: String? = null,
    val milestoneId: String? = null
)

@Entity(
    tableName = "workspace_routine_entries",
    indices = [Index(value = ["routineId", "updatedAt"]), Index(value = ["syncState"])]
)
data class WorkspaceRoutineEntryEntity(
    @PrimaryKey val id: String,
    val routineId: String,
    val title: String,
    val status: String,
    val startedAt: Long,
    val updatedAt: Long,
    val lastEventAt: Long,
    val elapsedSeconds: Long = 0L,
    val revision: Long = 0L,
    val reflection: String? = null,
    val syncState: String = RoutineSyncState.CONFIRMED,
    val pendingEventId: String? = null,
    val pendingAction: String? = null,
    val syncReason: String? = null
)

@Entity(tableName = "workspace_goals", indices = [Index(value = ["updatedAt"])])
data class WorkspaceGoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val status: String = "active",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Entity(
    tableName = "workspace_milestones",
    foreignKeys = [androidx.room.ForeignKey(
        entity = WorkspaceGoalEntity::class,
        parentColumns = ["id"],
        childColumns = ["goalId"],
        onDelete = androidx.room.ForeignKey.CASCADE
    )],
    indices = [Index(value = ["goalId", "position"]), Index(value = ["taskId"])]
)
data class WorkspaceMilestoneEntity(
    @PrimaryKey val id: String,
    val goalId: String,
    val title: String,
    val description: String = "",
    val status: String = "pending",
    val taskId: String? = null,
    val position: Int = 0
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
    val confirmationRulesJson: String = "[]",
    val revision: Int = 0,
    val usesRemaining: Int? = null
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
    val leaseUntil: Long? = null,
    val lastSentAt: Long? = null,
    val businessStatus: String? = null,
    val businessReason: String? = null,
    val resultRevision: Long? = null,
    val localActionId: String? = null,
    val localActionState: String? = null,
    val privacyCategory: String? = null,
    @ColumnInfo(defaultValue = "0") val privacyRevision: Long = 0L,
    @ColumnInfo(defaultValue = "'{}'") val privacyRevisionsJson: String = "{}",
    @ColumnInfo(defaultValue = "1") val quarantined: Boolean = false
)

@Entity(tableName = "workspace_privacy_state")
data class WorkspacePrivacyStateEntity(
    @PrimaryKey val category: String,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0L,
    @ColumnInfo(defaultValue = "0") val migrationRequired: Boolean = false,
    val decision: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L
)

@Dao
abstract class WorkspaceDao {
    @Query("SELECT * FROM workspace_sessions ORDER BY updatedAt DESC")
    abstract suspend fun sessions(): List<WorkspaceSessionEntity>

    @Query("DELETE FROM workspace_sessions")
    abstract suspend fun clearSessions()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSession(session: WorkspaceSessionEntity)

    @Query("SELECT * FROM workspace_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    abstract suspend fun messages(sessionId: String): List<WorkspaceMessageEntity>

    @Query("DELETE FROM workspace_messages")
    abstract suspend fun clearMessages()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveMessage(message: WorkspaceMessageEntity)

    @Query("SELECT * FROM workspace_tasks ORDER BY updatedAt DESC")
    abstract suspend fun tasks(): List<WorkspaceTaskEntity>

    @Query("SELECT * FROM workspace_tasks WHERE goalId = :goalId ORDER BY updatedAt DESC")
    abstract suspend fun tasksForGoal(goalId: String): List<WorkspaceTaskEntity>

    @Query("DELETE FROM workspace_tasks")
    abstract suspend fun clearTasks()

    @Query("DELETE FROM workspace_tasks WHERE source = 'conversation'")
    abstract suspend fun clearConversationTasks()

    @Query("DELETE FROM workspace_tasks WHERE id = :taskId")
    abstract suspend fun clearTaskById(taskId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveTask(task: WorkspaceTaskEntity)

    @Query("SELECT * FROM workspace_routine_entries ORDER BY updatedAt DESC")
    abstract suspend fun routineEntries(): List<WorkspaceRoutineEntryEntity>

    @Query("SELECT * FROM workspace_routine_entries WHERE routineId = :routineId ORDER BY updatedAt DESC LIMIT 1")
    abstract suspend fun routineEntry(routineId: String): WorkspaceRoutineEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveRoutineEntry(entry: WorkspaceRoutineEntryEntity)

    @Query("DELETE FROM workspace_routine_entries WHERE id = :entryId")
    abstract suspend fun deleteRoutineEntry(entryId: String)

    @Query("DELETE FROM workspace_routine_entries")
    abstract suspend fun clearRoutineEntries()

    @Query("SELECT COUNT(*) FROM workspace_routine_entries")
    abstract suspend fun countRoutineEntries(): Long

    @Query("SELECT * FROM workspace_goals ORDER BY updatedAt DESC")
    abstract suspend fun goals(): List<WorkspaceGoalEntity>

    @Query("SELECT * FROM workspace_milestones WHERE goalId = :goalId ORDER BY position ASC, id ASC")
    abstract suspend fun milestonesForGoal(goalId: String): List<WorkspaceMilestoneEntity>

    @Query("SELECT * FROM workspace_milestones WHERE id = :milestoneId LIMIT 1")
    abstract suspend fun milestone(milestoneId: String): WorkspaceMilestoneEntity?

    @Query("SELECT * FROM workspace_milestones ORDER BY goalId ASC, position ASC, id ASC")
    abstract suspend fun allMilestones(): List<WorkspaceMilestoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveGoal(goal: WorkspaceGoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveGoals(goals: List<WorkspaceGoalEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveMilestones(milestones: List<WorkspaceMilestoneEntity>)

    @Query("DELETE FROM workspace_milestones WHERE goalId = :goalId")
    abstract suspend fun clearMilestonesForGoal(goalId: String)

    @Query("DELETE FROM workspace_goals")
    abstract suspend fun clearGoals()

    @Query("DELETE FROM workspace_goals WHERE id = :goalId")
    abstract suspend fun deleteGoal(goalId: String)

    @Query("SELECT COUNT(*) FROM workspace_goals")
    abstract suspend fun countGoals(): Long

    @Query("SELECT COUNT(*) FROM workspace_milestones")
    abstract suspend fun countMilestones(): Long

    @Query("UPDATE workspace_milestones SET taskId = NULL, status = 'pending' WHERE taskId IS NOT NULL")
    abstract suspend fun unbindGoalMilestoneTasks()

    @Query("UPDATE workspace_milestones SET taskId = NULL, status = 'pending' WHERE taskId IN (:taskIds)")
    abstract suspend fun unbindMilestonesForTasks(taskIds: List<String>)

    @Query("UPDATE workspace_milestones SET taskId = NULL, status = 'pending' WHERE taskId = :taskId")
    abstract suspend fun unbindMilestoneForTask(taskId: String)

    @Query("DELETE FROM workspace_tasks WHERE goalId IN (:goalIds)")
    abstract suspend fun deleteTasksForGoals(goalIds: List<String>)

    @Query("SELECT * FROM workspace_attention ORDER BY updatedAt DESC")
    abstract suspend fun attention(): List<WorkspaceAttentionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveAttentionItem(item: WorkspaceAttentionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveAttentionItems(items: List<WorkspaceAttentionEntity>)

    @Query("DELETE FROM workspace_attention")
    abstract suspend fun clearAttention()

    @Query("DELETE FROM workspace_attention WHERE relatedSessionId IS NOT NULL")
    abstract suspend fun clearConversationAttention()

    @Query("DELETE FROM workspace_attention WHERE relatedTaskId = :taskId")
    abstract suspend fun clearAttentionForTask(taskId: String)

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

    @Query("DELETE FROM workspace_action_runs WHERE sessionId IS NOT NULL")
    abstract suspend fun clearConversationActionRuns()

    @Query("DELETE FROM workspace_action_runs WHERE taskId = :taskId")
    abstract suspend fun clearActionRunsForTask(taskId: String)

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

    @Query("SELECT * FROM workspace_outbox WHERE ack = 0 AND quarantined = 0 AND nextAttemptAt <= :now AND (leaseUntil IS NULL OR leaseUntil <= :now) ORDER BY createdAt ASC")
    abstract suspend fun readyOutbox(now: Long): List<WorkspaceOutboxEntity>

    @Query("SELECT * FROM workspace_outbox WHERE ack = 0 ORDER BY createdAt ASC")
    abstract suspend fun pendingOutbox(): List<WorkspaceOutboxEntity>

    @Query("SELECT * FROM workspace_outbox WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun outbox(eventId: String): WorkspaceOutboxEntity?

    @Query("SELECT * FROM workspace_outbox ORDER BY createdAt ASC")
    abstract suspend fun allOutbox(): List<WorkspaceOutboxEntity>

    @Query("DELETE FROM workspace_outbox WHERE eventId = :eventId")
    abstract suspend fun deleteOutboxEvent(eventId: String)

    @Query("UPDATE workspace_outbox SET privacyCategory = :category, quarantined = 1 WHERE eventId = :eventId")
    abstract suspend fun classifyAndQuarantineLegacyOutbox(eventId: String, category: String)

    @Transaction
    open suspend fun purgeConversationData(linkedTaskIds: List<String>) {
        clearMessages()
        clearSessions()
        clearConversationTasks()
        clearConversationAttention()
        clearConversationActionRuns()
        linkedTaskIds.filter(String::isNotBlank).distinct().forEach {
            clearTaskById(it)
            clearAttentionForTask(it)
            clearActionRunsForTask(it)
        }
    }

    @Transaction
    open suspend fun purgeTaskData() {
        unbindGoalMilestoneTasks()
        clearTasks()
        clearAttention()
        clearActionRuns()
    }

    @Transaction
    open suspend fun purgeProgressData() = Unit

    @Query("SELECT eventId FROM workspace_outbox WHERE ack = 0 AND leaseUntil IS NOT NULL AND leaseUntil <= :now")
    abstract suspend fun expiredOutbox(now: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueue(event: WorkspaceOutboxEntity): Long

    @Query("SELECT * FROM workspace_privacy_state ORDER BY category ASC")
    abstract suspend fun privacyStates(): List<WorkspacePrivacyStateEntity>

    @Query("SELECT * FROM workspace_privacy_state WHERE category = :category LIMIT 1")
    abstract suspend fun privacyState(category: String): WorkspacePrivacyStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun savePrivacyState(state: WorkspacePrivacyStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun savePrivacyStates(states: List<WorkspacePrivacyStateEntity>)

    @Query("SELECT COUNT(*) FROM workspace_sessions")
    abstract suspend fun countSessions(): Long

    @Query("SELECT COUNT(*) FROM workspace_messages")
    abstract suspend fun countMessages(): Long

    @Query("SELECT COUNT(*) FROM workspace_tasks")
    abstract suspend fun countTasks(): Long

    @Query("SELECT COUNT(*) FROM workspace_tasks WHERE source = 'conversation'")
    abstract suspend fun countConversationTasks(): Long

    @Query("SELECT COUNT(*) FROM workspace_attention")
    abstract suspend fun countAttention(): Long

    @Query("SELECT COUNT(*) FROM workspace_attention WHERE relatedSessionId IS NOT NULL")
    abstract suspend fun countConversationAttention(): Long

    @Query("SELECT COUNT(*) FROM workspace_action_runs")
    abstract suspend fun countActionRuns(): Long

    @Query("SELECT COUNT(*) FROM workspace_action_runs WHERE sessionId IS NOT NULL")
    abstract suspend fun countConversationActionRuns(): Long

    @Query("UPDATE workspace_outbox SET quarantined = 1 WHERE eventId = :eventId")
    abstract suspend fun quarantineOutbox(eventId: String)

    @Query("UPDATE workspace_outbox SET leaseUntil = :leaseUntil, lastSentAt = :sentAt WHERE eventId = :eventId AND ack = 0 AND quarantined = 0 AND nextAttemptAt <= :now AND (leaseUntil IS NULL OR leaseUntil <= :now)")
    abstract suspend fun claimOutbox(eventId: String, now: Long, leaseUntil: Long, sentAt: Long): Int

    @Query("UPDATE workspace_outbox SET ack = 1, leaseUntil = NULL, businessStatus = :businessStatus, businessReason = :businessReason, resultRevision = :resultRevision WHERE eventId = :eventId")
    abstract suspend fun acknowledge(eventId: String, businessStatus: String, businessReason: String?, resultRevision: Long?)

    @Query("UPDATE workspace_outbox SET retryCount = retryCount + 1, nextAttemptAt = :nextAttemptAt, leaseUntil = NULL, businessStatus = NULL, businessReason = NULL, resultRevision = NULL, localActionState = COALESCE(:localActionState, localActionState) WHERE eventId = :eventId")
    abstract suspend fun retry(eventId: String, nextAttemptAt: Long, localActionState: String? = null)
}

@Database(
    entities = [
        WorkspaceSessionEntity::class,
        WorkspaceMessageEntity::class,
        WorkspaceTaskEntity::class,
        WorkspaceRoutineEntryEntity::class,
        WorkspaceGoalEntity::class,
        WorkspaceMilestoneEntity::class,
        WorkspaceAttentionEntity::class,
        WorkspaceAutonomyPolicyEntity::class,
        WorkspaceActionRunEntity::class,
        WorkspaceOutboxEntity::class,
        WorkspacePrivacyStateEntity::class
    ],
    version = WORKSPACE_DB_VERSION,
    exportSchema = true
)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
}
