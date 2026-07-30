package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// --- Farmer ---
data class CreateFarmerDto(
    @SerializedName("mobile_number") val mobileNumber: String,
    @SerializedName("village_id") val villageId: String,
    @SerializedName("primary_crop_code") val primaryCropCode: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("assistance_mode") val assistanceMode: String = "DEALER_ASSISTED"
)

data class FarmerResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("mobile_number") val mobileNumber: String,
    @SerializedName("village_id") val villageId: String,
    @SerializedName("primary_crop_code") val primaryCropCode: String? = null,
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("assistance_mode") val assistanceMode: String? = null
)

// --- Parcel ---
data class CreateParcelDto(
    @SerializedName("farmer_id") val farmerId: String,
    @SerializedName("village_id") val villageId: String,
    @SerializedName("reported_area") val reportedArea: Double,
    @SerializedName("reported_area_unit") val reportedAreaUnit: String,
    @SerializedName("ownership_type") val ownershipType: String = "OWNED",
    @SerializedName("irrigation_type") val irrigationType: String? = null
)

data class ParcelResponseDto(
    @SerializedName("id") val id: String,
    @SerializedName("farmer_id") val farmerId: String,
    @SerializedName("village_id") val villageId: String,
    @SerializedName("reported_area") val reportedArea: Double,
    @SerializedName("reported_area_unit") val reportedAreaUnit: String,
    @SerializedName("area_hectares") val areaHectares: Double? = null,
    @SerializedName("geometry_source") val geometrySource: String? = null
)

// --- Sync ---
data class SyncBatchRequestDto(
    @SerializedName("events") val events: List<SyncEventDto>
)

data class SyncEventDto(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_id") val entityId: String,
    @SerializedName("operation") val operation: String,
    @SerializedName("payload") val payload: Map<String, Any?>,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("dependency_ids") val dependencyIds: List<String>? = null,
    @SerializedName("metadata") val metadata: Map<String, Any?>? = null
)

data class SyncBatchResponseDto(
    @SerializedName("accepted") val accepted: List<String> = emptyList(),
    @SerializedName("conflicts") val conflicts: List<SyncConflictDto> = emptyList(),
    @SerializedName("failed") val failed: List<SyncFailedDto> = emptyList()
)

data class SyncConflictDto(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("conflict_type") val conflictType: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("resolution_strategy") val resolutionStrategy: String? = null,
    @SerializedName("detail") val detail: String? = null
)

data class SyncFailedDto(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("error_code") val errorCode: String,
    @SerializedName("message") val message: String? = null,
    @SerializedName("retryable") val retryable: Boolean = true
)

data class ResolveConflictDto(
    @SerializedName("strategy") val strategy: String // ACCEPT_CLIENT, ACCEPT_SERVER
)
