package com.phonebridge

enum class VoiceForegroundPhase {
    STOPPED,
    PREPARING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED
}

data class VoiceForegroundState(
    val serviceAlive: Boolean = true,
    val bridgeOnline: Boolean = true,
    val microphonePermissionGranted: Boolean = true,
    val sessionActive: Boolean = false,
    val phase: VoiceForegroundPhase = VoiceForegroundPhase.STOPPED,
    val detail: String = "监听已停止。",
    val proactiveMessage: String = "",
    val proactiveKey: String = ""
) {
    val running: Boolean get() = sessionActive
    val listening: Boolean get() = sessionActive && phase == VoiceForegroundPhase.LISTENING
    val speaking: Boolean get() = sessionActive && phase == VoiceForegroundPhase.SPEAKING

    fun preparing(detail: String = "正在加载离线中文识别模型。"): VoiceForegroundState =
        copy(
            sessionActive = true,
            phase = VoiceForegroundPhase.PREPARING,
            detail = detail,
            microphonePermissionGranted = true
        )

    fun listening(detail: String = "离线连续语音聊天中，直接说话即可。"): VoiceForegroundState =
        copy(
            sessionActive = true,
            phase = VoiceForegroundPhase.LISTENING,
            detail = detail,
            microphonePermissionGranted = true
        )

    fun processing(transcript: String): VoiceForegroundState =
        copy(
            sessionActive = true,
            phase = VoiceForegroundPhase.PROCESSING,
            detail = transcript.trim().takeIf { it.isNotEmpty() }?.let { "你说：$it" }
                ?: "正在处理语音内容。",
            microphonePermissionGranted = true
        )

    fun speaking(reply: String): VoiceForegroundState =
        copy(
            sessionActive = true,
            phase = VoiceForegroundPhase.SPEAKING,
            detail = reply.trim().ifEmpty { "正在播报回复。" },
            microphonePermissionGranted = true
        )

    fun paused(reason: String = "已暂停，请在 App 内重新开启。"): VoiceForegroundState =
        copy(sessionActive = false, phase = VoiceForegroundPhase.PAUSED, detail = reason)

    fun stopped(reason: String = "监听已停止。"): VoiceForegroundState =
        copy(sessionActive = false, phase = VoiceForegroundPhase.STOPPED, detail = reason)

    fun permissionMissing(reason: String = "请先打开 App 授予麦克风权限。"): VoiceForegroundState =
        copy(
            sessionActive = false,
            phase = VoiceForegroundPhase.STOPPED,
            detail = reason,
            microphonePermissionGranted = false
        )

    fun withBridgeOnline(online: Boolean): VoiceForegroundState = copy(bridgeOnline = online)

    fun withProactive(message: String, key: String): VoiceForegroundState =
        copy(proactiveMessage = message.trim(), proactiveKey = key.trim())
}

data class VoiceForegroundPresentation(
    val title: String,
    val status: String,
    val detail: String,
    val text: String,
    val running: Boolean,
    val listening: Boolean,
    val speaking: Boolean,
    val stopActionLabel: String?
)

object VoiceForegroundFormatter {
    fun present(state: VoiceForegroundState): VoiceForegroundPresentation {
        val title = if (state.serviceAlive) "PhoneBridge 后台存活" else "PhoneBridge 后台未运行"
        val status = when {
            !state.microphonePermissionGranted -> "已停止"
            state.phase == VoiceForegroundPhase.PREPARING -> "正在准备监听"
            state.phase == VoiceForegroundPhase.LISTENING -> "麦克风持续监听中"
            state.phase == VoiceForegroundPhase.PROCESSING -> "语音处理中"
            state.phase == VoiceForegroundPhase.SPEAKING -> "正在播报回复"
            state.phase == VoiceForegroundPhase.PAUSED -> "已暂停"
            else -> "已停止"
        }
        val parts = mutableListOf<String>()
        parts += if (state.bridgeOnline) "节点在线" else "节点离线"
        if (state.detail.isNotBlank()) {
            parts += shorten(state.detail, 96)
        }
        proactiveSnippet(state.proactiveMessage, state.proactiveKey)?.let(parts::add)
        val detail = parts.joinToString(" · ")
        return VoiceForegroundPresentation(
            title = title,
            status = status,
            detail = detail,
            text = if (detail.isBlank()) status else "$status · $detail",
            running = state.running,
            listening = state.listening,
            speaking = state.speaking,
            stopActionLabel = if (state.running) "停止监听" else null
        )
    }

    fun proactiveTitle(key: String): String {
        val cleanKey = key.trim()
        return if (cleanKey.isEmpty()) {
            "Mote 主动提醒"
        } else {
            "Mote 主动提醒 · ${shorten(cleanKey, 24)}"
        }
    }

    fun proactiveText(message: String, key: String): String {
        val cleanMessage = message.trim()
        val cleanKey = key.trim()
        if (cleanMessage.isEmpty()) return cleanKey
        return if (cleanKey.isEmpty() || cleanMessage.contains(cleanKey, ignoreCase = true)) {
            cleanMessage
        } else {
            "${shorten(cleanKey, 24)} · $cleanMessage"
        }
    }

    private fun proactiveSnippet(message: String, key: String): String? {
        val text = proactiveText(message, key).trim()
        return text.takeIf { it.isNotEmpty() }?.let { "提醒 ${shorten(it, 64)}" }
    }

    private fun shorten(text: String, limit: Int): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= limit) return normalized
        return normalized.take(limit - 1).trimEnd() + "…"
    }
}
