package com.agrios.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local soil profile record.
 * Each parcel can have multiple soil profiles over time.
 */
@Entity(
    tableName = "soil_profiles_local",
    indices = [
        Index(value = ["parcel_id"]),
        Index(value = ["farmer_id"])
    ]
)
data class SoilProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "parcel_id") val parcelId: String,
    @ColumnInfo(name = "farmer_id") val farmerId: String,
    @ColumnInfo(name = "soil_type_code") val soilTypeCode: String? = null,
    @ColumnInfo(name = "soil_texture") val soilTexture: String? = null,
    @ColumnInfo(name = "soil_color") val soilColor: String? = null,
    @ColumnInfo(name = "ph") val ph: Double? = null,
    @ColumnInfo(name = "nitrogen_n") val nitrogenN: Double? = null,
    @ColumnInfo(name = "phosphorus_p") val phosphorusP: Double? = null,
    @ColumnInfo(name = "potassium_k") val potassiumK: Double? = null,
    @ColumnInfo(name = "sulphur_s") val sulphurS: Double? = null,
    @ColumnInfo(name = "zinc_zn") val zincZn: Double? = null,
    @ColumnInfo(name = "iron_fe") val ironFe: Double? = null,
    @ColumnInfo(name = "copper_cu") val copperCu: Double? = null,
    @ColumnInfo(name = "manganese_mn") val manganeseMn: Double? = null,
    @ColumnInfo(name = "boron_b") val boronB: Double? = null,
    @ColumnInfo(name = "ec") val ec: Double? = null,
    @ColumnInfo(name = "organic_carbon_oc") val organicCarbonOc: Double? = null,
    @ColumnInfo(name = "shc_card_number") val shcCardNumber: String? = null,
    @ColumnInfo(name = "data_source") val dataSource: String = "MANUAL",
    @ColumnInfo(name = "test_date") val testDate: String? = null,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING.name,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "actor_id") val actorId: String
)
