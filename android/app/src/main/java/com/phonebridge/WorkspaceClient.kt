package com.phonebridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WorkspaceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun request(serverUrl: String, token: String, path: String, method: String = "GET", payload: JSONObject? = null): Result<JSONObject> = runCatching {
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
        client.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: ${text.take(180)}")
            JSONObject(text.ifBlank { "{}" })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
