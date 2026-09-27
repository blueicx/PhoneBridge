package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionStageEngineTest {
    @Test
    fun stageReflectsNightRestAndRecentInteractionWithoutLosingAccessibilitySettings() {
        val state = CompanionStageEngine.resolve(
            CompanionStageInput(
                hourOfDay = 23,
                energy = 12,
                recentInteractionAgeMs = 300L,
                activeTasks = 0,
                observingReality = false,
                quietMode = true,
                reduceMotion = true,
            )
        )

        assertEquals(StageLighting.NIGHT, state.lighting)
        assertEquals(CompanionStageActivity.INTERACTION, state.activity)
        assertTrue(state.quietMode)
        assertTrue(state.reduceMotion)
    }

    @Test
    fun stagePrioritizesRecentTouchThenObservationAndTaskFocus() {
        fun resolve(age: Long?, observing: Boolean, tasks: Int) = CompanionStageEngine.resolve(
            CompanionStageInput(
                hourOfDay = 12,
                energy = 80,
                recentInteractionAgeMs = age,
                activeTasks = tasks,
                observingReality = observing,
            )
        ).activity

        assertEquals(CompanionStageActivity.INTERACTION, resolve(100L, true, 2))
        assertEquals(CompanionStageActivity.OBSERVE, resolve(null, true, 2))
        assertEquals(CompanionStageActivity.FOCUS, resolve(null, false, 1))
        assertEquals(CompanionStageActivity.REST, CompanionStageEngine.resolve(
            CompanionStageInput(hourOfDay = 8, energy = 10)
        ).activity)
    }

    @Test
    fun expiredMessagesAndDuplicateDecorationsAreRemovedFromTheStageProjection() {
        val state = CompanionStageEngine.resolve(
            CompanionStageInput(
                hourOfDay = 18,
                energy = 70,
                nowMs = 10_000L,
                message = "任务已完成",
                messageExpiresAtMs = 9_999L,
                decorations = listOf(
                    StageDecoration("decor_01", "星灯"),
                    StageDecoration("decor_01", "星灯"),
                    StageDecoration("decor_02", "苔石"),
                ),
            )
        )

        assertEquals(StageLighting.DUSK, state.lighting)
        assertNull(state.message)
        assertEquals(listOf("decor_01", "decor_02"), state.decorations.map { it.id })
        assertFalse(state.reduceMotion)
    }
}
