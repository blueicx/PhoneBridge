package com.phonebridge

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

data class HandoffState(
    val goal: String = "",
    val currentTask: String = "",
    val nextSteps: String = "",
    val keyConstraints: String = "",
    val recentDecisions: String = "",
    val notes: String = "",
    val revision: Long = 0L,
    val updatedAtMs: Long = 0L,
    val updatedBy: String = "phone"
) {
    fun isEmpty(): Boolean = listOf(goal, currentTask, nextSteps, keyConstraints, recentDecisions, notes)
        .all { it.isBlank() }
}

object MoteHandoff {
    private const val PREFS = "mote_handoff"
    private const val KEY_STATE = "state"

    fun load(context: Context): HandoffState = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return HandoffState()
        fromJson(JSONObject(raw))
    }.getOrDefault(HandoffState())

    fun save(context: Context, state: HandoffState): HandoffState {
        val now = System.currentTimeMillis()
        val saved = state.copy(
            revision = maxOf(state.revision + 1L, now),
            updatedAtMs = now,
            updatedBy = "phone"
        )
        write(context, saved)
        export(context, saved)
        return saved
    }

    fun replace(context: Context, state: HandoffState): HandoffState {
        write(context, state)
        export(context, state)
        return state
    }

    fun merge(local: HandoffState, remote: HandoffState): HandoffState =
        if (remote.revision > local.revision ||
            (remote.revision == local.revision && remote.updatedAtMs > local.updatedAtMs)
        ) remote else local

    fun summary(context: Context): String = summary(load(context))

    fun summary(state: HandoffState): String = listOf(
        "目标:${state.goal}".trimEnd(':'),
        "当前任务:${state.currentTask}".trimEnd(':'),
        "下一步:${state.nextSteps}".trimEnd(':'),
        "关键约束:${state.keyConstraints}".trimEnd(':'),
        "近期决定:${state.recentDecisions}".trimEnd(':'),
        "备注:${state.notes}".trimEnd(':')
    ).filter { !it.endsWith(":") }.joinToString("\n")

    fun editableDocument(state: HandoffState): String = listOf(
        "目标:${state.goal}",
        "当前任务:${state.currentTask}",
        "下一步:${state.nextSteps}",
        "关键约束:${state.keyConstraints}",
        "近期决定:${state.recentDecisions}",
        "备注:${state.notes}"
    ).joinToString("\n")

    fun parse(document: String, previous: HandoffState): HandoffState {
        val fields = linkedMapOf(
            "goal" to StringBuilder(),
            "currentTask" to StringBuilder(),
            "nextSteps" to StringBuilder(),
            "keyConstraints" to StringBuilder(),
            "recentDecisions" to StringBuilder(),
            "notes" to StringBuilder()
        )
        var active: StringBuilder? = null
        Regex("^\\s*(目标|当前任务|下一步|关键约束|近期决定|备注)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
            .findAll(document).forEach { match ->
                when (match.groupValues[1]) {
                    "目标" -> active = fields["goal"]
                    "当前任务" -> active = fields["currentTask"]
                    "下一步" -> active = fields["nextSteps"]
                    "关键约束" -> active = fields["keyConstraints"]
                    "近期决定" -> active = fields["recentDecisions"]
                    else -> active = fields["notes"]
                }
                active?.append(document.substring(match.range.last + 1, nextHeaderStart(document, match.range.last)))
            }
        if (fields.values.all { it.isBlank() } && document.isNotBlank()) {
            fields["notes"]!!.append(document.trim())
        }
        return HandoffState(
            goal = fields["goal"].toString().trim(),
            currentTask = fields["currentTask"].toString().trim(),
            nextSteps = fields["nextSteps"].toString().trim(),
            keyConstraints = fields["keyConstraints"].toString().trim(),
            recentDecisions = fields["recentDecisions"].toString().trim(),
            notes = fields["notes"].toString().trim(),
            revision = previous.revision,
            updatedAtMs = previous.updatedAtMs,
            updatedBy = previous.updatedBy
        )
    }

    fun toJson(state: HandoffState): JSONObject = JSONObject()
        .put("goal", state.goal)
        .put("currentTask", state.currentTask)
        .put("nextSteps", state.nextSteps)
        .put("keyConstraints", state.keyConstraints)
        .put("recentDecisions", state.recentDecisions)
        .put("notes", state.notes)
        .put("revision", state.revision)
        .put("updatedAtMs", state.updatedAtMs)
        .put("updatedBy", state.updatedBy)

    fun fromJson(json: JSONObject): HandoffState = HandoffState(
        goal = json.optString("goal"),
        currentTask = json.optString("currentTask"),
        nextSteps = json.optString("nextSteps"),
        keyConstraints = json.optString("keyConstraints"),
        recentDecisions = json.optString("recentDecisions"),
        notes = json.optString("notes"),
        revision = json.optLong("revision", 0L),
        updatedAtMs = json.optLong("updatedAtMs", 0L),
        updatedBy = json.optString("updatedBy", "pc").ifBlank { "pc" }
    )

    fun export(context: Context, state: HandoffState): List<File> {
        val dir = File(context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "handoff")
        dir.mkdirs()
        val base = File(dir, "mote-handoff")
        val jsonFile = File("$base.json").also { it.writeText(toJson(state).toString(2)) }
        val markdown = """
            # Mote 交接文档

            - 版本: ${state.revision}
            - 更新时间: ${state.updatedAtMs}
            - 来源: ${state.updatedBy}

            ${editableDocument(state)}
        """.trimIndent()
        val mdFile = File("$base.md").also { it.writeText(markdown) }
        return listOf(jsonFile, mdFile)
    }

    private fun write(context: Context, state: HandoffState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, toJson(state).toString())
            .apply()
    }

    private fun nextHeaderStart(text: String, afterIndex: Int): Int {
        val match = Regex("\\s+(?:目标|当前任务|下一步|关键约束|近期决定|备注)\\s*[:：]")
            .find(text, startIndex = afterIndex + 1) ?: return text.length
        return match.range.first
    }
}
