package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientToneGeneratorTest {
    @Test
    fun generatedLoopIsBoundedAudibleAndHasNoLargeBoundaryDiscontinuity() {
        val samples = AmbientToneGenerator.generate(sampleRate = 22_050, durationSeconds = 4)

        assertEquals(88_200, samples.size)
        assertTrue(samples.any { it.toInt() != 0 })
        assertTrue(kotlin.math.abs(samples.first().toInt() - samples.last().toInt()) < 1_000)
    }
}
