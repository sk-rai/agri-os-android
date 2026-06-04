package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Soil Type Inference ---
data class SoilInferenceResponseDto(
    @SerializedName("district_name") val districtName: String,
    @SerializedName("inferred_soil_type") val inferredSoilType: String,
    @SerializedName("inferred_soil_type_name") val inferredSoilTypeName: String,
    @SerializedName("typical_ph_range") val typicalPhRange: String?,
    @SerializedName("typical_texture") val typicalTexture: String?,
    @SerializedName("confidence") val confidence: String?,
    @SerializedName("description") val description: String?
)

// --- Create Soil Profile ---
data class CreateSoilProfileDto(
    @SerializedName("parcel_id") val parcelId: String,
    @SerializedName("farmer_id") val farmerId: String,
    @SerializedName("soil_type_code") val soilTypeCode: String? = null,
    @SerializedName("soil_texture") val soilTexture: String? = null,
    @SerializedName("soil_color") val soilColor: String? = null,
    @SerializedName("ph") val ph: Double? = null,
    @SerializedName("nitrogen_n") val nitrogenN: Double? = null,
    @SerializedName("phosphorus_p") val phosphorusP: Double? = null,
    @SerializedName("potassium_k") val potassiumK: Double? = null,
    @SerializedName("sulphur_s") val sulphurS: Double? = null,
    @SerializedName("zinc_zn") val zincZn: Double? = null,
    @SerializedName("iron_fe") val ironFe: Double? = null,
    @SerializedName("copper_cu") val copperCu: Double? = null,
    @SerializedName("manganese_mn") val manganeseMn: Double? = null,
    @SerializedName("boron_b") val boronB: Double? = null,
    @SerializedName("ec") val ec: Double? = null,
    @SerializedName("organic_carbon_oc") val organicCarbonOc: Double? = null,
    @SerializedName("shc_card_number") val shcCardNumber: String? = null,
    @SerializedName("data_source") val dataSource: String = "MANUAL",
    @SerializedName("test_date") val testDate: String? = null
)

// --- Soil Profile Response ---
data class SoilProfileResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("parcel_id") val parcelId: String,
    @SerializedName("farmer_id") val farmerId: String,
    @SerializedName("soil_type_code") val soilTypeCode: String?,
    @SerializedName("soil_texture") val soilTexture: String?,
    @SerializedName("soil_color") val soilColor: String?,
    @SerializedName("ph") val ph: Double?,
    @SerializedName("nitrogen_n") val nitrogenN: Double?,
    @SerializedName("phosphorus_p") val phosphorusP: Double?,
    @SerializedName("potassium_k") val potassiumK: Double?,
    @SerializedName("sulphur_s") val sulphurS: Double?,
    @SerializedName("zinc_zn") val zincZn: Double?,
    @SerializedName("iron_fe") val ironFe: Double?,
    @SerializedName("copper_cu") val copperCu: Double?,
    @SerializedName("manganese_mn") val manganeseMn: Double?,
    @SerializedName("boron_b") val boronB: Double?,
    @SerializedName("ec") val ec: Double?,
    @SerializedName("organic_carbon_oc") val organicCarbonOc: Double?,
    @SerializedName("shc_card_number") val shcCardNumber: String?,
    @SerializedName("data_source") val dataSource: String?,
    @SerializedName("test_date") val testDate: String?,
    @SerializedName("created_at") val createdAt: String?
)
