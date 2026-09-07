package com.phonebridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BridgeLink {
    private const val TAG = "BridgeLink"

    interface Listener {
        fun onBridgeOpen()
        fun onBridgeText(text: String)
        fun onBridgeAudio(data: okio.ByteString)
        fun onBridgeLost(reason: String)
        fun onBridgeState(state: DeviceHealthState) {}
        fun onWorkspaceAck(eventId: String, accepted: Boolean) {}
    }

    interface DeviceListener {
        fun onDeviceCommand(action: String)
        fun onServerStats(stats: JSONObject) {}
        fun onProactive(message: String, key: String) {}
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6L, TimeUnit.SECONDS)
        .pingInterval(20L, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var listenerRef: WeakReference<Listener>? = null
    private var deviceListener: DeviceListener? = null
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var targetUrl: String? = null
    @Volatile private var accessToken: String = ""
    @Volatile private var autoReconnect = false
    @Volatile private var healthState = DeviceHealthState()

    val isOnline: Boolean get() = webSocket != null
    val url: String? get() = targetUrl

    fun setListener(listener: Listener?) {
        listenerRef = listener?.let { WeakReference(it) }
    }

    fun setDeviceListener(listener: DeviceListener?) {
        deviceListener = listener
    }

    fun connect(rawUrl: String, token: String, listener: Listener?) {
        val url = when {
            rawUrl.startsWith("ws://") || rawUrl.startsWith("wss://") -> rawUrl
            rawUrl.contains(":") -> "ws://$rawUrl"
            else -> "ws://$rawUrl:9503"
        }
        targetUrl = url
        accessToken = token
        autoReconnect = true
        // The resident service can reconnect before/after MainActivity. A null
        // listener must not erase the Activity's command receiver.
        if (listener != null) setListener(listener)
        emitHealth(DeviceHealthEvent.Connecting(url, System.currentTimeMillis()))
        open()
    }

    fun disconnect(permanent: Boolean = true) {
        if (permanent) {
            autoReconnect = false
            targetUrl = null
        }
        webSocket?.close(1000, "bye")
        webSocket = null
        if (permanent) emitHealth(DeviceHealthEvent.ManualDisconnect(System.currentTimeMillis()))
    }

    fun send(json: JSONObject): Boolean {
        val socket = webSocket ?: return false
        return socket.send(json.toString())
    }

    fun sendWorkspaceEvent(event: WorkspaceEvent): Boolean {
        val payload = runCatching { JSONObject(event.payload) }.getOrElse { JSONObject().put("value", event.payload) }
        return send(
            JSONObject()
                .put("type", "workspace.event")
                .put("eventId", event.eventId)
                .put("origin", event.origin)
                .put("sequence", event.sequence)
                .put("eventType", event.type)
                .put("payload", payload)
                .put("createdAt", event.createdAt)
                .put("ack", event.ack)
                .put("localActionId", event.localActionId)
                .put("localActionState", event.localActionState)
        )
    }

    fun send(type: Int, sequence: Int, payload: ByteArray): Boolean {
        val socket = webSocket ?: return false
        val packet = ByteArray(payload.size + 2)
        packet[0] = type.toByte()
        packet[1] = sequence.toByte()
        payload.copyInto(packet, 2)
        return socket.send(okio.Buffer().write(packet).readByteString())
    }

    private fun open() {
        val url = targetUrl ?: return
        webSocket?.cancel()
        val request = Request.Builder()
            .url(authorizedUrl(url))
            .header("x-phonebridge-token", accessToken)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                emitHealth(DeviceHealthEvent.Opened(System.currentTimeMillis()))
                listener()?.onBridgeOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener()?.onBridgeAudio(bytes)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchDeviceCommand(text)
                listener()?.onBridgeText(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@BridgeLink.webSocket === webSocket) {
                    this@BridgeLink.webSocket = null
                }
                Log.w(TAG, "bridge failed", t)
                val reason = t.rootMessage()
                val authFailure = response?.code == 401 || response?.code == 403 || reason.contains(Regex("unauthoriz|forbidden|令牌|token", RegexOption.IGNORE_CASE))
                emitHealth(DeviceHealthEvent.Closed(reason, authFailure, System.currentTimeMillis()))
                listener()?.onBridgeLost(reason)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BridgeLink.webSocket === webSocket) {
                    this@BridgeLink.webSocket = null
                }
                Log.i(TAG, "bridge closed code=$code reason=$reason")
                val closeReason = reason.ifBlank { "closed" }
                if (!autoReconnect && code == 1000) {
                    emitHealth(DeviceHealthEvent.ManualDisconnect(System.currentTimeMillis()))
                } else {
                    emitHealth(DeviceHealthEvent.Closed(closeReason, code == 4001 || code == 4401 || code == 4403, System.currentTimeMillis()))
                }
                listener()?.onBridgeLost(closeReason)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || targetUrl.isNullOrBlank() || !DeviceHealthReducer.shouldRetry(healthState.bridge)) return
        val delayMs = DeviceHealthReducer.retryDelayMs(healthState.reconnectAttempt)
        reconnectExecutor.schedule({
            if (!autoReconnect || webSocket != null) return@schedule
            Log.i(TAG, "reconnecting $targetUrl")
            open()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun authorizedUrl(url: String): String {
        if (accessToken.isBlank()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}token=${android.net.Uri.encode(accessToken)}"
    }

    private fun listener(): Listener? = listenerRef?.get()

    private fun emitHealth(event: DeviceHealthEvent) {
        healthState = DeviceHealthReducer.reduce(healthState, event)
        listener()?.onBridgeState(healthState)
    }

    private fun dispatchDeviceCommand(text: String) {
        runCatching {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "device" -> {
                    val action = json.optString("action")
                    if (action.isNotBlank()) deviceListener?.onDeviceCommand(action)
                }
                "snapshot" -> json.optJSONObject("stats")?.let { stats ->
                    deviceListener?.onServerStats(stats)
                }
                "proactive" -> {
                    val message = json.optString("message").trim()
                    if (message.isNotBlank()) deviceListener?.onProactive(message, json.optString("key"))
                }
                "workspace.ack" -> listener()?.onWorkspaceAck(json.optString("eventId"), json.optBoolean("accepted"))
                else -> Unit
            }
            Unit
        }
    }

    private fun Throwable.rootMessage(): String {
        return when {
            message.isNullOrBlank() && cause != null -> (cause ?: this).rootMessage()
            message.isNullOrBlank() -> this::class.java.simpleName
            else -> message!!
        }
    }
}
