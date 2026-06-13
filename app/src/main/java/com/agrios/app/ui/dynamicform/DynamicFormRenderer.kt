package com.agrios.app.ui.dynamicform

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.dto.FormFieldDto
import com.agrios.app.data.remote.dto.FormSchemaDto
import com.agrios.app.data.remote.dto.resolve
import com.agrios.app.ui.components.SearchableDropdown
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "DynamicFormRenderer"

/**
 * Resolves a Map<String, String> label to current language.
 */
private fun resolveLabel(map: Map<String, String>?, lang: String): String {
    if (map == null) return ""
    return map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""
}

/**
 * DynamicFormRenderer: Renders a form from a JSON schema.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DynamicFormRenderer(
    schema: FormSchemaDto,
    formValues: MutableMap<String, Any?>,
    onValueChange: (fieldId: String, value: Any?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lang = if (LanguageManager.isHindi()) "hi" else "en"
    val scope = rememberCoroutineScope()
    val dynamicOptions = remember { mutableStateMapOf<String, List<Pair<String, String>>>() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        schema.fields.forEach { field ->
            val isVisible = isFieldVisible(field, formValues)
            AnimatedVisibility(visible = isVisible) {
                RenderField(
                    field = field,
                    value = formValues[field.id],
                    lang = lang,
                    enabled = enabled,
                    dynamicOptions = dynamicOptions[field.id],
                    onValueChange = { newValue ->
                        onValueChange(field.id, newValue)
                        schema.fields.filter { it.dependsOn == field.id }.forEach { dep ->
                            onValueChange(dep.id, null)
                            dynamicOptions.remove(dep.id)
                        }
                        schema.fields.filter { it.dependsOn == field.id && it.source != null }.forEach { dep ->
                            scope.launch {
                                val options = loadDynamicOptions(dep.source!!, formValues + (field.id to newValue))
                                if (options != null) dynamicOptions[dep.id] = options
                            }
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderField(
    field: FormFieldDto,
    value: Any?,
    lang: String,
    enabled: Boolean,
    dynamicOptions: List<Pair<String, String>>?,
    onValueChange: (Any?) -> Unit
) {
    val label = resolveLabel(field.label, lang) + if (field.required) " *" else ""
    val placeholder = resolveLabel(field.placeholder, lang)
    val hint = resolveLabel(field.hint, lang).takeIf { it.isNotBlank() }

    when (field.type) {
        "text", "phone" -> {
            OutlinedTextField(
                value = (value as? String) ?: "",
                onValueChange = { onValueChange(it) },
                label = { Text(label) },
                placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = if (field.type == "phone") KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (hint != null) { { Text(hint) } } else null
            )
        }

        "number" -> {
            OutlinedTextField(
                value = (value as? String) ?: (value?.toString() ?: ""),
                onValueChange = { newVal -> onValueChange(newVal.filter { it.isDigit() || it == '.' }) },
                label = { Text(label) },
                placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (hint != null) { { Text(hint) } } else null
            )
        }

        "textarea" -> {
            OutlinedTextField(
                value = (value as? String) ?: "",
                onValueChange = { onValueChange(it) },
                label = { Text(label) },
                placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
                enabled = enabled,
                singleLine = false,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }

        "date" -> {
            val dateValue = (value as? String) ?: resolveDefaultDate(field.defaultValue)
            OutlinedTextField(
                value = dateValue,
                onValueChange = { onValueChange(it) },
                label = { Text(label) },
                placeholder = { Text("YYYY-MM-DD") },
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(LanguageManager.localize("Format: YYYY-MM-DD", "प्रारूप: YYYY-MM-DD")) }
            )
        }

        "dropdown" -> {
            val options = dynamicOptions ?: field.options?.map { it.value to resolveLabel(it.label, lang) } ?: emptyList()
            val selectedId = (value as? String) ?: ""

            if (field.source == "local_parcels") {
                val db = AgriOsApp.instance.database
                val parcels by db.parcelDao().observeAll().collectAsState(initial = emptyList())
                val parcelItems = parcels.map { it.id to "${it.reportedArea} ${it.reportedAreaUnit} (${it.ownershipType})" }
                SearchableDropdown(
                    label = label,
                    items = parcelItems,
                    selectedId = selectedId,
                    onSelect = { id, _ -> onValueChange(id) }
                )
            } else if (options.isNotEmpty()) {
                SearchableDropdown(
                    label = label,
                    items = options,
                    selectedId = selectedId,
                    onSelect = { id, _ -> onValueChange(id) }
                )
            } else {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text(label) },
                    placeholder = { Text(LanguageManager.localize("Select above fields first", "पहले ऊपर के फ़ील्ड चुनें")) },
                    enabled = false,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        "single_select" -> {
            val options = field.options ?: emptyList()
            val selectedValue = (value as? String) ?: ""
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { option ->
                        FilterChip(
                            selected = selectedValue == option.value,
                            onClick = { if (enabled) onValueChange(option.value) },
                            label = { Text(resolveLabel(option.label, lang), style = MaterialTheme.typography.labelSmall) },
                            enabled = enabled
                        )
                    }
                }
            }
        }

        "multi_select" -> {
            val options = field.options ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val selectedValues = (value as? List<String>) ?: emptyList()
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { option ->
                        FilterChip(
                            selected = option.value in selectedValues,
                            onClick = {
                                if (enabled) {
                                    val newList = if (option.value in selectedValues) selectedValues - option.value else selectedValues + option.value
                                    onValueChange(newList)
                                }
                            },
                            label = { Text(resolveLabel(option.label, lang), style = MaterialTheme.typography.labelSmall) },
                            enabled = enabled
                        )
                    }
                }
            }
        }

        "boolean" -> {
            val checked = (value as? Boolean) ?: false
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = checked, onCheckedChange = { if (enabled) onValueChange(it) }, enabled = enabled)
            }
        }

        else -> {
            Text("⚠️ Unknown field type: ${field.type}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun isFieldVisible(field: FormFieldDto, formValues: Map<String, Any?>): Boolean {
    if (field.dependsOn == null) return true
    val dependencyValue = formValues[field.dependsOn]
    if (dependencyValue == null || dependencyValue == "") return false
    if (field.dependsOnValue != null) return dependencyValue.toString() == field.dependsOnValue
    return true
}

private fun resolveDefaultDate(defaultValue: String?): String {
    return when (defaultValue) {
        "today" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        else -> defaultValue ?: ""
    }
}

private suspend fun loadDynamicOptions(source: String, formValues: Map<String, Any?>): List<Pair<String, String>>? {
    if (source.startsWith("local_")) return null

    var url = source
    val variableRegex = Regex("\\{(\\w+)\\}")
    variableRegex.findAll(source).forEach { match ->
        val fieldId = match.groupValues[1]
        val fieldValue = formValues[fieldId]?.toString() ?: ""
        if (fieldValue.isEmpty()) return null
        url = url.replace(match.value, fieldValue)
    }

    return withContext(Dispatchers.IO) {
        try {
            val db = AgriOsApp.instance.database
            val okHttp = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(db.authDao()))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val fullUrl = if (url.startsWith("/")) ApiConfig.BASE_URL.trimEnd('/') + url.removePrefix("/api/v1") else url
            val request = okhttp3.Request.Builder().url(fullUrl).build()
            val response = okHttp.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return@withContext null
                val items: List<Map<String, Any?>> = Gson().fromJson(body, object : TypeToken<List<Map<String, Any?>>>() {}.type)
                items.mapNotNull { item ->
                    val id = (item["id"] ?: item["code"] ?: item["value"])?.toString() ?: return@mapNotNull null
                    val name = (item["canonical_name"] ?: item["name"] ?: item["label"] ?: item["display_name"])?.toString() ?: id
                    id to name
                }
            } else {
                Log.w(TAG, "Dynamic options load failed: ${response.code} for $fullUrl")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic options load error: ${e.message}")
            null
        }
    }
}

/**
 * Validate all form fields against schema rules.
 */
