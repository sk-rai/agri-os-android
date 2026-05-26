package com.agrios.app.core.sync

import android.util.Log
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.SyncBatchRequestDto
import com.agrios.app.data.remote.dto.SyncEventDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        private const val BATCH_SIZE = 20
        private const val INITIAL_BACKOFF_MS = 30_000L // 30 seconds
        private const val MAX_BACKOFF_MS = 86_400_000L // 24 hours
    }

    /**
     * Process pending sync queue. Call from WorkManager or on connectivity restore.
     * Returns number of items successfully synced.
     */
    suspend fun processQueue(): SyncResult {
        val now = System.currentTimeMillis()
        val pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE)

        if (pending.isEmpty()) {
            return SyncResult(0, 0, 0)
        }

        // Filter by dependencies: skip items whose parents haven't synced
        val eligible = filterByDependencies(pending)
        if (eligible.isEmpty()) {
            Log.d(TAG, "All pending items blocked by unsynced dependencies")
            return SyncResult(0, 0, 0)
        }

        // Build batch payload
        val events = eligible.map { item ->
            val payloadMap: Map<String, Any?> = gson.fromJson(
                item.payload,
                object : TypeToken<Map<String, Any?>>() {}.type
            )
            SyncEventDto(
                eventId = item.eventId,
                entityType = item.entityType,
                entityId = item.entityId,
                operation = item.operation,
                payload = payloadMap,
                version = item.version,
                dependencyIds = item.dependencyIds?.split(",")?.filter { it.isNotBlank() }
            )
        }

        return try {
            // Mark as syncing
            eligible.forEach { syncQueueDao.updateStatus(it.eventId, SyncStatus.SYNCING.name) }

            // Send batch to server
            val response = api.syncEvents(SyncBatchRequestDto(events))

            if (response.isSuccessful) {
                val body = response.body()!!
                handleSyncResponse(body.accepted, body.conflicts, body.failed, eligible)
                SyncResult(
                    accepted = body.accepted.size,
                    conflicts = body.conflicts.size,
                    failed = body.failed.size
                )
            } else {
                // Server error — retry all
                markBatchForRetry(eligible, "HTTP ${response.code()}: ${response.message()}")
                SyncResult(0, 0, eligible.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync network error", e)
            markBatchForRetry(eligible, e.message ?: "Network error")
            SyncResult(0, 0, eligible.size)
        }
    }

    private suspend fun filterByDependencies(items: List<SyncQueueEntity>): List<SyncQueueEntity> {
        val eligible = mutableListOf<SyncQueueEntity>()
        for (item in items) {
            if (item.dependencyIds.isNullOrBlank()) {
                eligible.add(item)
                continue
            }
            val depIds = item.dependencyIds.split(",").filter { it.isNotBlank() }
            // Check if all dependencies are synced
            val unsyncedDeps = depIds.filter { depId ->
                val depItems = syncQueueDao.getPendingBatch(Long.MAX_VALUE, 1000)
                depItems.any { it.entityId == depId && it.syncStatus != SyncStatus.SYNCED.name }
            }
            if (unsyncedDeps.isEmpty()) {
                eligible.add(item)
            } else {
                Log.d(TAG, "Skipping ${item.entityId}: waiting on ${unsyncedDeps.size} dependencies")
            }
        }
        return eligible
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
            val item = items.find { it.eventId == failure.eventId }
            if (item != null) {
                if (failure.retryable && item.retryCount < item.maxRetries) {
                    val nextRetry = calculateNextRetry(item.retryCount + 1)
                    syncQueueDao.markForRetry(failure.eventId, nextRetry, failure.message)
                    Log.d(TAG, "🔄 Retry scheduled: ${failure.eventId} (attempt ${item.retryCount + 1})")
                } else {
                    syncQueueDao.markFailed(failure.eventId, "MAX_RETRIES: ${failure.message}")
                    Log.e(TAG, "❌ Failed permanently: ${failure.eventId}")
                }
            }
        }
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
}
