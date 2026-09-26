package com.phonebridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.EnumMap

object PairingQrDecoder {
    private const val MAX_PAYLOAD_LENGTH = 4096

    fun copyLuminancePlane(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int = 0,
        cropTop: Int = 0,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0 || cropLeft < 0 || cropTop < 0) return null
        val pixelCount = width.toLong() * height
        if (pixelCount > Int.MAX_VALUE) return null
        val base = source.position()
        val lastIndex = base.toLong() + (cropTop.toLong() + height - 1) * rowStride +
            (cropLeft.toLong() + width - 1) * pixelStride
        if (lastIndex >= source.limit()) return null

        val output = ByteArray(pixelCount.toInt())
        for (y in 0 until height) {
            val sourceRow = base + (cropTop + y) * rowStride + cropLeft * pixelStride
            val outputRow = y * width
            for (x in 0 until width) {
                output[outputRow + x] = source.get(sourceRow + x * pixelStride)
            }
        }
        return output
    }

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height > luminance.size || width.toLong() * height > Int.MAX_VALUE) {
            return null
        }
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, true)
        }
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                .takeIf { it.isNotBlank() && it.length <= MAX_PAYLOAD_LENGTH }
        } catch (_: ReaderException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            reader.reset()
        }
    }
}
