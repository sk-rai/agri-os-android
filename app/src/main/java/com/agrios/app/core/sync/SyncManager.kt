package com.agrios.app.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * - Single-flight in this process; stable IDs retained for uncertain replay.
 * - Server exactly-once materialization is a separate backend responsibility.
 */
class SyncManager(
    private val syncQueueDao: SyncQueueDao,
    private val api: AgriOsApi,
    private val gson: Gson = Gson(),
    private val singleFlight: QueueSingleFlight = QueueSingleFlight.applicationQueue,
    private val diagnostic: (SyncDiagnostic) -> Unit = { android.util.Log.d("SyncManager", it.toString()) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryJitter: () -> Long = { (0..5000).random().toLong() }
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val BATCH_SIZE = 10
        private const val INITIAL_BACKOFF_MS = 30_000L // 30 seconds
        private const val MAX_BACKOFF_MS = 86_400_000L // 24 hours
    }

    /**
     * Process pending sync queue. Call from WorkManager or on connectivity restore.
     * Returns number of items successfully synced.
     */
    suspend fun processQueue(drainFollowUps: Boolean = true): SyncResult = singleFlight.run(
        syncQueueDao, { syncQueueDao.recoverInterruptedSubmissions() }
    ) { processQueueLocked(drainFollowUps) }

    /** Startup repair and the drain must not race a manual/other worker drain. */
    suspend fun processScheduledQueue(beforeDrain: suspend () -> Unit): SyncResult = singleFlight.run(
        syncQueueDao, { syncQueueDao.recoverInterruptedSubmissions() }
    ) {
        beforeDrain()
        fixAndRetryFailedItemsLocked()
        processQueueLocked(true)
    }

    private suspend fun processQueueLocked(drainFollowUps: Boolean): SyncResult {
        val now = clock()
        var pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE)

        if (pending.isEmpty()) {
            return SyncResult(0, 0, 0)
        }

        val normalizedCount = normalizeInvalidCropStageEntityIds(pending)
        if (normalizedCount > 0) {
            pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE)
        }


        // Filter by dependencies: skip items whose parents haven't synced
        val eligible = filterByDependencies(pending)
        if (eligible.isEmpty()) {
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


        // Keep this bounded HTTP batch and acknowledgement under the lock even
        // when its caller is cancelled. Observe cancellation before another batch.
        val result = withContext(NonCancellable) { try {
            // Mark as syncing
            syncEventItems.forEach { syncQueueDao.updateStatus(it.eventId, SyncStatus.SYNCING.name) }

            // Send batch to server
            val response = api.syncEvents(SyncBatchRequestDto(events))

            if (response.isSuccessful) {
                val body = response.body()!!
                val expectedIds = syncEventItems.map { it.eventId }.toSet()
                val acknowledgements = body.accepted + body.conflicts.map { it.eventId } + body.failed.map { it.eventId }
                check(acknowledgements.all { it in expectedIds } && acknowledgements.distinct().size == acknowledgements.size)
                val unacknowledged = syncEventItems.filter { it.eventId !in acknowledgements }
                diagnostic(SyncDiagnostic(if (unacknowledged.isEmpty()) SyncOutcome.RESPONSE else SyncOutcome.RESPONSE_UNCERTAIN,
                    body.accepted.size, body.conflicts.size, body.failed.size + unacknowledged.size, response.code()))
                handleSyncResponse(body.accepted, body.conflicts, body.failed, syncEventItems)
                markBatchForRetry(unacknowledged, "RESPONSE_UNCERTAIN")
                SyncResult(
                    accepted = body.accepted.size,
                    conflicts = body.conflicts.size,
                    failed = body.failed.size + unacknowledged.size
                )
            } else {
                response.errorBody()?.close()
                diagnostic(SyncDiagnostic(SyncOutcome.HTTP_FAILURE, failed = syncEventItems.size, httpStatus = response.code()))
                markBatchForRetry(syncEventItems, "HTTP ${response.code()}")
                SyncResult(0, 0, syncEventItems.size)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { markBatchForRetry(syncEventItems, "TRANSPORT_UNCERTAIN") }
            throw e
        } catch (_: Exception) {
            diagnostic(SyncDiagnostic(SyncOutcome.TRANSPORT_UNCERTAIN, failed = syncEventItems.size))
            markBatchForRetry(syncEventItems, "TRANSPORT_UNCERTAIN")
            SyncResult(0, 0, syncEventItems.size)
        } }
        currentCoroutineContext().ensureActive()
        val followUp = if (drainFollowUps && result.accepted > 0) processQueueLocked(true) else SyncResult(0, 0, 0)
        return SyncResult(geometryResult.accepted + result.accepted + followUp.accepted,
            geometryResult.conflicts + result.conflicts + followUp.conflicts,
            geometryResult.failed + result.failed + followUp.failed)
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
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { try {
                syncQueueDao.updateStatus(item.eventId, SyncStatus.SYNCING.name)
                val result = GeometryRepository.resultFromQueuePayload(item.payload)
                val response = api.updateParcelGeometry(item.entityId, GeometryRepository.requestFromResult(result))
                if (response.isSuccessful) {
                    syncQueueDao.markSynced(item.eventId)
                    accepted++
                } else {
                    response.errorBody()?.close()
                    val error = "HTTP ${response.code()}"
                    if (response.code() in 400..499) {
                        syncQueueDao.markFailed(item.eventId, error)
                    } else {
                        markBatchForRetry(listOf(item), error)
                    }
                    failed++
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { markBatchForRetry(listOf(item), "TRANSPORT_UNCERTAIN") }
                throw e
            } catch (_: Exception) {
                markBatchForRetry(listOf(item), "TRANSPORT_UNCERTAIN")
                failed++
            } }
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
        }

        // Mark conflicts
        conflicts.forEach { conflict ->
            syncQueueDao.markConflicted(conflict.eventId, gson.toJson(conflict))
        }

        // Handle failures
        failed.forEach { failure ->
            diagnostic(SyncDiagnostic(SyncOutcome.SERVER_REJECTED, failed = 1,
                failure = safeSyncFailure(failure.errorCode)))
            val item = items.find { it.eventId == failure.eventId }
            if (item != null) {
                val errorDetail = safeSyncFailure(failure.errorCode).name
                if (failure.isStaleLocalContextFailure()) {
                    syncQueueDao.markFailed(failure.eventId, gson.toJson(failure.copy(message = "Refresh local context before retrying.")))
                } else if (failure.isRetryableDependencyMissing() && item.retryCount < item.maxRetries) {
                    val nextRetry = calculateNextRetry(item.retryCount + 1)
                    syncQueueDao.markForRetry(failure.eventId, nextRetry, errorDetail)
                } else {
                    syncQueueDao.markFailed(failure.eventId, errorDetail)
                }
            }
        }
    }

    private fun com.agrios.app.data.remote.dto.SyncFailedDto.isRetryableDependencyMissing(): Boolean {
        return retryable || errorCode == "DEPENDENCY_MISSING"
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
        return clock() + capped + retryJitter()
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
    suspend fun fixAndRetryFailedItems(): Int = singleFlight.run(
        syncQueueDao, { syncQueueDao.recoverInterruptedSubmissions() }
    ) { fixAndRetryFailedItemsLocked() }

    private suspend fun fixAndRetryFailedItemsLocked(): Int {
        val failedItems = syncQueueDao.getFailedItems()
        var fixedCount = 0

        for (item in failedItems) {
            if (item.lastError.isStaleLocalContextError()) {
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
            }

            if (modified) {
                syncQueueDao.updatePayload(item.eventId, gson.toJson(payloadMap))
            }

            if (item.entityType == "crop_stage" && runCatching { UUID.fromString(item.entityId) }.isFailure) {
                val fixedEntityId = UUID.randomUUID().toString()
                syncQueueDao.updateEntityId(item.eventId, fixedEntityId)
                modified = true
            }

            // A permanent server failure is not an implicit retry request.
            if (!modified) continue
            syncQueueDao.resetFailedItem(item.eventId)
            fixedCount++
        }

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
