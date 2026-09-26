package com.phonebridge

enum class ArCoreAvailabilityState { CHECKING, UNSUPPORTED, INSTALLED, NEEDS_INSTALL }

enum class ArCoreInstallAction {
    WAIT,
    START_SESSION,
    REQUEST_USER_CONFIRMATION,
    CHECK_WITHOUT_PROMPT,
    FALLBACK,
}

/** Pure one-entry install flow; only the first request may show Google's confirmation UI. */
class ArCoreInstallFlow(initialInstallUiPending: Boolean = false) {
    private var installRequestIssued = initialInstallUiPending
    private var installUiPending = initialInstallUiPending
    private var silentResumeCheckConsumed = false

    fun evaluate(availability: ArCoreAvailabilityState): ArCoreInstallAction = when (availability) {
        ArCoreAvailabilityState.CHECKING -> ArCoreInstallAction.WAIT
        ArCoreAvailabilityState.UNSUPPORTED -> finish(ArCoreInstallAction.FALLBACK)
        ArCoreAvailabilityState.INSTALLED -> finish(ArCoreInstallAction.START_SESSION)
        ArCoreAvailabilityState.NEEDS_INSTALL -> {
            if (installRequestIssued || installUiPending) {
                finish(ArCoreInstallAction.FALLBACK)
            } else {
                installRequestIssued = true
                installUiPending = true
                ArCoreInstallAction.REQUEST_USER_CONFIRMATION
            }
        }
    }

    fun onUserInstallResult(installed: Boolean, installUiRequested: Boolean): ArCoreInstallAction = when {
        installed -> finish(ArCoreInstallAction.START_SESSION)
        installUiRequested -> {
            installRequestIssued = true
            installUiPending = true
            ArCoreInstallAction.WAIT
        }
        else -> finish(ArCoreInstallAction.FALLBACK)
    }

    /** Called once after the system install UI returns, including after Activity recreation. */
    fun onActivityResumed(): ArCoreInstallAction {
        if (!installUiPending || silentResumeCheckConsumed) return ArCoreInstallAction.WAIT
        silentResumeCheckConsumed = true
        return ArCoreInstallAction.CHECK_WITHOUT_PROMPT
    }

    fun onSilentInstallResult(installed: Boolean): ArCoreInstallAction =
        finish(if (installed) ArCoreInstallAction.START_SESSION else ArCoreInstallAction.FALLBACK)

    fun hasPendingSystemInstall(): Boolean = installUiPending

    private fun finish(action: ArCoreInstallAction): ArCoreInstallAction {
        installUiPending = false
        return action
    }
}
