package com.agrios.app.core.sync

import com.agrios.app.core.auth.SessionExpiryCoordinator
import com.agrios.app.core.auth.SessionLease

/** Runtime request tag only; never serialized or persisted with queue data. */
class QueueDispatchLease internal constructor(
    internal val coordinator: SessionExpiryCoordinator,
    internal val selected: SessionLease
) {
    override fun toString() = "QueueDispatchLease(REDACTED)"
}
