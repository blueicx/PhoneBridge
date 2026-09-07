package com.phonebridge

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OfflineApiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: String = "chat"
) {
    val isReady: Boolean
        get() = enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

object OfflineBrain {
    private const val PREFS = "mote_offline_api"
    private const val KEY_CONFIG = "config"
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun load(context: Context): OfflineApiConfig = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null) ?: return OfflineApiConfig()
        fromJson(JSONObject(raw))
    }.getOrDefault(OfflineApiConfig())

    fun save(context: Context, config: OfflineApiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG, toJson(config).toString())
            .apply()
    }

    fun toJson(config: OfflineApiConfig): JSONObject = JSONObject()
        .put("enabled", config.enabled)
        .put("baseUrl", config.baseUrl.trim())
        .put("apiKey", config.apiKey)
        .put("model", config.model.trim())
        .put("protocol", if (config.protocol == "responses") "responses" else "chat")

    fun fromJson(json: JSONObject): OfflineApiConfig = OfflineApiConfig(
        enabled = json.optBoolean("enabled", false),
        baseUrl = json.optString("baseUrl").trim(),
        apiKey = json.optString("apiKey"),
        model = json.optString("model").trim(),
        protocol = json.optString("protocol", "chat")
    )

    fun chat(
        context: Context,
        text: String,
        memories: List<String> = emptyList(),
        history: List<Pair<String, String>> = emptyList(),
        configOverride: OfflineApiConfig? = null
    ): Result<String> {
        val config = configOverride ?: load(context)
        if (!config.isReady) {
            return Result.failure(IllegalStateException("离线 API 未配置完整"))
        }
        val handoff = MoteHandoff.load(context)
        val systemPrompt = buildString {
            appendLine("你是 Mote，一台驻留在 Xperia 手机上的感官同伴。回答简短自然，用户要求技术细节时再展开。")
            if (MoteHandoff.summary(handoff).isNotBlank()) {
                appendLine("共享交接上下文（手机和电脑保持一致）：")
                appendLine(MoteHandoff.summary(handoff))
            }
            if (memories.isNotEmpty()) {
                appendLine("用户要求长期记住的事实与偏好：")
                memories.forEachIndexed { index, item -> appendLine("${index + 1}. $item") }
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.takeLast(12).forEach { (role, content) ->
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", text))

        val endpoint = endpointFor(config)
        val body = JSONObject()
            .put("model", config.model)
            .put(if (config.protocol == "responses") "input" else "messages", messages)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("离线 API HTTP ${response.code}:${raw.take(160)}")
                }
                val payload = JSONObject(raw)
                val reply = extractReply(payload, config.protocol).trim()
                if (reply.isEmpty()) throw IllegalStateException("离线 API 返回空回复")
                reply
            }
        }
    }

    private fun endpointFor(config: OfflineApiConfig): String {
        val baseUrl = config.baseUrl
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/chat/completions") || clean.endsWith("/responses") -> clean
            clean.endsWith("/v1") -> "$clean/${if (config.protocol == "responses") "responses" else "chat/completions"}"
            else -> "$clean/v1/chat/completions"
        }
    }

    fun extractReply(payload: JSONObject, protocol: String): String {
        if (protocol != "responses") {
            return payload.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
        }
        payload.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = payload.optJSONArray("output") ?: return ""
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text" || part.has("text")) {
                    text.append(part.optString("text"))
                }
            }
        }
        return text.toString()
    }
}
