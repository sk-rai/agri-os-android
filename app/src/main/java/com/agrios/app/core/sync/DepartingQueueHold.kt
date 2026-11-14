package com.agrios.app.core.sync

import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.dao.SyncQueueDao
import com.agrios.app.data.local.entity.AuthStateEntity

/** Caller supplies the Room transaction; never derives ownership from a later login. */
internal suspend fun holdDepartingQueueRows(queue: SyncQueueDao, departing: AuthStateEntity) {
    val tenant = departing.tenantId?.takeIf { it.isNotBlank() } ?: return
    val user = departing.userId?.takeIf { it.isNotBlank() } ?: return
    val expected = queue.countDepartingInFlight(tenant, user)
    check(queue.holdDepartingInFlight(tenant, user) == expected) { "LOCAL_SESSION_HOLD_NOT_CONFIRMED" }
}

/** Ordering logic only. Production invokes it inside one Room transaction. */
internal suspend fun invalidateSessionWithQueueHold(auth: AuthDao, queue: SyncQueueDao, expectedJwt: String): Boolean {
    val departing = auth.getAuthState()
    if (departing?.isAuthenticated != true || departing.jwt != expectedJwt) return false
    holdDepartingQueueRows(queue, departing)
    check(auth.invalidateSession(expectedJwt)) { "LOCAL_SESSION_HOLD_NOT_CONFIRMED" }
    return true
}
