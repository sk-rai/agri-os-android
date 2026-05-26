package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["sync_status"]),
        Index(value = ["priority", "created_at"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id") val eventId: String, // Client-generated UUID
    @ColumnInfo(name = "entity_type") val entityType: String, // FARMER, PARCEL, CROP_CYCLE, etc.
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "operation") val operation: String, // CREATE, UPDATE
    @ColumnInfo(name = "payload") val payload: String, // JSON serialized entity
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING.name,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 10,
    @ColumnInfo(name = "next_retry_after") val nextRetryAfter: Long? = null, // epoch millis
    @ColumnInfo(name = "priority") val priority: String = SyncPriority.MEDIUM.name,
    @ColumnInfo(name = "dependency_ids") val dependencyIds: String? = null, // comma-separated
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long, // epoch millis
)

enum class SyncStatus {
    PENDING,
    SYNCING,
    CONFLICTED,
    FAILED,
    SYNCED
}

enum class SyncPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

enum class EntityType {
    FARMER,
    PARCEL,
    CROP_CYCLE,
    STAGE_INSTANCE,
    CROP_ACTIVITY
}
