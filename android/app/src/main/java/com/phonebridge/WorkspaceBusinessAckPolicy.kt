package com.phonebridge

object WorkspaceBusinessAckPolicy {
    fun isAccepted(transportAccepted: Boolean, status: String?, businessStatus: String?): Boolean =
        when (businessStatus?.trim()?.lowercase()) {
            "rejected" -> false
            "accepted", "duplicate" -> true
            else -> transportAccepted && status != "rejected" || status == "duplicate"
        }
}
