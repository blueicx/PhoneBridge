package com.phonebridge

data class MoteProfile(
    val id: PetAppearance,
    val name: String,
    val initial: Boolean,
    val voice: String,
    val proactive: String,
    val taskAffinity: List<String>,
    val emotionBias: Map<String, Float>,
    val primaryColor: String,
    val secondaryColor: String,
    val motion: String,
    val particles: String,
    val visualPreset: String
)

object MoteProfiles {
    val all: List<MoteProfile> = listOf(
        MoteProfile(PetAppearance.MOTE, "星核", true, "理性稳重", "balanced", listOf("planning", "analysis"), mapOf("calm" to .18f, "focus" to .24f), "#8EA7FF", "#D8E2FF", "measured-orbit", "stardust", "star-core"),
        MoteProfile(PetAppearance.SPRITE, "叶狐", true, "好奇活泼", "high", listOf("exploration", "creative"), mapOf("joy" to .22f, "curiosity" to .25f), "#8EE6B0", "#EFFFA9", "quick-hop", "leaf-spark", "leaf-fox"),
        MoteProfile(PetAppearance.GHOST, "雾猫", true, "安静共情", "low", listOf("reflection", "conversation"), mapOf("empathy" to .28f, "calm" to .2f), "#B8A7E8", "#EDF1FF", "soft-float", "mist", "mist-cat"),
        MoteProfile(PetAppearance.CIRCUIT, "机甲兽", true, "警觉技术", "high", listOf("monitoring", "debugging"), mapOf("alert" to .26f, "focus" to .2f), "#53D9FF", "#A8FFF0", "precise-pulse", "circuit", "mecha-beast"),
        MoteProfile(PetAppearance.CLOUD_WHALE, "云鲸", true, "耐心宏观", "low", listOf("long-form", "planning"), mapOf("calm" to .3f, "patience" to .3f), "#9CC9FF", "#FFF1C7", "slow-swell", "cloud", "cloud-whale"),
        MoteProfile(PetAppearance.RIMURU, "利姆鲁", true, "温暖适应", "balanced", listOf("conversation", "adaptation"), mapOf("joy" to .2f, "empathy" to .22f), "#75E6D1", "#D1FFF5", "elastic-bob", "bubble", "rimuru"),
        MoteProfile(PetAppearance.EMBER_SPRIG, "焰芽", false, "勇敢迅捷", "high", listOf("execution", "momentum"), mapOf("courage" to .3f, "urgency" to .18f), "#FF6B35", "#FFD166", "rapid-bounce", "embers", "ember-sprig"),
        MoteProfile(PetAppearance.PRISM_MOTH, "棱光蝶", false, "敏锐灵巧", "balanced", listOf("observation", "triage"), mapOf("curiosity" to .25f, "alert" to .2f), "#64E8FF", "#C896FF", "orbiting-glide", "prismatic-rays", "prism-moth"),
        MoteProfile(PetAppearance.MOSS_TORTOISE, "苔龟", false, "沉稳守护", "low", listOf("health", "maintenance"), mapOf("calm" to .32f, "protect" to .28f), "#77B255", "#C3A66B", "slow-breath", "moss-dust", "moss-tortoise"),
        MoteProfile(PetAppearance.ORBIT_RAVEN, "星鸦", false, "远眺侦察", "high", listOf("remote-status", "scanning"), mapOf("alert" to .28f, "distance" to .24f), "#5267C9", "#F4F6FF", "scan-turn", "scan-arcs", "orbit-raven")
    )
    val initial: List<MoteProfile> = all.filter { it.initial }
    val explorable: List<MoteProfile> = all.filterNot { it.initial }
    fun profile(appearance: PetAppearance): MoteProfile = all.firstOrNull { it.id == appearance } ?: all.first()
    fun fromWire(value: String?): MoteProfile = profile(PetAppearance.fromWire(value))
}
