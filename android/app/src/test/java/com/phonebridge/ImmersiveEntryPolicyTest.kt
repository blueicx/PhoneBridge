package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveEntryPolicyTest {
    @Test
    fun firstLaunchStartsInCompanionWithoutRequestingPermissions() {
        val state = ImmersiveEntryState()

        assertEquals(ImmersiveSurface.COMPANION, ImmersiveEntryPolicy.surfaceFor(state))
        assertFalse(ImmersiveEntryPolicy.shouldRequestPermissionsOnLaunch())
    }

    @Test
    fun restoresRealityOnlyWhenThePreviousSurfaceWasRealityAndCameraPermissionRemains() {
        val state = ImmersiveEntryState(
            lastSurface = ImmersiveSurface.REALITY,
            cameraPermissionGranted = true,
        )

        assertEquals(ImmersiveSurface.REALITY, ImmersiveEntryPolicy.surfaceFor(state))
        assertEquals(
            ImmersiveSurface.COMPANION,
            ImmersiveEntryPolicy.surfaceFor(state.copy(cameraPermissionGranted = false)),
        )
        assertEquals(
            ImmersiveSurface.COMPANION,
            ImmersiveEntryPolicy.surfaceFor(state.copy(lastSurface = ImmersiveSurface.COMPANION)),
        )
    }

    @Test
    fun persistedValuesNormalizeUnknownSurfaceToCompanion() {
        assertEquals(ImmersiveSurface.COMPANION, ImmersiveSurface.fromPersisted("unexpected"))
        assertEquals(ImmersiveSurface.REALITY, ImmersiveSurface.fromPersisted("reality"))
        assertTrue(ImmersiveSurface.COMPANION.persistedValue == "companion")
    }
}
