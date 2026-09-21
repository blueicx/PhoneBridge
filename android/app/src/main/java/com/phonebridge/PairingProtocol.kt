package com.phonebridge

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
    fun isUsable(now: Long): Boolean = pairingId.isNotBlank() && code.length == 6 && nonce.isNotBlank() && expiresAt > now
    fun isSecureRemote(): Boolean = !PairingProtocol.isLoopbackHost(host) && scheme.equals("wss", ignoreCase = true) && !fingerprint.isNullOrBlank()
    fun endpointUrl(): String = "${scheme.lowercase()}://$host:$port"
}

object PairingProtocol {
    fun normalizeCode(value: String): String = value.filter(Char::isDigit).take(6)
    fun isLoopbackHost(host: String): Boolean = host == "127.0.0.1" || host == "localhost" || host == "::1"
}
