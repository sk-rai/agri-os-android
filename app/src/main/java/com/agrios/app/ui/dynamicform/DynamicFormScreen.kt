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
import com.agrios.app.core.cache.CropCycleCache
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.FormSchemaDto
import com.agrios.app.ui.cropcycle.StageInferenceDialog
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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
    onSuccess: () -> Unit,
    onCycleCreated: ((cycleId: String) -> Unit)? = null
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
    var createdCycleId by remember { mutableStateOf<String?>(null) }
    var createdCycleResponse by remember { mutableStateOf<com.agrios.app.data.remote.dto.CropCycleResponseDto?>(null) }
    var showInferenceDialog by remember { mutableStateOf(false) }

    // Pre-fill context values
    LaunchedEffect(contextValues) {
        contextValues.forEach { (key, value) ->
            // Apply context values to form fields (for pre-fill from recommendations)
            if (key != "crop_cycle_id") {
                formValues[key] = value
            }
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
                    // Show inference dialog if crop cycle has inferred stage
                    if (showInferenceDialog && createdCycleResponse != null && createdCycleResponse!!.inferredCurrentStage != null) {
                        StageInferenceDialog(
                            cycle = createdCycleResponse!!,
                            onStartFromInferred = {
                                showInferenceDialog = false
                                val cycleId = createdCycleId
                                if (cycleId != null && onCycleCreated != null) {
                                    onCycleCreated(cycleId)
                                } else {
                                    onSuccess()
                                }
                            },
                            onStartFromBeginning = {
                                showInferenceDialog = false
                                val cycleId = createdCycleId
                                if (cycleId != null && onCycleCreated != null) {
                                    onCycleCreated(cycleId)
                                } else {
                                    onSuccess()
                                }
                            },
                            onDismiss = {
                                showInferenceDialog = false
                            }
                        )
                    }

                    // Check if we should show inference dialog on first success
                    LaunchedEffect(saveSuccess) {
                        if (createdCycleResponse?.inferredCurrentStage != null) {
                            showInferenceDialog = true
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("✅ ${LanguageManager.localize("Saved!", "सहेजा!")}", style = MaterialTheme.typography.titleSmall)
                            if (createdCycleResponse != null) {
                                val c = createdCycleResponse!!
                                if (c.expectedHarvestDate != null) {
                                    Text("${LanguageManager.localize("Expected harvest", "अपेक्षित कटाई")}: ${c.expectedHarvestDate}", style = MaterialTheme.typography.bodySmall)
                                }
                                if (c.stages.isNotEmpty()) {
                                    Text("${c.stages.size} ${LanguageManager.localize("stages planned", "चरण नियोजित")}", style = MaterialTheme.typography.bodySmall)
                                }
                            } else {
                                Text(LanguageManager.localize("Syncing in background.", "पृष्ठभूमि में सिंक हो रहा।"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val cycleId = createdCycleId
                        if (cycleId != null && onCycleCreated != null) {
                            onCycleCreated(cycleId)
                        } else {
                            onSuccess()
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(LanguageManager.localize("Continue", "आगे बढ़ें"))
                    }
                }

                schema != null -> {
                    // Render the form
                    DynamicFormRenderer(
                        schema = schema!!,
                        formId = formId,
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
                            if (formId == "crop_cycle_create" && formValues["parcel_id"]?.toString().isNullOrBlank()) {
                                validationErrors = mapOf(
                                    "parcel_id" to LanguageManager.localize(
                                        "Select an eligible parcel before starting a crop cycle",
                                        "Select an eligible parcel before starting a crop cycle"
                                    )
                                )
                                return@Button
                            }
                            validationErrors = emptyMap()
                            saveError = null
                            isSaving = true

                            scope.launch {
                                try {
                                    val entityType = schema!!.entityType
                                    val now = System.currentTimeMillis()

                                    // Build payload (filter out null values)
                                    val payload = formValues.filter { it.value != null && it.value.toString().isNotBlank() }

                                    // Route to correct endpoint based on form type
                                    if (formId == "activity_log") {
                                        // Activity log — direct API call to /crop-cycles/{cycleId}/activities
                                        val cycleId = contextValues["crop_cycle_id"] ?: ""
                                        if (cycleId.isBlank()) {
                                            throw Exception("Missing crop_cycle_id for activity log")
                                        }
                                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val okHttp = OkHttpClient.Builder()
                                                .addInterceptor(AuthInterceptor(db.authDao()))
                                                .connectTimeout(15, TimeUnit.SECONDS)
                                                .readTimeout(15, TimeUnit.SECONDS)
                                                .build()

                                            val activityUrl = "${ApiConfig.BASE_URL}crop-cycles/$cycleId/activities"
                                            Log.d(TAG, "Posting activity to: $activityUrl")
                                            Log.d(TAG, "Activity payload: ${Gson().toJson(payload)}")

                                            val requestBody = okhttp3.RequestBody.create(
                                                "application/json".toMediaTypeOrNull(),
                                                Gson().toJson(payload)
                                            )
                                            val request = okhttp3.Request.Builder()
                                                .url(activityUrl)
                                                .post(requestBody)
                                                .build()
                                            val response = okHttp.newCall(request).execute()

                                            if (response.isSuccessful) {
                                                Log.d(TAG, "Activity logged successfully")
                                            } else {
                                                val errorBody = response.body?.string()
                                                Log.e(TAG, "Activity log failed: ${response.code} $errorBody")
                                                throw Exception("HTTP ${response.code}: ${errorBody ?: response.message}")
                                            }
                                        }
                                    } else if (entityType == "CROP_CYCLE") {
                                        // Crop cycle creation — direct API call to get stages/inference
                                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val parcelId = payload["parcel_id"]?.toString()
                                            var farmerId: String? = null
                                            if (parcelId != null) {
                                                val parcel = db.parcelDao().getById(parcelId)
                                                farmerId = parcel?.farmerId
                                            }
                                            if (farmerId == null) {
                                                val authState = db.authDao().getAuthState()
                                                farmerId = authState?.userId
                                            }
                                            val enrichedPayload = payload.toMutableMap()
                                            if (farmerId != null) {
                                                enrichedPayload["farmer_id"] = farmerId
                                            }
                                            val okHttp = OkHttpClient.Builder()
                                                .addInterceptor(AuthInterceptor(db.authDao()))
                                                .connectTimeout(15, TimeUnit.SECONDS)
                                                .readTimeout(15, TimeUnit.SECONDS)
                                                .build()
                                            val api = Retrofit.Builder()
                                                .baseUrl(ApiConfig.BASE_URL)
                                                .client(okHttp)
                                                .addConverterFactory(GsonConverterFactory.create())
                                                .build()
                                                .create(com.agrios.app.data.remote.api.AgriOsApi::class.java)

                                            val response = api.createCropCycle(enrichedPayload)
                                            if (response.isSuccessful) {
                                                val cycleResponse = response.body()
                                                Log.d(TAG, "Crop cycle created: ${cycleResponse?.id}, inferred stage: ${cycleResponse?.inferredCurrentStage}")
                                                createdCycleId = cycleResponse?.id
                                                createdCycleResponse = cycleResponse
                                                cycleResponse?.let { CropCycleCache.upsert(AgriOsApp.instance, it) }
                                            } else {
                                                val errorBody = response.errorBody()?.string()
                                                Log.e(TAG, "Crop cycle creation failed: ${response.code()} $errorBody")
                                                throw Exception("HTTP ${response.code()}: ${errorBody ?: response.message()}")
                                            }
                                        }
                                    } else {
                                        // For other entity types, use sync queue
                                        val entityId = UUID.randomUUID().toString()
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
                                        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                                    }

                                    isSaving = false
                                    saveSuccess = true
                                    Log.d(TAG, "Form saved: $entityType")
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
