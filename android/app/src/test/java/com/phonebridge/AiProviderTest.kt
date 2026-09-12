package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {

    @Test
    fun aiProviderConfigRedactsSecrets() {
        val config = AiProviderConfig(
            id = "openai",
            name = "OpenAI",
            type = "openai",
            endpoint = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
            apiKey = listOf("sk", "-1234567890abcdef1234567890").joinToString("")
        )

        val redacted = config.redacted()
        assertNotEquals(listOf("sk", "-1234567890abcdef1234567890").joinToString(""), redacted.apiKey)
        assertTrue(redacted.apiKey.contains("***"))
        assertEquals("openai", redacted.id)
        assertEquals("gpt-4o-mini", redacted.model)
    }

    @Test
    fun fallbackResolverFallsBackOnlyToLocalRules() {
        var secondaryNetworkCalled = false

        val primaryProvider = object : AiProviderClient {
            override val id = "openai"
            override suspend fun generate(prompt: String): AiCompletionResult {
                throw RuntimeException("Network unreachable")
            }
        }

        val secondaryOnlineProvider = object : AiProviderClient {
            override val id = "gemini"
            override suspend fun generate(prompt: String): AiCompletionResult {
                secondaryNetworkCalled = true
                return AiCompletionResult("from gemini", degraded = false)
            }
        }

        val localFallbackProvider = object : AiProviderClient {
            override val id = "local"
            override suspend fun generate(prompt: String): AiCompletionResult {
                return AiCompletionResult("本地离线响应: $prompt", degraded = true, fallbackProvider = "local")
            }
        }

        val resolver = AiFallbackResolver(
            primary = primaryProvider,
            secondaryOnline = secondaryOnlineProvider,
            localFallback = localFallbackProvider
        )

        val result = kotlinx.coroutines.runBlocking {
            resolver.resolve("测试问题")
        }

        assertFalse("Secondary online provider must NOT be automatically called", secondaryNetworkCalled)
        assertTrue(result.degraded)
        assertEquals("local", result.fallbackProvider)
        assertTrue(result.text.contains("本地离线响应"))
    }

    @Test
    fun probeResultParsesAndSanitizes() {
        val json = """
            {
                "ok": true,
                "providerId": "codex",
                "latencyMs": 45,
                "model": "codex-chat",
                "status": "healthy"
            }
        """.trimIndent()

        val probe = AiProbeResult.fromJson(json)
        assertTrue(probe.ok)
        assertEquals("codex", probe.providerId)
        assertEquals(45L, probe.latencyMs)
        assertEquals("codex-chat", probe.model)
    }
}
