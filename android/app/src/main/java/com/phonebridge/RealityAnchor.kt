package com.phonebridge

import kotlin.math.abs
import kotlin.math.max

data class RealityFrame(
    val timestampMs: Long,
    val bearingDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val targetBearingDegrees: Float,
    val distanceBand: String,
    val width: Int,
    val height: Int,
    val fps: Float,
    val temperatureCelsius: Float,
    val targetPitchDegrees: Float = 0f,
)

data class AnchorPose(
    val x: Float,
    val y: Float,
    val scale: Float,
    val visible: Boolean,
    val source: String,
)

interface RealityAnchorProvider {
    val available: Boolean
    fun update(frame: RealityFrame): AnchorPose
}

class CanvasSensorAnchorProvider : RealityAnchorProvider {
    override val available: Boolean = true

    override fun update(frame: RealityFrame): AnchorPose {
        val width = max(1, frame.width)
        val height = max(1, frame.height)
        val delta = normalizeDegrees(frame.targetBearingDegrees - frame.bearingDegrees)
        val horizontalRatio = delta / 60f
        val x = (width / 2f + horizontalRatio * width / 2f).coerceIn(0f, width.toFloat())
        val pitchDelta = frame.targetPitchDegrees - frame.pitchDegrees
        val y = (height / 2f - pitchDelta * height / 90f).coerceIn(0f, height.toFloat())
        val scale = when (frame.distanceBand.lowercase()) {
            "near" -> 1f
            "mid" -> .8f
            "far" -> .6f
            else -> .7f
        }
        return AnchorPose(
            x = x,
            y = y,
            scale = scale,
            visible = abs(delta) <= 90f,
            source = "canvas",
        )
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized > 180f) normalized -= 360f
        if (normalized < -180f) normalized += 360f
        return normalized
    }
}

fun interface ArCorePoseSource {
    fun update(frame: RealityFrame): AnchorPose?
}

/**
 * Adapter seam for a real ARCore Session. It is deliberately unavailable until
 * an ARCore session owns the camera and can return a tracked pose; a Canvas pose
 * is never relabeled as ARCore.
 */
class ArCoreAnchorProvider(
    private val poseSource: ArCorePoseSource? = null,
) : RealityAnchorProvider {
    override val available: Boolean
        get() = poseSource != null
    private val canvasProjection = CanvasSensorAnchorProvider()

    override fun update(frame: RealityFrame): AnchorPose =
        poseSource?.update(frame)?.takeIf { it.source != "canvas" }
            ?: canvasProjection.update(frame)
}

class RealityAnchorSelector(
    private val arCore: RealityAnchorProvider,
    private val canvas: RealityAnchorProvider,
    private val maxTemperatureCelsius: Float = 40f,
    private val minFps: Float = 24f,
) : RealityAnchorProvider {
    var usingArCore: Boolean = false
        private set
    private var windowStartedAtMs: Long? = null
    private var windowFrameRateTotal = 0f
    private var windowSampleCount = 0
    private var consecutiveLowFrameWindows = 0
    private var frameRateFallback = false
    private var thermalFallback = false
    var fallbackReason: String? = null
        private set

    override val available: Boolean
        get() = canvas.available || arCore.available

    override fun update(frame: RealityFrame): AnchorPose {
        if (frame.temperatureCelsius >= maxTemperatureCelsius) {
            thermalFallback = true
            fallbackReason = "thermal"
            usingArCore = false
            windowStartedAtMs = frame.timestampMs
            windowFrameRateTotal = 0f
            windowSampleCount = 0
            return canvas.update(frame)
        }

        if (thermalFallback || frameRateFallback || !arCore.available) {
            if (frameRateFallback) fallbackReason = "frame_rate"
            usingArCore = false
            return canvas.update(frame)
        }

        recordFrameRate(frame)
        usingArCore = !frameRateFallback
        return if (usingArCore) arCore.update(frame) else canvas.update(frame)
    }

    private fun recordFrameRate(frame: RealityFrame) {
        val windowStart = windowStartedAtMs
        if (windowStart == null || frame.timestampMs < windowStart) {
            windowStartedAtMs = frame.timestampMs
            windowFrameRateTotal = frame.fps
            windowSampleCount = 1
            return
        }

        windowFrameRateTotal += frame.fps
        windowSampleCount += 1
        if (frame.timestampMs - windowStart < QUALITY_WINDOW_MS) return

        val averageFps = windowFrameRateTotal / windowSampleCount.coerceAtLeast(1)
        consecutiveLowFrameWindows = if (averageFps < minFps) consecutiveLowFrameWindows + 1 else 0
        if (consecutiveLowFrameWindows >= REQUIRED_LOW_FRAME_WINDOWS) {
            frameRateFallback = true
            fallbackReason = "frame_rate"
        }
        windowStartedAtMs = frame.timestampMs
        windowFrameRateTotal = 0f
        windowSampleCount = 0
    }

    /** Starts a new Reality entry; thermal protection remains latched for the owning view. */
    fun beginRealityEntry() {
        windowStartedAtMs = null
        windowFrameRateTotal = 0f
        windowSampleCount = 0
        consecutiveLowFrameWindows = 0
        frameRateFallback = false
        usingArCore = false
        if (!thermalFallback) fallbackReason = null
    }

    fun clearThermalLockoutAfterExplicitStart() {
        thermalFallback = false
        frameRateFallback = false
        consecutiveLowFrameWindows = 0
        fallbackReason = null
        beginRealityEntry()
    }

    private companion object {
        const val QUALITY_WINDOW_MS = 2_000L
        const val REQUIRED_LOW_FRAME_WINDOWS = 3
    }
}
