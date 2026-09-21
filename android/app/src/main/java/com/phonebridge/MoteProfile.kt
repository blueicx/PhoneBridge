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
        MoteProfile(PetAppearance.ORBIT_RAVEN, "星鸦", false, "远眺侦察", "high", listOf("remote-status", "scanning"), mapOf("alert" to .28f, "distance" to .24f), "#5267C9", "#F4F6FF", "scan-turn", "scan-arcs", "orbit-raven"),
        MoteProfile(PetAppearance.TIDE_OTTER, "潮獭", false, "轻快亲和", "high", listOf("social-care", "flow"), mapOf("joy" to .3f, "empathy" to .18f), "#3BC7D8", "#D8FFFF", "wave-roll", "water-rings", "tide-otter"),
        MoteProfile(PetAppearance.MOON_DEER, "月鹿", false, "宁静敏锐", "low", listOf("reflection", "night"), mapOf("calm" to .34f, "focus" to .2f), "#8B91E8", "#FFF2C9", "quiet-step", "moon-dust", "moon-deer"),
        MoteProfile(PetAppearance.STONE_MOLE, "岩鼹", false, "踏实专注", "low", listOf("collection", "maintenance"), mapOf("calm" to .28f, "protect" to .25f), "#9B8066", "#E4C9A5", "burrow-pulse", "stone-specks", "stone-mole"),
        MoteProfile(PetAppearance.WIND_MARTEN, "风貂", false, "灵巧迅疾", "high", listOf("speed", "execution"), mapOf("urgency" to .28f, "joy" to .2f), "#8DE4EC", "#FFFFFF", "wind-dash", "ribbon-wind", "wind-marten"),
        MoteProfile(PetAppearance.VOLT_SPARROW, "雷雀", false, "清醒机敏", "high", listOf("devices", "alerts"), mapOf("alert" to .34f, "focus" to .22f), "#F5D547", "#B8F3FF", "spark-hop", "electric-feathers", "volt-sparrow"),
        MoteProfile(PetAppearance.FROST_HARE, "雪兔", false, "谨慎精准", "balanced", listOf("precision", "observation"), mapOf("calm" to .2f, "alert" to .24f), "#D9F2FF", "#A4C8FF", "frost-bounce", "snow-points", "frost-hare"),
        MoteProfile(PetAppearance.BLOOM_SPRITE, "花灵", false, "温柔滋养", "balanced", listOf("habitat", "growth"), mapOf("joy" to .22f, "empathy" to .3f), "#F59CC8", "#D9FFB5", "petal-sway", "petals", "bloom-sprite"),
        MoteProfile(PetAppearance.CRYSTAL_LIZARD, "晶蜥", false, "敏锐折射", "balanced", listOf("light", "analysis"), mapOf("curiosity" to .32f, "focus" to .18f), "#B5A2FF", "#75F2E2", "crystal-turn", "shards", "crystal-lizard"),
        MoteProfile(PetAppearance.DUNE_FOX, "沙狐", false, "坚韧从容", "balanced", listOf("endurance", "field"), mapOf("courage" to .25f, "calm" to .2f), "#E8AA5B", "#FFE1A8", "sand-glide", "sand-stars", "dune-fox"),
        MoteProfile(PetAppearance.SHADOW_MOTH, "影蛾", false, "安静侦察", "low", listOf("stealth", "remote-status"), mapOf("empathy" to .2f, "alert" to .26f), "#5A4D83", "#E6D7FF", "shadow-drift", "ink-wings", "shadow-moth")
    )
    val initial: List<MoteProfile> = all.filter { it.initial }
    val explorable: List<MoteProfile> = all.filterNot { it.initial }
    fun profile(appearance: PetAppearance): MoteProfile = all.firstOrNull { it.id == appearance } ?: all.first()
    fun fromWire(value: String?): MoteProfile = profile(PetAppearance.fromWire(value))
}
