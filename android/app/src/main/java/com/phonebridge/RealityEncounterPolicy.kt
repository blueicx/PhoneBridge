package com.phonebridge

object RealityEncounterPolicy {
    private val allowedActions = setOf("observe", "soothe", "dodge", "skill")

    fun wireAction(value: String?): String? = value?.trim()?.lowercase()?.takeIf(allowedActions::contains)

    fun observationPrompt(
        appearance: PetAppearance,
        clueType: String?,
        relationshipLevel: Int,
    ): String {
        val characterLine = MoteCharacterizationEngine
            .resolve(appearance, MoteMoment.EXPLORATION, relationshipLevel)
            .line
        val focus = when (RealityClueProtocol.canonicalType(clueType)) {
            "object" -> "先观察物体的轮廓、材质和边缘变化。"
            "light" -> "留意光线的方向、明暗和颜色变化。"
            else -> "看看周围环境的氛围与粗略方位，不记录具体地址。"
        }
        return "$characterLine\n$focus"
    }

    fun trackingHint(snapshot: RealityTrackingSnapshot, repositioning: Boolean): String = when {
        repositioning && snapshot.isTracking -> "轻触新位置重新放置 Mote"
        repositioning -> "锚点暂时丢失 · 缓慢移动镜头，找到平面后轻触新位置"
        snapshot.anchorPlaced && (!snapshot.isTracking || snapshot.motePose?.visible != true) ->
            "锚点暂时丢失 · 缓慢移动镜头重新识别平面"
        snapshot.anchorPlaced -> "Mote 已锚定 · 轻触伙伴互动"
        snapshot.isTracking -> "找到平面后轻触空白处放置 Mote"
        snapshot.fallbackReason != null -> snapshot.fallbackReason
        snapshot.status == RealityTrackingStatus.SEARCHING -> "移动设备寻找平面"
        else -> "仍可手动探索线索"
    }
}
