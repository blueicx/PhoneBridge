package com.phonebridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Device-keystore encrypted, read-only holding area for data the user chose to keep locally. */
object PrivacyQuarantineStore {
    private const val PREFS = "phonebridge_privacy_quarantine"
    private const val KEY_ALIAS = "phonebridge_privacy_quarantine_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun replaceCategory(context: Context, category: String, payload: String) {
        require(category in PrivacyDataPolicy.categories) { "unknown privacy category" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val persisted = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ivKey(category), Base64.getEncoder().encodeToString(cipher.iv))
            .putString(dataKey(category), Base64.getEncoder().encodeToString(encrypted))
            .commit()
        check(persisted) { "privacy quarantine persistence failed" }
    }

    fun snapshot(context: Context): JSONObject {
        val result = JSONObject()
        for (category in PrivacyDataPolicy.categories) {
            val payload = readCategory(context, category) ?: continue
            result.put(category, payload)
        }
        return result
    }

    fun hasCategory(context: Context, category: String): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(dataKey(category))

    fun clearCategory(context: Context, category: String) {
        require(category in PrivacyDataPolicy.categories) { "unknown privacy category" }
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ivKey(category)).remove(dataKey(category)).commit()) { "privacy quarantine removal failed" }
    }

    private fun readCategory(context: Context, category: String): String? {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = preferences.getString(ivKey(category), null) ?: return null
        val encrypted = preferences.getString(dataKey(category), null) ?: throw IllegalStateException("privacy quarantine is incomplete")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("privacy quarantine cannot be decrypted", error)
        }
    }

    private fun ivKey(category: String): String = "$category.iv"
    private fun dataKey(category: String): String = "$category.ciphertext"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}

data class PrivacyLocalArchiveEnvelope(
    val salt: String,
    val nonce: String,
    val sha256: String,
    val ciphertext: String
) {
    fun toJson(): String = """{"format":"phonebridge-local-privacy","version":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":210000,"salt":"$salt","cipher":"AES-256-GCM","nonce":"$nonce","sha256":"$sha256","ciphertext":"$ciphertext"}"""
}

/** Passphrase-encrypted export for user review; the passphrase is never persisted. */
object PrivacyLocalArchiveCrypto {
    private const val ITERATIONS = 210_000
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val AAD = "PhoneBridge local privacy archive v1"

    fun encrypt(plaintext: String, passphrase: String): PrivacyLocalArchiveEnvelope {
        validatePassphrase(passphrase)
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "local privacy archive is too large" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(bytes)
        return PrivacyLocalArchiveEnvelope(
            salt = Base64.getEncoder().encodeToString(salt),
            nonce = Base64.getEncoder().encodeToString(nonce),
            sha256 = sha256(bytes),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext)
        )
    }

    fun decrypt(envelope: PrivacyLocalArchiveEnvelope, passphrase: String): String {
        validatePassphrase(passphrase)
        val salt = decode(envelope.salt, 16)
        val nonce = decode(envelope.nonce, 12)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        require(ciphertext.size <= MAX_BYTES + 16) { "local privacy archive is too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val plaintext = try { cipher.doFinal(ciphertext) }
        catch (error: Exception) { throw IllegalArgumentException("local privacy archive authentication failed", error) }
        require(sha256(plaintext).equals(envelope.sha256, ignoreCase = true)) { "local privacy archive integrity check failed" }
        return String(plaintext, Charsets.UTF_8)
    }

    private fun validatePassphrase(value: String) {
        require(value.length in 12..1024) { "passphrase must be 12-1024 characters" }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun decode(value: String, expectedSize: Int): ByteArray = Base64.getDecoder().decode(value).also {
        require(it.size == expectedSize) { "invalid local privacy archive" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
