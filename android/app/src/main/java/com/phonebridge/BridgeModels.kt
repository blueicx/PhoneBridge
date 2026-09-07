package com.phonebridge

import org.json.JSONObject
import kotlin.math.roundToInt

enum class PetMood {
    CALM, CURIOUS, HAPPY, ALERT, SLEEPY, LONELY
}

enum class PetAppearance(val label: String) {
    MOTE("星核"),
    SPRITE("叶狐"),
    GHOST("雾猫"),
    CIRCUIT("机甲兽"),
    CLOUD_WHALE("云鲸"),
    RIMURU("利姆鲁"),
    EMBER_SPRIG("焰芽"),
    PRISM_MOTH("棱光蝶"),
    MOSS_TORTOISE("苔龟"),
    ORBIT_RAVEN("星鸦");

    companion object {
        fun fromWire(value: String?): PetAppearance = when (value.orEmpty().trim().lowercase()) {
            "mote", "star_core", "星核" -> MOTE
            "sprite", "leaf_fox", "叶狐" -> SPRITE
            "ghost", "mist_cat", "雾猫" -> GHOST
            "circuit", "mecha_beast", "机甲兽" -> CIRCUIT
            "cloud_whale", "云鲸" -> CLOUD_WHALE
            "rimuru", "利姆鲁" -> RIMURU
            "ember_sprig", "ember-sprig", "焰芽" -> EMBER_SPRIG
            "prism_moth", "prism-moth", "棱光蝶" -> PRISM_MOTH
            "moss_tortoise", "moss-tortoise", "苔龟" -> MOSS_TORTOISE
            "orbit_raven", "orbit-raven", "星鸦" -> ORBIT_RAVEN
            else -> MOTE
        }
    }
}

data class PetEmotion(
    val joy: Float = .34f,
    val tension: Float = .18f,
    val fatigue: Float = .14f,
    val loneliness: Float = .12f,
    val attention: Float = .46f
) {
    fun rounded(): PetEmotion = copy(
        joy = (joy * 20f).roundToInt() / 20f,
        tension = (tension * 20f).roundToInt() / 20f,
        fatigue = (fatigue * 20f).roundToInt() / 20f,
        loneliness = (loneliness * 20f).roundToInt() / 20f,
        attention = (attention * 20f).roundToInt() / 20f
    )

    companion object {
        fun fromMood(mood: PetMood): PetEmotion = when (mood) {
            PetMood.HAPPY -> PetEmotion(joy = .78f, attention = .42f)
            PetMood.CURIOUS -> PetEmotion(attention = .74f, joy = .38f)
            PetMood.ALERT -> PetEmotion(tension = .68f, attention = .62f)
            PetMood.LONELY -> PetEmotion(loneliness = .72f, joy = .12f)
            PetMood.SLEEPY -> PetEmotion(fatigue = .80f, attention = .14f)
            else -> PetEmotion()
        }
    }
}

data class PetState(
    val name: String = "Mote",
    val level: Int = 1,
    val experience: Int = 0,
    val energy: Int = 82,
    val affection: Int = 40,
    val mood: PetMood = PetMood.CURIOUS,
    val emotion: PetEmotion = PetEmotion(),
    val appearance: PetAppearance = PetAppearance.MOTE,
    val connected: Boolean = false,
    val cameraActive: Boolean = false,
    val listening: Boolean = false,
    val speaking: Boolean = false,
    val activeTasks: Int = 0,
    val lastInteractionMs: Long = 0L,
    val totalCares: Int = 0,
    val careStreak: Int = 0,
    val lastCareDay: String = "",
    val successfulTasks: Int = 0,
    val failedTasks: Int = 0
) {
    fun normalizedEnergy() = energy.coerceIn(0, 100) / 100f
}

data class TaskItem(
    val id: String,
    val title: String,
    val status: String,
    val progress: Int,
    val detail: String = ""
)

data class LogItem(
    val time: String,
    val level: String,
    val message: String
)

data class ChatMessage(
    val role: String,
    val text: String,
    val time: String,
    val streaming: Boolean = false
)

data class MemoryItem(
    val id: String,
    val text: String,
    val createdAtMs: Long,
    val importance: Int = 3,
    val lastUsedAtMs: Long = 0L,
    val useCount: Int = 0
)

object BridgeJson {
    fun task(json: JSONObject): TaskItem = TaskItem(
        id = json.optString("id"),
        title = json.optString("title", "Task"),
        status = json.optString("status", "running"),
        progress = json.optInt("progress", 0),
        detail = json.optString("detail")
    )

    fun log(json: JSONObject): LogItem {
        val message = json.optString("message", json.optString("text", ""))
        return LogItem(
            time = json.optString("time", ""),
            level = json.optString("level", "info").lowercase(),
            message = message
        )
    }
}
