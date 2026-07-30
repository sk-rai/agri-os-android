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
import com.agrios.app.core.geo.GeoJson
import com.agrios.app.core.network.AndroidDynamicTestContext
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.UnitConverter
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.ParcelEntity
import com.agrios.app.data.local.entity.SoilProfileEntity
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.FormSchemaDto
import com.agrios.app.data.remote.dto.LandIntelligenceContextDto
import com.agrios.app.data.repository.BackendBootstrapRepository
import com.agrios.app.data.repository.GeometryRepository
import com.agrios.app.ui.cropcycle.StageInferenceDialog
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
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
    projectId: String? = null,
    contextValues: Map<String, String> = emptyMap(), // Pre-filled values (e.g., parcel_id, farmer_id)
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    onCycleCreated: ((cycleId: String) -> Unit)? = null
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()
    val api = remember {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao()))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgriOsApi::class.java)
    }

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
    var landIntelligenceContext by remember { mutableStateOf<LandIntelligenceContextDto?>(null) }
    var backendProjectId by remember(projectId) { mutableStateOf(projectId) }

    // Pre-fill context values
    LaunchedEffect(contextValues) {
        contextValues.forEach { (key, value) ->
            // Apply context values to form fields (for pre-fill from recommendations)
            if (key != "crop_cycle_id") {
                formValues[key] = value
            }
        }
    }

    LaunchedEffect(formId) {
        if (formId == "farmer_registration" && formValues["mobile_number"] == null) {
            val mobileNumber = withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.authDao().getAuthState()?.mobileNumber
            }
            if (!mobileNumber.isNullOrBlank()) {
                formValues["mobile_number"] = mobileNumber
            }
        }
    }

    LaunchedEffect(projectId) {
        if (projectId == null) {
            backendProjectId = withContext(kotlinx.coroutines.Dispatchers.IO) {
                AndroidDynamicTestContext.projectIdFor(db.authDao())
            }
        }
    }

    // Load schema from backend
    LaunchedEffect(formId, backendProjectId) {
        isLoading = true
        loadError = null
        try {
            val loadedSchema = withContext(kotlinx.coroutines.Dispatchers.IO) {
                BackendBootstrapRepository(api).loadFormSchema(formId, backendProjectId).getOrThrow()
            }
            schema = loadedSchema
            Log.d(TAG, "Schema loaded: ${loadedSchema.formId}, ${loadedSchema.fields.size} fields")
            // Apply default values
            loadedSchema.fields.forEach { field ->
                if (formValues[field.id] == null && field.defaultValue != null) {
                    formValues[field.id] = coerceDefaultValue(field.type, field.defaultValue)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Network error"
            Log.e(TAG, "Schema load error", e)
        } finally {
            isLoading = false
        }
    }

    val landPinCode = formValues["pin_code"]?.toString()?.filter { it.isDigit() }?.takeIf { it.length == 6 }
    val landDistrictLgdCode = formValues["district_lgd_code"]?.toString()?.takeIf { it.isNotBlank() }
    val landStateLgdCode = formValues["state_lgd_code"]?.toString()?.takeIf { it.isNotBlank() }
    val landCropCode = resolveLandIntelligenceCropCode(formValues)
    val landSeasonCode = resolveLandIntelligenceSeasonCode(formValues)
    LaunchedEffect(formId, landPinCode, landDistrictLgdCode, landStateLgdCode, landCropCode, landSeasonCode, backendProjectId) {
        if (formId in setOf("farmer_registration", "parcel_registration", "soil_profile") &&
            listOf(landPinCode, landDistrictLgdCode, landStateLgdCode).any { !it.isNullOrBlank() }
        ) {
            landIntelligenceContext = BackendBootstrapRepository(api).loadLandIntelligenceContext(
                stateLgdCode = landStateLgdCode,
                districtLgdCode = landDistrictLgdCode,
                pinCode = landPinCode,
                cropCode = landCropCode,
                seasonCode = landSeasonCode,
                projectId = backendProjectId
            ).getOrNull()
        } else {
            landIntelligenceContext = null
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
                    landIntelligenceContext?.let { LandIntelligenceGuidanceCard(it) }
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
                                    val entityType = resolveProfileEntityType(formId, schema!!)
                                    val now = System.currentTimeMillis()

                                    // Build payload (filter out null values)
                                    val rawPayload = formValues
                                        .filter { it.value != null && it.value.toString().isNotBlank() }
                                        .mapValues { (_, fieldValue) ->
                                            if (GeoJson.isGeoJson(fieldValue)) JsonParser.parseString(fieldValue.toString()) else fieldValue
                                        }
                                    val payload = normalizeProfileSubmitPayload(
                                        formId = formId,
                                        payload = rawPayload,
                                        db = db
                                    )

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
                                            AndroidDynamicTestContext.projectIdFor(db.authDao())?.let { projectId ->
                                                enrichedPayload["project_id"] = projectId
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
                                    } else if (entityType in setOf("FARMER", "PARCEL", "SOIL_PROFILE")) {
                                        val entityId = saveProfileFormLocally(
                                            db = db,
                                            entityType = entityType,
                                            payload = payload,
                                            now = now
                                        )
                                        val dependencyIds = when (entityType) {
                                            "PARCEL" -> payload["farmer_id"]?.toString()
                                            "SOIL_PROFILE" -> listOfNotNull(
                                                payload["parcel_id"]?.toString(),
                                                payload["farmer_id"]?.toString()
                                            ).joinToString(",").ifBlank { null }
                                            else -> null
                                        }
                                        db.syncQueueDao().enqueue(SyncQueueEntity(
                                            eventId = UUID.randomUUID().toString(),
                                            entityType = entityType,
                                            entityId = entityId,
                                            operation = "CREATE",
                                            payload = Gson().toJson(payload),
                                            syncStatus = SyncStatus.PENDING.name,
                                            priority = SyncPriority.HIGH.name,
                                            dependencyIds = dependencyIds,
                                            createdAt = now
                                        ))
                                        enqueueGeometryIfPresent(
                                            entityType = entityType,
                                            entityId = entityId,
                                            payload = payload,
                                            dependencyIds = entityId
                                        )
                                        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
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

private fun resolveProfileEntityType(formId: String, schema: FormSchemaDto): String {
    return when (formId) {
        "farmer_registration" -> "FARMER"
        "parcel_registration" -> "PARCEL"
        "soil_profile" -> "SOIL_PROFILE"
        "crop_cycle_create" -> "CROP_CYCLE"
        else -> schema.entityType
    }
}

private fun coerceDefaultValue(fieldType: String, defaultValue: String?): Any? {
    if (defaultValue == null) return null
    return when (fieldType) {
        "boolean" -> defaultValue.equals("true", ignoreCase = true)
        "number" -> defaultValue.toDoubleOrNull() ?: defaultValue
        "date" -> {
            if (defaultValue == "today") {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            } else {
                defaultValue
            }
        }
        else -> defaultValue
    }
}

private suspend fun saveProfileFormLocally(
    db: AppDatabase,
    entityType: String,
    payload: Map<String, Any?>,
    now: Long
): String {
    val authState = db.authDao().getAuthState()
    val actorId = authState?.userId ?: "unknown"
    return when (entityType) {
        "FARMER" -> {
            val farmerId = payload.stringValue("id") ?: UUID.randomUUID().toString()
            val villageId = payload.stringValue("village_id") ?: ""
            val enrollmentPoint = payload.geoJsonValue("enrollment_location")
                ?: payload.geoJsonValue("enrollment_gps")
            val point = GeoJson.parsePoint(enrollmentPoint)
            db.farmerDao().insert(
                FarmerEntity(
                    id = farmerId,
                    mobileNumber = payload.stringValue("mobile_number") ?: "",
                    villageId = villageId,
                    villageName = payload.stringValue("village_name_manual"),
                    primaryCropCode = payload.stringValue("primary_crop_code"),
                    displayName = payload.stringValue("display_name"),
                    fatherName = payload.stringValue("father_name"),
                    age = payload.intValue("age"),
                    gender = payload.stringValue("gender"),
                    aadhaarNumber = payload.stringValue("aadhaar_number"),
                    assistanceMode = payload.stringValue("assistance_mode") ?: "DEALER_ASSISTED",
                    syncStatus = SyncStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                    actorId = actorId,
                    gpsLat = point?.lat ?: payload.doubleValue("enrollment_gps_lat"),
                    gpsLng = point?.lng ?: payload.doubleValue("enrollment_gps_lng")
                )
            )
            farmerId
        }

        "PARCEL" -> {
            val parcelId = payload.stringValue("id") ?: UUID.randomUUID().toString()
            val farmerId = payload.stringValue("farmer_id")
                ?: db.farmerDao().getFirst()?.id
                ?: throw IllegalStateException("Missing farmer_id for parcel")
            val farmer = db.farmerDao().getById(farmerId)
            val area = payload.doubleValue("reported_area")
                ?: throw IllegalStateException("Missing reported_area for parcel")
            val unit = payload.stringValue("reported_area_unit") ?: "BIGHA"
            val pinDrop = payload.geoJsonValue("parcel_location")
            val boundary = payload.geoJsonValue("parcel_boundary")
            val point = GeoJson.parsePoint(pinDrop)
            val geometrySource = when {
                !boundary.isNullOrBlank() -> "GPS_WALK"
                !pinDrop.isNullOrBlank() -> "PIN_DROP"
                else -> payload.stringValue("geometry_source") ?: "NONE"
            }
            db.parcelDao().insert(
                ParcelEntity(
                    id = parcelId,
                    farmerId = farmerId,
                    villageId = payload.stringValue("village_id") ?: farmer?.villageId ?: "",
                    villageName = payload.stringValue("village_name_manual") ?: farmer?.villageName,
                    reportedArea = area,
                    reportedAreaUnit = unit,
                    areaHectares = UnitConverter.toHectares(area, unit),
                    geometrySource = geometrySource,
                    gpsLat = point?.lat ?: payload.doubleValue("centroid_lat"),
                    gpsLng = point?.lng ?: payload.doubleValue("centroid_lng"),
                    ownershipType = payload.stringValue("ownership_type") ?: "OWNED",
                    irrigationSource = payload.stringValue("irrigation_source"),
                    surveyNumber = payload.stringValue("survey_number"),
                    annualRent = payload.doubleValue("annual_rent"),
                    sharePercentage = payload.intValue("share_percentage"),
                    sharecropPercentage = payload.intValue("sharecrop_percentage"),
                    syncStatus = SyncStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                    actorId = actorId
                )
            )
            parcelId
        }

        "SOIL_PROFILE" -> {
            val soilProfileId = payload.stringValue("id") ?: UUID.randomUUID().toString()
            val parcelId = payload.stringValue("parcel_id")
                ?: throw IllegalStateException("Missing parcel_id for soil profile")
            val farmerId = payload.stringValue("farmer_id")
                ?: db.parcelDao().getById(parcelId)?.farmerId
                ?: throw IllegalStateException("Missing farmer_id for soil profile")
            db.soilProfileDao().insert(
                SoilProfileEntity(
                    id = soilProfileId,
                    parcelId = parcelId,
                    farmerId = farmerId,
                    soilTypeCode = payload.stringValue("soil_type_code"),
                    soilTexture = payload.stringValue("soil_texture"),
                    soilColor = payload.stringValue("soil_color"),
                    ph = payload.doubleValue("ph"),
                    nitrogenN = payload.doubleValue("nitrogen_n"),
                    phosphorusP = payload.doubleValue("phosphorus_p"),
                    potassiumK = payload.doubleValue("potassium_k"),
                    sulphurS = payload.doubleValue("sulphur_s"),
                    zincZn = payload.doubleValue("zinc_zn"),
                    ironFe = payload.doubleValue("iron_fe"),
                    copperCu = payload.doubleValue("copper_cu"),
                    manganeseMn = payload.doubleValue("manganese_mn"),
                    boronB = payload.doubleValue("boron_bo") ?: payload.doubleValue("boron_b"),
                    ec = payload.doubleValue("ec"),
                    organicCarbonOc = payload.doubleValue("organic_carbon_oc"),
                    shcCardNumber = payload.stringValue("shc_card_number"),
                    dataSource = payload.stringValue("data_source") ?: "MANUAL",
                    testDate = payload.stringValue("test_date"),
                    syncStatus = SyncStatus.PENDING.name,
                    createdAt = now,
                    updatedAt = now,
                    actorId = actorId
                )
            )
            soilProfileId
        }

        else -> UUID.randomUUID().toString()
    }
}

private suspend fun normalizeProfileSubmitPayload(
    formId: String,
    payload: Map<String, Any?>,
    db: AppDatabase
): Map<String, Any?> {
    val projectScopedPayload = payload.withDynamicProjectId(db)
    return when (formId) {
        "parcel_registration" -> projectScopedPayload.withParcelLocationScope(db)
        else -> projectScopedPayload
    }
}

private suspend fun Map<String, Any?>.withDynamicProjectId(db: AppDatabase): Map<String, Any?> {
    val projectId = AndroidDynamicTestContext.projectIdFor(db.authDao()) ?: return this
    if (stringValue("project_id") == projectId) return this
    return toMutableMap().apply {
        this["project_id"] = projectId
    }
}

private suspend fun Map<String, Any?>.withParcelLocationScope(db: AppDatabase): Map<String, Any?> {
    val farmerContext = db.resolveFarmerPayloadContext(stringValue("farmer_id"))
    val villageName = stringValue("village_name_manual")
        ?: farmerContext["village_name_manual"]?.toString()
    val pinCode = stringValue("pin_code")
        ?: farmerContext["pin_code"]?.toString()
    val villageId = stringValue("village_id")
        ?: farmerContext["village_id"]?.toString()

    if (villageName.isNullOrBlank() && pinCode.isNullOrBlank() && villageId.isNullOrBlank()) {
        return this
    }

    val locationScope = mutableMapOf<String, Any?>(
        "scope_type" to "SINGLE_VILLAGE"
    )
    villageId?.let { locationScope["village_id"] = it }
    villageName?.let { locationScope["village_name_manual"] = it }
    pinCode?.let { locationScope["pin_code"] = it }

    return toMutableMap().apply {
        this["location_scope"] = locationScope
    }
}

private suspend fun AppDatabase.resolveFarmerPayloadContext(farmerId: String?): Map<String, Any?> {
    if (farmerId.isNullOrBlank()) return emptyMap()

    val farmer = farmerDao().getById(farmerId)
    val fromLocal = mutableMapOf<String, Any?>()
    farmer?.villageId?.takeIf { it.isNotBlank() }?.let { fromLocal["village_id"] = it }
    farmer?.villageName?.takeIf { it.isNotBlank() }?.let { fromLocal["village_name_manual"] = it }

    val queuedPayload = syncQueueDao().getAllForDependencyCheck()
        .asSequence()
        .filter { it.entityType == "FARMER" && it.entityId == farmerId }
        .sortedByDescending { it.createdAt }
        .mapNotNull { item ->
            runCatching {
                JsonParser.parseString(item.payload).asJsonObject
            }.getOrNull()
        }
        .firstOrNull()

    if (queuedPayload != null) {
        listOf("village_id", "village_name_manual", "pin_code").forEach { key ->
            if (!queuedPayload.has(key) || queuedPayload.get(key).isJsonNull) return@forEach
            fromLocal[key] = queuedPayload.get(key).asString
        }
    }

    return fromLocal
}

private suspend fun enqueueGeometryIfPresent(
    entityType: String,
    entityId: String,
    payload: Map<String, Any?>,
    dependencyIds: String?
) {
    if (entityType != "PARCEL") return
    val boundary = payload.geoJsonValue("parcel_boundary")
    val pinDrop = payload.geoJsonValue("parcel_location")
    val geoJson = boundary ?: pinDrop ?: return
    val source = if (boundary != null) "GPS_WALK" else "PIN_DROP"
    GeometryRepository.enqueueParcelGeometry(
        syncQueueDao = AgriOsApp.instance.database.syncQueueDao(),
        parcelId = entityId,
        result = GeometryRepository.resultForSource(
            geometrySource = source,
            geoJson = geoJson
        ),
        dependencyIds = dependencyIds
    )
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any?>.doubleValue(key: String): Double? {
    return when (val value = this[key]) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull()
    }
}

private fun Map<String, Any?>.intValue(key: String): Int? {
    return when (val value = this[key]) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull()
    }
}

private fun Map<String, Any?>.geoJsonValue(key: String): String? {
    val value = this[key] ?: return null
    return value.toString().takeIf { GeoJson.isGeoJson(it) }
}

private fun resolveLandIntelligenceCropCode(formValues: Map<String, Any?>): String? {
    return formValues.stringValue("crop_code")
        ?: formValues.stringValue("primary_crop_code")
        ?: formValues.stringValue("current_crop_code")
        ?: formValues.firstListValue("kharif_crops")
        ?: formValues.firstListValue("rabi_crops")
        ?: formValues.firstListValue("zaid_crops")
}

private fun resolveLandIntelligenceSeasonCode(formValues: Map<String, Any?>): String? {
    formValues.stringValue("season_code")?.let { return it }
    if (formValues.firstListValue("kharif_crops") != null) return "KHARIF"
    if (formValues.firstListValue("rabi_crops") != null) return "RABI"
    if (formValues.firstListValue("zaid_crops") != null) return "ZAID"
    return null
}

private fun Map<String, Any?>.firstListValue(key: String): String? {
    val value = this[key] ?: return null
    return when (value) {
        is List<*> -> value.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
        else -> value.toString().takeIf { it.isNotBlank() }
    }
}

@Composable
private fun LandIntelligenceGuidanceCard(context: LandIntelligenceContextDto) {
    val suitability = context.cropSuitability
    val guidance = context.soilCaptureGuidance
    val firstRegion = context.climateContext?.regions?.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Land intelligence guidance", style = MaterialTheme.typography.titleSmall)
            firstRegion?.regionName?.let { regionName ->
                Text("Region: $regionName", style = MaterialTheme.typography.bodySmall)
            }
            context.climateContext?.mappingPrecision?.let { precision ->
                Text("Mapping precision: $precision", style = MaterialTheme.typography.bodySmall)
            }
            if (suitability?.inputProvided == true) {
                Text(
                    "Crop suitability: ${suitability.status ?: "UNKNOWN"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            guidance?.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            suitability?.warnings?.forEach { warning ->
                val severity = warning.severity?.takeIf { it.isNotBlank() } ?: "INFO"
                val message = warning.message?.takeIf { it.isNotBlank() } ?: warning.code ?: return@forEach
                Text("$severity: $message", style = MaterialTheme.typography.bodySmall)
            }
            if (suitability?.requiresConfirmation == true) {
                Text(
                    "Please confirm irrigation, local practice, and farmer observation before continuing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
