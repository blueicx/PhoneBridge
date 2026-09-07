package com.phonebridge

data class MoteBehaviorInput(
    val taskState: String? = null,
    val deviceHealth: String? = null,
    val interaction: String? = null,
    val explorationProgress: Int = 0,
    val emotion: PetEmotion = PetEmotion()
)

data class MoteBehaviorOutput(
    val motionIntensity: Float,
    val gaze: String,
    val auraColor: String,
    val particleType: String,
    val proactive: Boolean,
    val voiceMode: String
)

object MoteBehaviorEngine {
    fun resolve(profile: MoteProfile, input: MoteBehaviorInput): MoteBehaviorOutput {
        val task = input.taskState.orEmpty().lowercase()
        val health = input.deviceHealth.orEmpty().lowercase()
        val interaction = input.interaction.orEmpty().lowercase()
        val intensity = (.22f + input.explorationProgress.coerceIn(0, 3) * .08f +
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
