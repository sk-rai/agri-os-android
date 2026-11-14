package com.agrios.app.core.sync

import com.agrios.app.data.local.entity.SyncQueueEntity

/** Nonsecret identity only. This does not prove source-record ownership or authenticate a caller. */
data class QueueOwner(val tenantId: String, val userId: String) {
    init { require(tenantId.isNotBlank() && userId.isNotBlank()) { "QUEUE_SOURCE_OWNER_REQUIRED" } }
    fun owns(row: SyncQueueEntity) = row.originTenantId == tenantId && row.originUserId == userId
    override fun toString() = "QueueOwner(REDACTED)"
}

internal fun SyncQueueEntity.requireQueueOwner(): QueueOwner {
    check(!originTenantId.isNullOrBlank() && !originUserId.isNullOrBlank()) { "QUEUE_SOURCE_OWNER_REQUIRED" }
    return QueueOwner(originTenantId!!, originUserId!!)
}

/** Exact retained representation, not semantic JSON equality or a canonical fingerprint. */
internal fun SyncQueueEntity.sameQueueBinding(other: SyncQueueEntity): Boolean =
    originTenantId != null && originUserId != null &&
    originTenantId == other.originTenantId && originUserId == other.originUserId &&
    eventId == other.eventId && entityType == other.entityType && entityId == other.entityId &&
    operation == other.operation && payload == other.payload && version == other.version &&
    dependencyIds == other.dependencyIds
