package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun normalizesSixDigitCodeAndChecksExpiry() {
        assertEquals("123456", PairingProtocol.normalizeCode(" 12a345678"))
        val offer = PairingOffer("pair_1", "123456", "nonce", "127.0.0.1", 9501, expiresAt = 1000)
        assertTrue(offer.isUsable(999))
        assertFalse(offer.isUsable(1000))
        assertTrue(PairingProtocol.isLoopbackHost("localhost"))
    }
}
