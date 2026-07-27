package com.agrios.app.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Flexible DTOs for backend-owned Android bootstrap/config contracts.
 *
 * These intentionally keep nested backend-owned objects as JsonElement/Map so
 * Android can start consuming stable endpoint boundaries without prematurely
 * freezing every backend metadata shape in Kotlin.
 */
data class AuthModeBootstrapDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("tenant") val tenant: JsonElement? = null,
    @SerializedName("user") val user: JsonElement? = null,
    @SerializedName("modes") val modes: JsonElement? = null,
    @SerializedName("farmer_profile") val farmerProfile: JsonElement? = null,
    @SerializedName("agent_profile") val agentProfile: JsonElement? = null,
    @SerializedName("project_access") val projectAccess: JsonElement? = null,
    @SerializedName("primary_project_id") val primaryProjectId: String? = null,
    @SerializedName("first_screen_hint") val firstScreenHint: String? = null,
    @SerializedName("feature_flags") val featureFlags: Map<String, Boolean> = emptyMap(),
    @SerializedName("profile_forms") val profileForms: Map<String, FormEndpointHintDto> = emptyMap(),
    @SerializedName("endpoints") val endpoints: Map<String, String> = emptyMap()
)

data class AppConfigBootstrapDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("tenant_id") val tenantId: String? = null,
    @SerializedName("tenant") val tenant: JsonElement? = null,
    @SerializedName("project") val project: JsonElement? = null,
    @SerializedName("branding") val branding: JsonElement? = null,
    @SerializedName("contracts") val contracts: JsonElement? = null,
    @SerializedName("enabled_modules") val enabledModules: List<String> = emptyList(),
    @SerializedName("forms") val forms: List<FormEndpointHintDto> = emptyList(),
    @SerializedName("feature_flags") val featureFlags: Map<String, Boolean> = emptyMap(),
    @SerializedName("profile_forms") val profileForms: Map<String, FormEndpointHintDto> = emptyMap(),
    @SerializedName("localization") val localization: JsonElement? = null,
    @SerializedName("self_service") val selfService: JsonElement? = null,
    @SerializedName("service") val service: JsonElement? = null,
    @SerializedName("runtime_config") val runtimeConfig: JsonElement? = null
)

data class FormEndpointHintDto(
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("endpoint") val endpoint: String? = null,
    @SerializedName("feature_flag") val featureFlag: String? = null,
    @SerializedName("form_id") val formId: String? = null,
    @SerializedName("title") val title: Map<String, String>? = null,
    @SerializedName("version") val version: String? = null
)

data class ProfileContractDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("forms") val forms: JsonElement? = null,
    @SerializedName("required_fields") val requiredFields: JsonElement? = null,
    @SerializedName("recommended_fields") val recommendedFields: JsonElement? = null,
    @SerializedName("payload_mappings") val payloadMappings: JsonElement? = null,
    @SerializedName("option_sets") val optionSets: JsonElement? = null,
    @SerializedName("android_handoff") val androidHandoff: JsonElement? = null,
    @SerializedName("backend_owned_contract") val backendOwnedContract: JsonElement? = null
)

data class SeasonLandUnitsMetadataDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("seasons") val seasons: JsonElement? = null,
    @SerializedName("land_units") val landUnits: JsonElement? = null,
    @SerializedName("area_normalization") val areaNormalization: JsonElement? = null,
    @SerializedName("android_warnings") val androidWarnings: JsonElement? = null
)

data class GeographyHierarchyProfileDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("country_code") val countryCode: String? = null,
    @SerializedName("compatibility_mode") val compatibilityMode: String? = null,
    @SerializedName("levels") val levels: List<GeographyLevelDto> = emptyList(),
    @SerializedName("endpoints") val endpoints: Map<String, String> = emptyMap(),
    @SerializedName("metadata") val metadata: JsonElement? = null
)

data class GeographyLevelDto(
    @SerializedName("code") val code: String? = null,
    @SerializedName("label") val label: Map<String, String>? = null,
    @SerializedName("order") val order: Int? = null,
    @SerializedName("required") val required: Boolean = false,
    @SerializedName("source") val source: String? = null
)

data class PinCodeVillageLookupDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("pin_code") val pinCode: String? = null,
    @SerializedName("is_valid_postal_pin") val isValidPostalPin: Boolean = false,
    @SerializedName("has_lgd_village_candidates") val hasLgdVillageCandidates: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("postal_reference_count") val postalReferenceCount: Int? = null,
    @SerializedName("village_candidate_count") val villageCandidateCount: Int? = null,
    @SerializedName("status_reason") val statusReason: String? = null,
    @SerializedName("village_candidates") val villageCandidates: List<PinVillageCandidateDto> = emptyList(),
    @SerializedName("postal_references") val postalReferences: List<JsonElement> = emptyList(),
    @SerializedName("metadata") val metadata: JsonElement? = null
)

data class PinVillageCandidateDto(
    @SerializedName("village_id") val villageId: String? = null,
    @SerializedName("village_name") val villageName: String? = null,
    @SerializedName("district_id") val districtId: String? = null,
    @SerializedName("district_name") val districtName: String? = null,
    @SerializedName("state_id") val stateId: String? = null,
    @SerializedName("state_name") val stateName: String? = null,
    @SerializedName("lgd_code") val lgdCode: String? = null,
    @SerializedName("display_label") val displayLabel: String? = null
)
