package com.agrios.app.ui.dynamicform

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.FormSchemaDto
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "DynamicFormScreen"

/**
 * DynamicFormScreen: Complete screen that loads a form schema from the backend,
 * renders it using DynamicFormRenderer, validates, saves, and syncs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicFormScreen(
    formId: String,
    contextValues: Map<String, String> = emptyMap(), // Pre-filled values (e.g., parcel_id, farmer_id)
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // Schema state
    var schema by remember { mutableStateOf<FormSchemaDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Form state
    val formValues = remember { mutableStateMapOf<String, Any?>() }
    var validationErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSaving by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Pre-fill context values
    LaunchedEffect(contextValues) {
        contextValues.forEach { (key, value) ->
            formValues[key] = value
        }
    }

    // Load schema from backend
    LaunchedEffect(formId) {
        isLoading = true
        loadError = null
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val okHttp = OkHttpClient.Builder()
                    .addInterceptor(AuthInterceptor(db.authDao()))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val fullUrl = "${ApiConfig.BASE_URL}forms/$formId"
                Log.d(TAG, "Loading schema from: $fullUrl")
                val request = okhttp3.Request.Builder().url(fullUrl).build()
                val response = okHttp.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Log.d(TAG, "Raw schema response (first 500 chars): ${body.take(500)}")
                        schema = Gson().fromJson(body, FormSchemaDto::class.java)
                        Log.d(TAG, "Schema loaded: ${schema?.formId}, ${schema?.fields?.size} fields")
                        // Apply default values
                        schema?.fields?.forEach { field ->
                            if (formValues[field.id] == null && field.defaultValue != null) {
                                formValues[field.id] = when {
                                    field.defaultValue == "today" -> {
                                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                                    }
                                    else -> field.defaultValue
                                }
                            }
                        }
                    } else {
                        loadError = "Empty response"
                    }
                } else {
                    loadError = "HTTP ${response.code}: ${response.message}"
                    Log.e(TAG, "Schema load failed: $loadError")
                }
            } // end withContext
        } catch (e: Exception) {
            loadError = e.message ?: "Network error"
            Log.e(TAG, "Schema load error", e)
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val lang = if (LanguageManager.isHindi()) "hi" else "en"
                    Text(schema?.resolveTitle(lang) ?: LanguageManager.localize("Loading...", "लोड हो रहा..."))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator()
                    Text(LanguageManager.localize("Loading form...", "फ़ॉर्म लोड हो रहा..."))
                }

                loadError != null -> {
                    Text("❌ $loadError", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { /* TODO: retry */ }) {
                        Text(LanguageManager.localize("Retry", "पुनः प्रयास"))
                    }
                }

                saveSuccess -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("✅ ${LanguageManager.localize("Saved!", "सहेजा!")}", style = MaterialTheme.typography.titleSmall)
                            Text(LanguageManager.localize("Syncing in background.", "पृष्ठभूमि में सिंक हो रहा।"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onSuccess, modifier = Modifier.fillMaxWidth()) {
                        Text(LanguageManager.localize("Done", "हो गया"))
                    }
                }

                schema != null -> {
                    // Render the form
                    DynamicFormRenderer(
                        schema = schema!!,
                        formValues = formValues,
                        onValueChange = { fieldId, value ->
                            formValues[fieldId] = value
                            // Clear validation error for this field
                            if (validationErrors.containsKey(fieldId)) {
                                validationErrors = validationErrors - fieldId
                            }
                        },
                        enabled = !isSaving
                    )

                    // Validation errors
                    if (validationErrors.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                validationErrors.values.forEach { error ->
                                    Text("• $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    if (saveError != null) {
                        Text("❌ $saveError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(16.dp))

                    // Submit button
                    Button(
                        onClick = {
                            // Validate
                            val errors = validateForm(schema!!, formValues)
                            if (errors.isNotEmpty()) {
                                validationErrors = errors
                                return@Button
                            }
                            validationErrors = emptyMap()
                            saveError = null
                            isSaving = true

                            scope.launch {
                                try {
                                    val entityType = schema!!.entityType
                                    val entityId = UUID.randomUUID().toString()
                                    val now = System.currentTimeMillis()

                                    // Build payload (filter out null values)
                                    val payload = formValues.filter { it.value != null && it.value.toString().isNotBlank() }

                                    // Queue for sync
                                    db.syncQueueDao().enqueue(SyncQueueEntity(
                                        eventId = UUID.randomUUID().toString(),
                                        entityType = entityType,
                                        entityId = entityId,
                                        operation = "CREATE",
                                        payload = Gson().toJson(payload),
                                        syncStatus = SyncStatus.PENDING.name,
                                        priority = SyncPriority.HIGH.name,
                                        createdAt = now
                                    ))

                                    // Trigger sync
                                    SyncWorker.triggerImmediateSync(AgriOsApp.instance)

                                    isSaving = false
                                    saveSuccess = true
                                    Log.d(TAG, "Form saved: $entityType/$entityId")
                                } catch (e: Exception) {
                                    isSaving = false
                                    saveError = e.message
                                    Log.e(TAG, "Form save error", e)
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            val lang = if (LanguageManager.isHindi()) "hi" else "en"
                            Text(
                                schema?.resolveSubmitLabel(lang)
                                    ?: LanguageManager.localize("💾 Save", "💾 सहेजें"),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
