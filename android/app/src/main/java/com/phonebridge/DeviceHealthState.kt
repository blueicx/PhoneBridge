package com.phonebridge

import kotlin.math.min

enum class BridgePhase { DISCONNECTED, CONNECTING, ONLINE, RETRYING, AUTH_FAILED }
enum class HealthStatus { UNKNOWN, INACTIVE, ACTIVE, CONNECTING, ONLINE, DEGRADED, ERROR, EXPIRED, PAUSED }

data class DeviceHealthState(
    val bridge: BridgePhase = BridgePhase.DISCONNECTED,
    val node: HealthStatus = HealthStatus.UNKNOWN,
    val camera: HealthStatus = HealthStatus.INACTIVE,
    val microphone: HealthStatus = HealthStatus.INACTIVE,
    val model: HealthStatus = HealthStatus.UNKNOWN,
    val authorization: HealthStatus = HealthStatus.UNKNOWN,
    val tasks: HealthStatus = HealthStatus.UNKNOWN,
    val automation: HealthStatus = HealthStatus.UNKNOWN,
    val outbox: HealthStatus = HealthStatus.UNKNOWN,
    val outboxPending: Int = 0,
    val reconnectAttempt: Int = 0,
    val nextRetryAt: Long? = null,
    val targetUrl: String? = null,
    val lastError: String? = null,
    val lastAckAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    val overall: HealthStatus
        get() = when {
            bridge == BridgePhase.AUTH_FAILED || authorization == HealthStatus.EXPIRED -> HealthStatus.EXPIRED
            bridge == BridgePhase.CONNECTING || bridge == BridgePhase.RETRYING -> HealthStatus.CONNECTING
            bridge == BridgePhase.ONLINE && (camera == HealthStatus.ERROR || microphone == HealthStatus.ERROR || outbox == HealthStatus.ERROR) -> HealthStatus.ERROR
            bridge == BridgePhase.ONLINE && (outboxPending > 0 || node == HealthStatus.DEGRADED) -> HealthStatus.DEGRADED
            bridge == BridgePhase.ONLINE -> HealthStatus.ONLINE
            else -> HealthStatus.INACTIVE
        }
}

sealed class DeviceHealthEvent {
    data class Connecting(val url: String, val at: Long) : DeviceHealthEvent()
    data class Opened(val at: Long) : DeviceHealthEvent()
    data class Closed(val reason: String, val authFailure: Boolean = false, val at: Long) : DeviceHealthEvent()
    data class ManualDisconnect(val at: Long) : DeviceHealthEvent()
    data class Telemetry(val cameraActive: Boolean, val microphoneActive: Boolean, val at: Long) : DeviceHealthEvent()
    data class Model(val available: Boolean, val at: Long) : DeviceHealthEvent()
    data class Authorization(val status: HealthStatus, val at: Long) : DeviceHealthEvent()
    data class Tasks(val active: Boolean, val failed: Boolean, val at: Long) : DeviceHealthEvent()
    data class Automation(val paused: Boolean, val at: Long) : DeviceHealthEvent()
    data class Outbox(val pending: Int, val lastAckAt: Long?, val at: Long) : DeviceHealthEvent()
}

object DeviceHealthReducer {
    fun reduce(previous: DeviceHealthState, event: DeviceHealthEvent): DeviceHealthState = when (event) {
        is DeviceHealthEvent.Connecting -> previous.copy(bridge = BridgePhase.CONNECTING, node = HealthStatus.CONNECTING, targetUrl = event.url, nextRetryAt = null, lastError = null, updatedAt = event.at)
        is DeviceHealthEvent.Opened -> previous.copy(bridge = BridgePhase.ONLINE, node = HealthStatus.ONLINE, reconnectAttempt = 0, nextRetryAt = null, lastError = null, updatedAt = event.at)
        is DeviceHealthEvent.Closed -> previous.copy(
            bridge = if (event.authFailure) BridgePhase.AUTH_FAILED else BridgePhase.RETRYING,
            node = if (event.authFailure) HealthStatus.EXPIRED else HealthStatus.UNKNOWN,
            authorization = if (event.authFailure) HealthStatus.EXPIRED else previous.authorization,
            reconnectAttempt = if (event.authFailure) previous.reconnectAttempt else previous.reconnectAttempt + 1,
            nextRetryAt = if (event.authFailure) null else event.at + retryDelayMs(previous.reconnectAttempt + 1),
            lastError = event.reason.ifBlank { null }, updatedAt = event.at,
        )
        is DeviceHealthEvent.ManualDisconnect -> previous.copy(bridge = BridgePhase.DISCONNECTED, node = HealthStatus.INACTIVE, reconnectAttempt = 0, nextRetryAt = null, updatedAt = event.at)
        is DeviceHealthEvent.Telemetry -> previous.copy(camera = if (event.cameraActive) HealthStatus.ACTIVE else HealthStatus.INACTIVE, microphone = if (event.microphoneActive) HealthStatus.ACTIVE else HealthStatus.INACTIVE, updatedAt = event.at)
        is DeviceHealthEvent.Model -> previous.copy(model = if (event.available) HealthStatus.ACTIVE else HealthStatus.ERROR, updatedAt = event.at)
        is DeviceHealthEvent.Authorization -> previous.copy(authorization = event.status, updatedAt = event.at)
        is DeviceHealthEvent.Tasks -> previous.copy(tasks = when { event.failed -> HealthStatus.ERROR; event.active -> HealthStatus.ACTIVE; else -> HealthStatus.INACTIVE }, updatedAt = event.at)
        is DeviceHealthEvent.Automation -> previous.copy(automation = if (event.paused) HealthStatus.PAUSED else HealthStatus.ACTIVE, updatedAt = event.at)
        is DeviceHealthEvent.Outbox -> previous.copy(outbox = if (event.pending > 0) HealthStatus.DEGRADED else HealthStatus.ONLINE, outboxPending = event.pending.coerceAtLeast(0), lastAckAt = event.lastAckAt, updatedAt = event.at)
    }

    fun retryDelayMs(attempt: Int): Long = min(60_000L, 1_000L * (1L shl (attempt.coerceIn(1, 7) - 1)))
    fun shouldRetry(phase: BridgePhase): Boolean = phase == BridgePhase.RETRYING
}
