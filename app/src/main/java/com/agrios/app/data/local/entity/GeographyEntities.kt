package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "geography_states",
    indices = [Index(value = ["lgd_code"], unique = true)]
)
data class GeographyStateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lgd_code") val lgdCode: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "aliases") val aliases: String? = null, // JSON array
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "geography_districts",
    indices = [
        Index(value = ["lgd_code"], unique = true),
        Index(value = ["state_id"])
    ]
)
data class GeographyDistrictEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lgd_code") val lgdCode: String,
    @ColumnInfo(name = "state_id") val stateId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "aliases") val aliases: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "geography_blocks",
    indices = [
        Index(value = ["lgd_code"], unique = true),
        Index(value = ["district_id"])
    ]
)
data class GeographyBlockEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lgd_code") val lgdCode: String,
    @ColumnInfo(name = "district_id") val districtId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "aliases") val aliases: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(
    tableName = "geography_villages",
    indices = [
        Index(value = ["lgd_code"], unique = true),
        Index(value = ["block_id"]),
        Index(value = ["district_id"])
    ]
)
data class GeographyVillageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "lgd_code") val lgdCode: String,
    @ColumnInfo(name = "block_id") val blockId: String,
    @ColumnInfo(name = "district_id") val districtId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "pin_codes") val pinCodes: String? = null, // JSON array
    @ColumnInfo(name = "aliases") val aliases: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
)

@Entity(tableName = "cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "server_version") val serverVersion: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long, // epoch millis
    @ColumnInfo(name = "record_count") val recordCount: Int = 0,
)
