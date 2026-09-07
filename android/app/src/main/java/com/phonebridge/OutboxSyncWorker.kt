package com.phonebridge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!BridgeLink.isOnline) return Result.retry()
        val repository = WorkspaceRepository.get(applicationContext)
        repository.readyOutbox().forEach { event ->
            if (!BridgeLink.sendWorkspaceEvent(event)) {
                repository.retry(event.eventId, retryCount = 1, localActionState = ActionRunState.FAILED)
                return Result.retry()
            }
        }
        return Result.success()
    }
}
