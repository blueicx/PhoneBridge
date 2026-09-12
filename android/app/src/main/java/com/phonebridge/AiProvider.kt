package com.phonebridge

data class AiProviderConfig(
    val id: String,
    val name: String = "",
    val type: String = "openai",
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val status: String = "ready"
) {
    fun redacted(): AiProviderConfig {
        val masked = if (apiKey.isBlank()) {
            ""
        } else if (apiKey.length <= 8) {
            "[REDACTED]"
        } else if (apiKey.startsWith("sk-")) {
            "sk-***" + apiKey.takeLast(4)
        } else {
            apiKey.take(3) + "***" + apiKey.takeLast(4)
        }

        val sanitizedHeaders = headers.mapValues { (k, v) ->
            if (k.contains("token", true) || k.contains("auth", true) || k.contains("secret", true)) {
                "[REDACTED]"
            } else {
                v
            }
        }

        return copy(apiKey = masked, headers = sanitizedHeaders)
    }
}

data class AiCompletionResult(
    val text: String,
    val degraded: Boolean = false,
    val fallbackProvider: String? = null,
    val model: String? = null
)

interface AiProviderClient {
    val id: String
    suspend fun generate(prompt: String): AiCompletionResult
}

class AiFallbackResolver(
    private val primary: AiProviderClient,
    private val secondaryOnline: AiProviderClient? = null,
    private val localFallback: AiProviderClient
) {
    suspend fun resolve(prompt: String): AiCompletionResult {
        return try {
            primary.generate(prompt)
        } catch (_: Exception) {
            // "显式 provider 优先，失败只回退本地，备用联网 provider 不自动调用"
            localFallback.generate(prompt)
        }
    }
}

data class AiProbeResult(
    val ok: Boolean,
    val providerId: String,
    val latencyMs: Long = 0L,
    val model: String? = null,
    val error: String? = null
) {
    companion object {
        fun fromJson(text: String): AiProbeResult {
            val root = AiJsonParser(text).parse()
            return AiProbeResult(
                ok = root["ok"] == true || root["ok"]?.toString().equals("true", ignoreCase = true),
                providerId = root["providerId"]?.toString() ?: "",
                latencyMs = (root["latencyMs"] as? Number)?.toLong() ?: root["latencyMs"]?.toString()?.toLongOrNull() ?: 0L,
                model = root["model"]?.toString(),
                error = root["error"]?.toString()
            )
        }
    }
}

private class AiJsonParser(private val source: String) {
    private var index = 0

    fun parse(): Map<String, Any?> {
        skipWhitespace()
        if (index >= source.length || source[index] != '{') return emptyMap()
        index++
        val result = linkedMapOf<String, Any?>()
        while (index < source.length) {
            skipWhitespace()
            if (index < source.length && source[index] == '}') {
                index++
                return result
            }
            val key = parseString()
            skipWhitespace()
            if (index < source.length && source[index] == ':') index++
            val value = parseValue()
            result[key] = value
            skipWhitespace()
            if (index < source.length && source[index] == ',') {
                index++
            } else if (index < source.length && source[index] == '}') {
                index++
                return result
            }
        }
        return result
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= source.length) return null
        return when (source[index]) {
            '"' -> parseString()
            't' -> { if (source.startsWith("true", index)) { index += 4; true } else null }
            'f' -> { if (source.startsWith("false", index)) { index += 5; false } else null }
            'n' -> { if (source.startsWith("null", index)) { index += 4; null } else null }
            '-', in '0'..'9' -> parseNumber()
            else -> null
        }
    }

    private fun parseString(): String {
        skipWhitespace()
        if (index >= source.length || source[index] != '"') return ""
        index++
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '"') return sb.toString()
            if (ch == '\\' && index < source.length) {
                sb.append(source[index++])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val start = index
        while (index < source.length && (source[index] in '0'..'9' || source[index] in ".-+eE")) {
            index++
        }
        val s = source.substring(start, index)
        return if (s.contains('.')) s.toDoubleOrNull() ?: 0.0 else s.toLongOrNull() ?: 0L
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
}
