package com.phonebridge

data class MemoryCandidateDraft(
    val text: String,
    val source: String = "auto_extract",
    val confirmed: Boolean = false,
)

object ChatMemoryPolicy {
    private val explicitRemember = Regex("^(?:记住|記住|remember)[：:，,\\s]+(.+)$", RegexOption.IGNORE_CASE)
    private val personalFact = Regex("^(?:我叫|我的名字是|我喜欢|我不喜欢|我更喜欢|我对.{1,20}过敏|我的生日是|我正在做).{2,100}$")

    fun explicitFact(text: String, rememberThisTurn: Boolean): String? {
        if (!rememberThisTurn) return null
        return explicitRemember.find(text.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    fun inferCandidate(text: String, rememberThisTurn: Boolean): MemoryCandidateDraft? {
        if (!rememberThisTurn) return null
        val clean = text.trim().replace(Regex("\\s+"), " ").take(240)
        if (!personalFact.matches(clean)) return null
        return MemoryCandidateDraft(text = clean)
    }

    fun isRecallEligible(confirmed: Boolean, excluded: Boolean): Boolean = confirmed && !excluded
}
