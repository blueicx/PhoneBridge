package com.phonebridge

data class TimedTemperature(
    val celsius: Float,
    val sampledAtMs: Long,
)

object RealityTemperaturePolicy {
    const val MAX_SAMPLE_AGE_MS = 10_000L

    fun maximumFresh(
        nowMs: Long,
        currentCelsius: Float?,
        cached: Iterable<TimedTemperature>,
    ): Float? {
        val values = buildList {
            currentCelsius?.takeIf(::isUsable)?.let(::add)
            cached.forEach { sample ->
                val ageMs = nowMs - sample.sampledAtMs
                if (isUsable(sample.celsius) && ageMs in 0L..MAX_SAMPLE_AGE_MS) {
                    add(sample.celsius)
                }
            }
        }
        return values.maxOrNull()
    }

    private fun isUsable(value: Float): Boolean = value.isFinite() && value > 0f
}
