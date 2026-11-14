package com.agrios.app.core.sync

import com.agrios.app.core.auth.SessionExpiryCoordinator
import com.agrios.app.core.auth.SessionLease
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

/**
 * SyncManager: Orchestrates offline queue processing.
 * - Ambiguous submissions retain their claim and are not automatically retried.
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
    private val authDao: com.agrios.app.data.local.dao.AuthDao? = null
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val BATCH_SIZE = 10
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

    /** The tap's exact lease governs admission, including recovery and failed-row repair. */
    internal suspend fun processManualQueue(admission: QueueDispatchLease): SyncResult? = processBoundQueue(admission)

    internal suspend fun processBoundQueue(
        admission: QueueDispatchLease, mayStart: suspend () -> Boolean = { true },
    ): SyncResult? {
        val owner = authDao?.let { SessionExpiryCoordinator.registeredFor(it) } ?: return null
        if (owner !== admission.coordinator) return null
        return singleFlight.runAdmitted(syncQueueDao, admit = { needsRecovery ->
            owner.withCurrentLease(admission.selected) {
                if (!mayStart()) false else {
                    if (needsRecovery) syncQueueDao.recoverInterruptedSubmissions()
                    true
                }
            } == true
        }) {
            owner.withCurrentLease(admission.selected) { fixAndRetryFailedItemsLocked() }
                ?: return@runAdmitted SyncResult(0, 0, 0)
            processQueueLocked(true, admission)
        }
    }

    private suspend fun processQueueLocked(drainFollowUps: Boolean, manualAdmission: QueueDispatchLease? = null): SyncResult {
        val sessionOwner = authDao?.let { SessionExpiryCoordinator.registeredFor(it) }
            ?: return SyncResult(0, 0, 0)
        if (manualAdmission != null && sessionOwner !== manualAdmission.coordinator) return SyncResult(0, 0, 0)
        val manualLease = manualAdmission?.selected
        val lease = manualLease ?: sessionOwner.requestLease() ?: return SyncResult(0, 0, 0)
        val eligible = if (manualLease == null) selectEligible(lease) else
            sessionOwner.withCurrentLease(lease) { selectEligible(lease) } ?: return SyncResult(0, 0, 0)
        if (eligible.isEmpty()) return SyncResult(0, 0, 0)

        val geometryItems = eligible.filter { it.entityType == GeometryRepository.ENTITY_TYPE_PARCEL_GEOMETRY }
        val syncEventItems = eligible.filterNot { it.entityType == GeometryRepository.ENTITY_TYPE_PARCEL_GEOMETRY }
        val geometryResult = if (manualLease == null) processParcelGeometryItems(geometryItems) else
            sessionOwner.withCurrentLease(lease) { processParcelGeometryItems(geometryItems) }
                ?: return SyncResult(0, 0, 0)

        if (syncEventItems.isEmpty()) return geometryResult

        return dispatchSelected(drainFollowUps, manualAdmission, sessionOwner, lease, geometryResult, syncEventItems)
    }

    private suspend fun selectEligible(lease: SessionLease): List<SyncQueueEntity> {
        val now = clock()
        var pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE).filter {
            it.originTenantId == lease.state.tenantId && it.originUserId == lease.state.userId && !it.dispatchReserved
        }

        if (pending.isEmpty()) {
            return emptyList()
        }

        val normalizedCount = normalizeInvalidCropStageEntityIds(pending)
        if (normalizedCount > 0) {
            pending = syncQueueDao.getPendingBatch(now, BATCH_SIZE).filter {
                it.originTenantId == lease.state.tenantId && it.originUserId == lease.state.userId && !it.dispatchReserved
            }
        }


        // Filter by dependencies: skip items whose parents haven't synced
        return filterByDependencies(pending)
    }

    private suspend fun dispatchSelected(
        drainFollowUps: Boolean, manualAdmission: QueueDispatchLease?, sessionOwner: SessionExpiryCoordinator,
        lease: SessionLease, geometryResult: SyncResult, syncEventItems: List<SyncQueueEntity>,
    ): SyncResult {
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
            val claim = UUID.randomUUID().toString()
            val tenant = checkNotNull(lease.state.tenantId)
            val user = checkNotNull(lease.state.userId)
            val reserved = sessionOwner.withCurrentLease(lease) {
                syncQueueDao.reserveBatch(syncEventItems, tenant, user, claim, clock())
                true
            } == true
            if (!reserved) return@withContext SyncResult(0, 0, syncEventItems.size)

            // Send batch to server
            val response = api.syncEvents(SyncBatchRequestDto(events), QueueDispatchLease(sessionOwner, lease))

            // Never let a replacement epoch settle an old reservation. A hold survives restart.
            if (!sessionOwner.isCurrentLease(lease)) {
                response.errorBody()?.close()
                return@withContext SyncResult(0, 0, syncEventItems.size)
            }

            if (response.isSuccessful) {
                val body = response.body()!!
                val expectedIds = syncEventItems.map { it.eventId }.toSet()
                val acknowledgements = body.accepted + body.conflicts.map { it.eventId } + body.failed.map { it.eventId }
                check(acknowledgements.all { it in expectedIds } && acknowledgements.distinct().size == acknowledgements.size)
                val unacknowledged = syncEventItems.filter { it.eventId !in acknowledgements }
                diagnostic(SyncDiagnostic(if (unacknowledged.isEmpty()) SyncOutcome.RESPONSE else SyncOutcome.RESPONSE_UNCERTAIN,
                    body.accepted.size, body.conflicts.size, body.failed.size + unacknowledged.size, response.code()))
                val settled = sessionOwner.withCurrentLease(lease) {
                    syncQueueDao.settleBatch(syncEventItems, tenant, user, claim,
                        responseSettlements(body.accepted, body.conflicts, body.failed, syncEventItems))
                    // Missing responses remain durably reserved, never automatically replayed.
                    true
                } == true
                if (!settled) return@withContext SyncResult(0, 0, syncEventItems.size)
                SyncResult(
                    accepted = body.accepted.size,
                    conflicts = body.conflicts.size,
                    failed = body.failed.size + unacknowledged.size
                )
            } else {
                response.errorBody()?.close()
                diagnostic(SyncDiagnostic(SyncOutcome.HTTP_FAILURE, failed = syncEventItems.size, httpStatus = response.code()))
                if (response.code() == 401 || response.code() == 403) {
                    val reason = if (response.code() == 401) "SESSION_REAUTH_REQUIRED" else "AUTHORIZATION_REVIEW_REQUIRED"
                    sessionOwner.withCurrentLease(lease) {
                        syncQueueDao.settleBatch(syncEventItems, tenant, user, claim, syncEventItems.map {
                            QueueClaimSettlement(it.eventId, QueueClaimOutcome.REJECTED, reason)
                        })
                    }
                }
                SyncResult(0, 0, syncEventItems.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: QueueClaimMismatch) {
            // Reservation rolled back, or settlement did not match. Do not guess or release a claim.
            SyncResult(0, 0, syncEventItems.size)
        } catch (_: Exception) {
            diagnostic(SyncDiagnostic(SyncOutcome.TRANSPORT_UNCERTAIN, failed = syncEventItems.size))
            SyncResult(0, 0, syncEventItems.size)
        } }
        currentCoroutineContext().ensureActive()
        val followUp = if (drainFollowUps && result.accepted > 0) processQueueLocked(true, manualAdmission) else SyncResult(0, 0, 0)
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
        // No approved owner-bound idempotency/reconciliation contract for PATCH.
        // Do not dispatch this path in the foundation slice.
        items.forEach { syncQueueDao.markFailed(it.eventId, "GEOMETRY_OWNER_ADMISSION_REQUIRED") }
        return SyncResult(0, 0, items.size)
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

    private fun responseSettlements(
        accepted: List<String>,
        conflicts: List<com.agrios.app.data.remote.dto.SyncConflictDto>,
        failed: List<com.agrios.app.data.remote.dto.SyncFailedDto>,
        items: List<SyncQueueEntity>
    ): List<QueueClaimSettlement> = buildList {
        accepted.forEach { add(QueueClaimSettlement(it, QueueClaimOutcome.ACCEPTED)) }
        conflicts.forEach { add(QueueClaimSettlement(it.eventId, QueueClaimOutcome.CONFLICT, gson.toJson(it))) }
        val byId = items.associateBy { it.eventId }
        failed.forEach { failure ->
            diagnostic(SyncDiagnostic(SyncOutcome.SERVER_REJECTED, failed = 1,
                failure = safeSyncFailure(failure.errorCode)))
            val item = byId.getValue(failure.eventId)
            val error = when {
                failure.isStaleLocalContextFailure() ->
                    gson.toJson(failure.copy(message = "Refresh local context before retrying."))
                // Retain the existing no-automatic-retry behavior for reserved retryable rows.
                (failure.retryable || failure.errorCode == "DEPENDENCY_MISSING") && item.retryCount < item.maxRetries ->
                    return@forEach
                else -> safeSyncFailure(failure.errorCode).name
            }
            add(QueueClaimSettlement(failure.eventId, QueueClaimOutcome.REJECTED, error))
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
        val failedItems = syncQueueDao.getFailedItems().filterNot { it.isOwnershipHeld() }
        var fixedCount = 0

        for (item in failedItems) {
            // Reauthentication is not permission to replay an already-rejected mutation.
            if (item.lastError in setOf("SESSION_REAUTH_REQUIRED", "AUTHORIZATION_REVIEW_REQUIRED")) continue
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
