package com.phonebridge

import org.json.JSONObject
import java.net.URI

data class PairingOffer(
    val pairingId: String,
    val code: String,
    val nonce: String,
    val host: String,
    val port: Int,
    val fingerprint: String? = null,
    val expiresAt: Long = 0L,
    val scheme: String = "ws",
    val version: Int = 2,
    val fingerprintType: String = "x509-der-sha256",
) {
    fun isUsable(now: Long): Boolean = version == 2 && pairingId.isNotBlank() && code.matches(Regex("\\d{6}")) &&
        nonce.isNotBlank() && expiresAt > now && host.isNotBlank() && port in 1..65535 &&
        (scheme.equals("ws", ignoreCase = true) && PairingProtocol.isLoopbackHost(host) || isPinnedWss())

    private fun isPinnedWss(): Boolean = scheme.equals("wss", ignoreCase = true) &&
        fingerprintType == "x509-der-sha256" && PairingProtocol.isValidFingerprint(fingerprint)

    fun isSecureRemote(): Boolean = version == 2 && !PairingProtocol.isLoopbackHost(host) &&
        isPinnedWss()
    fun endpointUrl(): String = "${scheme.lowercase()}://$host:$port"
    fun claimUrl(): String = "${if (scheme.equals("wss", true)) "https" else "http"}://$host:$port/api/pairing/claim"
}

object PairingProtocol {
    fun normalizeCode(value: String): String = value.filter(Char::isDigit).take(6)
    fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase().removeSurrounding("[", "]")
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"
    }

    fun fromQrPayload(value: String): PairingOffer {
        require(value.length <= 4096) { "pairing QR payload is too large" }
        fun string(name: String, fallback: String = ""): String {
            val match = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(value)
            return match?.groupValues?.getOrNull(1) ?: fallback
        }
        fun number(name: String, fallback: Long = 0L): Long {
            val match = Regex("\\\"$name\\\"\\s*:\\s*(-?\\d+)").find(value)
            return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: fallback
        }
        val endpoint = string("endpoint", string("url"))
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(
            uri != null && uri.scheme?.lowercase() in setOf("ws", "wss") && uri.host != null &&
                uri.port in 1..65535 && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/")
        ) {
            "pairing QR endpoint is invalid"
        }
        return PairingOffer(
            pairingId = string("pairingId", string("id")),
            code = normalizeCode(string("code")),
            nonce = string("nonce"),
            host = uri.host,
            port = uri.port,
            fingerprint = string("fingerprint").ifBlank { null },
            expiresAt = number("expiresAt"),
            scheme = uri.scheme ?: "",
            version = number("version").toInt(),
            fingerprintType = string("fingerprintType"),
        )
    }

    fun fromJson(json: JSONObject): PairingOffer {
        val endpoint = json.optString("endpoint", json.optString("url"))
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val scheme = uri?.scheme ?: ""
        val host = uri?.host ?: ""
        val port = uri?.port ?: -1
        return PairingOffer(
            pairingId = json.optString("pairingId", json.optString("id")),
            code = normalizeCode(json.optString("code")),
            nonce = json.optString("nonce"),
            host = host,
            port = port,
            fingerprint = json.optString("fingerprint").ifBlank { null },
            expiresAt = json.optLong("expiresAt", 0L),
            scheme = scheme,
            version = json.optInt("version", 0),
            fingerprintType = json.optString("fingerprintType"),
        )
    }

    fun claimPayload(offer: PairingOffer): String = """{"id":${quoteJson(offer.pairingId)},"code":${quoteJson(offer.code)},"nonce":${quoteJson(offer.nonce)}}"""

    fun claimFields(offer: PairingOffer): Map<String, String> = mapOf(
        "id" to offer.pairingId,
        "code" to offer.code,
        "nonce" to offer.nonce,
    )

    fun isValidFingerprint(value: String?): Boolean = value?.trim()?.matches(Regex("sha256:[0-9a-fA-F]{64}")) == true

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

}
