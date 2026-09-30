package com.phonebridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

data class WorkspaceHttpResponse(val statusCode: Int, val body: String) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

class WorkspaceClient {
    private val clientsByFingerprint = ConcurrentHashMap<String, OkHttpClient>()

    fun realityLogPath(cursor: String? = null, limit: Int = 50): String {
        require(limit in 1..100) { "reality log limit must be between 1 and 100" }
        val normalizedCursor = cursor?.takeIf(String::isNotBlank)
        require(normalizedCursor == null || REALITY_LOG_CURSOR.matches(normalizedCursor)) {
            "reality log cursor must be base64url"
        }
        return buildString {
            append("/api/reality/log?limit=")
            append(limit)
            if (normalizedCursor != null) {
                append("&cursor=")
                append(normalizedCursor)
            }
        }
    }

    internal fun clientForFingerprint(certificateFingerprint: String?): OkHttpClient {
        val fingerprint = certificateFingerprint?.trim()?.takeIf(String::isNotEmpty)
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return clientsByFingerprint.computeIfAbsent(fingerprint) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
            if (it.isNotEmpty()) PairingTls.configure(builder, it)
            builder.build()
        }
    }

    fun execute(
        serverUrl: String,
        token: String,
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        certificateFingerprint: String? = null
    ): Result<WorkspaceHttpResponse> = runCatching {
        val base = serverUrl.trim()
            .replaceFirst("^ws://".toRegex(), "http://")
            .replaceFirst("^wss://".toRegex(), "https://")
            .trimEnd('/')
        val requestBuilder = Request.Builder()
            .url("$base$path")
            .header("x-phonebridge-token", token)
        if (payload != null) {
            requestBuilder.method(method, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        } else if (method != "GET") {
            requestBuilder.method(method, "{}".toRequestBody(JSON_MEDIA_TYPE))
        }
        clientForFingerprint(certificateFingerprint).newCall(requestBuilder.build()).execute().use { response ->
            WorkspaceHttpResponse(response.code, response.body?.string().orEmpty())
        }
    }

    fun request(
        serverUrl: String,
        token: String,
        path: String,
        method: String = "GET",
        payload: JSONObject? = null,
        certificateFingerprint: String? = null
    ): Result<JSONObject> = execute(serverUrl, token, path, method, payload, certificateFingerprint).map { response ->
        if (!response.isSuccessful) error("HTTP ${response.statusCode}: ${response.body.take(180)}")
        JSONObject(response.body.ifBlank { "{}" })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val REALITY_LOG_CURSOR = Regex("^[A-Za-z0-9_-]{1,512}$")
    }
}
