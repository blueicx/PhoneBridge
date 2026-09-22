package com.phonebridge

import org.json.JSONObject

data class CompanionSummary(
    val version: Int = 1,
    val generatedAt: Long = 0L,
    val connectionOnline: Boolean = false,
    val battery: Int = 0,
    val temperature: Float = 0f,
    val totalTasks: Int = 0,
    val runningTasks: Int = 0,
    val needsConfirmation: Int = 0,
    val activeTaskId: String? = null,
    val openAttention: Int = 0,
    val highestSeverity: String = "none",
    val moteId: String = "rimuru",
    val moteName: String = "利姆鲁",
    val moteLevel: Int = 1,
    val moteXp: Int = 0,
    val growthLevel: Int = 1,
    val growthXp: Int = 0,
    val growthDailyDate: String = "",
    val growthDailyClues: Map<String, Boolean> = emptyMap(),
    val growthDailyCompleted: Boolean = false,
    val growthActiveBoostId: String? = null,
    val gaze: String = "ambient",
    val reminderStrength: Float = 0f,
    val realityRegion: String = "",
    val realityEvents: Int = 0,
    val realityLevel: Int = 1,
    val realityXp: Int = 0,
    val inventoryCount: Int = 0,
    val providerId: String = "local",
    val providerName: String = "本地离线规则",
    val providerStatus: String = "unknown",
    val budgetRemaining: Int = 0,
    val degradationCount: Int = 0,
    val memoryCount: Int = 0,
    val emergencyStop: Boolean = false,
    val autonomyLevel: String = "whitelist"
) {
    fun compactStatus(): String = buildList {
        add("${moteName.ifBlank { "Mote" }} Lv.$moteLevel")
        add(if (connectionOnline) "在线" else "离线")
        add("任务 $runningTasks/$totalTasks")
        if (needsConfirmation > 0) add("待确认 $needsConfirmation")
        add("提醒 $openAttention")
        add("现实 $realityEvents")
        add("AI ${providerName.ifBlank { providerId }}")
    }.joinToString(" · ")

    fun widgetMeta(): String = buildList {
        add(if (connectionOnline) "在线" else "离线")
        add("任务 $runningTasks/$totalTasks")
        add("提醒 $openAttention")
        add("现实 $realityEvents")
    }.joinToString(" · ")
}

object CompanionSummaryParser {
    fun parse(root: JSONObject?): CompanionSummary = parseValues(root?.let(::toMap).orEmpty())

    fun parseValues(root: Map<String, Any?>): CompanionSummary {
        val connection = root.map("connection")
        val tasks = root.map("tasks")
        val attention = root.map("attention")
        val mote = root.map("mote")
        val growth = mote.map("growth")
        val daily = growth.map("daily")
        val clues = daily.map("clues")
        val reality = root.map("reality")
        val ai = root.map("ai")
        val safety = root.map("safety")
        return CompanionSummary(
            version = root.int("version", 1).coerceAtLeast(1),
            generatedAt = root.long("generatedAt", 0L),
            connectionOnline = connection.boolean("online", false),
            battery = connection.int("battery", 0).coerceIn(0, 100),
            temperature = connection.double("temperature", 0.0).toFloat(),
            totalTasks = tasks.int("total", 0).coerceAtLeast(0),
            runningTasks = tasks.int("running", 0).coerceAtLeast(0),
            needsConfirmation = tasks.int("needsConfirmation", 0).coerceAtLeast(0),
            activeTaskId = tasks.string("activeId").takeIf { it.isNotBlank() },
            openAttention = attention.int("open", 0).coerceAtLeast(0),
            highestSeverity = attention.string("highestSeverity", "none").ifBlank { "none" },
            moteId = mote.string("id", "rimuru").ifBlank { "rimuru" },
            moteName = mote.string("name", "利姆鲁").ifBlank { "利姆鲁" },
            moteLevel = mote.int("level", 1).coerceAtLeast(1),
            moteXp = mote.int("xp", 0).coerceAtLeast(0),
            growthLevel = growth.int("level", 1).coerceAtLeast(1),
            growthXp = growth.int("xp", 0).coerceAtLeast(0),
            growthDailyDate = daily.string("date"),
            growthDailyClues = listOf("location", "object", "light").associateWith { clues.boolean(it, false) },
            growthDailyCompleted = daily.boolean("completed", false),
            growthActiveBoostId = growth.string("activeBoostId").takeIf { it.isNotBlank() },
            gaze = mote.string("gaze", "ambient").ifBlank { "ambient" },
            reminderStrength = mote.double("reminderStrength", 0.0).toFloat().coerceIn(0f, 1f),
            realityRegion = reality.string("region"),
            realityEvents = reality.int("eventCount", 0).coerceAtLeast(0),
            realityLevel = reality.int("level", 1).coerceAtLeast(1),
            realityXp = reality.int("xp", 0).coerceAtLeast(0),
            inventoryCount = reality.int("inventoryCount", 0).coerceAtLeast(0),
            providerId = ai.string("providerId", "local").ifBlank { "local" },
            providerName = ai.string("providerName", "本地离线规则").ifBlank { "本地离线规则" },
            providerStatus = ai.string("status", "unknown").ifBlank { "unknown" },
            budgetRemaining = ai.int("budgetRemaining", 0).coerceAtLeast(0),
            degradationCount = ai.int("degradationCount", 0).coerceAtLeast(0),
            memoryCount = ai.int("memoryCount", 0).coerceAtLeast(0),
            emergencyStop = safety.boolean("emergencyStop", false),
            autonomyLevel = safety.string("autonomyLevel", "whitelist").ifBlank { "whitelist" }
        )
    }

    private fun toMap(root: JSONObject): Map<String, Any?> = root.keys().asSequence().associateWith { key ->
        when (val value = root.opt(key)) {
            is JSONObject -> toMap(value)
            else -> value
        }
    }

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.entries?.associate { (name, value) -> name.toString() to value } ?: emptyMap()

    private fun Map<String, Any?>.string(key: String, default: String = ""): String = this[key]?.toString() ?: default
    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int = when (val value = this[key]) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull() ?: default
    }
    private fun Map<String, Any?>.long(key: String, default: Long = 0L): Long = when (val value = this[key]) {
        is Number -> value.toLong()
        else -> value?.toString()?.toLongOrNull() ?: default
    }
    private fun Map<String, Any?>.double(key: String, default: Double = 0.0): Double = when (val value = this[key]) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull() ?: default
    }
    private fun Map<String, Any?>.boolean(key: String, default: Boolean = false): Boolean = when (val value = this[key]) {
        is Boolean -> value
        else -> value?.toString()?.toBooleanStrictOrNull() ?: default
    }
}
