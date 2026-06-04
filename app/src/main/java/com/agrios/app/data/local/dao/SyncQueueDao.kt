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

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'CONFLICTED'")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status = 'SYNCED'")
    suspend fun getSyncedCount(): Int

    @Query("SELECT * FROM sync_queue WHERE sync_status = 'CONFLICTED'")
    suspend fun getConflicts(): List<SyncQueueEntity>

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

    /**
     * Get all failed items for review/reset.
     */
    @Query("SELECT * FROM sync_queue WHERE sync_status = 'FAILED'")
    suspend fun getFailedItems(): List<SyncQueueEntity>
}
