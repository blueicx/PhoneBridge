package com.phonebridge

enum class PrivacyRevisionDisposition {
    ACCEPT,
    MIGRATION_REQUIRED,
    REVISION_REQUIRED,
    STALE
}

object PrivacyRevisionPolicy {
    fun hasCompleteOverview(requiredCategories: List<String>, loadedCategories: Set<String>): Boolean =
        requiredCategories.isNotEmpty() && loadedCategories.containsAll(requiredCategories)

    fun shouldHoldOutbox(privacyOverviewLoaded: Boolean, migrationPending: Boolean): Boolean =
        !privacyOverviewLoaded || migrationPending

    /** New events created after a local migration choice carry the revision the server will assign. */
    fun revisionForNewEvent(currentRevision: Long, migrationRequired: Boolean, localDecision: String?): Long {
        val current = currentRevision.coerceAtLeast(0L)
        return if (migrationRequired && localDecision in setOf("clear", "keep") && current < MAX_SAFE_REVISION) {
            current + 1L
        } else current
    }

    fun check(currentRevision: Long, clientRevision: Long?, migrationRequired: Boolean): PrivacyRevisionDisposition {
        if (migrationRequired) return PrivacyRevisionDisposition.MIGRATION_REQUIRED
        val current = currentRevision.coerceAtLeast(0L)
        if (clientRevision == null) {
            return if (current > 0L) PrivacyRevisionDisposition.REVISION_REQUIRED else PrivacyRevisionDisposition.ACCEPT
        }
        if (clientRevision < 0L || clientRevision < current) return PrivacyRevisionDisposition.STALE
        return PrivacyRevisionDisposition.ACCEPT
    }

    fun canResumeMigration(requiredCategories: Set<String>, decisions: Map<String, String>): Boolean =
        requiredCategories.all { decisions[it] == "clear" || decisions[it] == "keep" }

    fun canApplyRemoteMigrationDecision(localDecision: String?, remoteDecision: String): Boolean =
        remoteDecision in setOf("clear", "keep") && (localDecision == null || localDecision == remoteDecision)

    fun resolveMigrationDecision(
        requiredCategories: Set<String>,
        decisions: Map<String, String>,
        category: String,
        decision: String
    ): Map<String, String>? {
        if (category !in requiredCategories || decision !in setOf("clear", "keep")) return null
        val previous = decisions[category]
        if (previous != null && previous != decision) return null
        return decisions + (category to decision)
    }

    private const val MAX_SAFE_REVISION = 9_007_199_254_740_991L
}
