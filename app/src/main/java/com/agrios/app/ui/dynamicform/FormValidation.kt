package com.agrios.app.ui.dynamicform

import com.agrios.app.data.remote.dto.FormFieldDto
import com.agrios.app.data.remote.dto.FormSchemaDto

data class FormValidationError(
    val fieldId: String,
    val message: String
)

object FormValidation {
    fun validate(schema: FormSchemaDto, values: Map<String, Any?>): List<FormValidationError> {
        return schema.fields
            .filter { isFieldVisible(it, values) }
            .flatMap { field -> validateField(field, values[field.id]) }
    }

    private fun validateField(field: FormFieldDto, value: Any?): List<FormValidationError> {
        val errors = mutableListOf<FormValidationError>()
        val text = when (value) {
            null -> ""
            is String -> value
            is List<*> -> if (value.isEmpty()) "" else value.joinToString(",")
            else -> value.toString()
        }
        val validation = field.validation

        if (field.required && text.isBlank()) {
            errors += FormValidationError(field.id, "${label(field)} is required")
            return errors
        }

        if (text.isBlank()) return errors

        validation?.minLength?.let { min ->
            if (text.length < min) errors += FormValidationError(field.id, "${label(field)} must be at least $min characters")
        }
        validation?.maxLength?.let { max ->
            if (text.length > max) errors += FormValidationError(field.id, "${label(field)} must be at most $max characters")
        }
        validation?.pattern?.let { pattern ->
            if (!Regex(pattern).matches(text)) {
                val message = validation.patternError?.get("en") ?: "${label(field)} has invalid format"
                errors += FormValidationError(field.id, message)
            }
        }
        if (field.type == "number") {
            val number = text.toDoubleOrNull()
            if (number == null) {
                errors += FormValidationError(field.id, "${label(field)} must be a number")
            } else {
                validation?.min?.let { min ->
                    if (number < min) errors += FormValidationError(field.id, "${label(field)} must be at least $min")
                }
                validation?.max?.let { max ->
                    if (number > max) errors += FormValidationError(field.id, "${label(field)} must be at most $max")
                }
            }
        }
        return errors
    }

    private fun label(field: FormFieldDto): String {
        return field.label["en"] ?: field.label.values.firstOrNull() ?: field.id
    }
}
