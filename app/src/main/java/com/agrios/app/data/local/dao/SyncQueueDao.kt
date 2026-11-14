package com.agrios.app.data.local.dao

import androidx.room.*
import com.agrios.app.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow
import com.agrios.app.core.sync.requireQueueOwner
import com.agrios.app.core.sync.QueueClaimMismatch
import com.agrios.app.core.sync.QueueClaimSettlement

@Dao
interface SyncQueueDao {
    @Query("SELECT :tenant AS tenantId, :user AS userId, COALESCE(SUM(CASE WHEN sync_status='PENDING' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS pending, COALESCE(SUM(CASE WHEN sync_status='FAILED' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS failed, COALESCE(SUM(CASE WHEN sync_status='CONFLICTED' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS conflicted, COALESCE(SUM(CASE WHEN sync_status='SYNCING' OR (dispatch_reserved=1 AND sync_status!='SYNCED') THEN 1 ELSE 0 END),0) AS held, COALESCE(SUM(CASE WHEN sync_status='SYNCED' THEN 1 ELSE 0 END),0) AS synced FROM sync_queue WHERE origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user)")
    fun observeOwnerSummary(tenant: String, user: String): Flow<com.agrios.app.core.sync.OwnerQueueSummary>

    @Query("SELECT :tenant AS tenantId, :user AS userId, COALESCE(SUM(CASE WHEN sync_status='PENDING' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS pending, COALESCE(SUM(CASE WHEN sync_status='FAILED' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS failed, COALESCE(SUM(CASE WHEN sync_status='CONFLICTED' AND dispatch_reserved=0 THEN 1 ELSE 0 END),0) AS conflicted, COALESCE(SUM(CASE WHEN sync_status='SYNCING' OR (dispatch_reserved=1 AND sync_status!='SYNCED') THEN 1 ELSE 0 END),0) AS held, COALESCE(SUM(CASE WHEN sync_status='SYNCED' THEN 1 ELSE 0 END),0) AS synced FROM sync_queue WHERE origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user)")
    suspend fun getOwnerSummary(tenant: String, user: String): com.agrios.app.core.sync.OwnerQueueSummary

    @Query("DELETE FROM sync_queue WHERE event_id=:eventId AND dispatch_reserved=0 AND sync_status IN ('FAILED','CONFLICTED') AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id=1 AND a.is_authenticated=1 AND a.reauth_required=0 AND a.tenant_id=:tenant AND a.user_id=:user)")
    suspend fun deleteOwnedUnreserved(eventId: String, tenant: String, user: String): Int

    /** Explicit departing owner: does not depend on authentication still being active. */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE sync_status='SYNCING' AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0")
    suspend fun countDepartingInFlight(tenant: String, user: String): Int

    @Query("UPDATE sync_queue SET dispatch_reserved=1, last_error='SESSION_REAUTH_REQUIRED' WHERE sync_status='SYNCING' AND origin_tenant_id=:tenant AND origin_user_id=:user AND length(trim(origin_tenant_id))>0 AND length(trim(origin_user_id))>0")
    suspend fun holdDepartingInFlight(tenant: String, user: String): Int

    // Called once per queue/process, under the application-wide drain boundary.
    // A terminated process may have lost the response: preserve the original ID.
    @Query("UPDATE sync_queue SET dispatch_reserved = 1 WHERE (sync_status = 'SYNCING') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0")
    suspend fun recoverInterruptedSubmissions()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRetained(event: SyncQueueEntity): Long

    @Query("SELECT COUNT(*) FROM auth_state WHERE id=1 AND is_authenticated=1 AND reauth_required=0 AND tenant_id=:tenant AND user_id=:user")
    suspend fun activeOwner(tenant: String, user: String): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE event_id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND entity_type=:type AND entity_id=:entity AND operation=:operation AND payload=:payload AND version=:version AND dependency_ids IS :dependencies")
    suspend fun exactBinding(id: String, tenant: String, user: String, type: String, entity: String,
        operation: String, payload: String, version: Int, dependencies: String?): Int

    @Transaction
    suspend fun enqueue(event: SyncQueueEntity) {
        check(event.dispatchClaimId == null && !event.dispatchReserved) { "QUEUE_NEW_EVENT_REQUIRED" }
        val owner = event.requireQueueOwner()
        val tenant = owner.tenantId
        val user = owner.userId
        check(activeOwner(tenant, user) == 1) { "QUEUE_OWNER_MISMATCH" }
        if (insertRetained(event) == -1L) {
            check(exactBinding(event.eventId, tenant, user, event.entityType, event.entityId,
                event.operation, event.payload, event.version, event.dependencyIds) == 1) {
                "LOCAL_EVENT_ID_BINDING_MISMATCH"
            }
        }
    }

    @Query("SELECT * FROM sync_queue WHERE event_id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND EXISTS (SELECT 1 FROM auth_state WHERE id=1 AND is_authenticated=1 AND reauth_required=0 AND tenant_id=:tenant AND user_id=:user)")
    suspend fun claimSnapshot(id: String, tenant: String, user: String): SyncQueueEntity?

