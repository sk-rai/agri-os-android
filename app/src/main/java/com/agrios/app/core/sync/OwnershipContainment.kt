package com.agrios.app.core.sync

import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.local.entity.SyncQueueEntity

/** Temporary action containment, not a new dispatch/retry policy. */
enum class UnsupportedOwnershipAction { PROFILE_GPS, ACTIVITY, STAGE_TRANSITION, HOME_DIAGNOSTIC }

object OwnershipContainment {
    const val MESSAGE = "Temporarily unavailable: ownership-safe synchronization is not yet integrated. Nothing was saved or sent by this action."
    const val SOURCE_UNAVAILABLE = "This profile is unavailable: original ownership is unknown or does not match this account."
    const val HELD_MESSAGE = "Held for investigation. No automatic retry, discard or recovery is available."
    const val NOT_REMOVED = "Local operation retained. No deletion was confirmed."

    fun block(action: UnsupportedOwnershipAction, show: (String) -> Unit): Boolean {
        // All listed paths deliberately remain closed regardless of the current login.
        when (action) {
            UnsupportedOwnershipAction.PROFILE_GPS, UnsupportedOwnershipAction.ACTIVITY,
            UnsupportedOwnershipAction.STAGE_TRANSITION, UnsupportedOwnershipAction.HOME_DIAGNOSTIC -> show(MESSAGE)
        }
        return true
    }
}

fun QueueOwner.matchesSession(state: AuthStateEntity?): Boolean =
    state?.isAuthenticated == true && !state.reauthRequired && !state.jwt.isNullOrBlank() &&
        tenantId == state.tenantId && userId == state.userId

/** A reserved SYNCED row is settled, not held. SYNCING is held even after an old restart. */
fun SyncQueueEntity.isOwnershipHeld(): Boolean = syncStatus == "SYNCING" || (dispatchReserved && syncStatus != "SYNCED")

fun SyncQueueEntity.canDiscardLocally(): Boolean = !dispatchReserved && !isOwnershipHeld() &&
    !originTenantId.isNullOrBlank() && !originUserId.isNullOrBlank() && syncStatus in setOf("FAILED", "CONFLICTED")

data class OwnerQueueSummary(
    val tenantId: String,
    val userId: String,
    val pending: Int,
    val failed: Int,
    val conflicted: Int,
    val held: Int,
    val synced: Int
) {
    fun visibleTo(owner: QueueOwner?) = owner != null && tenantId == owner.tenantId && userId == owner.userId
    fun message(): String = when {
        held > 0 -> "$held held for investigation. No automatic retry."
        failed > 0 -> "Sync needs review: failed rows remain."
        conflicted > 0 -> "Sync needs review: conflicted rows remain."
        pending > 0 -> "$pending items waiting for synchronization."
        else -> "No outstanding work in this account's last queue snapshot."
    }
}

fun localDiscardMessage(affected: Int): String = if (affected == 1)
    "Local draft removed. Backend state was not changed or reconciled." else OwnershipContainment.NOT_REMOVED
