package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "farmers_local",
    indices = [
        Index(value = ["mobile_number"]),
        Index(value = ["village_id"]),
        Index(value = ["sync_status"])
    ]
)
data class FarmerEntity(
    @PrimaryKey val id: String, // Client-generated UUID
    @ColumnInfo(name = "mobile_number") val mobileNumber: String,
    @ColumnInfo(name = "village_id") val villageId: String,
    @ColumnInfo(name = "village_name") val villageName: String? = null,
    @ColumnInfo(name = "primary_crop_code") val primaryCropCode: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "assistance_mode") val assistanceMode: String = "DEALER_ASSISTED",
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING.name,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "actor_id") val actorId: String,
    @ColumnInfo(name = "gps_lat") val gpsLat: Double? = null,
    @ColumnInfo(name = "gps_lng") val gpsLng: Double? = null,
)
