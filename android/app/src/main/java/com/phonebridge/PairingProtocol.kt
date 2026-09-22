package com.phonebridge

import org.json.JSONObject
import java.net.URI
import java.util.Base64

data class PairingOffer(
    val pairingId: String,
    val code: String,
    val nonce: String,
    val host: String,
    val port: Int,
    val fingerprint: String? = null,
    val expiresAt: Long = 0L,
    val scheme: String = "ws",
) {
    fun isUsable(now: Long): Boolean = pairingId.isNotBlank() && code.matches(Regex("\\d{6}")) && nonce.isNotBlank() && expiresAt > now
    fun isSecureRemote(): Boolean = !PairingProtocol.isLoopbackHost(host) && scheme.equals("wss", ignoreCase = true) && PairingProtocol.isValidFingerprint(fingerprint)
    fun endpointUrl(): String = "${scheme.lowercase()}://$host:$port"
}

object PairingProtocol {
    fun normalizeCode(value: String): String = value.filter(Char::isDigit).take(6)
    fun isLoopbackHost(host: String): Boolean = host == "127.0.0.1" || host == "localhost" || host == "::1"

    fun fromQrPayload(value: String): PairingOffer {
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
        return PairingOffer(
            pairingId = string("pairingId", string("id")),
            code = normalizeCode(string("code")),
            nonce = string("nonce"),
            host = string("host", uri?.host ?: ""),
            port = string("port").toIntOrNull() ?: (uri?.port ?: -1),
            fingerprint = string("fingerprint").ifBlank { null },
            expiresAt = number("expiresAt"),
            scheme = string("scheme", uri?.scheme ?: "ws"),
        )
    }

    fun fromJson(json: JSONObject): PairingOffer {
        val endpoint = json.optString("endpoint", json.optString("url"))
        val uri = runCatching { URI(endpoint) }.getOrNull()
        val scheme = json.optString("scheme", uri?.scheme ?: "ws")
        val host = json.optString("host", uri?.host ?: "")
        val port = if (json.has("port")) json.optInt("port", -1) else (uri?.port ?: -1)
        return PairingOffer(
            pairingId = json.optString("pairingId", json.optString("id")),
            code = normalizeCode(json.optString("code")),
            nonce = json.optString("nonce"),
            host = host,
            port = port,
            fingerprint = json.optString("fingerprint").ifBlank { null },
            expiresAt = json.optLong("expiresAt", 0L),
            scheme = scheme,
        )
    }

    fun claimPayload(offer: PairingOffer): JSONObject = JSONObject()
        .put("id", offer.pairingId)
        .put("code", offer.code)
        .put("nonce", offer.nonce)

    fun claimFields(offer: PairingOffer): Map<String, String> = mapOf(
        "id" to offer.pairingId,
        "code" to offer.code,
        "nonce" to offer.nonce,
    )

    fun isValidFingerprint(value: String?): Boolean = value?.trim()?.matches(Regex("sha256:[0-9a-fA-F]{64}")) == true

    /** Converts the server's documented sha256:<hex> fingerprint to OkHttp pin syntax. */
    fun certificatePin(value: String?): String? {
        if (!isValidFingerprint(value)) return null
        val hex = value!!.substringAfter(':')
        val bytes = ByteArray(32) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return "sha256/${Base64.getEncoder().encodeToString(bytes)}"
    }
}
