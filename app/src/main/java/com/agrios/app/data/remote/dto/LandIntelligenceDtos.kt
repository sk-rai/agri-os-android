package com.agrios.app.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

data class LandIntelligenceContextDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("project_id") val projectId: String? = null,
    @SerializedName("geography") val geography: JsonElement? = null,
    @SerializedName("climate_context") val climateContext: ClimateContextDto? = null,
    @SerializedName("crop_suitability") val cropSuitability: CropSuitabilityContextDto? = null,
    @SerializedName("soil_capture_guidance") val soilCaptureGuidance: SoilCaptureGuidanceDto? = null,
    @SerializedName("android_contract") val androidContract: JsonElement? = null
)

data class ClimateContextDto(
    @SerializedName("region_count") val regionCount: Int? = null,
    @SerializedName("mapping_count") val mappingCount: Int? = null,
    @SerializedName("mapping_level") val mappingLevel: String? = null,
    @SerializedName("mapping_precision") val mappingPrecision: String? = null,
    @SerializedName("regions") val regions: List<ClimateRegionDto> = emptyList()
)

data class ClimateRegionDto(
    @SerializedName("region_code") val regionCode: String? = null,
    @SerializedName("region_name") val regionName: String? = null,
    @SerializedName("region_system") val regionSystem: String? = null,
    @SerializedName("rainfall_band_mm") val rainfallBandMm: JsonElement? = null,
    @SerializedName("temperature_band_c") val temperatureBandC: JsonElement? = null,
    @SerializedName("dominant_soil_groups") val dominantSoilGroups: List<String> = emptyList(),
    @SerializedName("irrigation_context") val irrigationContext: JsonElement? = null,
    @SerializedName("confidence") val confidence: String? = null,
    @SerializedName("review_status") val reviewStatus: String? = null
)

data class CropSuitabilityContextDto(
    @SerializedName("input_provided") val inputProvided: Boolean = false,
    @SerializedName("crop_code") val cropCode: String? = null,
    @SerializedName("season_code") val seasonCode: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("confidence") val confidence: String? = null,
    @SerializedName("requires_confirmation") val requiresConfirmation: Boolean = false,
    @SerializedName("warnings") val warnings: List<LandIntelligenceWarningDto> = emptyList(),
    @SerializedName("effective_rules") val effectiveRules: JsonElement? = null
)

data class LandIntelligenceWarningDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("severity") val severity: String? = null
)

data class SoilCaptureGuidanceDto(
    @SerializedName("ask_soil_texture") val askSoilTexture: Boolean? = null,
    @SerializedName("ask_soil_color_or_local_soil_name") val askSoilColorOrLocalSoilName: Boolean? = null,
    @SerializedName("ask_irrigation_source") val askIrrigationSource: Boolean? = null,
    @SerializedName("ask_waterlogging_or_drainage") val askWaterloggingOrDrainage: Boolean? = null,
    @SerializedName("ask_soil_test_values_if_available") val askSoilTestValuesIfAvailable: Boolean? = null,
    @SerializedName("highlight_irrigation_question") val highlightIrrigationQuestion: Boolean? = null,
    @SerializedName("do_not_autofill_soil_type_from_climate_zone") val doNotAutofillSoilTypeFromClimateZone: Boolean? = null,
    @SerializedName("message") val message: String? = null
)
