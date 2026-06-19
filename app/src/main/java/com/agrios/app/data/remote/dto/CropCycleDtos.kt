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
    @SerializedName("code") val code: String,
    @SerializedName("name") val name: Map<String, String> = emptyMap(),
    @SerializedName("order") val order: Int = 0,
    @SerializedName("day_offset") val dayOffset: Int = 0,
    @SerializedName("duration_days") val durationDays: Int = 0,
    @SerializedName("expected_start_date") val expectedStartDate: String? = null,
    @SerializedName("expected_end_date") val expectedEndDate: String? = null,
    @SerializedName("status") val status: String = "PENDING", // PENDING, IN_PROGRESS, COMPLETED, SKIPPED
    @SerializedName("stage_type") val stageType: String? = null, // PRE_FIELD, VEGETATIVE, REPRODUCTIVE, HARVEST
    @SerializedName("phase") val phase: String? = null,
    @SerializedName("description") val description: Map<String, String>? = null,
    @SerializedName("farmer_actions") val farmerActions: List<String>? = null,
    @SerializedName("typical_inputs") val typicalInputs: List<String>? = null,
    @SerializedName("key_observations") val keyObservations: List<String>? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("color") val color: String? = null
)

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
    @SerializedName("stages") val stages: List<CropStageDto> = emptyList()
)

// --- Stage update request ---
data class StageUpdateDto(
    @SerializedName("status") val status: String, // IN_PROGRESS, COMPLETED, SKIPPED
    @SerializedName("actual_date") val actualDate: String? = null,
    @SerializedName("notes") val notes: String? = null
)
