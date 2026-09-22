package com.phonebridge

enum class MoteBodyKind {
    CORE,
    LEAF_FOX,
    MIST_CAT,
    MECHA_BEAST,
    CLOUD_WHALE,
    SLIME,
    FLAME,
    PRISM_MOTH,
    MOSS_TORTOISE,
    ORBIT_RAVEN,
    TIDE_OTTER,
    MOON_DEER,
    STONE_MOLE,
    WIND_MARTEN,
    VOLT_SPARROW,
    FROST_HARE,
    BLOOM_SPRITE,
    CRYSTAL_LIZARD,
    DUNE_FOX,
    SHADOW_MOTH,
}

data class MoteVisualProfile(
    val appearance: PetAppearance,
    val bodyKind: MoteBodyKind,
    val primaryHex: String,
    val secondaryHex: String,
    val motionScale: Float,
    val particleStyle: String,
)

object MoteVisualProfiles {
    private val bodyKinds = mapOf(
        "star-core" to MoteBodyKind.CORE,
        "leaf-fox" to MoteBodyKind.LEAF_FOX,
        "mist-cat" to MoteBodyKind.MIST_CAT,
        "mecha-beast" to MoteBodyKind.MECHA_BEAST,
        "cloud-whale" to MoteBodyKind.CLOUD_WHALE,
        "rimuru" to MoteBodyKind.SLIME,
        "ember-sprig" to MoteBodyKind.FLAME,
        "prism-moth" to MoteBodyKind.PRISM_MOTH,
        "moss-tortoise" to MoteBodyKind.MOSS_TORTOISE,
        "orbit-raven" to MoteBodyKind.ORBIT_RAVEN,
        "tide-otter" to MoteBodyKind.TIDE_OTTER,
        "moon-deer" to MoteBodyKind.MOON_DEER,
        "stone-mole" to MoteBodyKind.STONE_MOLE,
        "wind-marten" to MoteBodyKind.WIND_MARTEN,
        "volt-sparrow" to MoteBodyKind.VOLT_SPARROW,
        "frost-hare" to MoteBodyKind.FROST_HARE,
        "bloom-sprite" to MoteBodyKind.BLOOM_SPRITE,
        "crystal-lizard" to MoteBodyKind.CRYSTAL_LIZARD,
        "dune-fox" to MoteBodyKind.DUNE_FOX,
        "shadow-moth" to MoteBodyKind.SHADOW_MOTH,
    )

    fun profile(appearance: PetAppearance): MoteVisualProfile {
        val source = MoteProfiles.profile(appearance)
        return MoteVisualProfile(
            appearance = source.id,
            bodyKind = bodyKinds[source.visualPreset] ?: MoteBodyKind.CORE,
            primaryHex = source.primaryColor,
            secondaryHex = source.secondaryColor,
            motionScale = motionScale(source.motion),
            particleStyle = source.particles,
        )
    }

    fun fromBehavior(appearance: PetAppearance, behavior: MoteBehaviorOutput?): MoteVisualProfile {
        val base = profile(appearance)
        if (behavior == null) return base
        return base.copy(
            primaryHex = behavior.primaryColor.ifBlank { base.primaryHex },
            secondaryHex = behavior.secondaryColor.ifBlank { base.secondaryHex },
            motionScale = (base.motionScale * (0.82f + behavior.motionIntensity * .36f)).coerceIn(.45f, 1.6f),
            particleStyle = behavior.particleType.ifBlank { base.particleStyle },
        )
    }

    private fun motionScale(motion: String): Float = when (motion) {
        "rapid-bounce", "spark-hop", "wind-dash" -> 1.25f
        "quick-hop", "orbiting-glide", "scan-turn" -> 1.12f
        "slow-breath", "slow-swell" -> .72f
        "soft-float", "shadow-drift", "quiet-step" -> .84f
        "burrow-pulse" -> .88f
        else -> 1f
    }
}
