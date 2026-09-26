package com.phonebridge

enum class RealityCaptureSurface { COMPANION, REALITY }
enum class RealityCameraOwner { NONE, CAMERAX, ARCORE }

data class RealityCaptureState(
    val surface: RealityCaptureSurface,
    val owner: RealityCameraOwner,
    val keepCameraOnExit: Boolean,
    val entryId: Long,
    val arAttempted: Boolean,
    val thermalLockout: Boolean,
    val fallbackReason: String? = null,
) {
    val cameraOwned: Boolean get() = owner != RealityCameraOwner.NONE
}

/** Serializes CameraX and ARCore ownership across Reality entry, fallback, and exit. */
class RealityCaptureController {
    private var nextEntryId = 0L
    private var thermalCoolSinceMs: Long? = null
    private var lastThermalSampleMs: Long? = null
    private var lastThermalSampleCelsius: Float? = null
    private var state = RealityCaptureState(
        surface = RealityCaptureSurface.COMPANION,
        owner = RealityCameraOwner.NONE,
        keepCameraOnExit = false,
        entryId = nextEntryId,
        arAttempted = false,
        thermalLockout = false,
    )

    fun enterReality(cameraAlreadyRunning: Boolean): RealityCaptureState {
        nextEntryId += 1L
        state = RealityCaptureState(
            surface = RealityCaptureSurface.REALITY,
            owner = RealityCameraOwner.NONE,
            keepCameraOnExit = cameraAlreadyRunning,
            entryId = nextEntryId,
            arAttempted = false,
            thermalLockout = state.thermalLockout,
        )
        return state
    }

    /** Marks one ARCore attempt for this entry and returns its stale-callback token. */
    fun beginArCoreAttempt(): Long? {
        if (state.surface != RealityCaptureSurface.REALITY || state.arAttempted || state.thermalLockout ||
            state.fallbackReason != null
        ) return null
        state = state.copy(arAttempted = true)
        return state.entryId
    }

    fun claimArCore(entryId: Long): Boolean {
        if (!isCurrentRealityEntry(entryId) || !state.arAttempted || state.thermalLockout ||
            state.fallbackReason != null
        ) return false
        if (state.owner != RealityCameraOwner.NONE) return false
        state = state.copy(owner = RealityCameraOwner.ARCORE)
        return true
    }

    fun claimCameraX(entryId: Long? = null): Boolean {
        if (state.owner == RealityCameraOwner.ARCORE || state.thermalLockout) return false
        if (state.surface == RealityCaptureSurface.REALITY && entryId != state.entryId) return false
        if (state.owner == RealityCameraOwner.CAMERAX) return true
        state = state.copy(owner = RealityCameraOwner.CAMERAX)
        return true
    }

    fun releaseCamera(owner: RealityCameraOwner, entryId: Long? = null): Boolean {
        if (owner == RealityCameraOwner.NONE || owner != state.owner) return false
        if (state.surface == RealityCaptureSurface.REALITY && entryId != state.entryId) return false
        state = state.copy(owner = RealityCameraOwner.NONE)
        return true
    }

    /** A normal AR failure permits CameraX fallback; a thermal failure locks cameras out. */
    fun markFallback(entryId: Long, reason: String, thermal: Boolean = false): Boolean {
        if (!isCurrentRealityEntry(entryId)) return false
        if (thermal) {
            thermalCoolSinceMs = null
            lastThermalSampleMs = null
            lastThermalSampleCelsius = null
        }
        state = state.copy(
            owner = RealityCameraOwner.NONE,
            thermalLockout = state.thermalLockout || thermal,
            fallbackReason = reason.take(120),
        )
        return true
    }

    /** Samples battery/device temperature while a thermal lockout is active. */
    fun observeTemperature(nowMs: Long, temperatureCelsius: Float?) {
        if (!state.thermalLockout) return
        val temperature = temperatureCelsius?.takeIf { it.isFinite() }
        lastThermalSampleMs = nowMs
        lastThermalSampleCelsius = temperature
        thermalCoolSinceMs = when {
            temperature == null || temperature >= THERMAL_COOLDOWN_CELSIUS -> null
            else -> thermalCoolSinceMs ?: nowMs
        }
    }

    /** Compatibility overload intentionally cannot clear a lockout without fresh cooling evidence. */
    fun clearThermalLockoutAfterExplicitStart(entryId: Long): Boolean =
        clearThermalLockoutAfterExplicitStart(nowMs = Long.MIN_VALUE, temperatureCelsius = null, entryId = entryId)

    /** A user-initiated camera start is the only way to clear a lockout after a full cool interval. */
    fun clearThermalLockoutAfterExplicitStart(
        nowMs: Long,
        temperatureCelsius: Float?,
        entryId: Long? = null,
    ): Boolean {
        if (!state.thermalLockout) return true
        if (entryId != null && !isCurrentRealityEntry(entryId)) return false
        observeTemperature(nowMs, temperatureCelsius)
        val coolSince = thermalCoolSinceMs ?: return false
        val lastSample = lastThermalSampleMs ?: return false
        val lastTemperature = lastThermalSampleCelsius ?: return false
        if (nowMs - lastSample !in 0L..MAX_THERMAL_SAMPLE_AGE_MS) return false
        if (lastTemperature >= THERMAL_COOLDOWN_CELSIUS || nowMs - coolSince < THERMAL_COOLDOWN_DURATION_MS) return false
        state = state.copy(thermalLockout = false)
        thermalCoolSinceMs = null
        lastThermalSampleMs = null
        lastThermalSampleCelsius = null
        return true
    }

    fun exitReality(): RealityCaptureState {
        val restoreCamera = state.keepCameraOnExit && !state.thermalLockout
        nextEntryId += 1L
        state = RealityCaptureState(
            surface = RealityCaptureSurface.COMPANION,
            owner = RealityCameraOwner.NONE,
            keepCameraOnExit = restoreCamera,
            entryId = nextEntryId,
            arAttempted = false,
            thermalLockout = state.thermalLockout,
            fallbackReason = null,
        )
        return state
    }

    fun isCurrentRealityEntry(entryId: Long): Boolean =
        state.surface == RealityCaptureSurface.REALITY && state.entryId == entryId

    fun snapshot(): RealityCaptureState = state

    private companion object {
        const val THERMAL_COOLDOWN_CELSIUS = 38f
        const val THERMAL_COOLDOWN_DURATION_MS = 60_000L
        const val MAX_THERMAL_SAMPLE_AGE_MS = 5_000L
    }
}
