package com.phonebridge

import org.json.JSONObject

data class MoteBehaviorInput(
    val taskState: String? = null,
    val deviceHealth: String? = null,
    val interaction: String? = null,
    val explorationProgress: Int = 0,
    val emotion: PetEmotion = PetEmotion(),
    val relationshipLevel: Int = 1
)

data class MoteBehaviorOutput(
    val motionIntensity: Float,
    val gaze: String,
    val auraColor: String,
    val particleType: String,
    val proactive: Boolean,
    val voiceMode: String,
    val version: Int = 1,
    val profileId: String = "mote",
    val reminderStrength: Float = 0f,
    val motion: String = "measured-orbit",
    val visualPreset: String = "star-core",
    val primaryColor: String = "#8EA7FF",
    val secondaryColor: String = "#D8E2FF",
    val taskAffinity: List<String> = emptyList(),
    val ability: String? = null
) {
    companion object {
        fun fromWire(json: JSONObject?): MoteBehaviorOutput? = json?.let {
            val colors = it.optJSONObject("colors")
            val affinity = it.optJSONArray("taskAffinity")?.let { values ->
                buildList {
                    for (index in 0 until values.length()) values.optString(index).takeIf { value -> value.isNotBlank() }?.let(::add)
                }
            } ?: emptyList()
            MoteBehaviorOutput(
                motionIntensity = it.optDouble("motionIntensity", .22).toFloat().coerceIn(.12f, 1f),
                gaze = it.optString("gaze", "ambient"),
                auraColor = it.optString("haloColor", it.optString("auraColor", "#8EA7FF")),
                particleType = it.optString("particleType", "stardust"),
                proactive = it.optString("proactive").equals("high", true) || it.optBoolean("proactive", false),
                voiceMode = it.optString("speechMode", it.optString("voiceMode", "理性稳重")),
                version = it.optInt("version", 1),
                profileId = it.optString("profileId", "mote"),
                reminderStrength = normalizeReminderStrength(it.optDouble("reminderStrength", 0.0)),
                motion = it.optString("motion", "measured-orbit"),
                visualPreset = it.optString("visualPreset", "star-core"),
                primaryColor = it.optString("primaryColor", colors?.optString("primary", "#8EA7FF") ?: "#8EA7FF"),
                secondaryColor = it.optString("secondaryColor", colors?.optString("secondary", "#D8E2FF") ?: "#D8E2FF"),
                taskAffinity = affinity,
                ability = it.optString("ability").takeIf { value -> value.isNotBlank() }
            )
        }

        fun normalizeReminderStrength(value: Double): Float = value.toFloat().coerceIn(0f, 1f)
    }
}

object MoteBehaviorEngine {
    fun resolve(profile: MoteProfile, input: MoteBehaviorInput): MoteBehaviorOutput {
        val task = input.taskState.orEmpty().lowercase()
        val health = input.deviceHealth.orEmpty().lowercase()
        val interaction = input.interaction.orEmpty().lowercase()
        val intensity = (.22f + (input.relationshipLevel.coerceAtLeast(1) - 1).coerceAtMost(12) * .02f + input.explorationProgress.coerceIn(0, 3) * .08f +
            if (task == "running") .18f else 0f +
            if (interaction == "tap" || interaction == "chat") .12f else 0f +
            if (health == "warning" || health == "critical") .14f else 0f +
            input.emotion.attention * .12f).coerceIn(.12f, 1f)
        val gaze = when {
            health == "critical" || health == "warning" -> "protective"
            task == "running" -> "focused"
            interaction.isNotBlank() -> "engaged"
            else -> "ambient"
        }
        val proactive = profile.proactive == "high" || (profile.proactive == "balanced" && (task == "failed" || health == "critical"))
        val voiceMode = when {
            health == "critical" -> "careful"
            task == "failed" -> "encouraging"
            task == "succeeded" -> "celebratory"
            else -> profile.voice
        }
        return MoteBehaviorOutput(
            motionIntensity = intensity,
            gaze = gaze,
            auraColor = if (gaze == "protective") profile.secondaryColor else profile.primaryColor,
            particleType = profile.particles,
            proactive = proactive,
            voiceMode = voiceMode,
            profileId = profile.id.name.lowercase(),
            motion = profile.motion,
            visualPreset = profile.visualPreset,
            primaryColor = profile.primaryColor,
            secondaryColor = profile.secondaryColor,
            taskAffinity = profile.taskAffinity,
        )
    }
}
