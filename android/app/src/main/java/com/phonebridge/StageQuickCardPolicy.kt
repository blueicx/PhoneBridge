package com.phonebridge

enum class StageQuickCardKind { APPROVAL, TASK, ACTIVITY, ATTENTION, RESULT }

data class StageToolApproval(
    val id: String,
    val taskId: String = "",
    val toolId: String = "",
    val state: String = "needs_confirmation",
    val createdAt: Long = 0L
)

data class StageActivityProgress(
    val routineId: String,
    val title: String,
    val status: String,
    val elapsedSeconds: Long,
    val updatedAt: Long = 0L,
    val syncState: String = "confirmed"
)

data class StageQuickCard(
    val id: String,
    val kind: StageQuickCardKind,
    val title: String,
    val detail: String,
    val taskId: String = "",
    val routineId: String = "",
    val approvalId: String = "",
    val action: String = "detail",
    val actionLabel: String = "查看"
)

/** Selects the tiny stage projection; full task/chat history stays in the drawer. */
object StageQuickCardPolicy {
    fun select(
        snapshot: CompanionSnapshot,
        approvals: List<StageToolApproval>,
        activities: List<StageActivityProgress> = emptyList()
    ): List<StageQuickCard> {
        val cards = mutableListOf<StageQuickCard>()
        val activeApprovals = approvals
            .filter { it.id.isNotBlank() && it.state.lowercase() in setOf("needs_confirmation", "approved") }
            .sortedWith(compareByDescending<StageToolApproval> { it.state.equals("needs_confirmation", true) }.thenByDescending { it.createdAt })
        val primaryApproval = activeApprovals.firstOrNull()
        if (primaryApproval != null) {
            val linkedTask = snapshot.tasks.firstOrNull { it.id == primaryApproval.taskId }
            val alreadyApproved = primaryApproval.state.equals("approved", true)
            cards += StageQuickCard(
                id = "approval:${primaryApproval.id}",
                kind = StageQuickCardKind.APPROVAL,
                title = linkedTask?.title?.takeIf { it.isNotBlank() } ?: "需要确认 · ${primaryApproval.toolId.ifBlank { "受限操作" }}",
                detail = if (alreadyApproved) "已确认，等待执行" else "待确认 · ${primaryApproval.toolId.ifBlank { "受限操作" }}",
                taskId = primaryApproval.taskId,
                approvalId = primaryApproval.id,
                action = if (alreadyApproved) "invoke" else "approve",
                actionLabel = if (alreadyApproved) "继续执行" else "查看确认"
            )
        }

        val task = snapshot.tasks
            .filter { it.state.lowercase() in setOf("pending", "queued", "running", "paused", "needs_confirmation") }
            .firstOrNull { activeApprovals.none { approval -> approval.taskId == it.id } }
        if (task != null && cards.size < 2) {
            val (action, label) = when (task.state.lowercase()) {
                "running" -> "pause" to "暂停"
                "paused" -> "continue" to "继续"
                "pending", "queued" -> "start" to "开始"
                else -> "detail" to "查看"
            }
            cards += StageQuickCard(
                id = "task:${task.id}",
                kind = StageQuickCardKind.TASK,
                title = task.title.ifBlank { "当前任务" },
                detail = "${taskStateLabel(task.state)} · ${task.progress.coerceIn(0, 100)}%",
                taskId = task.id,
                action = action,
                actionLabel = label
            )
        }

        activities
            .filter { it.routineId.isNotBlank() && it.status.lowercase() in setOf("active", "paused", "pending") }
            .maxByOrNull { it.updatedAt }
            ?.takeIf { cards.size < 2 }
            ?.let { activity ->
                val status = when (activity.status.lowercase()) {
                    "active" -> "进行中"
                    "paused" -> "已暂停"
                    else -> when (activity.syncState.lowercase()) {
                        RoutineSyncState.REJECTED -> "同步被拒绝"
                        else -> "待同步"
                    }
                }
                cards += StageQuickCard(
                    id = "activity:${activity.routineId}",
                    kind = StageQuickCardKind.ACTIVITY,
                    title = activity.title.ifBlank { "日常活动" },
                    detail = "$status · 已记录 ${activity.elapsedSeconds.coerceAtLeast(0L) / 60L} 分钟",
                    routineId = activity.routineId,
                    action = "detail",
                    actionLabel = "查看"
                )
            }

        val attention = snapshot.pendingAttention().firstOrNull()
        if (attention != null && cards.size < 2) {
            cards += StageQuickCard(
                id = "attention:${attention.id}",
                kind = StageQuickCardKind.ATTENTION,
                title = attention.title.ifBlank { "需要查看" },
                detail = attention.summary.ifBlank { "打开查看相关任务" }.take(140),
                taskId = attention.relatedTaskId.orEmpty(),
                action = "detail",
                actionLabel = "查看"
            )
        }

        if (cards.size < 2) snapshot.recentResults(1).firstOrNull()?.let { result ->
            cards += StageQuickCard(
                id = "result:${result.id}",
                kind = StageQuickCardKind.RESULT,
                title = result.title.ifBlank { "最近结果" },
                detail = (result.recentResult ?: result.detail).take(140).ifBlank { taskStateLabel(result.state) },
                taskId = result.id,
                action = "detail",
                actionLabel = "查看"
            )
        }
        return cards.take(2)
    }

    private fun taskStateLabel(state: String): String = when (state.lowercase()) {
        "needs_confirmation" -> "待确认"
        "running" -> "进行中"
        "paused" -> "已暂停"
        "pending" -> "待处理"
        "queued" -> "排队中"
        "succeeded", "done" -> "已完成"
        "failed", "error" -> "失败"
        "cancelled" -> "已取消"
        "archived" -> "已归档"
        else -> state.ifBlank { "未知" }
    }
}
