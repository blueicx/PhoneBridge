package com.phonebridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

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

object RealityLocationCoordinator {
    fun resolve(
        permissionGranted: Boolean,
        locationEnabled: Boolean,
        samples: List<RealityLocationSample>,
        nowMs: Long,
    ): RealityLocationDecision {
        val usable = samples
            .sortedByDescending { it.timestampMs }
            .map { RealityLocationPolicy.resolve(permissionGranted, locationEnabled, it, nowMs) }
            .firstOrNull { it.mode == RealityLocationMode.COARSE_REGION }
        return usable ?: RealityLocationPolicy.resolve(permissionGranted, locationEnabled, null, nowMs)
    }
}

class RealityLocationSampler(private val context: Context) {
    fun sample(nowMs: Long = System.currentTimeMillis()): RealityLocationDecision {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = manager?.allProviders?.any { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        } == true
        val samples = if (permissionGranted && manager != null) {
            manager.allProviders.mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { location ->
                    RealityLocationSample(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            location.isFromMockProvider
                        } else {
                            false
                        },
                        timestampMs = location.time,
                    )
                }
            }
        } else {
            emptyList()
        }
        return RealityLocationCoordinator.resolve(permissionGranted, enabled, samples, nowMs)
    }
}
