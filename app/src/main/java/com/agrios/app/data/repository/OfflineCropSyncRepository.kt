package com.agrios.app.data.repository

import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.google.gson.Gson
import java.util.UUID

/**
 * Queues backend-contract crop operation replay events for offline sync.
 *
 * Backend entity_type values are intentionally lower snake_case:
 * - crop_cycle
 * - crop_stage
 * - crop_activity
 *
 * Keep these values separate from older local enum names so replay payloads
 * match POST /api/v1/sync/events exactly.
 */
object OfflineCropSyncRepository {
    const val ENTITY_TYPE_CROP_CYCLE = "crop_cycle"
    const val ENTITY_TYPE_CROP_STAGE = "crop_stage"
    const val ENTITY_TYPE_CROP_ACTIVITY = "crop_activity"

    private const val META_KEY = "_sync_metadata"

    suspend fun enqueueCropCycleCreate(
        syncQueueDao: SyncQueueDao,
        cropCycleId: String,
        payload: Map<String, Any?>,
        eventId: String = UUID.randomUUID().toString(),
        dependencyIds: List<String> = emptyList(),
        metadata: Map<String, Any?> = mapOf("android_flow" to "offline_crop_cycle_create"),
        gson: Gson = Gson()
    ): String {
        enqueue(
            syncQueueDao = syncQueueDao,
            eventId = eventId,
            entityType = ENTITY_TYPE_CROP_CYCLE,
            entityId = cropCycleId,
            operation = "CREATE",
            payload = payload,
            dependencyIds = dependencyIds,
            metadata = metadata,
            priority = SyncPriority.HIGH,
            gson = gson
        )
        return eventId
    }

    suspend fun enqueueStageTransition(
        syncQueueDao: SyncQueueDao,
        cropCycleId: String,
        stageCode: String,
        action: String,
        eventId: String = UUID.randomUUID().toString(),
        entityId: String = UUID.randomUUID().toString(),
        dependencyIds: List<String>,
        actualStartDate: String? = null,
        actualEndDate: String? = null,
        gpsLat: Double? = null,
        gpsLng: Double? = null,
        notes: String? = null,
        metadata: Map<String, Any?> = mapOf("android_flow" to "offline_stage_transition"),
        gson: Gson = Gson()
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "crop_cycle_id" to cropCycleId,
            "stage_code" to stageCode,
            "action" to action,
            "actual_start_date" to actualStartDate,
            "actual_end_date" to actualEndDate,
            "gps_lat" to gpsLat,
            "gps_lng" to gpsLng,
            "notes" to notes
        ).filterValues { it != null }

        enqueue(
            syncQueueDao = syncQueueDao,
            eventId = eventId,
            entityType = ENTITY_TYPE_CROP_STAGE,
            entityId = entityId,
            operation = "UPDATE",
            payload = payload,
            dependencyIds = dependencyIds,
            metadata = metadata,
            priority = SyncPriority.HIGH,
            gson = gson
        )
        return eventId
    }

    suspend fun enqueueActivityCreate(
        syncQueueDao: SyncQueueDao,
        activityId: String,
        payload: Map<String, Any?>,
        eventId: String = UUID.randomUUID().toString(),
        dependencyIds: List<String>,
        metadata: Map<String, Any?> = mapOf("android_flow" to "offline_activity_log"),
        gson: Gson = Gson()
    ): String {
        enqueue(
            syncQueueDao = syncQueueDao,
            eventId = eventId,
            entityType = ENTITY_TYPE_CROP_ACTIVITY,
            entityId = activityId,
            operation = "CREATE",
            payload = payload,
            dependencyIds = dependencyIds,
            metadata = metadata,
            priority = SyncPriority.HIGH,
            gson = gson
        )
        return eventId
    }

    private suspend fun enqueue(
        syncQueueDao: SyncQueueDao,
        eventId: String,
        entityType: String,
        entityId: String,
        operation: String,
        payload: Map<String, Any?>,
        dependencyIds: List<String>,
        metadata: Map<String, Any?>,
        priority: SyncPriority,
        gson: Gson
    ) {
        val queuePayload = payload.toMutableMap()
        if (metadata.isNotEmpty()) {
            queuePayload[META_KEY] = metadata
        }
        syncQueueDao.enqueue(
            SyncQueueEntity(
                eventId = eventId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = gson.toJson(queuePayload),
                syncStatus = SyncStatus.PENDING.name,
                priority = priority.name,
                dependencyIds = dependencyIds.joinToString(",").ifBlank { null },
                createdAt = System.currentTimeMillis()
            )
        )
    }
}
