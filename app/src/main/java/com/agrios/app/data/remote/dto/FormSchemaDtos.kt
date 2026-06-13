package com.agrios.app.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * DTOs for the schema-driven form API.
 * Backend endpoint: GET /api/v1/forms/{formId}
 *
 * All translatable strings use Map<String, String> with language codes as keys.
 * e.g., {"en": "Season", "hi": "मौसम", "bn": "ঋতু"}
 * Resolved at render time: map[currentLang] ?: map["en"]
 */

data class FormSchemaDto(
    @SerializedName("form_id") val formId: String = "",
    @SerializedName("version") val version: String = "1.0.0",
    @SerializedName("title") val title: Map<String, String> = emptyMap(),
    @SerializedName("description") val description: Map<String, String>? = null,
    @SerializedName("entity_type") val entityType: String = "CROP_CYCLE",
    @SerializedName("fields") val fields: List<FormFieldDto> = emptyList(),
    @SerializedName("submit_label") val submitLabel: Map<String, String>? = null
) {
    fun resolveTitle(lang: String): String = title[lang] ?: title["en"] ?: ""
    fun resolveDescription(lang: String): String? = description?.get(lang) ?: description?.get("en")
    fun resolveSubmitLabel(lang: String): String = submitLabel?.get(lang) ?: submitLabel?.get("en") ?: "Save"
}

/**
 * Extension to resolve a translatable map to the best available language.
 */
fun Map<String, String>?.resolve(lang: String): String {
    if (this == null) return ""
    return this[lang] ?: this["en"] ?: values.firstOrNull() ?: ""
}

data class FormFieldDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("type") val type: String = "text",
    @SerializedName("label") val label: Map<String, String> = emptyMap(),
    @SerializedName("placeholder") val placeholder: Map<String, String>? = null,
    @SerializedName("hint") val hint: Map<String, String>? = null,
    @SerializedName("required") val required: Boolean = false,
    @SerializedName("visible_for_roles") val visibleForRoles: List<String>? = null,
    @SerializedName("options") val options: List<FormFieldOptionDto>? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("source_filter") val sourceFilter: Map<String, String>? = null,
    @SerializedName("default_value") val defaultValue: String? = null,
    @SerializedName("validation") val validation: FormFieldValidationDto? = null,
    @SerializedName("depends_on") val dependsOn: String? = null,
    @SerializedName("depends_on_value") val dependsOnValue: String? = null
)

data class FormFieldOptionDto(
    @SerializedName("value") val value: String = "",
    @SerializedName("label") val label: Map<String, String> = emptyMap()
)

data class FormFieldValidationDto(
    @SerializedName("min") val min: Double? = null,
    @SerializedName("max") val max: Double? = null,
    @SerializedName("min_length") val minLength: Int? = null,
    @SerializedName("max_length") val maxLength: Int? = null,
    @SerializedName("pattern") val pattern: String? = null,
    @SerializedName("pattern_error") val patternError: Map<String, String>? = null
)
