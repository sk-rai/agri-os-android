package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "self_profile_cache_farmer", primaryKeys = ["tenant_id", "user_id", "farmer_id"])
data class SelfProfileCacheFarmerEntity(
    @ColumnInfo(name = "tenant_id") val tenantId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "farmer_id") val farmerId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "village_name_manual") val villageNameManual: String?
)

@Entity(
    tableName = "self_profile_cache_parcel",
    primaryKeys = ["tenant_id", "user_id", "farmer_id", "parcel_id"],
    foreignKeys = [ForeignKey(
        entity = SelfProfileCacheFarmerEntity::class,
        parentColumns = ["tenant_id", "user_id", "farmer_id"],
        childColumns = ["tenant_id", "user_id", "farmer_id"]
    )],
    indices = [Index(value = ["tenant_id", "user_id", "parcel_id"], unique = true)]
)
data class SelfProfileCacheParcelEntity(
    @ColumnInfo(name = "tenant_id") val tenantId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "farmer_id") val farmerId: String,
    @ColumnInfo(name = "parcel_id") val parcelId: String,
    @ColumnInfo(name = "survey_number") val surveyNumber: String?,
    @ColumnInfo(name = "reported_area_unit") val reportedAreaUnit: String?,
    @ColumnInfo(name = "reported_area") val reportedArea: Double?
)

@Entity(
    tableName = "self_profile_cache_soil",
    primaryKeys = ["tenant_id", "user_id", "farmer_id", "parcel_id", "soil_profile_id"],
    foreignKeys = [ForeignKey(
        entity = SelfProfileCacheParcelEntity::class,
        parentColumns = ["tenant_id", "user_id", "farmer_id", "parcel_id"],
        childColumns = ["tenant_id", "user_id", "farmer_id", "parcel_id"]
    )],
    indices = [Index(value = ["tenant_id", "user_id", "soil_profile_id"], unique = true)]
)
data class SelfProfileCacheSoilEntity(
    @ColumnInfo(name = "tenant_id") val tenantId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "farmer_id") val farmerId: String,
    @ColumnInfo(name = "parcel_id") val parcelId: String,
    @ColumnInfo(name = "soil_profile_id") val soilProfileId: String,
    @ColumnInfo(name = "soil_type_code") val soilTypeCode: String?,
    @ColumnInfo(name = "ph") val ph: Double?
)
