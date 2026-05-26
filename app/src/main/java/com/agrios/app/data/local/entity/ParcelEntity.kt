package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "parcels_local",
    indices = [
        Index(value = ["farmer_id"]),
        Index(value = ["village_id"]),
        Index(value = ["sync_status"])
    ]
)
data class ParcelEntity(
    @PrimaryKey val id: String, // Client-generated UUID
    @ColumnInfo(name = "farmer_id") val farmerId: String,
    @ColumnInfo(name = "village_id") val villageId: String,
    @ColumnInfo(name = "village_name") val villageName: String? = null,
    @ColumnInfo(name = "reported_area") val reportedArea: Double,
    @ColumnInfo(name = "reported_area_unit") val reportedAreaUnit: String, // BIGHA, ACRE, HECTARE, etc.
    @ColumnInfo(name = "area_hectares") val areaHectares: Double? = null, // Converted canonical
    @ColumnInfo(name = "geometry_source") val geometrySource: String = "NONE", // NONE, PIN_DROP, GPS_WALK
    @ColumnInfo(name = "gps_lat") val gpsLat: Double? = null,
    @ColumnInfo(name = "gps_lng") val gpsLng: Double? = null,
    @ColumnInfo(name = "ownership_type") val ownershipType: String = "OWNED",
    @ColumnInfo(name = "irrigation_type") val irrigationType: String? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING.name,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "actor_id") val actorId: String,
)
