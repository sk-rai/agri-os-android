package com.agrios.app.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class FarmerProfileHydrationDto(
    @SerializedName("profile_exists") val profileExists: Boolean = false,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("farmer") val farmer: HydratedFarmerDto? = null,
    @SerializedName("parcels") val parcels: List<HydratedParcelDto> = emptyList(),
    @SerializedName("soil_profiles") val soilProfiles: List<HydratedSoilProfileDto> = emptyList(),
    @SerializedName("crop_cycles") val cropCycles: HydratedCropCyclesDto? = null,
    @SerializedName("summary") val summary: HydrationSummaryDto? = null,
    @SerializedName("duplicates") val duplicates: List<JsonElement> = emptyList(),
    @SerializedName("geometry_contract") val geometryContract: Map<String, String>? = null
)

data class HydratedFarmerDto(
    @SerializedName("id") val id: String,
    @SerializedName("mobile_number") val mobileNumber: String,
    @SerializedName("village_id") val villageId: String? = null,
    @SerializedName("village_name_manual") val villageNameManual: String? = null,
    @SerializedName("primary_crop_code") val primaryCropCode: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("father_name") val fatherName: String? = null,
    @SerializedName("age") val age: Int? = null,
    @SerializedName("gender") val gender: String? = null,
    @SerializedName("language_preference") val languagePreference: String? = null,
    @SerializedName("status") val status: String? = null
)

data class HydratedParcelDto(
    @SerializedName("id") val id: String,
    @SerializedName("farmer_id") val farmerId: String,
    @SerializedName("village_id") val villageId: String? = null,
    @SerializedName("village_name") val villageName: String? = null,
    @SerializedName("survey_number") val surveyNumber: String? = null,
    @SerializedName("ownership_type") val ownershipType: String? = null,
    @SerializedName("reported_area") val reportedArea: Double? = null,
    @SerializedName("reported_area_unit") val reportedAreaUnit: String? = null,
    @SerializedName("area_hectares") val areaHectares: Double? = null,
    @SerializedName("geometry_source") val geometrySource: String? = null,
    @SerializedName("centroid_lat") val centroidLat: Double? = null,
    @SerializedName("centroid_lng") val centroidLng: Double? = null,
    @SerializedName("computed_area_hectares") val computedAreaHectares: Double? = null,
    @SerializedName("irrigation_source") val irrigationSource: String? = null,
    @SerializedName("annual_rent") val annualRent: Double? = null,
    @SerializedName("share_percentage") val sharePercentage: Int? = null,
    @SerializedName("sharecrop_percentage") val sharecropPercentage: Int? = null,
    @SerializedName("geojson") val geoJson: JsonElement? = null,
    @SerializedName("geojson_type") val geoJsonType: String? = null
)

data class HydratedSoilProfileDto(
    @SerializedName("id") val id: String,
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
    @SerializedName("data_source") val dataSource: String? = null,
    @SerializedName("test_date") val testDate: String? = null
)

data class HydratedCropCyclesDto(
    @SerializedName("active") val active: List<CropCycleResponseDto> = emptyList(),
    @SerializedName("completed") val completed: List<CropCycleResponseDto> = emptyList(),
    @SerializedName("other") val other: List<CropCycleResponseDto> = emptyList()
)

data class HydrationSummaryDto(
    @SerializedName("parcel_count") val parcelCount: Int = 0,
    @SerializedName("soil_profile_count") val soilProfileCount: Int = 0,
    @SerializedName("active_crop_cycle_count") val activeCropCycleCount: Int = 0,
    @SerializedName("completed_crop_cycle_count") val completedCropCycleCount: Int = 0,
    @SerializedName("archived_crop_cycle_count") val archivedCropCycleCount: Int = 0,
    @SerializedName("duplicate_farmer_count") val duplicateFarmerCount: Int = 0
)
