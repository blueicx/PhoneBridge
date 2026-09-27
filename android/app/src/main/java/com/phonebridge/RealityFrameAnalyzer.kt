package com.phonebridge

import android.media.Image
import java.lang.AutoCloseable
import kotlin.math.abs
import kotlin.math.max

data class RealityImagePlane(
    val data: ByteArray,
    val rowStride: Int,
    val pixelStride: Int,
)

data class RealityImagePlanes(
    val width: Int,
    val height: Int,
    val y: RealityImagePlane,
    val u: RealityImagePlane,
    val v: RealityImagePlane,
)

data class RealityNv21Frame(val width: Int, val height: Int, val data: ByteArray)

/** Copies only bounded YUV planes from an ARCore image and never retains the camera Image. */
object RealityFrameAnalyzer {
    inline fun <T : AutoCloseable, R> withFrameClosed(frame: T, block: (T) -> R): R =
        try {
            block(frame)
        } finally {
            frame.close()
        }

    fun copyPlanes(image: Image): RealityImagePlanes? {
        if (image.planes.size < 3 || image.width <= 0 || image.height <= 0) return null
        val copied = image.planes.take(3).map { plane ->
            val buffer = plane.buffer.duplicate()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            RealityImagePlane(bytes, plane.rowStride, plane.pixelStride)
        }
        return RealityImagePlanes(image.width, image.height, copied[0], copied[1], copied[2])
    }

    fun sampleLuminance(image: Image): RealityImageSignal? {
        if (image.planes.isEmpty() || image.width <= 0 || image.height <= 0) return null
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        if (plane.rowStride <= 0 || plane.pixelStride <= 0 || !buffer.hasRemaining()) return null
        val xStep = max(1, image.width / 24)
        val yStep = max(1, image.height / 24)
        val start = buffer.position()
        var sum = 0f
        var samples = 0
        var edgeSum = 0f
        var edgeSamples = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val index = start + y * plane.rowStride + x * plane.pixelStride
                if (index !in start until buffer.limit()) return null
                val luma = (buffer.get(index).toInt() and 0xff) / 255f
                sum += luma
                samples += 1
                if (x + xStep < image.width) {
                    val edgeIndex = start + y * plane.rowStride + (x + xStep) * plane.pixelStride
                    if (edgeIndex !in start until buffer.limit()) return null
                    val edgeLuma = (buffer.get(edgeIndex).toInt() and 0xff) / 255f
                    edgeSum += abs(luma - edgeLuma)
                    edgeSamples += 1
                }
                x += xStep
            }
            y += yStep
        }
        if (samples == 0) return null
        return RealityImageSignal(sum / samples, if (edgeSamples == 0) 0f else edgeSum / edgeSamples)
    }

    fun sampleLuminance(planes: RealityImagePlanes): RealityImageSignal? {
        if (!valid(planes.width, planes.height, planes.y)) return null
        val xStep = max(1, planes.width / 24)
        val yStep = max(1, planes.height / 24)
        var sum = 0f
        var samples = 0
        var edgeSum = 0f
        var edgeSamples = 0
        var y = 0
        while (y < planes.height) {
            var x = 0
            while (x < planes.width) {
                val luma = sample(planes.y, x, y) ?: return null
                val normalized = luma / 255f
                sum += normalized
                samples += 1
                if (x + xStep < planes.width) {
                    val next = sample(planes.y, x + xStep, y) ?: return null
                    edgeSum += abs(normalized - next / 255f)
                    edgeSamples += 1
                }
                x += xStep
            }
            y += yStep
        }
        if (samples == 0) return null
        return RealityImageSignal(sum / samples, if (edgeSamples == 0) 0f else edgeSum / edgeSamples)
    }

    fun toNv21(planes: RealityImagePlanes): ByteArray? {
        val width = planes.width
        val height = planes.height
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null
        if (!valid(width, height, planes.y) || !valid(width / 2, height / 2, planes.u) || !valid(width / 2, height / 2, planes.v)) return null
        val lumaSize = width * height
        val output = ByteArray(lumaSize + lumaSize / 2)
        var destination = 0
        for (row in 0 until height) {
            for (column in 0 until width) output[destination++] = sample(planes.y, column, row)?.toByte() ?: return null
        }
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                output[destination++] = sample(planes.v, column, row)?.toByte() ?: return null
                output[destination++] = sample(planes.u, column, row)?.toByte() ?: return null
            }
        }
        return output
    }

    /** Downsamples on the plane-copy boundary so opted-in remote frames stay bounded. */
    fun toBoundedNv21(planes: RealityImagePlanes, maxDimension: Int = 640): RealityNv21Frame? {
        val sourceWidth = planes.width
        val sourceHeight = planes.height
        if (sourceWidth < 2 || sourceHeight < 2 || maxDimension < 2) return null
        if (!valid(sourceWidth, sourceHeight, planes.y) ||
            !valid(sourceWidth / 2, sourceHeight / 2, planes.u) ||
            !valid(sourceWidth / 2, sourceHeight / 2, planes.v)
        ) return null

        val factor = max(1, (maxOf(sourceWidth, sourceHeight) + maxDimension - 1) / maxDimension)
        val width = ((sourceWidth / factor).coerceAtLeast(2) and -2)
        val height = ((sourceHeight / factor).coerceAtLeast(2) and -2)
        val output = ByteArray(width * height + width * height / 2)
        var destination = 0
        for (row in 0 until height) {
            val sourceRow = (row * factor).coerceAtMost(sourceHeight - 1)
            for (column in 0 until width) {
                val sourceColumn = (column * factor).coerceAtMost(sourceWidth - 1)
                output[destination++] = sample(planes.y, sourceColumn, sourceRow)?.toByte() ?: return null
            }
        }
        val sourceChromaWidth = sourceWidth / 2
        val sourceChromaHeight = sourceHeight / 2
        for (row in 0 until height / 2) {
            val sourceRow = (row * factor).coerceAtMost(sourceChromaHeight - 1)
            for (column in 0 until width / 2) {
                val sourceColumn = (column * factor).coerceAtMost(sourceChromaWidth - 1)
                output[destination++] = sample(planes.v, sourceColumn, sourceRow)?.toByte() ?: return null
                output[destination++] = sample(planes.u, sourceColumn, sourceRow)?.toByte() ?: return null
            }
        }
        return RealityNv21Frame(width, height, output)
    }

    fun shouldSampleLocalCue(nowMs: Long, previousSampleMs: Long, intervalMs: Long = 240L): Boolean =
        nowMs - previousSampleMs >= intervalMs

    private fun valid(width: Int, height: Int, plane: RealityImagePlane): Boolean =
        width > 0 && height > 0 && plane.rowStride > 0 && plane.pixelStride > 0 && plane.data.isNotEmpty()

    private fun sample(plane: RealityImagePlane, x: Int, y: Int): Int? {
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.data.getOrNull(index)?.toInt()?.and(0xff)
    }
}

