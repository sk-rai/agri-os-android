package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(event: SyncQueueEntity)

    @Query("""
        SELECT * FROM sync_queue 
        WHERE sync_status = 'PENDING' 
        AND (next_retry_after IS NULL OR next_retry_after <= :now)
        AND retry_count < max_retries
        ORDER BY 
            CASE priority 
                WHEN 'CRITICAL' THEN 0 
                WHEN 'HIGH' THEN 1 
                WHEN 'MEDIUM' THEN 2 
                WHEN 'LOW' THEN 3 
            END,
            created_at ASC
        LIMIT :batchSize
    """)
    suspend fun getPendingBatch(now: Long, batchSize: Int = 20): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET sync_status = :status WHERE event_id = :eventId")
    suspend fun updateStatus(eventId: String, status: String)

    @Query("""
        UPDATE sync_queue 
        SET sync_status = 'PENDING', 
            retry_count = retry_count + 1, 
            next_retry_after = :nextRetry, 
            last_error = :error 
        WHERE event_id = :eventId
    """)
    suspend fun markForRetry(eventId: String, nextRetry: Long, error: String?)

    @Query("UPDATE sync_queue SET sync_status = 'FAILED', last_error = :error WHERE event_id = :eventId")
    suspend fun markFailed(eventId: String, error: String?)

    @Query("UPDATE sync_queue SET sync_status = 'SYNCED' WHERE event_id = :eventId")
    suspend fun markSynced(eventId: String)

    @Query("UPDATE sync_queue SET sync_status = 'CONFLICTED', last_error = :conflictData WHERE event_id = :eventId")
    suspend fun markConflicted(eventId: String, conflictData: String?)

    @Query("DELETE FROM sync_queue WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String)

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'CONFLICTED'")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'SYNCED'")
    suspend fun getSyncedCount(): Int

    @Query("SELECT * FROM sync_queue WHERE sync_status = 'CONFLICTED'")
    suspend fun getConflicts(): List<SyncQueueEntity>

    @Query("""
        SELECT * FROM sync_queue
        WHERE sync_status IN ('FAILED', 'CONFLICTED')
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    fun observeAttentionItems(limit: Int = 3): Flow<List<SyncQueueEntity>>

    @Query("""
        DELETE FROM sync_queue
        WHERE payload LIKE '%stale_context_test%'
           OR payload LIKE '%version_mismatch_test%'
           OR payload LIKE '%workflow_invalid_test%'
           OR payload LIKE '%cold_start_persistence_test%'
           OR event_id = '0f7e0a6b-8472-5d6d-8a14-a9d000000111'
           OR event_id = '0f7e0a6b-8472-5d6d-8a14-a9d000000121'
    """)
    suspend fun deleteDynamicSyncTestRows()

    @Query("DELETE FROM sync_queue WHERE sync_status = 'SYNCED' AND created_at < :olderThan")
    suspend fun cleanupSynced(olderThan: Long)

    /**
     * Get all queue items (any status) for dependency resolution.
     * Used by SyncManager to check if a parent entity has been synced.
     */
    @Query("SELECT * FROM sync_queue")
    suspend fun getAllForDependencyCheck(): List<SyncQueueEntity>

    /**
     * Reset a FAILED item back to PENDING for retry (e.g., after payload fix).
     */
    @Query("""
        UPDATE sync_queue 
        SET sync_status = 'PENDING', 
            retry_count = 0, 
            next_retry_after = NULL, 
            last_error = NULL 
        WHERE event_id = :eventId AND sync_status = 'FAILED'
    """)
    suspend fun resetFailedItem(eventId: String)

    /**
     * Update the payload of a queue item (for fixing malformed data before retry).
     */
    @Query("UPDATE sync_queue SET payload = :payload WHERE event_id = :eventId")
    suspend fun updatePayload(eventId: String, payload: String)

    @Query("UPDATE sync_queue SET entity_id = :entityId WHERE event_id = :eventId")
    suspend fun updateEntityId(eventId: String, entityId: String)

    /**
     * Get all failed items for review/reset.
     */
    @Query("SELECT * FROM sync_queue WHERE sync_status = 'FAILED'")
    suspend fun getFailedItems(): List<SyncQueueEntity>

    /**
     * Find the latest queued event for an entity, regardless of status.
     * Used to attach child replay events to the correct parent event_id.
     */
    @Query("""
        SELECT * FROM sync_queue
        WHERE entity_type = :entityType
        AND entity_id = :entityId
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLatestByEntity(entityType: String, entityId: String): SyncQueueEntity?

    /**
     * Find the latest queued crop-stage replay event for a cycle/stage pair.
     * Stage replay entity_id is a UUID for backend validation, so cycle/stage
     * matching lives in the JSON payload.
     */
    @Query("""
        SELECT * FROM sync_queue
        WHERE entity_type = :entityType
        AND payload LIKE '%' || :cycleId || '%'
        AND payload LIKE '%' || :stageCode || '%'
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLatestCropStageEvent(
        entityType: String,
        cycleId: String,
        stageCode: String
    ): SyncQueueEntity?
}
