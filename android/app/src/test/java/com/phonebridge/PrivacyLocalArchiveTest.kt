package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivacyLocalArchiveTest {
    @Test
    fun encryptedLocalArchiveRoundTripsWithoutExposingPlaintext() {
        val plaintext = "{\"conversations\":[{\"text\":\"private draft\"}]}"
        val envelope = PrivacyLocalArchiveCrypto.encrypt(plaintext, "a sufficiently long passphrase")

        assertFalse(envelope.toJson().contains("private draft"))
        assertEquals(plaintext, PrivacyLocalArchiveCrypto.decrypt(envelope, "a sufficiently long passphrase"))
    }

    @Test
    fun encryptedLocalArchiveRejectsWrongPassphraseAndShortPasswords() {
        val envelope = PrivacyLocalArchiveCrypto.encrypt("{}", "a sufficiently long passphrase")

        assertThrows(IllegalArgumentException::class.java) {
            PrivacyLocalArchiveCrypto.decrypt(envelope, "a different long passphrase")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivacyLocalArchiveCrypto.encrypt("{}", "short")
        }
    }
}
