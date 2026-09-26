package com.phonebridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class PairingQrDecoderTest {
    @Test
    fun decodesQrFromGrayscaleCameraPixels() {
        val expected = "{\"version\":2,\"pairingId\":\"pair_test\"}"
        val matrix = MultiFormatWriter().encode(expected, BarcodeFormat.QR_CODE, 256, 256)
        val pixels = ByteArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0 else 0xff.toByte()
        }

        assertEquals(expected, PairingQrDecoder.decode(pixels, matrix.width, matrix.height))
    }

    @Test
    fun ignoresInvalidOrNonQrCameraFrames() {
        assertNull(PairingQrDecoder.decode(byteArrayOf(1, 2), 2, 2))
        assertNull(PairingQrDecoder.decode(ByteArray(64) { 127 }, 8, 8))
    }

    @Test
    fun copiesPaddedCameraPlaneUsingCropAndPixelStride() {
        val source = ByteBuffer.wrap(ByteArray(20) { it.toByte() }).apply { position(1) }
        assertArrayEquals(
            byteArrayOf(9, 11, 15, 17),
            PairingQrDecoder.copyLuminancePlane(
                source = source,
                width = 2,
                height = 2,
                rowStride = 6,
                pixelStride = 2,
                cropLeft = 1,
                cropTop = 1,
            ),
        )
    }
}
