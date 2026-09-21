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

    @Test
    fun remotePairingRequiresWssAndCertificateFingerprint() {
        val insecure = PairingOffer("pair_1", "123456", "nonce", "192.168.1.9", 9503, scheme = "ws")
        val secure = insecure.copy(scheme = "wss", fingerprint = "sha256:abc")
        assertFalse(insecure.isSecureRemote())
        assertTrue(secure.isSecureRemote())
        assertEquals("wss://192.168.1.9:9503", secure.endpointUrl())
    }
}
