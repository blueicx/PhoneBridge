package com.phonebridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!BridgeLink.isOnline) return Result.retry()
        val repository = WorkspaceRepository.get(applicationContext)
        repeat(8) {
            val ready = repository.readyOutbox()
            if (ready.isEmpty()) return Result.success()
            ready.forEach { event ->
                if (!BridgeLink.sendWorkspaceEvent(event)) {
                    repository.retry(event.eventId, retryCount = 1, localActionState = ActionRunState.FAILED)
                    return Result.retry()
                }
            }
        }
        return if (repository.readyOutbox().isEmpty()) Result.success() else Result.retry()
    }
}
