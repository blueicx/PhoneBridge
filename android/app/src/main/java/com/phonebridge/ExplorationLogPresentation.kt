package com.phonebridge

data class ExplorationLogPresentation(
    val clueLabel: String,
    val statusLabel: String,
    val regionLabel: String,
    val rewardLabel: String?,
    val detailText: String,
    val canOpenReality: Boolean,
    val isReadOnly: Boolean
) {
    companion object {
        fun from(entry: ExplorationLogEntry): ExplorationLogPresentation {
            val clue = when (entry.clueType) {
                "location" -> "地点"
                "light" -> "光线"
                else -> "物体"
            }
            val status = when (entry.status) {
                ExplorationLogStatus.CONFIRMED -> "已确认"
                ExplorationLogStatus.PENDING -> if (entry.acknowledged) "节点已收到，奖励待确认" else "待同步"
                ExplorationLogStatus.REJECTED -> if (entry.reason == "offline_event_expired") "已过期 · 仅查看" else "已拒绝 · 仅查看"
            }
            val region = if (entry.coarseRegion == "camera") "仅镜头，未使用位置" else "粗区域 ${entry.coarseRegion}"
            val reward = if (entry.status == ExplorationLogStatus.CONFIRMED) {
                buildList {
                    entry.reward?.xp?.takeIf { it > 0 }?.let { add("经验 +$it") }
                    entry.reward?.items.orEmpty().forEach { add("${it.id} ×${it.amount}") }
                    entry.reward?.boost?.let { add("${it.id} ×${it.multiplier}") }
                }.joinToString(" · ").ifBlank { "已确认 · 无经验或道具奖励" }
            } else null
            val detail = buildList {
                add("${clue}线索 · $status")
                add(region)
                add("收据 ${entry.eventId}")
                entry.moteId?.let { add("Mote $it") }
                entry.observation?.takeIf(String::isNotBlank)?.let(::add)
                reward?.let(::add)
            }.joinToString("\n")
            return ExplorationLogPresentation(
                clueLabel = clue,
                statusLabel = status,
                regionLabel = region,
                rewardLabel = reward,
                detailText = detail,
                canOpenReality = entry.status == ExplorationLogStatus.CONFIRMED,
                isReadOnly = true
            )
        }
    }
}
