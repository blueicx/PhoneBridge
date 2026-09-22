package com.phonebridge

enum class RealityCaptureSurface { COMPANION, REALITY }

data class RealityCaptureState(
    val surface: RealityCaptureSurface,
    val cameraOwned: Boolean,
    val keepCameraOnExit: Boolean,
)

/** One owner decides whether the current CameraX session belongs to the companion or RealityLens. */
class RealityCaptureController {
    private var state = RealityCaptureState(RealityCaptureSurface.COMPANION, cameraOwned = false, keepCameraOnExit = false)

    fun enterReality(cameraAlreadyRunning: Boolean): RealityCaptureState {
        state = RealityCaptureState(
            surface = RealityCaptureSurface.REALITY,
            cameraOwned = !cameraAlreadyRunning,
            keepCameraOnExit = cameraAlreadyRunning,
        )
        return state
    }

    fun exitReality(): RealityCaptureState {
        state = RealityCaptureState(
            surface = RealityCaptureSurface.COMPANION,
            cameraOwned = false,
            keepCameraOnExit = state.keepCameraOnExit,
        )
        return state
    }

    fun snapshot(): RealityCaptureState = state
}
