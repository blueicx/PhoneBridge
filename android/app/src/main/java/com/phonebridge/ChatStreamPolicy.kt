package com.phonebridge

object ChatStreamPolicy {
    fun shouldRenderDelta(
        requestId: String,
        activeRequestId: String,
        cancelledRequestIds: Set<String>
    ): Boolean {
        if (requestId.isBlank() || requestId in cancelledRequestIds) return false
        return activeRequestId.isBlank() || requestId == activeRequestId
    }
}