fun validateForm(schema: FormSchemaDto, formValues: Map<String, Any?>): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val lang = if (LanguageManager.isHindi()) "hi" else "en"

    schema.fields.forEach { field ->
        if (!isFieldVisible(field, formValues)) return@forEach
        val value = formValues[field.id]

        if (field.required && (value == null || value.toString().isBlank())) {
            errors[field.id] = "${resolveLabel(field.label, lang)} ${LanguageManager.localize("is required", "आवश्यक है")}"
            return@forEach
        }
        if (value == null || value.toString().isBlank()) return@forEach

        val validation = field.validation
        if (validation != null && field.type == "number") {
            val numVal = value.toString().toDoubleOrNull()
            if (numVal != null) {
                if (validation.min != null && numVal < validation.min) errors[field.id] = "${resolveLabel(field.label, lang)}: min ${validation.min}"
                if (validation.max != null && numVal > validation.max) errors[field.id] = "${resolveLabel(field.label, lang)}: max ${validation.max}"
            }
        }
        if (validation?.pattern != null && field.type == "text") {
            val regex = Regex(validation.pattern)
            if (!regex.matches(value.toString())) {
                errors[field.id] = resolveLabel(validation.patternError, lang).takeIf { it.isNotBlank() }
                    ?: "${resolveLabel(field.label, lang)} ${LanguageManager.localize("format invalid", "प्रारूप अमान्य")}"
            }
        }
    }
    return errors
}
