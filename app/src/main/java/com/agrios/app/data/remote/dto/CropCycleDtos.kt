package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Crop Cycle Response (from POST /api/v1/crop-cycles) ---
data class CropCycleResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String = "ACTIVE",
    @SerializedName("crop_code") val cropCode: String,
    @SerializedName("crop_name") val cropName: String? = null,
    @SerializedName("season_code") val seasonCode: String,
    @SerializedName("parcel_id") val parcelId: String? = null,
    @SerializedName("farmer_id") val farmerId: String? = null,
    @SerializedName("planned_sowing_date") val plannedSowingDate: String? = null,
    @SerializedName("expected_harvest_date") val expectedHarvestDate: String? = null,
    @SerializedName("inferred_current_stage") val inferredCurrentStage: String? = null,
    @SerializedName("seed_source") val seedSource: String? = null,
    @SerializedName("variety") val variety: String? = null,
    @SerializedName("stages") val stages: List<CropStageDto> = emptyList(),
    @SerializedName("created_at") val createdAt: String? = null
)

// --- Stage within a crop cycle ---
data class CropStageDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("code") val code: String = "",
    @SerializedName("name") val name: String? = null, // Plain string from cycle response (English only)
    @SerializedName("order") val order: Int = 0,
    @SerializedName("day_offset") val dayOffset: Int = 0,
    @SerializedName("duration_days") val durationDays: Int = 0,
    @SerializedName("expected_start_date") val expectedStartDate: String? = null,
    @SerializedName("expected_end_date") val expectedEndDate: String? = null,
    @SerializedName("status") val status: String = "PENDING",
    @SerializedName("stage_type") val stageType: String? = null,
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("description") val description: String? = null, // English only from cycle response
    @SerializedName("farmer_actions") val farmerActions: List<String>? = null,
    @SerializedName("typical_inputs") val typicalInputs: List<String>? = null,
    @SerializedName("key_observations") val keyObservations: List<String>? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("stage_name") val stageName: String? = null // Alternative field name from backend
) {
    fun getDisplayName(): String = name ?: stageName ?: code
}

// --- Crop Template (from GET /api/v1/crop-cycles/templates/{crop_code}) ---
data class CropTemplateDto(
    @SerializedName("template_id") val templateId: String? = null,
    @SerializedName("crop_code") val cropCode: String,
    @SerializedName("crop_name") val cropName: String? = null,
    @SerializedName("season_code") val seasonCode: String? = null,
    @SerializedName("total_duration_days") val totalDurationDays: Int = 0,
    @SerializedName("crop_group") val cropGroup: String? = null,
    @SerializedName("propagation_method") val propagationMethod: String? = null,
    @SerializedName("has_nursery") val hasNursery: Boolean = false,
    @SerializedName("date_label") val dateLabel: Map<String, String>? = null,
    @SerializedName("staging_system") val stagingSystem: String? = null,
    @SerializedName("stages") val stages: List<TemplateStageDto> = emptyList()
)

// --- Template stage (includes recommended_activities) ---
data class TemplateStageDto(
    @SerializedName("code") val code: String = "",
    @SerializedName("name") val name: Map<String, String>? = null,
    @SerializedName("order") val order: Int = 0,
    @SerializedName("day_offset") val dayOffset: Int = 0,
    @SerializedName("duration_days") val durationDays: Int = 0,
    @SerializedName("stage_type") val stageType: String? = null,
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("description") val description: Map<String, String>? = null,
    @SerializedName("farmer_actions") val farmerActions: List<String>? = null,
    @SerializedName("typical_inputs") val typicalInputs: List<String>? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("recommended_activities") val recommendedActivities: List<RecommendedActivityDto>? = null
)

// --- Stage update request ---
data class StageUpdateDto(
    @SerializedName("action") val action: String, // START, COMPLETE, SKIP, FAIL
    @SerializedName("gps_lat") val gpsLat: Double? = null,
    @SerializedName("gps_lng") val gpsLng: Double? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("skip_reason") val skipReason: String? = null
)

// --- Stage transition response (PATCH /crop-cycles/{cycleId}/stages/{stageId}) ---
data class StageTransitionResponseDto(
    @SerializedName("stage") val stage: CropStageDto? = null,
    @SerializedName("crop_cycle") val cropCycle: CropCycleResponseDto? = null,
    @SerializedName("auto_completed_stage_codes") val autoCompletedStageCodes: List<String> = emptyList()
)

// --- Recommended Activity (from template) ---
data class RecommendedActivityDto(
    @SerializedName("day_offset") val dayOffset: Int = 0,
    @SerializedName("activity_type") val activityType: String = "",
    @SerializedName("input_name") val inputName: String = "",
    @SerializedName("typical_quantity") val typicalQuantity: String? = null,
    @SerializedName("typical_cost_per_acre") val typicalCostPerAcre: Double? = null,
    @SerializedName("is_critical") val isCritical: Boolean = false,
    @SerializedName("description") val description: Map<String, String>? = null
)

// --- Cycle-aware recommended activities (from GET /crop-cycles/{id}/recommended-activities) ---
data class CycleRecommendedActivitiesResponseDto(
    @SerializedName("cycle_id") val cycleId: String? = null,
    @SerializedName("crop_code") val cropCode: String? = null,
    @SerializedName("season_code") val seasonCode: String? = null,
    @SerializedName("planned_sowing_date") val plannedSowingDate: String? = null,
    @SerializedName("recommendations") val recommendedActivities: List<CycleRecommendedActivityDto> = emptyList()
)

data class CycleRecommendedActivityDto(
    @SerializedName("stage_code") val stageCode: String = "",
    @SerializedName("stage_name") val stageName: String? = null,
    @SerializedName("stage_expected_start_date") val stageExpectedStartDate: String? = null,
    @SerializedName("activity_type") val activityType: String = "",
    @SerializedName("input_name") val inputName: String = "",
    @SerializedName("day_offset") val dayOffset: Int = 0,
    @SerializedName("recommended_date") val recommendedDate: String? = null,
    @SerializedName("typical_quantity") val typicalQuantity: String? = null,
    @SerializedName("typical_cost_per_acre") val typicalCostPerAcre: Double? = null,
    @SerializedName("is_critical") val isCritical: Boolean = false,
    @SerializedName("description") val description: Map<String, String>? = null
)
