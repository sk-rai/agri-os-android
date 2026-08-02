package com.agrios.app.core.sync

import android.util.Log
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.SyncBatchRequestDto
import com.agrios.app.data.remote.dto.SyncEventDto
import com.agrios.app.core.util.VillageIdUtil
import com.agrios.app.data.repository.GeometryRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

/**
 * SyncManager: Orchestrates offline queue processing.
 * - Exponential backoff (30s → 60s → 2min → ... → 24h cap)
 * - Max 10 retries then FAILED (dead letter)
 * - Dependency ordering (parent must sync before child)
 * - Idempotent (same event_id submitted twice = safe)
 */
class SyncManager(
    private val syncQueueDao: SyncQueueDao,
    private val api: AgriOsApi,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val BATCH_SIZE = 100
        private const val INITIAL_BACKOFF_MS = 30_000L // 30 seconds
        private const val MAX_BACKOFF_MS = 86_400_000L // 24 hours
    }

    /**
     * Process pending sync queue. Call from WorkManager or on connectivity restore.
     * Returns number of items successfully synced.
     */
    suspend fun processQueue(): SyncResult {
        val now = System.currentTimeMillis()
        var pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE)

        if (pending.isEmpty()) {
            return SyncResult(0, 0, 0)
        }

        val normalizedCount = normalizeInvalidCropStageEntityIds(pending)
        if (normalizedCount > 0) {
            Log.d(TAG, "Normalized $normalizedCount pending crop_stage entity IDs before sync")
            pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE)
        }

        Log.d(TAG, "Processing ${pending.size} pending items")
        pending.forEach { item ->
            Log.d(TAG, "  Queue item: eventId=${item.eventId}, entityType=${item.entityType}, entityId=${item.entityId}, deps=${item.dependencyIds}, status=${item.syncStatus}, retries=${item.retryCount}")
        }

        // Filter by dependencies: skip items whose parents haven't synced
        val eligible = filterByDependencies(pending)
        if (eligible.isEmpty()) {
            Log.d(TAG, "All pending items blocked by unsynced dependencies")
            return SyncResult(0, 0, 0)
        }

        val geometryItems = eligible.filter { it.entityType == GeometryRepository.ENTITY_TYPE_PARCEL_GEOMETRY }
        val syncEventItems = eligible.filterNot { it.entityType == GeometryRepository.ENTITY_TYPE_PARCEL_GEOMETRY }
        val geometryResult = processParcelGeometryItems(geometryItems)

        if (syncEventItems.isEmpty()) {
            return geometryResult
        }

        // Build batch payload
        val events = syncEventItems.map { item ->
            val payloadMap: MutableMap<String, Any?> = gson.fromJson(
                item.payload,
                object : TypeToken<MutableMap<String, Any?>>() {}.type
            )
            val metadata = payloadMap.remove("_sync_metadata") as? Map<String, Any?>

            // Sanitize village_id: never send non-UUID strings to backend
            val villageId = payloadMap["village_id"] as? String
            if (villageId != null && !VillageIdUtil.isValidUuid(villageId)) {
                Log.w(TAG, "Sanitizing invalid village_id='$villageId' → null (extracting manual name)")
                val manualName = VillageIdUtil.extractManualVillageName(villageId)
                    ?: payloadMap["village_name_manual"] as? String
                payloadMap["village_id"] = null
                if (manualName != null && payloadMap["village_name_manual"] == null) {
                    payloadMap["village_name_manual"] = manualName
                }
            }

            SyncEventDto(
                eventId = item.eventId,
                entityType = item.entityType,
                entityId = item.entityId,
                operation = item.operation,
                payload = payloadMap,
                version = item.version,
                dependencyIds = item.dependencyIds?.split(",")?.filter { it.isNotBlank() },
                metadata = metadata
            )
        }

        Log.d(TAG, "Sending ${events.size} events to POST /sync/events")
        events.forEach { event ->
            Log.d(TAG, "  → eventId=${event.eventId}, type=${event.entityType}, op=${event.operation}")
            Log.d(TAG, "    payload=${gson.toJson(event.payload)}")
        }

        return try {
            // Mark as syncing
            syncEventItems.forEach { syncQueueDao.updateStatus(it.eventId, SyncStatus.SYNCING.name) }

            // Send batch to server
            val response = api.syncEvents(SyncBatchRequestDto(events))

            if (response.isSuccessful) {
                val body = response.body()!!
                Log.d(TAG, "✅ Server response: accepted=${body.accepted.size}, conflicts=${body.conflicts.size}, failed=${body.failed.size}")
                handleSyncResponse(body.accepted, body.conflicts, body.failed, syncEventItems)
                val result = SyncResult(
                    accepted = body.accepted.size,
                    conflicts = body.conflicts.size,
                    failed = body.failed.size
                )
                // If items were accepted, immediately process again to pick up
                // any newly unblocked dependents (e.g. parcels waiting on farmer)
                if (result.accepted > 0) {
                    Log.d(TAG, "Items accepted — running follow-up pass for unblocked dependents")
                    val followUp = processQueue()
                    return SyncResult(
                        accepted = geometryResult.accepted + result.accepted + followUp.accepted,
                        conflicts = geometryResult.conflicts + result.conflicts + followUp.conflicts,
                        failed = geometryResult.failed + result.failed + followUp.failed
                    )
                }
                SyncResult(
                    accepted = geometryResult.accepted + result.accepted,
                    conflicts = geometryResult.conflicts + result.conflicts,
                    failed = geometryResult.failed + result.failed
                )
            } else {
                // Server error — log full details for debugging
                val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                Log.e(TAG, "❌ Server rejected sync: HTTP ${response.code()} ${response.message()}")
                Log.e(TAG, "❌ Response body: $errorBody")
                Log.e(TAG, "Request had ${events.size} events for entities: ${syncEventItems.map { "${it.entityType}:${it.entityId}" }}")
                markBatchForRetry(syncEventItems, "HTTP ${response.code()}: ${errorBody ?: response.message()}")
                SyncResult(geometryResult.accepted, geometryResult.conflicts, geometryResult.failed + syncEventItems.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync network error: ${e.javaClass.simpleName}: ${e.message}", e)
            markBatchForRetry(syncEventItems, "${e.javaClass.simpleName}: ${e.message}")
            SyncResult(geometryResult.accepted, geometryResult.conflicts, geometryResult.failed + syncEventItems.size)
        }
    }

    /**
     * Filter items by dependency chain.
     * An item is eligible if:
     * - It has no dependencies, OR
     * - All its dependency entity_ids have been SYNCED (no longer in queue as non-SYNCED)
     *
     * dependencyIds stores the entityId of the parent (e.g., parcel depends on farmer entityId).
     * We check if any queue item with that entityId is still unsynced.
     */
    private suspend fun processParcelGeometryItems(items: List<SyncQueueEntity>): SyncResult {
        if (items.isEmpty()) return SyncResult(0, 0, 0)
        var accepted = 0
        var failed = 0
        items.forEach { item ->
            try {
                syncQueueDao.updateStatus(item.eventId, SyncStatus.SYNCING.name)
                val result = GeometryRepository.resultFromQueuePayload(item.payload)
                val response = api.updateParcelGeometry(item.entityId, GeometryRepository.requestFromResult(result))
                if (response.isSuccessful) {
                    syncQueueDao.markSynced(item.eventId)
                    accepted++
                    Log.d(TAG, "Parcel geometry synced: parcelId=${item.entityId}, source=${result.geometrySource}")
                } else {
                    val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                    val error = "HTTP ${response.code()}: ${errorBody ?: response.message()}"
                    if (response.code() in 400..499) {
                        syncQueueDao.markFailed(item.eventId, error)
                    } else {
                        markBatchForRetry(listOf(item), error)
                    }
                    failed++
                    Log.e(TAG, "Parcel geometry sync failed: parcelId=${item.entityId}, $error")
                }
            } catch (e: Exception) {
                markBatchForRetry(listOf(item), "${e.javaClass.simpleName}: ${e.message}")
                failed++
                Log.e(TAG, "Parcel geometry sync error: parcelId=${item.entityId}, ${e.message}", e)
            }
        }
        return SyncResult(accepted, 0, failed)
    }

    private suspend fun filterByDependencies(items: List<SyncQueueEntity>): List<SyncQueueEntity> {
        // Get ALL items in queue (not just pending) to check dependency status
        val allItems = syncQueueDao.getAllForDependencyCheck()

        val eligible = mutableListOf<SyncQueueEntity>()
        for (item in items) {
            if (item.dependencyIds.isNullOrBlank()) {
                eligible.add(item)
                continue
            }
            val depIds = item.dependencyIds.split(",").filter { it.isNotBlank() }
            // Check if all dependencies are synced.
            // A dependency can be an event_id or an entity_id. If the dependency is
            // not present locally, let the backend decide whether it is committed
            // server-side or should return DEPENDENCY_MISSING.
            val unsyncedDeps = depIds.filter { depId ->
                val depItems = allItems.filter { it.entityId == depId || it.eventId == depId }
                depItems.isNotEmpty() && depItems.none { it.syncStatus == SyncStatus.SYNCED.name }
            }
            if (unsyncedDeps.isEmpty()) {
                eligible.add(item)
            } else {
                Log.d(TAG, "Skipping ${item.eventId}: waiting on ${unsyncedDeps.size} dependencies (depIds=$depIds)")
            }
        }
        return eligible
    }

    private suspend fun normalizeInvalidCropStageEntityIds(items: List<SyncQueueEntity>): Int {
        var fixedCount = 0
        items
            .filter { it.entityType == "crop_stage" }
            .filter { runCatching { UUID.fromString(it.entityId) }.isFailure }
            .forEach { item ->
                val fixedEntityId = UUID.randomUUID().toString()
                syncQueueDao.updateEntityId(item.eventId, fixedEntityId)
                fixedCount++
                Log.d(TAG, "Fixed pending crop_stage entity_id for ${item.eventId}: '${item.entityId}' -> '$fixedEntityId'")
            }
        return fixedCount
    }

    private suspend fun handleSyncResponse(
        accepted: List<String>,
        conflicts: List<com.agrios.app.data.remote.dto.SyncConflictDto>,
        failed: List<com.agrios.app.data.remote.dto.SyncFailedDto>,
        items: List<SyncQueueEntity>
    ) {
        // Mark accepted
        accepted.forEach { eventId ->
            syncQueueDao.markSynced(eventId)
            Log.d(TAG, "✅ Synced: $eventId")
        }

        // Mark conflicts
        conflicts.forEach { conflict ->
            syncQueueDao.markConflicted(conflict.eventId, gson.toJson(conflict))
            Log.w(TAG, "⚠️ Conflict: ${conflict.eventId} - ${conflict.conflictType}")
        }

        // Handle failures
        failed.forEach { failure ->
            Log.e(TAG, "Server failed event: ${failure.eventId}, code=${failure.errorCode}, detailCode=${failure.detailCode}, message=${failure.message}, retryable=${failure.retryable}")
            val item = items.find { it.eventId == failure.eventId }
            if (item != null) {
                val errorDetail = listOfNotNull(failure.errorCode, failure.detailCode, failure.message)
                    .joinToString(": ")
                if (failure.isStaleLocalContextFailure()) {
                    syncQueueDao.markFailed(failure.eventId, gson.toJson(failure))
                    Log.w(TAG, "Stale local context detected for ${failure.eventId}; refresh profile/parcels and rebuild local draft")
                } else if (failure.retryable && item.retryCount < item.maxRetries) {
                    val nextRetry = calculateNextRetry(item.retryCount + 1)
                    syncQueueDao.markForRetry(failure.eventId, nextRetry, errorDetail)
                    Log.d(TAG, "Retry scheduled: ${failure.eventId} (attempt ${item.retryCount + 1})")
                } else {
                    syncQueueDao.markFailed(failure.eventId, errorDetail)
                    Log.e(TAG, "Failed permanently: ${failure.eventId} - $errorDetail")
                }
            }
        }
    }

    private fun com.agrios.app.data.remote.dto.SyncFailedDto.isStaleLocalContextFailure(): Boolean {
        return errorCode == "MATERIALIZATION_FAILED" && detailCode in setOf(
            "PARCEL_FARMER_MISMATCH",
            "PARCEL_PROJECT_MISMATCH",
            "INVALID_PARCEL_FOR_FARMER",
            "INVALID_FARMER_FOR_TENANT",
            "INVALID_PROJECT_FOR_TENANT"
        )
    }
    private suspend fun markBatchForRetry(items: List<SyncQueueEntity>, error: String) {
        items.forEach { item ->
            if (item.retryCount < item.maxRetries) {
                val nextRetry = calculateNextRetry(item.retryCount + 1)
                syncQueueDao.markForRetry(item.eventId, nextRetry, error)
            } else {
                syncQueueDao.markFailed(item.eventId, "MAX_RETRIES: $error")
            }
        }
    }

    private fun calculateNextRetry(retryCount: Int): Long {
        val delayMs = INITIAL_BACKOFF_MS * 2.0.pow(retryCount.toDouble()).toLong()
        val capped = min(delayMs, MAX_BACKOFF_MS)
        val jitter = (0..5000).random() // 0-5 seconds jitter
        return System.currentTimeMillis() + capped + jitter
    }

    data class SyncResult(
        val accepted: Int,
        val conflicts: Int,
        val failed: Int
    ) {
        val total get() = accepted + conflicts + failed
        val isSuccess get() = conflicts == 0 && failed == 0
    }

    /**
     * Fix and retry all FAILED items by sanitizing their payloads.
     * Fixes invalid village_id values (e.g., "manual_saraimohan" → null + village_name_manual).
     * Returns the number of items reset for retry.
     */
    suspend fun fixAndRetryFailedItems(): Int {
        val failedItems = syncQueueDao.getFailedItems()
        var fixedCount = 0

        for (item in failedItems) {
            if (item.lastError.isStaleLocalContextError()) {
                Log.d(TAG, "Skipping auto-retry for stale local context item ${item.eventId}")
                continue
            }

            val payloadMap: MutableMap<String, Any?> = gson.fromJson(
                item.payload,
                object : TypeToken<MutableMap<String, Any?>>() {}.type
            )

            var modified = false

            // Fix invalid village_id
            val villageId = payloadMap["village_id"] as? String
            if (villageId != null && !VillageIdUtil.isValidUuid(villageId)) {
                val manualName = VillageIdUtil.extractManualVillageName(villageId)
                    ?: payloadMap["village_name_manual"] as? String
                payloadMap["village_id"] = null
                if (manualName != null) {
                    payloadMap["village_name_manual"] = manualName
                }
                modified = true
                Log.d(TAG, "Fixed payload for ${item.eventId}: village_id='$villageId' → null, manual='$manualName'")
            }

            if (modified) {
                syncQueueDao.updatePayload(item.eventId, gson.toJson(payloadMap))
            }

            if (item.entityType == "crop_stage" && runCatching { UUID.fromString(item.entityId) }.isFailure) {
                val fixedEntityId = UUID.randomUUID().toString()
                syncQueueDao.updateEntityId(item.eventId, fixedEntityId)
                Log.d(TAG, "Fixed crop_stage entity_id for ${item.eventId}: '${item.entityId}' -> '$fixedEntityId'")
            }

            syncQueueDao.resetFailedItem(item.eventId)
            fixedCount++
            Log.d(TAG, "Reset failed item ${item.eventId} (${item.entityType}) for retry")
        }

        Log.d(TAG, "Fixed and reset $fixedCount failed items")
        return fixedCount
    }

    private fun String?.isStaleLocalContextError(): Boolean {
        val raw = this.orEmpty()
        if (raw.startsWith("STALE_LOCAL_CONTEXT")) return true
        if (!raw.trimStart().startsWith("{")) return false
        return runCatching {
            val parsed: Map<String, Any?> = gson.fromJson(
                raw,
                object : TypeToken<Map<String, Any?>>() {}.type
            )
            parsed["error_code"] == "MATERIALIZATION_FAILED" &&
                parsed["detail_code"] in setOf(
                    "PARCEL_FARMER_MISMATCH",
                    "PARCEL_PROJECT_MISMATCH",
                    "INVALID_PARCEL_FOR_FARMER",
                    "INVALID_FARMER_FOR_TENANT",
                    "INVALID_PROJECT_FOR_TENANT"
                )
        }.getOrDefault(false)
    }
}
