package com.agrios.app.core.sync

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import com.agrios.app.core.auth.SessionExpiryCoordinator
import com.agrios.app.core.auth.SessionLease
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.LocallyOwnedRecord

/** Captured before a fresh form is exposed, not reconstructed from a later login.
 * New records may be stamped at creation. Existing records must independently pass requireSource.
 * No token or credential is serialized into a record or a saved form.
 */
internal class NewQueueOrigin private constructor(
    private val coordinator: SessionExpiryCoordinator,
    private val lease: SessionLease
) {
    val owner = QueueOwner(lease.state.tenantId!!, lease.state.userId!!)
    internal fun immediateSyncInput() = SyncWorkInput.forSave(coordinator, lease)
    val sessionEpoch get() = coordinator.sessionEpoch

    fun matchesObservedSession(state: AuthStateEntity?, epoch: Long): Boolean =
        state == lease.state && epoch == lease.epoch

    suspend fun isCurrentSession(): Boolean = coordinator.isCurrentLease(lease)

    /** Publish only short, non-suspending form feedback, never network or scheduling work. */
    suspend fun publishIfCurrent(publish: () -> Unit) {
        try {
            coordinator.withCurrentLease(lease) { publish() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Feedback is best effort; an unavailable auth read cannot undo a committed write.
        }
    }

    fun requireSource(source: LocallyOwnedRecord?) {
        check(source != null && source.originTenantId == owner.tenantId && source.originUserId == owner.userId) {
            "QUEUE_SOURCE_OWNER_MISMATCH"
        }
    }

    fun bindNewEvent(event: SyncQueueEntity): SyncQueueEntity {
        check(event.originTenantId == null && event.originUserId == null && !event.dispatchReserved && event.dispatchClaimId == null) {
            "QUEUE_NEW_EVENT_REQUIRED"
        }
        return event.copy(originTenantId = owner.tenantId, originUserId = owner.userId)
    }

    /** The callback must contain local DB work only. Scheduling happens after commit. */
    suspend fun commit(db: AppDatabase, saveNewRecords: suspend () -> Unit) {
        check(coordinator.owns(db.authDao())) { "QUEUE_OWNER_MISMATCH" }
        commitLocal { db.withTransaction { saveNewRecords() } }
    }

    internal suspend fun commitLocal(transaction: suspend () -> Unit) {
        check(coordinator.withCurrentLease(lease) { transaction(); true } == true) {
            "QUEUE_ORIGIN_SESSION_CHANGED"
        }
    }

    override fun toString() = "NewQueueOrigin(REDACTED)"

    companion object {
        suspend fun capture(authDao: AuthDao): NewQueueOrigin? {
            val coordinator = SessionExpiryCoordinator.registeredFor(authDao) ?: return null
            val lease = coordinator.requestLease() ?: return null
            return NewQueueOrigin(coordinator, lease)
        }
    }
}
