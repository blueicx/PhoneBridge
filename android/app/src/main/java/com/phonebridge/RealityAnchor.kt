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

class ArCoreAnchorProvider(
    override val available: Boolean,
) : RealityAnchorProvider {
    private val canvasProjection = CanvasSensorAnchorProvider()

    override fun update(frame: RealityFrame): AnchorPose = canvasProjection.update(frame).copy(source = "arcore")
}

class RealityAnchorSelector(
    private val arCore: RealityAnchorProvider,
    private val canvas: RealityAnchorProvider,
    private val maxTemperatureCelsius: Float = 40f,
    private val minFps: Float = 24f,
) : RealityAnchorProvider {
    var usingArCore: Boolean = false
        private set

    override val available: Boolean
        get() = canvas.available || arCore.available

    override fun update(frame: RealityFrame): AnchorPose {
        usingArCore = arCore.available && frame.temperatureCelsius <= maxTemperatureCelsius && frame.fps >= minFps
        return if (usingArCore) arCore.update(frame) else canvas.update(frame)
    }
}
