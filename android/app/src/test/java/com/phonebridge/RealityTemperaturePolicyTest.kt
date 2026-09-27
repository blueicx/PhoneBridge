package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RealityTemperaturePolicyTest {
    @Test
    fun ignoresStaleAndFutureTelemetryInsteadOfKeepingTheThermalGuardLatched() {
        val current = RealityTemperaturePolicy.maximumFresh(
            nowMs = 20_000L,
            currentCelsius = 37.2f,
            cached = listOf(
                TimedTemperature(42f, sampledAtMs = 1_000L),
                TimedTemperature(39f, sampledAtMs = 19_500L),
                TimedTemperature(45f, sampledAtMs = 21_000L),
            ),
        )

        assertEquals(39f, current!!, 0.001f)
    }

    @Test
    fun rejectsInvalidTemperatureSamples() {
        val current = RealityTemperaturePolicy.maximumFresh(
            nowMs = 20_000L,
            currentCelsius = Float.NaN,
            cached = listOf(TimedTemperature(-1f, 19_900L)),
        )

        assertNull(current)
    }
}
