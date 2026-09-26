package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun acceptsPinnedWssLoopbackAndRejectsUnpinnedWssLoopback() {
        val fingerprint = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val secureLoopback = PairingOffer(
            "pair_local", "123456", "nonce", "127.0.0.1", 9503,
            fingerprint = fingerprint,
            expiresAt = 2000,
            scheme = "wss",
        )
        assertTrue(secureLoopback.isUsable(1000))
        assertFalse(secureLoopback.copy(fingerprint = null).isUsable(1000))
        assertTrue(PairingProtocol.isLoopbackHost("[::1]"))
    }

    @Test
    fun rejectsQrEndpointsWithQueryOrFragment() {
        val base = "\"version\":2,\"pairingId\":\"pair_1\",\"code\":\"123456\",\"nonce\":\"nonce\",\"expiresAt\":2000"
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.fromQrPayload("{$base,\"endpoint\":\"wss://192.168.1.9:9503/?token=leak\",\"fingerprintType\":\"x509-der-sha256\",\"fingerprint\":\"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.fromQrPayload("{$base,\"endpoint\":\"wss://192.168.1.9:9503/#fragment\",\"fingerprintType\":\"x509-der-sha256\",\"fingerprint\":\"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}")
        }
    }

    @Test
    fun parsesQrPayloadAndBuildsClaimPayload() {
        val offer = PairingProtocol.fromQrPayload(
            """{"version":2,"pairingId":"pair_1","code":"123456","nonce":"nonce","endpoint":"wss://192.168.1.9:9503","scheme":"wss","fingerprint":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","fingerprintType":"x509-der-sha256","expiresAt":2000}"""
        )
        assertEquals("pair_1", offer.pairingId)
        assertEquals("wss://192.168.1.9:9503", offer.endpointUrl())
        assertTrue(offer.isUsable(1000))
        assertEquals("pair_1", PairingProtocol.claimFields(offer)["id"])
        assertEquals("nonce", PairingProtocol.claimFields(offer)["nonce"])
        assertEquals("https://192.168.1.9:9503/api/pairing/claim", offer.claimUrl())
        assertTrue(PairingTls.matchesCertificate("certificate".toByteArray(), PairingTls.derFingerprint("certificate".toByteArray())))
        assertFalse(PairingTls.matchesCertificate("other".toByteArray(), PairingTls.derFingerprint("certificate".toByteArray())))
    }

    @Test
    fun legacyQrAndMalformedRemoteFingerprintsRequireRepairing() {
        val old = PairingProtocol.fromQrPayload(
            """{"version":1,"pairingId":"pair_1","code":"123456","nonce":"nonce","endpoint":"wss://192.168.1.9:9503","fingerprint":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","expiresAt":2000}"""
        )
        assertFalse(old.isUsable(1000))
        assertFalse(old.isSecureRemote())
        assertFalse(old.copy(version = 2, fingerprintType = "unknown").isSecureRemote())
        assertFalse(old.copy(version = 2, fingerprintType = "x509-der-sha256", fingerprint = "sha256:bad").isSecureRemote())
    }
}