enum class RealitySessionLifecycleState { READY, RESUMED, PAUSED, FAILED, CLOSED }

interface RealitySessionLifecyclePort {
    fun resume()
    fun pause()
    fun close()
}

/** Idempotent lifecycle wrapper used by the Android ARCore adapter and JVM fakes. */
class RealitySessionLifecycle(private val port: RealitySessionLifecyclePort) {
    var state: RealitySessionLifecycleState = RealitySessionLifecycleState.READY
        private set

    fun resume(): Boolean {
        if (state == RealitySessionLifecycleState.RESUMED) return true
        if (state == RealitySessionLifecycleState.CLOSED || state == RealitySessionLifecycleState.FAILED) return false
        return try {
            port.resume()
            state = RealitySessionLifecycleState.RESUMED
            true
        } catch (_: Exception) {
            state = RealitySessionLifecycleState.FAILED
            false
        }
    }

    fun pause() {
        if (state != RealitySessionLifecycleState.RESUMED) return
        try {
            port.pause()
        } finally {
            state = RealitySessionLifecycleState.PAUSED
        }
    }

    fun close() {
        if (state == RealitySessionLifecycleState.CLOSED) return
        var failure: Exception? = null
        try {
            pause()
        } catch (error: Exception) {
            failure = error
        }
        try {
            port.close()
        } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } finally {
            state = RealitySessionLifecycleState.CLOSED
        }
        failure?.let { throw it }
    }
}
