package com.agrios.app.core.sync

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ActivitySubmissionOutcome { DIRECT_ACCEPTED, QUEUED }

/**
 * Preserves one activity/event identity across the direct attempt and its
 * IOException fallback. HTTP rejection must be represented by a
 * non-IOException by the direct adapter.
 *
 * This is cross-path idempotency, not a UI once-only submission guard.
 */
class ActivitySubmissionCoordinator(
    private val direct: suspend (String, Map<String, Any?>) -> Unit,
    private val findStageDependency: suspend (String, String) -> String?,
    private val enqueue: suspend (String, String, Map<String, Any?>, List<String>) -> Unit,
    private val schedule: () -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val fallbackDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun submit(
        cycleId: String,
        stageCode: String?,
        payload: Map<String, Any?>
    ): ActivitySubmissionOutcome {
        if (cycleId.isBlank()) {
            throw Exception("Missing crop_cycle_id for activity log")
        }
        val activityId = newId()
        val eventId = newId()
        val identifiedPayload = payload.toMutableMap().apply {
            this["activity_id"] = activityId
        }
        try {
            direct(cycleId, identifiedPayload)
        } catch (_: IOException) {
            return withContext(fallbackDispatcher) {
                val offlinePayload = identifiedPayload.toMutableMap()
                offlinePayload.putIfAbsent("crop_cycle_id", cycleId)
                stageCode?.takeIf { it.isNotBlank() }?.let {
                    offlinePayload.putIfAbsent("stage_code", it)
                }
                val dependency = stageCode?.takeIf { it.isNotBlank() }?.let {
                    findStageDependency(cycleId, it)
                }
                enqueue(activityId, eventId, offlinePayload, listOfNotNull(dependency))
                schedule()
                ActivitySubmissionOutcome.QUEUED
            }
        }
        return ActivitySubmissionOutcome.DIRECT_ACCEPTED
    }
}
