package com.phonebridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = WorkspaceRepository.get(applicationContext)
        // A missing overview or unresolved legacy decision is a user-action gate,
        // not a transient transport failure. Reconnect schedules the worker again
        // after the server confirms all category decisions.
        if (repository.privacySyncBlocked()) return Result.success()
        if (!BridgeLink.isOnline) return Result.retry()
        repository.expireOutboxLeases(System.currentTimeMillis())
        var sent = 0
        repeat(8) {
            val ready = repository.readyOutbox()
            if (ready.isEmpty()) return@repeat
            ready.forEach { event ->
                if (!repository.claimOutbox(event.eventId)) return@forEach
                if (!BridgeLink.sendWorkspaceEvent(event)) {
                    repository.retry(event.eventId, localActionState = ActionRunState.FAILED)
                    return Result.retry()
                }
                sent++
            }
        }
        // Keep one retry alive while ACKs are in flight. The lease prevents a
        // second worker from sending the same event before the timeout.
        return if (sent > 0) Result.retry() else Result.success()
    }
}
