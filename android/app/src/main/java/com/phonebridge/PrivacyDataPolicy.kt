package com.phonebridge

import java.util.Locale

data class PrivacyEventClassification(val categories: List<String>)

/** Shared client-side validation for privacy export and deletion requests. */
object PrivacyDataPolicy {
    const val DELETE_CONFIRMATION = "DELETE SELECTED DATA"
    val categories: List<String> = listOf("memories", "conversations", "tasks", "progress", "routines", "goals")

    fun normalizeCategories(selected: List<String>): List<String>? {
        val requested = selected.map(String::trim).filter(String::isNotEmpty).toSet()
        if (requested.isEmpty() || requested.any { it !in categories }) return null
        return categories.filter { it in requested }
    }

    fun isValidPassphrase(passphrase: String, repeated: String): Boolean =
        passphrase.length in 12..1024 && passphrase == repeated

    fun isValidDeleteConfirmation(confirmation: String, requestId: String): Boolean =
        confirmation == DELETE_CONFIRMATION && requestId.isNotBlank()

    /** Returns null for an event with no safe category mapping; callers must quarantine it. */
    fun classifyEvent(type: String, payload: Map<String, Any?>): PrivacyEventClassification? {
        val normalized = type.trim().lowercase(Locale.ROOT)
        if (normalized == WorkspaceEventTypes.MESSAGE || normalized == "chat.message") {
            return PrivacyEventClassification(listOf("conversations"))
        }
        if (normalized.startsWith("mote.") || normalized.startsWith("reality.")) {
            return PrivacyEventClassification(listOf("progress"))
        }
        if (isTaskEvent(normalized)) {
            val records = sequenceOf("task", "attention", "actionRun")
                .mapNotNull { payload[it] as? Map<*, *> }
                .toList() + payload
            val linkedToConversation = records.any { record ->
                val metadata = record["metadata"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                record.stringValue("source").equals("conversation", ignoreCase = true) ||
                    listOf("sessionId", "relatedSessionId", "messageId", "relatedMessageId").any { key ->
                        record.stringValue(key).isNotBlank() || metadata.stringValue(key).isNotBlank()
                    }
            }
            return PrivacyEventClassification(
                if (linkedToConversation) listOf("conversations", "tasks") else listOf("tasks")
            )
        }
        if (normalized in NON_PERSONAL_EVENTS) return PrivacyEventClassification(emptyList())
        // New personal event names must be reviewed and explicitly classified before sending.
        return null
    }

    private fun isTaskEvent(type: String): Boolean =
        type.startsWith("workspace.task") || type == WorkspaceEventTypes.ATTENTION ||
            type.startsWith("${WorkspaceEventTypes.ATTENTION}.") || type == WorkspaceEventTypes.ACTION_RUN ||
            type.startsWith("${WorkspaceEventTypes.ACTION_RUN}.")

    private val NON_PERSONAL_EVENTS = setOf(
        WorkspaceEventTypes.DEVICE_STATE,
        WorkspaceEventTypes.SYNC_STATE,
        WorkspaceEventTypes.POLICY,
        WorkspaceEventTypes.AUTONOMY_APPROVAL
    )

    private fun Map<*, *>.stringValue(key: String): String = this[key]?.toString()?.trim().orEmpty()

    fun pendingDeletionIds(receiptIds: List<String>, appliedIds: Set<String>): List<String> =
        receiptIds.map(String::trim).filter(String::isNotEmpty).distinct().filterNot(appliedIds::contains)
}
