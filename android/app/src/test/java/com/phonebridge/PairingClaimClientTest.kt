package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PairingClaimClientTest {
    @Test
    fun postsOneTimeOfferAndReturnsOnlyTheClaimedToken() {
        val requestText = AtomicReference("")
        val token = withClaimServer(200, "{\"ok\":true,\"token\":\"rotated-secret\"}", requestText) { port ->
            PairingClaimClient().claim(localOffer(port))
        }

        assertEquals("rotated-secret", token)
        assert(requestText.get().startsWith("POST /api/pairing/claim HTTP/1.1"))
        assert(requestText.get().contains("\"id\":\"pair_test\""))
        assert(requestText.get().contains("\"code\":\"123456\""))
        assert(requestText.get().contains("\"nonce\":\"one-time-nonce\""))
        assert(!requestText.get().contains("Authorization:"))
    }

    @Test
    fun rejectsHttpFailuresAndNeverFollowsRedirects() {
        val requestText = AtomicReference("")
        assertThrows(PairingClaimException::class.java) {
            withClaimServer(302, "{\"ok\":true,\"token\":\"must-not-be-used\"}", requestText) { port ->
                PairingClaimClient().claim(localOffer(port))
            }
        }
        assert(requestText.get().startsWith("POST /api/pairing/claim HTTP/1.1"))
    }

    @Test
    fun rejectsSuccessfulResponsesWithoutRotatedToken() {
        val requestText = AtomicReference("")
        assertThrows(PairingClaimException::class.java) {
            withClaimServer(200, "{\"ok\":true,\"token\":\" \"}", requestText) { port ->
                PairingClaimClient().claim(localOffer(port))
            }
        }
    }

    private fun localOffer(port: Int) = PairingOffer(
        pairingId = "pair_test",
        code = "123456",
        nonce = "one-time-nonce",
        host = "127.0.0.1",
        port = port,
        expiresAt = Long.MAX_VALUE,
    )

    private fun <T> withClaimServer(
        status: Int,
        body: String,
        capturedRequest: AtomicReference<String>,
        request: (Int) -> T,
    ): T {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val headerBytes = ByteArrayOutputStream()
                var marker = 0
                while (marker != 4) {
                    val next = input.read()
                    if (next < 0) break
                    headerBytes.write(next)
                    marker = when {
                        marker == 0 && next == '\r'.code -> 1
                        marker == 1 && next == '\n'.code -> 2
                        marker == 2 && next == '\r'.code -> 3
                        marker == 3 && next == '\n'.code -> 4
                        next == '\r'.code -> 1
                        else -> 0
                    }
                }
                val headers = headerBytes.toString(StandardCharsets.UTF_8.name())
                val contentLength = Regex("(?im)^Content-Length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val payload = ByteArray(contentLength)
                var offset = 0
                while (offset < payload.size) {
                    val count = input.read(payload, offset, payload.size - offset)
                    if (count < 0) break
                    offset += count
                }
                capturedRequest.set(headers + String(payload, StandardCharsets.UTF_8))
                val reason = if (status == 200) "OK" else "Found"
                val responseBody = body.toByteArray(StandardCharsets.UTF_8)
                val responseHeaders = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${responseBody.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(responseHeaders.toByteArray(StandardCharsets.US_ASCII))
                    write(responseBody)
                    flush()
                }
            }
        }
        return try {
            request(server.localPort)
        } finally {
            server.close()
            worker.join(5000)
        }
    }
}
