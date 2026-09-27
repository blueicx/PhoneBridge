package com.phonebridge

enum class StageLighting { DAY, DUSK, NIGHT }

enum class CompanionStageActivity { IDLE, FOCUS, REST, OBSERVE, INTERACTION }

data class StageDecoration(val id: String, val name: String)

data class CompanionStageInput(
    val hourOfDay: Int,
    val energy: Int,
    val activeTasks: Int = 0,
    val observingReality: Boolean = false,
    val recentInteractionAgeMs: Long? = null,
    val quietMode: Boolean = false,
    val reduceMotion: Boolean = false,
    val nowMs: Long = 0L,
    val message: String? = null,
    val messageExpiresAtMs: Long = 0L,
    val decorations: List<StageDecoration> = emptyList(),
)

data class CompanionStageState(
    val lighting: StageLighting,
    val activity: CompanionStageActivity,
    val quietMode: Boolean,
    val reduceMotion: Boolean,
    val message: String?,
    val decorations: List<StageDecoration>,
)

object CompanionStageEngine {
    fun resolve(input: CompanionStageInput): CompanionStageState {
        val lighting = when (input.hourOfDay.coerceIn(0, 23)) {
            in 6..16 -> StageLighting.DAY
            in 17..19 -> StageLighting.DUSK
            else -> StageLighting.NIGHT
        }
        val activity = when {
            input.recentInteractionAgeMs in 0L..2_000L -> CompanionStageActivity.INTERACTION
            input.observingReality -> CompanionStageActivity.OBSERVE
            input.activeTasks > 0 -> CompanionStageActivity.FOCUS
            input.energy.coerceIn(0, 100) < 20 -> CompanionStageActivity.REST
            else -> CompanionStageActivity.IDLE
        }
        val message = input.message?.trim()?.takeIf {
            it.isNotEmpty() && input.messageExpiresAtMs > input.nowMs
        }?.take(56)
        val decorations = input.decorations.asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .take(4)
            .toList()
        return CompanionStageState(
            lighting = lighting,
            activity = activity,
            quietMode = input.quietMode,
            reduceMotion = input.reduceMotion,
            message = message,
            decorations = decorations,
        )
    }
}

object StageAudioPolicy {
    fun shouldPlayAmbient(enabled: Boolean, quietMode: Boolean, immersiveVisible: Boolean, resumed: Boolean): Boolean =
        enabled && !quietMode && immersiveVisible && resumed

    fun shouldPlayVoice(quietMode: Boolean): Boolean = !quietMode
}

data class CompanionStagePreferences(
    val quietMode: Boolean = false,
    val reduceMotion: Boolean = false,
    val oneHanded: Boolean = false,
    val ambientSound: Boolean = false,
)
