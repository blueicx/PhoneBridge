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
    val profileId: String = "mote"
) {
    companion object {
        fun fromWire(json: JSONObject?): MoteBehaviorOutput? = json?.let {
            MoteBehaviorOutput(
                motionIntensity = it.optDouble("motionIntensity", .22).toFloat().coerceIn(.12f, 1f),
                gaze = it.optString("gaze", "ambient"),
                auraColor = it.optString("haloColor", it.optString("auraColor", "#8EA7FF")),
                particleType = it.optString("particleType", "stardust"),
                proactive = it.optString("proactive").equals("high", true) || it.optBoolean("proactive", false),
                voiceMode = it.optString("speechMode", it.optString("voiceMode", "理性稳重")),
                version = it.optInt("version", 1),
                profileId = it.optString("profileId", "mote")
            )
        }
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
        return MoteBehaviorOutput(intensity, gaze, if (gaze == "protective") profile.secondaryColor else profile.primaryColor, profile.particles, proactive, voiceMode)
    }
}
