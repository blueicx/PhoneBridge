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
        val secure = insecure.copy(scheme = "wss", fingerprint = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        assertFalse(insecure.isSecureRemote())
        assertTrue(secure.isSecureRemote())
        assertEquals("wss://192.168.1.9:9503", secure.endpointUrl())
    }

    @Test
    fun parsesQrPayloadAndBuildsClaimPayload() {
        val offer = PairingProtocol.fromQrPayload(
            """{"version":1,"pairingId":"pair_1","code":"123456","nonce":"nonce","endpoint":"wss://192.168.1.9:9503","scheme":"wss","fingerprint":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","expiresAt":2000}"""
        )
        assertEquals("pair_1", offer.pairingId)
        assertEquals("wss://192.168.1.9:9503", offer.endpointUrl())
        assertEquals("pair_1", PairingProtocol.claimFields(offer)["id"])
        assertEquals("nonce", PairingProtocol.claimFields(offer)["nonce"])
        assertTrue(PairingProtocol.certificatePin(offer.fingerprint).orEmpty().startsWith("sha256/"))
    }
}
