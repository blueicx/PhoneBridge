package com.phonebridge

/** Shared client-side validation for privacy export and deletion requests. */
object PrivacyDataPolicy {
    const val DELETE_CONFIRMATION = "DELETE SELECTED DATA"
    val categories: List<String> = listOf("memories", "conversations", "tasks", "progress")

    fun normalizeCategories(selected: List<String>): List<String>? {
        val requested = selected.map(String::trim).filter(String::isNotEmpty).toSet()
        if (requested.isEmpty() || requested.any { it !in categories }) return null
        return categories.filter { it in requested }
    }

    fun isValidPassphrase(passphrase: String, repeated: String): Boolean =
        passphrase.length in 12..1024 && passphrase == repeated

    fun isValidDeleteConfirmation(confirmation: String, requestId: String): Boolean =
        confirmation == DELETE_CONFIRMATION && requestId.isNotBlank()

    fun pendingDeletionIds(receiptIds: List<String>, appliedIds: Set<String>): List<String> =
        receiptIds.map(String::trim).filter(String::isNotEmpty).distinct().filterNot(appliedIds::contains)
}
