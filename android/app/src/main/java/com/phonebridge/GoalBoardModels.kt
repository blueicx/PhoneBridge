package com.phonebridge

data class GoalBoardMilestone(
    val id: String,
    val goalId: String,
    val title: String,
    val description: String = "",
    val status: String = "pending",
    val taskId: String? = null
)

data class GoalBoardGoal(
    val id: String,
    val title: String,
    val description: String = "",
    val status: String = "active",
    val createdAt: String = "",
    val updatedAt: String = "",
    val milestones: List<GoalBoardMilestone> = emptyList()
)

data class GoalBoardSnapshot(
    val goals: List<GoalBoardGoal> = emptyList(),
    val privacyRevision: Long = 0L,
    val taskPrivacyRevision: Long = 0L,
    val migrationRequired: Boolean = false
)

data class GoalBoardStep(val title: String, val description: String = "")

data class GoalBoardDraft(
    val goalId: String,
    val eventId: String,
    val steps: List<GoalBoardStep>,
    val providerId: String = "local",
    val fallbackProvider: String? = null,
    val fallbackReason: String? = null
)

data class GoalTaskRef(
    val taskId: String,
    val goalId: String,
    val milestoneId: String,
    val title: String,
    val state: String
)

object GoalBoardProtocol {
    private const val MAX_STEPS = 8
    private const val MAX_STEP_TITLE = 120

    fun parseSnapshot(json: String): GoalBoardSnapshot {
        val root = parseWorkspaceJsonObject(json)
        return GoalBoardSnapshot(
            goals = root.list("goals").mapNotNull(::parseGoal),
            privacyRevision = root.long("privacyRevision").coerceAtLeast(0L),
            taskPrivacyRevision = root.long("taskPrivacyRevision").coerceAtLeast(0L),
            migrationRequired = root.boolean("migrationRequired")
        )
    }

    fun parseDraft(json: String, eventId: String): GoalBoardDraft? {
        val root = runCatching { parseWorkspaceJsonObject(json) }.getOrNull() ?: return null
        val goalId = root.string("goalId").trim()
        val normalizedEventId = eventId.trim()
        if (goalId.isBlank() || !normalizedEventId.matches(Regex("^[A-Za-z0-9_-]{8,96}$"))) return null
        val steps = root.list("steps").mapNotNull { parseStep(it) }
        if (!validSteps(steps)) return null
        return GoalBoardDraft(
            goalId = goalId,
            eventId = normalizedEventId,
            steps = steps,
            providerId = root.string("providerId").ifBlank { "local" }.take(64),
            fallbackProvider = root.string("fallbackProvider").ifBlank { null },
            fallbackReason = root.string("fallbackReason").ifBlank { null }
        )
    }

    fun editStepTitles(draft: GoalBoardDraft, text: String): GoalBoardDraft? {
        val titles = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (titles.isEmpty() || titles.size > MAX_STEPS || titles.any { it.length > MAX_STEP_TITLE }) return null
        val descriptionsByTitle = draft.steps.groupBy { it.title }.mapValues { (_, steps) ->
            steps.map { it.description }.toMutableList()
        }
        val steps = titles.map { title ->
            GoalBoardStep(title, descriptionsByTitle[title]?.removeFirstOrNull().orEmpty())
        }
        return draft.copy(steps = steps).takeIf { validSteps(it.steps) }
    }

    fun canAccept(draft: GoalBoardDraft, selectedGoalId: String): Boolean =
        draft.goalId == selectedGoalId && draft.eventId.matches(Regex("^[A-Za-z0-9_-]{8,96}$")) && validSteps(draft.steps)

    fun acceptancePayload(draft: GoalBoardDraft, privacyRevision: Long, taskPrivacyRevision: Long = 0L): String {
        require(validSteps(draft.steps)) { "goal draft steps are invalid" }
        require(draft.eventId.matches(Regex("^[A-Za-z0-9_-]{8,96}$"))) { "goal eventId is invalid" }
        val steps = draft.steps.joinToString(",") { step ->
            "{\"title\":${step.title.jsonQuoted()},\"description\":${step.description.jsonQuoted()}}"
        }
        return "{\"eventId\":${draft.eventId.jsonQuoted()},\"steps\":[$steps],\"privacyRevision\":${privacyRevision.coerceAtLeast(0L)},\"taskPrivacyRevision\":${taskPrivacyRevision.coerceAtLeast(0L)}}"
    }

    fun taskRefs(tasks: List<Map<String, Any?>>): List<GoalTaskRef> = tasks.mapNotNull { task ->
        val metadata = task.map("metadata")
        val goalId = metadata.string("goalId").ifBlank { task.string("goalId") }.trim()
        val milestoneId = metadata.string("milestoneId").ifBlank { task.string("milestoneId") }.trim()
        val taskId = task.string("id").trim()
        if (taskId.isBlank() || goalId.isBlank() || milestoneId.isBlank()) return@mapNotNull null
        GoalTaskRef(
            taskId = taskId,
            goalId = goalId,
            milestoneId = milestoneId,
            title = task.string("title").ifBlank { "未命名任务" },
            state = task.string("state").ifBlank { "pending" }
        )
    }

    private fun parseGoal(raw: Any?): GoalBoardGoal? {
        val value = raw.asMap() ?: return null
        val id = value.string("id").trim()
        val title = value.string("title").trim()
        if (id.isBlank() || title.isBlank()) return null
        return GoalBoardGoal(
            id = id,
            title = title,
            description = value.string("description"),
            status = value.string("status").ifBlank { "active" },
            createdAt = value.string("createdAt"),
            updatedAt = value.string("updatedAt"),
            milestones = value.list("milestones").mapNotNull { rawMilestone ->
                val milestone = rawMilestone.asMap() ?: return@mapNotNull null
                val milestoneId = milestone.string("id").trim()
                val goalId = milestone.string("goalId").ifBlank { id }.trim()
                val milestoneTitle = milestone.string("title").trim()
                if (milestoneId.isBlank() || goalId != id || milestoneTitle.isBlank()) return@mapNotNull null
                GoalBoardMilestone(
                    id = milestoneId,
                    goalId = goalId,
                    title = milestoneTitle,
                    description = milestone.string("description"),
                    status = milestone.string("status").ifBlank { "pending" },
                    taskId = milestone.string("taskId").ifBlank { null }
                )
            }
        )
    }

    private fun parseStep(raw: Any?): GoalBoardStep? {
        val value = raw.asMap() ?: return null
        val title = value.string("title").trim()
        val description = value.string("description").trim()
        return if (title.isNotBlank() && title.length <= MAX_STEP_TITLE && description.length <= 500) {
            GoalBoardStep(title, description)
        } else null
    }

    private fun validSteps(steps: List<GoalBoardStep>): Boolean = steps.size in 1..MAX_STEPS && steps.all {
        it.title.isNotBlank() && it.title.trim() == it.title && it.title.length <= MAX_STEP_TITLE && it.description.length <= 500
    }

    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<*> ?: emptyList()
    private fun Map<String, Any?>.string(key: String): String = (this[key] as? String).orEmpty()
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: 0L
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: false
    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap().orEmpty()
    private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
        ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()
    private fun String.jsonQuoted(): String = buildString(length + 2) {
        append('"')
        this@jsonQuoted.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
