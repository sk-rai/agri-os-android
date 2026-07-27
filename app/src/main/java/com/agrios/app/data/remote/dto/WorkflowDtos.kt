package com.agrios.app.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * Flexible DTOs for backend-configured workflow discovery.
 *
 * The backend is intentionally taking ownership of farmer/parcel/soil workflow
 * shape. Android should use these objects to discover form IDs/endpoints and
 * render dynamic forms without adding new business-specific branching.
 */
data class WorkflowSummaryDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("workflow_id") val workflowId: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("title") val title: Map<String, String>? = null,
    @SerializedName("description") val description: Map<String, String>? = null,
    @SerializedName("version") val version: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("form_id") val formId: String? = null,
    @SerializedName("endpoint") val endpoint: String? = null,
    @SerializedName("metadata") val metadata: JsonElement? = null
) {
    val stableId: String?
        get() = workflowId ?: id ?: code
}

data class WorkflowConfigDto(
    @SerializedName("schema_version") val schemaVersion: String? = null,
    @SerializedName("id") val id: String? = null,
    @SerializedName("workflow_id") val workflowId: String? = null,
    @SerializedName("code") val code: String? = null,
    @SerializedName("title") val title: Map<String, String>? = null,
    @SerializedName("description") val description: Map<String, String>? = null,
    @SerializedName("version") val version: String? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
    @SerializedName("form_id") val formId: String? = null,
    @SerializedName("endpoint") val endpoint: String? = null,
    @SerializedName("forms") val forms: JsonElement? = null,
    @SerializedName("steps") val steps: JsonElement? = null,
    @SerializedName("transitions") val transitions: JsonElement? = null,
    @SerializedName("metadata") val metadata: JsonElement? = null
) {
    val stableId: String?
        get() = workflowId ?: id ?: code
}
