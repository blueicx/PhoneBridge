package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ArCoreInstallFlowTest {
    @Test
    fun unsupportedAndInstalledDevicesNeverReceiveAnInstallPrompt() {
        assertEquals(
            ArCoreInstallAction.FALLBACK,
            ArCoreInstallFlow().evaluate(ArCoreAvailabilityState.UNSUPPORTED),
        )
        assertEquals(
            ArCoreInstallAction.START_SESSION,
            ArCoreInstallFlow().evaluate(ArCoreAvailabilityState.INSTALLED),
        )
    }

    @Test
    fun installConfirmationIsRequestedOnceAndResumeUsesOneSilentCheck() {
        val flow = ArCoreInstallFlow()
        assertEquals(ArCoreInstallAction.REQUEST_USER_CONFIRMATION, flow.evaluate(ArCoreAvailabilityState.NEEDS_INSTALL))
        assertEquals(ArCoreInstallAction.WAIT, flow.onUserInstallResult(installed = false, installUiRequested = true))
        assertEquals(ArCoreInstallAction.CHECK_WITHOUT_PROMPT, flow.onActivityResumed())
        assertEquals(ArCoreInstallAction.WAIT, flow.onActivityResumed())
        assertEquals(ArCoreInstallAction.START_SESSION, flow.onSilentInstallResult(installed = true))
    }

    @Test
    fun declinedOrUnfinishedInstallFallsBackWithoutPromptLoop() {
        val declined = ArCoreInstallFlow()
        assertEquals(ArCoreInstallAction.REQUEST_USER_CONFIRMATION, declined.evaluate(ArCoreAvailabilityState.NEEDS_INSTALL))
        assertEquals(ArCoreInstallAction.FALLBACK, declined.onUserInstallResult(installed = false, installUiRequested = false))
        assertEquals(ArCoreInstallAction.FALLBACK, declined.evaluate(ArCoreAvailabilityState.NEEDS_INSTALL))

        val restored = ArCoreInstallFlow(initialInstallUiPending = true)
        assertEquals(ArCoreInstallAction.CHECK_WITHOUT_PROMPT, restored.onActivityResumed())
        assertEquals(ArCoreInstallAction.FALLBACK, restored.onSilentInstallResult(installed = false))
    }
}
