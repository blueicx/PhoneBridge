package com.phonebridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class PairingClaimException(message: String, cause: Throwable? = null) : IOException(message, cause)

class PairingClaimClient {
    fun claim(offer: PairingOffer): String {
        if (!offer.isUsable(System.currentTimeMillis())) {
            throw PairingClaimException("二维码已过期或配对信息不安全，请重新生成。")
        }

        val request = Request.Builder()
            .url(offer.claimUrl())
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .post(PairingProtocol.claimPayload(offer).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            clientFor(offer).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PairingClaimException("节点拒绝配对（HTTP ${response.code}），请重新生成二维码。")
                }
                val body = response.body ?: throw PairingClaimException("配对节点返回内容为空，请重新生成二维码。")
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw PairingClaimException("配对节点响应过大，已拒绝处理。")
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    throw PairingClaimException("配对节点响应过大，已拒绝处理。")
                }
                val payload = source.buffer.clone().readString(StandardCharsets.UTF_8)
                val token = extractToken(payload)
                if (token == null) {
                    throw PairingClaimException("节点未确认配对，请重新生成二维码。")
                }
                return token
            }
        } catch (error: PairingClaimException) {
            throw error
        } catch (error: SSLPeerUnverifiedException) {
            throw PairingClaimException("节点证书与二维码不匹配，请核对后重新配对。", error)
        } catch (error: SSLHandshakeException) {
            throw PairingClaimException("TLS 证书校验失败，请核对二维码并重试。", error)
        } catch (error: SocketTimeoutException) {
            throw PairingClaimException("配对连接超时，请检查网络并重新生成二维码。", error)
        } catch (error: IOException) {
            throw PairingClaimException("无法连接配对节点，请检查网络并重新生成二维码。", error)
        }
    }

    private fun extractToken(payload: String): String? {
        if (!Regex("\\\"ok\\\"\\s*:\\s*true\\s*(?:,|})").containsMatchIn(payload)) return null
        val encoded = Regex("\\\"token\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            .find(payload)?.groupValues?.getOrNull(1) ?: return null
        val token = decodeJsonString(encoded) ?: return null
        return token.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_LENGTH && it.none(Char::isWhitespace) }
    }

    private fun decodeJsonString(encoded: String): String? = runCatching {
        buildString {
            var index = 0
            while (index < encoded.length) {
                val character = encoded[index++]
                if (character != '\\') {
                    require(character >= ' ')
                    append(character)
                    continue
                }
                require(index < encoded.length)
                when (val escape = encoded[index++]) {
                    '"', '\\', '/' -> append(escape)
                    'b' -> append('\b')
                    'f' -> append('\u000c')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        require(index + 4 <= encoded.length)
                        append(encoded.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> error("invalid JSON string escape")
                }
            }
        }
    }.getOrNull()

    private fun clientFor(offer: PairingOffer): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
        if (offer.scheme.equals("wss", ignoreCase = true)) {
            PairingTls.configure(builder, requireNotNull(offer.fingerprint))
        }
        return builder
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RESPONSE_BYTES = 8192L
        private const val MAX_TOKEN_LENGTH = 4096
    }
}
