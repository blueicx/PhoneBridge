package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityProjectionTest {
    @Test
    fun clipOriginProjectsToViewportCenterAndPreservesScale() {
        val pose = RealityProjection.fromClipCoordinates(
            clipX = 0f, clipY = 0f, clipW = 1f, width = 1000, height = 600, scale = .75f,
        )
        assertEquals(500f, pose.x, .001f)
        assertEquals(300f, pose.y, .001f)
        assertEquals(.75f, pose.scale, .001f)
        assertTrue(pose.visible)
        assertEquals("arcore-session", pose.source)
    }

    @Test
    fun projectionHidesBehindCameraOutsideViewportAndWhenTrackingIsPaused() {
        assertFalse(RealityProjection.fromClipCoordinates(0f, 0f, -1f, 100, 100).visible)
        assertFalse(RealityProjection.fromClipCoordinates(1.1f, 0f, 1f, 100, 100).visible)
        assertFalse(RealityProjection.fromClipCoordinates(0f, 0f, 1f, 100, 100, tracking = false).visible)
        assertFalse(RealityProjection.fromClipCoordinates(0f, 0f, 1f, 100, 100, anchorPlaced = false).visible)
        assertFalse(RealityProjection.fromClipCoordinates(Float.NaN, 0f, 1f, 100, 100).visible)
    }

    @Test
    fun planePlacementRequiresTrackingEmptyAnchorAndPlaneHit() {
        assertTrue(RealityPlanePlacementPolicy.canPlace(tracking = true, anchorAlreadyPlaced = false, planeHit = true))
        assertFalse(RealityPlanePlacementPolicy.canPlace(tracking = false, anchorAlreadyPlaced = false, planeHit = true))
        assertFalse(RealityPlanePlacementPolicy.canPlace(tracking = true, anchorAlreadyPlaced = true, planeHit = true))
        assertFalse(RealityPlanePlacementPolicy.canPlace(tracking = true, anchorAlreadyPlaced = false, planeHit = false))
    }
}
