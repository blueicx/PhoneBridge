package com.phonebridge

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject

class OutboxSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = WorkspaceRepository.get(applicationContext)
        // A missing overview or unresolved legacy decision is a user-action gate,
        // not a transient transport failure. Reconnect schedules the worker again
        // after the server confirms all category decisions.
        if (repository.privacySyncBlocked()) return Result.success()
        if (!BridgeLink.isOnline) return Result.retry()
        repository.expireOutboxLeases(System.currentTimeMillis())
        val client = WorkspaceClient()
        var awaitingWorkspaceAck = 0
        repeat(8) {
            val ready = repository.readyOutbox()
            if (ready.isEmpty()) return@repeat
            ready.forEach { event ->
                val routineEvent = event.type == WorkspaceEventTypes.ROUTINE_EVENT
                if (!repository.claimOutbox(event.eventId, leaseMs = if (routineEvent) 180_000L else 15_000L)) return@forEach
                if (routineEvent) {
                    val envelope = DailyRoutineProtocol.parseActionPayload(event.payload)
                    if (envelope == null || envelope.request.eventId != event.eventId) {
                        repository.completeRoutineAction(
                            eventId = event.eventId,
                            accepted = false,
                            reason = "本机日常事件格式无效"
                        )
                        return@forEach
                    }
                    val access = BridgeLink.httpAccess()
                    val privacyRevision = event.privacyRevisions["routines"]
                    if (access == null || privacyRevision == null) {
                        repository.retry(event.eventId)
                        return@forEach
                    }
                    val body = runCatching {
                        JSONObject(DailyRoutineProtocol.actionPayload(envelope.request, privacyRevision))
                    }.getOrElse {
                        repository.completeRoutineAction(event.eventId, accepted = false, reason = "本机日常事件无法编码")
                        return@forEach
                    }
                    val response = client.execute(
                        serverUrl = access.serverUrl,
                        token = access.token,
                        path = "/api/routines/${Uri.encode(envelope.request.routineId)}/events",
                        method = "POST",
                        payload = body,
                        certificateFingerprint = access.certificateFingerprint
                    ).getOrElse {
                        repository.retry(event.eventId)
                        return Result.retry()
                    }
                    val delivery = DailyRoutineProtocol.classifyHttpResponse(response.statusCode, response.body)
                    when (delivery.kind) {
                        RoutineDeliveryKind.ACCEPTED -> repository.completeRoutineAction(
                            eventId = event.eventId,
                            accepted = true,
                            serverEntry = delivery.entry,
                            resultRevision = delivery.revision
                        )
                        RoutineDeliveryKind.RETRY -> {
                            repository.retry(event.eventId)
                            return Result.retry()
                        }
                        RoutineDeliveryKind.REJECTED -> repository.completeRoutineAction(
                            eventId = event.eventId,
                            accepted = false,
                            reason = delivery.reason,
                            resultRevision = delivery.revision
                        )
                    }
                    return@forEach
                }
                if (!BridgeLink.sendWorkspaceEvent(event)) {
                    repository.retry(event.eventId, localActionState = ActionRunState.FAILED)
                    return Result.retry()
                }
                awaitingWorkspaceAck++
            }
        }
        // Keep one retry alive while ACKs are in flight. The lease prevents a
        // second worker from sending the same event before the timeout.
        return if (awaitingWorkspaceAck > 0) Result.retry() else Result.success()
    }
}
