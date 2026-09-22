package com.phonebridge

data class RealityImageSignal(
    val averageLuma: Float,
    val edgeScore: Float,
)

data class RealityCueHints(
    val types: Set<String>,
    val confidence: Float,
)

/** Local-only, deliberately small heuristic. It never receives coordinates or an image upload policy. */
object RealityCueAnalyzer {
    fun analyze(signal: RealityImageSignal): RealityCueHints {
        val luma = signal.averageLuma.coerceIn(0f, 1f)
        val edge = signal.edgeScore.coerceIn(0f, 1f)
        val types = linkedSetOf<String>()
        if (luma >= .78f || luma <= .22f) types += "light"
        if (edge >= .22f) types += "object"
        if (types.isEmpty() || (luma in .24f.. .76f && edge < .22f)) types += "location"
        return RealityCueHints(types, (maxOf(if (types.contains("light")) .62f else .38f, edge)).coerceIn(.2f, .95f))
    }

    fun shouldUploadOriginalFrame(defaultAllowUpload: Boolean): Boolean = defaultAllowUpload
}
