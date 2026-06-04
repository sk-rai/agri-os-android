package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for the schema-driven form API.
 * Backend endpoint: GET /api/v1/forms/{formId}
 */

data class FormSchemaDto(
    @SerializedName("form_id") val formId: String,
    @SerializedName("version") val version: Int,
    @SerializedName("title") val title: LocalizedStringDto,
    @SerializedName("subtitle") val subtitle: LocalizedStringDto? = null,
    @SerializedName("entity_type") val entityType: String, // CROP_CYCLE, STAGE_ACTIVITY, etc.
    @SerializedName("fields") val fields: List<FormFieldDto>,
    @SerializedName("submit_label") val submitLabel: LocalizedStringDto? = null
)

data class LocalizedStringDto(
    @SerializedName("en") val en: String,
    @SerializedName("hi") val hi: String? = null
) {
    fun resolve(isHindi: Boolean): String = if (isHindi && !hi.isNullOrBlank()) hi else en
}

data class FormFieldDto(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String, // text, number, phone, date, dropdown, single_select, multi_select, boolean, textarea
    @SerializedName("label") val label: LocalizedStringDto,
    @SerializedName("placeholder") val placeholder: LocalizedStringDto? = null,
    @SerializedName("hint") val hint: LocalizedStringDto? = null,
    @SerializedName("required") val required: Boolean = false,
    @SerializedName("visible_for_roles") val visibleForRoles: List<String>? = null, // null = all roles
    @SerializedName("options") val options: List<FormFieldOptionDto>? = null, // for single_select, multi_select
    @SerializedName("source") val source: String? = null, // "master_data.crops", "master_data.villages", etc.
    @SerializedName("source_filter") val sourceFilter: Map<String, String>? = null, // e.g. {"season": "KHARIF"}
    @SerializedName("default_value") val defaultValue: String? = null, // "today" for dates, or literal value
    @SerializedName("validation") val validation: FormFieldValidationDto? = null,
    @SerializedName("depends_on") val dependsOn: String? = null, // field id this field depends on
    @SerializedName("depends_on_value") val dependsOnValue: String? = null // show only when dependsOn == this value
)

data class FormFieldOptionDto(
    @SerializedName("value") val value: String,
    @SerializedName("label") val label: LocalizedStringDto
)

data class FormFieldValidationDto(
    @SerializedName("min") val min: Double? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("min_length") val minLength: Int? = null,
    @SerializedName("max_length") val maxLength: Int? = null,
    @SerializedName("pattern") val pattern: String? = null, // regex
    @SerializedName("pattern_error") val patternError: LocalizedStringDto? = null
)
