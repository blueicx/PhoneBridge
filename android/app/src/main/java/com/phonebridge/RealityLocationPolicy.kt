package com.phonebridge

data class RealityLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val isMock: Boolean,
    val timestampMs: Long,
)

enum class RealityLocationMode {
    COARSE_REGION,
    CAMERA_ONLY,
}

data class RealityLocationDecision(
    val mode: RealityLocationMode,
    val region: String? = null,
    val reason: String = "",
)

object RealityLocationPolicy {
    private const val MAX_AGE_MS = 120_000L
    private const val MAX_ACCURACY_METERS = 500f

    fun resolve(
        permissionGranted: Boolean,
        locationEnabled: Boolean,
        sample: RealityLocationSample?,
        nowMs: Long,
    ): RealityLocationDecision {
        if (!permissionGranted) return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "permission_denied")
        if (!locationEnabled) return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "location_disabled")
        if (sample == null) return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "no_sample")
        if (sample.isMock) return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "mock_location")
        if (sample.timestampMs <= 0L || nowMs - sample.timestampMs !in 0..MAX_AGE_MS) {
            return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "stale_sample")
        }
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0
        ) {
            return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "invalid_coordinates")
        }
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters <= 0f || sample.accuracyMeters > MAX_ACCURACY_METERS) {
            return RealityLocationDecision(RealityLocationMode.CAMERA_ONLY, reason = "poor_accuracy")
        }
        return RealityLocationDecision(
            mode = RealityLocationMode.COARSE_REGION,
            region = RealityRegion.fromCoordinates(sample.latitude, sample.longitude),
            reason = "coarse_region",
        )
    }
}
