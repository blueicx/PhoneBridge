package com.phonebridge

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecureTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun put(token: String) {
        if (token.isBlank()) {
            clear()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun get(): String {
        val iv = preferences.getString(KEY_IV, null) ?: return ""
        val value = preferences.getString(KEY_VALUE, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.DEFAULT)))
            String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun migrateLegacy(legacyPreferences: android.content.SharedPreferences): String {
        val current = get()
        if (current.isNotBlank()) return current
        val legacy = legacyPreferences.getString(LEGACY_KEY, "").orEmpty()
        if (legacy.isBlank()) return ""
        put(legacy)
        legacyPreferences.edit().remove(LEGACY_KEY).apply()
        return get()
    }

    fun clear() = preferences.edit().remove(KEY_IV).remove(KEY_VALUE).apply()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFERENCES = "phonebridge_secure"
        private const val KEY_ALIAS = "phonebridge_access_token_v1"
        private const val KEY_IV = "access_token_iv"
        private const val KEY_VALUE = "access_token_ciphertext"
        private const val LEGACY_KEY = "access_token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