    @Query("UPDATE sync_queue SET dispatch_reserved=1, sync_status='SYNCING', dispatch_claim_id=:claim WHERE event_id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND dispatch_claim_id IS NULL AND dispatch_reserved=0 AND sync_status='PENDING' AND retry_count < max_retries AND (next_retry_after IS NULL OR next_retry_after <= :now) AND EXISTS (SELECT 1 FROM auth_state WHERE id=1 AND is_authenticated=1 AND reauth_required=0 AND tenant_id=:tenant AND user_id=:user)")
    suspend fun claimPendingRow(id: String, tenant: String, user: String, claim: String, now: Long): Int

    @Transaction
    suspend fun reserveBatch(rows: List<SyncQueueEntity>, tenant: String, user: String, claim: String, now: Long) {
        if (tenant.isBlank() || user.isBlank() || claim.isBlank() || rows.isEmpty() ||
            rows.map { it.eventId }.toSet().size != rows.size) throw QueueClaimMismatch()
        for (row in rows) {
            if (claimSnapshot(row.eventId, tenant, user) != row ||
                claimPendingRow(row.eventId, tenant, user, claim, now) != 1) throw QueueClaimMismatch()
        }
    }

    @Query("UPDATE sync_queue SET sync_status=:status, last_error=CASE WHEN :status='SYNCED' THEN last_error ELSE :error END WHERE :status IN ('SYNCED','CONFLICTED','FAILED') AND event_id=:id AND origin_tenant_id=:tenant AND origin_user_id=:user AND dispatch_claim_id=:claim AND dispatch_reserved=1 AND sync_status='SYNCING' AND EXISTS (SELECT 1 FROM auth_state WHERE id=1 AND is_authenticated=1 AND reauth_required=0 AND tenant_id=:tenant AND user_id=:user)")
    suspend fun settleClaimedRow(id: String, tenant: String, user: String, claim: String, status: String, error: String?): Int

    @Transaction
    suspend fun settleBatch(rows: List<SyncQueueEntity>, tenant: String, user: String, claim: String,
                            outcomes: List<QueueClaimSettlement>) {
        val byId = rows.associateBy { it.eventId }
        if (tenant.isBlank() || user.isBlank() || claim.isBlank() || byId.size != rows.size ||
            outcomes.map { it.eventId }.toSet().size != outcomes.size ||
            outcomes.any { it.eventId !in byId }) throw QueueClaimMismatch()
        for (outcome in outcomes) {
            val expected = byId.getValue(outcome.eventId).copy(
                syncStatus = "SYNCING", dispatchReserved = true, dispatchClaimId = claim)
            if (claimSnapshot(outcome.eventId, tenant, user) != expected ||
                settleClaimedRow(outcome.eventId, tenant, user, claim, outcome.outcome.status, outcome.error) != 1) {
                throw QueueClaimMismatch()
            }
        }
    }

    @Query("""
        SELECT * FROM sync_queue 
        WHERE (sync_status = 'PENDING'
        AND dispatch_reserved = 0 AND dispatch_claim_id IS NULL AND (next_retry_after IS NULL OR next_retry_after <= :now)
        AND retry_count < max_retries) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ORDER BY
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

    @Query("UPDATE sync_queue SET sync_status = 'FAILED', last_error = :error WHERE (event_id = :eventId AND sync_status != 'SYNCED' AND dispatch_reserved=0 AND dispatch_claim_id IS NULL) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    suspend fun markFailed(eventId: String, error: String?)

    @Query("DELETE FROM sync_queue WHERE (event_id = :eventId) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("SELECT COUNT(*) FROM sync_queue WHERE (event_id = :eventId) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    suspend fun countByEventId(eventId: String): Int

    @Query("""
        SELECT COUNT(*) FROM sync_queue
        WHERE (payload LIKE '%' || :payloadNeedle || '%'
          AND sync_status = :status) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) """)
    suspend fun countByPayloadNeedleAndStatus(payloadNeedle: String, status: String): Int

    @Query("""
        SELECT * FROM sync_queue
        WHERE (payload LIKE '%' || :payloadNeedle || '%') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLatestByPayloadNeedle(payloadNeedle: String): SyncQueueEntity?

