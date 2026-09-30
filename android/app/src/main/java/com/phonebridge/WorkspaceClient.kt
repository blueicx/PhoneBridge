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
    }
}
