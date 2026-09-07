package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthStateTest {
    @Test fun lifecycleUsesBoundedRetryAndKeepsSubsystemsIndependent() {
        val connecting = DeviceHealthReducer.reduce(DeviceHealthState(), DeviceHealthEvent.Connecting("ws://127.0.0.1:9503", 100L))
        assertEquals(BridgePhase.CONNECTING, connecting.bridge)
        val online = DeviceHealthReducer.reduce(connecting, DeviceHealthEvent.Opened(200L))
        val telemetry = DeviceHealthReducer.reduce(online, DeviceHealthEvent.Telemetry(true, false, 300L))
        assertEquals(HealthStatus.ACTIVE, telemetry.camera)
        assertEquals(HealthStatus.INACTIVE, telemetry.microphone)
        val degraded = DeviceHealthReducer.reduce(telemetry, DeviceHealthEvent.Outbox(4, 250L, 400L))
        assertEquals(HealthStatus.DEGRADED, degraded.overall)
        assertEquals(4, degraded.outboxPending)
        assertEquals(250L, degraded.lastAckAt)
    }

    @Test fun authorizationFailureIsHardStopAndDoesNotRetry() {
        val state = DeviceHealthReducer.reduce(DeviceHealthState(reconnectAttempt = 2), DeviceHealthEvent.Closed("令牌无效", true, 500L))
        assertEquals(BridgePhase.AUTH_FAILED, state.bridge)
        assertEquals(HealthStatus.EXPIRED, state.authorization)
        assertNull(state.nextRetryAt)
        assertFalse(DeviceHealthReducer.shouldRetry(state.bridge))
    }

    @Test fun retryPolicyIsExponentialAndCapped() {
        assertEquals(1_000L, DeviceHealthReducer.retryDelayMs(1))
        assertEquals(2_000L, DeviceHealthReducer.retryDelayMs(2))
        assertEquals(60_000L, DeviceHealthReducer.retryDelayMs(99))
        assertTrue(DeviceHealthReducer.shouldRetry(BridgePhase.RETRYING))
    }
}