    @Query("SELECT COUNT(*) FROM sync_queue WHERE (sync_status = 'PENDING' AND dispatch_reserved = 0) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE (sync_status = 'CONFLICTED' AND dispatch_reserved = 0) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE (sync_status = 'FAILED' AND dispatch_reserved = 0) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE (sync_status = 'SYNCED') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    suspend fun getSyncedCount(): Int

    @Query("SELECT * FROM sync_queue WHERE (sync_status = 'CONFLICTED') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    suspend fun getConflicts(): List<SyncQueueEntity>

    @Query("""
        SELECT * FROM sync_queue
        WHERE (sync_status IN ('FAILED', 'CONFLICTED') OR sync_status = 'SYNCING' OR (dispatch_reserved = 1 AND sync_status != 'SYNCED')) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ORDER BY CASE WHEN sync_status = 'SYNCING' OR (dispatch_reserved = 1 AND sync_status != 'SYNCED') THEN 0 ELSE 1 END, created_at DESC
        LIMIT :limit
    """)
    fun observeAttentionItems(limit: Int = 3): Flow<List<SyncQueueEntity>>

    @Query("""
        DELETE FROM sync_queue
        WHERE (payload LIKE '%stale_context_test%'
           OR payload LIKE '%version_mismatch_test%'
           OR payload LIKE '%workflow_invalid_test%'
           OR payload LIKE '%cold_start_persistence_test%'
           OR payload LIKE '%device_restart_persistence_test%'
           OR payload LIKE '%uncertain_result_idempotency_test%'
           OR payload LIKE '%dependency_order_replay_test%'
           OR payload LIKE '%partial_batch_replay_test%'
           OR payload LIKE '%partial_batch_conflict_test%'
           OR payload LIKE '%multi_conflict_pending_drawer_test%'
           OR payload LIKE '%queue_backpressure_test%'
           OR payload LIKE '%interrupted_multibatch_resume_test%'
           OR payload LIKE '%poison_row_backlog_test%'
           OR payload LIKE '%stale_conflict_404_test%'
           OR event_id = '0f7e0a6b-8472-5d6d-8a14-a9d000000111'
           OR event_id = '0f7e0a6b-8472-5d6d-8a14-a9d000000121'
           OR event_id = '0f7e0a6b-8472-5d6d-8a14-a9d000000404') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0""")
    suspend fun deleteDynamicSyncTestRows()

    @Query("DELETE FROM sync_queue WHERE (sync_status = 'SYNCED' AND created_at < :olderThan) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0")
    suspend fun cleanupSynced(olderThan: Long)
    /**
     * Test hook: simulate an uncertain sync result by putting an already-synced
     * queue row back to PENDING without changing event_id/entity_id/payload.
     */
    @Query("""
        UPDATE sync_queue
        SET sync_status = 'PENDING',
            retry_count = retry_count + 1,
            next_retry_after = NULL,
            last_error = :reason
        WHERE (event_id = :eventId AND sync_status = 'SYNCED') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0""")
    suspend fun resetSyncedItemForUncertainRetry(eventId: String, reason: String)
    /**
     * Test hook: make a retryable dependency-missing row eligible immediately
     * after its missing dependency has been queued/committed.
     */
    @Query("""
        UPDATE sync_queue
        SET sync_status = 'PENDING',
            next_retry_after = NULL,
            last_error = NULL
        WHERE (event_id = :eventId AND sync_status = 'PENDING') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0""")
    suspend fun makePendingRetryReadyNow(eventId: String)

    /**
     * Get all queue items (any status) for dependency resolution.
     * Used by SyncManager to check if a parent entity has been synced.
     */
    @Query("SELECT * FROM sync_queue WHERE origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)")
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
        WHERE (event_id = :eventId AND sync_status = 'FAILED') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0""")
    suspend fun resetFailedItem(eventId: String)

    /**
     * Update the payload of a queue item (for fixing malformed data before retry).
     */
    @Query("UPDATE sync_queue SET payload = :payload WHERE (event_id = :eventId) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0")
    suspend fun updatePayload(eventId: String, payload: String)

    @Query("UPDATE sync_queue SET entity_id = :entityId WHERE (event_id = :eventId) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id))  AND dispatch_reserved = 0")
    suspend fun updateEntityId(eventId: String, entityId: String)

    /**
     * Get all failed items for review/reset.
     */
    @Query("SELECT * FROM sync_queue WHERE (sync_status = 'FAILED') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ")
    suspend fun getFailedItems(): List<SyncQueueEntity>

    /**
     * Find the latest queued event for an entity, regardless of status.
     * Used to attach child replay events to the correct parent event_id.
     */
    @Query("""
        SELECT * FROM sync_queue
        WHERE (entity_type = :entityType
        AND entity_id = :entityId) AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ORDER BY created_at DESC
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
        WHERE (entity_type = :entityType
        AND payload LIKE '%' || :cycleId || '%'
        AND payload LIKE '%' || :stageCode || '%') AND (origin_tenant_id IS NOT NULL AND length(trim(origin_tenant_id)) > 0 AND origin_user_id IS NOT NULL AND length(trim(origin_user_id)) > 0 AND EXISTS (SELECT 1 FROM auth_state a WHERE a.id = 1 AND a.is_authenticated = 1 AND a.reauth_required = 0 AND a.tenant_id = sync_queue.origin_tenant_id AND a.user_id = sync_queue.origin_user_id)) ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLatestCropStageEvent(
        entityType: String,
        cycleId: String,
        stageCode: String
    ): SyncQueueEntity?
}
