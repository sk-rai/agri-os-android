package com.agrios.app.ui.enrollment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.UnitConverter
import com.agrios.app.core.util.VillageIdUtil
import com.agrios.app.data.local.entity.*
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.ui.components.SearchableDropdown
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Parcel data holder for the form
 */
data class ParcelFormData(
    val id: String = UUID.randomUUID().toString(),
    var area: String = "",
    var unit: String = "BIGHA",
    var ownership: String = "OWNED",
    var sharePercentage: String = "",
    var sharecropPercentage: String = "",
    var surveyNumber: String = "",
    var annualRent: String = "",
    var irrigationSource: String = "",
    var gpsMode: String = "NONE",
    var lat: String = "",
    var lng: String = "",
    // Season crops (per parcel)
    var kharifCrops: Set<String> = emptySet(),
    var rabiCrops: Set<String> = emptySet(),
    var zaidCrops: Set<String> = emptySet(),
    // Inline soil
    var soilTexture: String = "",
    var soilColor: String = "",
    var isExpanded: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UnifiedEnrollmentScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // Farmer fields
    var mobileNumber by remember { mutableStateOf("+91") }
    var displayName by remember { mutableStateOf("") }
    var fatherName by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var aadhaarNumber by remember { mutableStateOf("") }
    var pinCode by remember { mutableStateOf("") }

    // Geography
    var states by remember { mutableStateOf<List<GeographyStateEntity>>(emptyList()) }
    var districts by remember { mutableStateOf<List<GeographyDistrictEntity>>(emptyList()) }
    var blocks by remember { mutableStateOf<List<GeographyBlockEntity>>(emptyList()) }
    var villages by remember { mutableStateOf<List<GeographyVillageEntity>>(emptyList()) }
    var selectedStateId by remember { mutableStateOf("") }
    var selectedDistrictId by remember { mutableStateOf("") }
    var selectedBlockId by remember { mutableStateOf("") }
    var selectedVillageId by remember { mutableStateOf("") }
    var selectedVillageName by remember { mutableStateOf("") }
    var isManualVillage by remember { mutableStateOf(false) }

    // Parcels (expandable list)
    var parcels by remember { mutableStateOf(listOf(ParcelFormData())) }

    // Soil inference
    var inferredSoilType by remember { mutableStateOf("") }
    var inferredSoilTypeName by remember { mutableStateOf("") }

    // State
    var isSaving by remember { mutableStateOf(false) }
    var isFormLocked by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var saveSuccess by remember { mutableStateOf(false) }

    // Load geography
    LaunchedEffect(Unit) { db.geographyCacheDao().getAllStates().collect { states = it } }
    LaunchedEffect(selectedStateId) {
        if (selectedStateId.isNotEmpty()) {
            db.geographyCacheDao().getDistrictsByState(selectedStateId).collect { districts = it }
        }
    }
    LaunchedEffect(selectedDistrictId) {
        if (selectedDistrictId.isNotEmpty()) {
            db.geographyCacheDao().getBlocksByDistrict(selectedDistrictId).collect {
                if (it.isEmpty()) {
                    val okHttp = OkHttpClient.Builder().addInterceptor(AuthInterceptor(db.authDao())).build()
                    val api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL).client(okHttp)
                        .addConverterFactory(GsonConverterFactory.create()).build().create(AgriOsApi::class.java)
                    com.agrios.app.data.repository.MasterDataRepository(api, db.geographyCacheDao()).downloadBlocksForDistrict(selectedDistrictId)
                    db.geographyCacheDao().getBlocksByDistrict(selectedDistrictId).collect { blocks = it }
                } else blocks = it
            }
        }
    }

    // Load villages district-wide (separate LaunchedEffect to avoid being blocked by Flow.collect)
    LaunchedEffect(selectedDistrictId) {
        if (selectedDistrictId.isNotEmpty()) {
            db.geographyCacheDao().getVillagesByDistrict(selectedDistrictId).collect {
                if (it.isEmpty()) {
                    android.util.Log.d("UnifiedEnroll", "Villages not cached for district $selectedDistrictId, downloading...")
                    val okHttp = OkHttpClient.Builder().addInterceptor(AuthInterceptor(db.authDao()))
                        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                    val api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL).client(okHttp)
                        .addConverterFactory(GsonConverterFactory.create()).build().create(AgriOsApi::class.java)
                    com.agrios.app.data.repository.MasterDataRepository(api, db.geographyCacheDao()).downloadVillagesForDistrict(selectedDistrictId)
                    db.geographyCacheDao().getVillagesByDistrict(selectedDistrictId).collect {
                        android.util.Log.d("UnifiedEnroll", "Villages loaded: ${it.size}")
                        villages = it
                    }
                } else {
                    android.util.Log.d("UnifiedEnroll", "Villages from cache: ${it.size}")
                    villages = it
                }
            }
        }
    }

    // Infer soil type when district is selected
    LaunchedEffect(selectedDistrictId, districts) {
        if (selectedDistrictId.isNotEmpty()) {
            try {
                val districtName = districts.find { it.id == selectedDistrictId }?.canonicalName ?: ""
                if (districtName.isNotEmpty()) {
                    val okHttp = OkHttpClient.Builder().addInterceptor(AuthInterceptor(db.authDao())).connectTimeout(10, TimeUnit.SECONDS).build()
                    val api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL).client(okHttp)
                        .addConverterFactory(GsonConverterFactory.create()).build().create(AgriOsApi::class.java)
                    val resp = api.inferSoilType(districtName)
                    if (resp.isSuccessful) {
                        inferredSoilType = resp.body()!!.inferredSoilType
                        inferredSoilTypeName = resp.body()!!.inferredSoilTypeName
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val areaUnits = UnitConverter.getDisplayUnits()
    val textureOptions = listOf("SANDY", "LOAMY", "CLAY", "SANDY_LOAM", "CLAY_LOAM")
    val colorOptions = listOf("DARK_BROWN", "LIGHT_BROWN", "REDDISH", "BLACK", "GREY")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.localize("Farmer Enrollment", "किसान नामांकन")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (saveSuccess) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ ${LanguageManager.localize("Profile saved & uploading!", "प्रोफ़ाइल सहेजा और अपलोड हो रहा!")}", style = MaterialTheme.typography.titleSmall)
                        Text(LanguageManager.localize("Syncing in background...", "पृष्ठभूमि में सिंक हो रहा है..."), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
                    Text(LanguageManager.localize("Go to Home", "होम पर जाएं"))
                }
                return@Column
            }

            // ═══════════ FARMER DETAILS ═══════════
            Text("👤 ${LanguageManager.localize("Farmer Details", "किसान विवरण")}", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(value = mobileNumber, onValueChange = { if (it.length <= 13) mobileNumber = it },
                label = { Text("${Labels.mobileNumber} *") }, enabled = !isFormLocked,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                label = { Text(Labels.farmerName) }, enabled = !isFormLocked, singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = aadhaarNumber, onValueChange = { aadhaarNumber = it.filter { c -> c.isDigit() }.take(12) },
                label = { Text(LanguageManager.localize("Aadhaar Number", "आधार नंबर")) }, enabled = !isFormLocked,
                placeholder = { Text("XXXX XXXX XXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = fatherName, onValueChange = { fatherName = it },
                label = { Text(LanguageManager.localize("Father's Name", "पिता का नाम")) }, enabled = !isFormLocked,
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = age, onValueChange = { age = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text(LanguageManager.localize("Age", "उम्र")) }, enabled = !isFormLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))

                Column(modifier = Modifier.weight(2f)) {
                    Text(LanguageManager.localize("Gender", "लिंग"), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("MALE" to LanguageManager.localize("Male", "पुरुष"),
                            "FEMALE" to LanguageManager.localize("Female", "महिला"),
                            "OTHER" to LanguageManager.localize("Other", "अन्य")).forEach { (code, label) ->
                            FilterChip(selected = gender == code, onClick = { gender = code },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isFormLocked)
                        }
                    }
                }
            }

            // Geography
            SearchableDropdown(label = Labels.selectState, items = states.map { it.id to it.canonicalName },
                selectedId = selectedStateId, onSelect = { id, _ -> selectedStateId = id; selectedDistrictId = ""; selectedBlockId = ""; selectedVillageId = "" })

            if (selectedStateId.isNotEmpty())
                SearchableDropdown(label = Labels.selectDistrict, items = districts.map { it.id to it.canonicalName },
                    selectedId = selectedDistrictId, onSelect = { id, _ -> selectedDistrictId = id; selectedBlockId = ""; selectedVillageId = "" })

            if (selectedDistrictId.isNotEmpty())
                SearchableDropdown(label = Labels.selectBlock, items = blocks.map { it.id to it.canonicalName },
                    selectedId = selectedBlockId, onSelect = { id, _ -> selectedBlockId = id })

            if (selectedDistrictId.isNotEmpty())
                SearchableDropdown(label = Labels.selectVillage, items = villages.map { it.id to it.canonicalName },
                    selectedId = selectedVillageId, allowManualEntry = true,
                    onSelect = { id, name -> selectedVillageId = id; selectedVillageName = name; isManualVillage = false
                        villages.find { it.id == id }?.pinCodes?.let { pc ->
                            val cleaned = pc.replace("[", "").replace("]", "").replace("\"", "").trim()
                            if (cleaned.isNotBlank()) pinCode = cleaned.split(",").first().trim()
                        }
                    },
                    onManualEntry = { name -> isManualVillage = true; selectedVillageName = name; selectedVillageId = "manual_$name" })

            if (selectedVillageId.isNotEmpty())
                OutlinedTextField(value = pinCode, onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinCode = it },
                    label = { Text(LanguageManager.localize("PIN Code", "पिन कोड")) }, enabled = !isFormLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()

            // ═══════════ LAND PARCELS ═══════════
            Text("📍 ${LanguageManager.localize("Land Parcels", "भूमि भूखंड")} (${parcels.size})", style = MaterialTheme.typography.titleMedium)

            parcels.forEachIndexed { index, parcel ->
                ParcelSection(
                    index = index,
                    parcel = parcel,
                    isLocked = isFormLocked,
                    areaUnits = areaUnits,
                    inferredSoilTypeName = inferredSoilTypeName,
                    textureOptions = textureOptions,
                    colorOptions = colorOptions,
                    onUpdate = { updated -> parcels = parcels.toMutableList().also { it[index] = updated } },
                    onRemove = if (parcels.size > 1) { { parcels = parcels.toMutableList().also { it.removeAt(index) } } } else null
                )
            }

            // Add parcel button
            if (!isFormLocked) {
                OutlinedButton(
                    onClick = { parcels = parcels + ParcelFormData() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(LanguageManager.localize("+ Add Another Parcel", "+ एक और भूखंड जोड़ें"))
                }
            }

            // Error
            if (errorMessage != null) Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(16.dp))

            // ═══════════ SAVE BUTTON ═══════════
            if (!isFormLocked) {
                Button(
                    onClick = {
                        // Validate
                        if (mobileNumber.length < 12) { errorMessage = LanguageManager.localize("Enter valid mobile number", "सही मोबाइल नंबर दर्ज करें"); return@Button }
                        if (selectedVillageId.isEmpty() && !isManualVillage) { errorMessage = LanguageManager.localize("Select village", "गाँव चुनें"); return@Button }
                        val invalidParcel = parcels.find { it.area.toDoubleOrNull() == null || it.area.toDoubleOrNull()!! <= 0 }
                        if (invalidParcel != null) { errorMessage = LanguageManager.localize("Enter valid area for all parcels", "सभी भूखंडों के लिए सही क्षेत्रफल दर्ज करें"); return@Button }
                        val leasedWithoutRent = parcels.find { it.ownership == "LEASED" && it.annualRent.toDoubleOrNull() == null }
                        if (leasedWithoutRent != null) { errorMessage = LanguageManager.localize("Enter annual rent for leased parcels", "पट्टे वाले भूखंडों के लिए वार्षिक किराया दर्ज करें"); return@Button }
                        errorMessage = null; isSaving = true

                        scope.launch {
                            val now = System.currentTimeMillis()
                            val farmerId = UUID.randomUUID().toString()
                            val authState = db.authDao().getAuthState()
                            val villageId = if (isManualVillage) "manual_$selectedVillageName" else selectedVillageId

                            // Save farmer
                            db.farmerDao().insert(FarmerEntity(
                                id = farmerId, mobileNumber = mobileNumber, villageId = villageId,
                                villageName = selectedVillageName, primaryCropCode = null,
                                displayName = displayName.ifBlank { null }, fatherName = fatherName.ifBlank { null },
                                age = age.toIntOrNull(), gender = gender.ifBlank { null },
                                aadhaarNumber = aadhaarNumber.ifBlank { null },
                                assistanceMode = "DEALER_ASSISTED", syncStatus = SyncStatus.PENDING.name,
                                createdAt = now, updatedAt = now, actorId = authState?.userId ?: "unknown"
                            ))
                            // Queue farmer sync
                            db.syncQueueDao().enqueue(SyncQueueEntity(
                                eventId = UUID.randomUUID().toString(), entityType = "FARMER", entityId = farmerId,
                                operation = "CREATE", payload = Gson().toJson(mapOf(
                                    "mobile_number" to mobileNumber,
                                    "village_id" to VillageIdUtil.getSyncVillageId(villageId),
                                    "village_name_manual" to VillageIdUtil.getSyncVillageNameManual(villageId, selectedVillageName),
                                    "pin_code" to pinCode.ifBlank { null },
                                    "display_name" to displayName.ifBlank { null },
                                    "father_name" to fatherName.ifBlank { null },
                                    "age" to age.toIntOrNull(),
                                    "gender" to gender.ifBlank { null },
                                    "aadhaar_number" to aadhaarNumber.ifBlank { null },
                                    "language_preference" to LanguageManager.getLanguage(),
                                    "assistance_mode" to "DEALER_ASSISTED"
                                )), syncStatus = SyncStatus.PENDING.name, priority = SyncPriority.HIGH.name, createdAt = now
                            ))

                            // Save each parcel + soil
                            for (parcel in parcels) {
                                val parcelId = parcel.id
                                val area = parcel.area.toDouble()
                                db.parcelDao().insert(ParcelEntity(
                                    id = parcelId, farmerId = farmerId, villageId = villageId, villageName = selectedVillageName,
                                    reportedArea = area, reportedAreaUnit = parcel.unit,
                                    areaHectares = UnitConverter.toHectares(area, parcel.unit),
                                    geometrySource = parcel.gpsMode, ownershipType = parcel.ownership,
                                    irrigationSource = parcel.irrigationSource.ifBlank { null },
                                    surveyNumber = parcel.surveyNumber.ifBlank { null },
                                    annualRent = parcel.annualRent.toDoubleOrNull(),
                                    sharePercentage = parcel.sharePercentage.toIntOrNull(),
                                    sharecropPercentage = parcel.sharecropPercentage.toIntOrNull(),
                                    syncStatus = SyncStatus.PENDING.name, createdAt = now, updatedAt = now, actorId = authState?.userId ?: "unknown"
                                ))
                                db.syncQueueDao().enqueue(SyncQueueEntity(
                                    eventId = UUID.randomUUID().toString(), entityType = "PARCEL", entityId = parcelId,
                                    operation = "CREATE", payload = Gson().toJson(mapOf(
                                        "farmer_id" to farmerId,
                                        "village_id" to VillageIdUtil.getSyncVillageId(villageId),
                                        "village_name_manual" to VillageIdUtil.getSyncVillageNameManual(villageId, selectedVillageName),
                                        "reported_area" to area, "reported_area_unit" to parcel.unit,
                                        "ownership_type" to parcel.ownership, "geometry_source" to parcel.gpsMode,
                                        "irrigation_source" to parcel.irrigationSource.ifBlank { null },
                                        "survey_number" to parcel.surveyNumber.ifBlank { null },
                                        "annual_rent" to parcel.annualRent.toDoubleOrNull(),
                                        "share_percentage" to parcel.sharePercentage.toIntOrNull(),
                                        "sharecrop_percentage" to parcel.sharecropPercentage.toIntOrNull(),
                                        "crops_by_season" to mapOf(
                                            "KHARIF" to parcel.kharifCrops.toList(),
                                            "RABI" to parcel.rabiCrops.toList(),
                                            "ZAID" to parcel.zaidCrops.toList()
                                        )
                                    )), syncStatus = SyncStatus.PENDING.name, priority = SyncPriority.HIGH.name,
                                    dependencyIds = farmerId, createdAt = now
                                ))

                                // Soil profile (if texture or color selected)
                                if (parcel.soilTexture.isNotBlank() || parcel.soilColor.isNotBlank() || inferredSoilType.isNotBlank()) {
                                    val soilId = UUID.randomUUID().toString()
                                    db.soilProfileDao().insert(SoilProfileEntity(
                                        id = soilId, parcelId = parcelId, farmerId = farmerId,
                                        soilTypeCode = inferredSoilType.ifBlank { null },
                                        soilTexture = parcel.soilTexture.ifBlank { null },
                                        soilColor = parcel.soilColor.ifBlank { null },
                                        dataSource = "INFERRED", syncStatus = SyncStatus.PENDING.name,
                                        createdAt = now, updatedAt = now, actorId = authState?.userId ?: "unknown"
                                    ))
                                    db.syncQueueDao().enqueue(SyncQueueEntity(
                                        eventId = UUID.randomUUID().toString(), entityType = "SOIL_PROFILE", entityId = soilId,
                                        operation = "CREATE", payload = Gson().toJson(mapOf(
                                            "parcel_id" to parcelId, "farmer_id" to farmerId,
                                            "soil_type_code" to inferredSoilType.ifBlank { null },
                                            "soil_texture" to parcel.soilTexture.ifBlank { null },
                                            "soil_color" to parcel.soilColor.ifBlank { null },
                                            "data_source" to "INFERRED"
                                        )), syncStatus = SyncStatus.PENDING.name, priority = SyncPriority.MEDIUM.name,
                                        dependencyIds = parcelId, createdAt = now
                                    ))
                                }
                            }

                            // Auto-trigger sync
                            SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                            isSaving = false; isFormLocked = true; saveSuccess = true
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    else Text(LanguageManager.localize("💾 Save & Upload", "💾 सहेजें और अपलोड करें"), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParcelSection(
    index: Int,
    parcel: ParcelFormData,
    isLocked: Boolean,
    areaUnits: List<String>,
    inferredSoilTypeName: String,
    textureOptions: List<String>,
    colorOptions: List<String>,
    onUpdate: (ParcelFormData) -> Unit,
    onRemove: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📍 ${LanguageManager.localize("Parcel", "भूखंड")} ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (onRemove != null && !isLocked) {
                    IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                }
            }

            // Area + Unit
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = parcel.area, onValueChange = { onUpdate(parcel.copy(area = it.filter { c -> c.isDigit() || c == '.' })) },
                    label = { Text("${Labels.area} *") }, enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                // Unit dropdown simplified as chips below
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                areaUnits.forEach { unit ->
                    FilterChip(selected = parcel.unit == unit, onClick = { onUpdate(parcel.copy(unit = unit)) },
                        label = { Text(Labels.getUnitLabel(unit), style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            // Ownership
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("OWNED", "LEASED", "SHARED", "SHARECROP", "FAMILY").forEach { type ->
                    FilterChip(selected = parcel.ownership == type, onClick = { onUpdate(parcel.copy(ownership = type)) },
                        label = { Text(when (type) {
                            "OWNED" -> LanguageManager.localize("Owned", "स्वामित्व")
                            "LEASED" -> LanguageManager.localize("Leased", "पट्टा")
                            "SHARED" -> LanguageManager.localize("Shared", "साझा")
                            "SHARECROP" -> LanguageManager.localize("Sharecrop", "बटाई")
                            "FAMILY" -> LanguageManager.localize("Family", "पारिवारिक")
                            else -> type
                        }, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            // Annual rent (only for LEASED)
            AnimatedVisibility(visible = parcel.ownership == "LEASED") {
                OutlinedTextField(value = parcel.annualRent, onValueChange = { onUpdate(parcel.copy(annualRent = it.filter { c -> c.isDigit() || c == '.' })) },
                    label = { Text(LanguageManager.localize("Annual Rent (₹)", "वार्षिक किराया (₹)")) }, enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            // Share percentage (for SHARED)
            AnimatedVisibility(visible = parcel.ownership == "SHARED") {
                OutlinedTextField(value = parcel.sharePercentage, onValueChange = { onUpdate(parcel.copy(sharePercentage = it.filter { c -> c.isDigit() }.take(3))) },
                    label = { Text(LanguageManager.localize("Your Share (%)", "आपका हिस्सा (%)")) }, enabled = !isLocked,
                    placeholder = { Text("e.g. 50") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            // Sharecrop percentage (for SHARECROP)
            AnimatedVisibility(visible = parcel.ownership == "SHARECROP") {
                OutlinedTextField(value = parcel.sharecropPercentage, onValueChange = { onUpdate(parcel.copy(sharecropPercentage = it.filter { c -> c.isDigit() }.take(3))) },
                    label = { Text(LanguageManager.localize("Harvest Share to Owner (%)", "मालिक को फसल हिस्सा (%)")) }, enabled = !isLocked,
                    placeholder = { Text("e.g. 40") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            // Irrigation source
            Text(LanguageManager.localize("Irrigation", "सिंचाई"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "TUBEWELL_DIESEL" to LanguageManager.localize("Tubewell (Diesel)", "बोरवेल (डीजल)"),
                    "TUBEWELL_ELECTRIC" to LanguageManager.localize("Tubewell (Electric)", "बोरवेल (बिजली)"),
                    "CANAL" to LanguageManager.localize("Canal", "नहर"),
                    "PURCHASED_WATER" to LanguageManager.localize("Purchased", "खरीदा पानी"),
                    "RAIN_FED" to LanguageManager.localize("Rain-fed", "वर्षा आधारित"),
                    "POND_TANK" to LanguageManager.localize("Pond/Tank", "तालाब"),
                    "RIVER_STREAM" to LanguageManager.localize("River", "नदी/नाला")
                ).forEach { (code, label) ->
                    FilterChip(selected = parcel.irrigationSource == code, onClick = { onUpdate(parcel.copy(irrigationSource = code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            // Survey number
            OutlinedTextField(value = parcel.surveyNumber, onValueChange = { onUpdate(parcel.copy(surveyNumber = it)) },
                label = { Text(LanguageManager.localize("Survey/Khasra No.", "सर्वे/खसरा नं.")) }, enabled = !isLocked,
                singleLine = true, modifier = Modifier.fillMaxWidth())

            // GPS (optional)
            Text(LanguageManager.localize("GPS Location (optional)", "GPS स्थान (वैकल्पिक)"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "NONE" to LanguageManager.localize("None", "नहीं"),
                    "PIN_DROP" to LanguageManager.localize("Pin Drop", "पिन ड्रॉप"),
                    "PIN_CORNERS" to LanguageManager.localize("Pin + Corners", "पिन + कोने"),
                    "GPS_WALK" to LanguageManager.localize("Walk Boundary", "सीमा चलें")
                ).forEach { (code, label) ->
                    FilterChip(selected = parcel.gpsMode == code, onClick = { onUpdate(parcel.copy(gpsMode = code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            // GPS coordinates input (for PIN_DROP — centroid only)
            AnimatedVisibility(visible = parcel.gpsMode == "PIN_DROP") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(LanguageManager.localize("Centroid (center of field)", "केंद्र बिंदु (खेत का मध्य)"), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = parcel.lat, onValueChange = { onUpdate(parcel.copy(lat = it)) },
                            label = { Text("Lat") }, enabled = !isLocked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = parcel.lng, onValueChange = { onUpdate(parcel.copy(lng = it)) },
                            label = { Text("Lng") }, enabled = !isLocked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
            }

            // GPS coordinates input (for PIN_CORNERS — centroid + boundary corners)
            AnimatedVisibility(visible = parcel.gpsMode == "PIN_CORNERS") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LanguageManager.localize("Centroid (center of field)", "केंद्र बिंदु (खेत का मध्य)"), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = parcel.lat, onValueChange = { onUpdate(parcel.copy(lat = it)) },
                            label = { Text("Lat") }, enabled = !isLocked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = parcel.lng, onValueChange = { onUpdate(parcel.copy(lng = it)) },
                            label = { Text("Lng") }, enabled = !isLocked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text(LanguageManager.localize("Boundary Corners (at least 3)", "सीमा कोने (कम से कम 3)"), style = MaterialTheme.typography.labelMedium)
                    Text(LanguageManager.localize("Stand at each corner and note GPS coordinates", "प्रत्येक कोने पर खड़े होकर GPS निर्देशांक नोट करें"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Corner points placeholder — future: auto-capture from device GPS
                    Text("📍 ${LanguageManager.localize("Corner capture coming soon. Use Pin Drop for now.", "कोने कैप्चर जल्द आ रहा। अभी पिन ड्रॉप उपयोग करें।")}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            AnimatedVisibility(visible = parcel.gpsMode == "GPS_WALK") {
                Text(
                    LanguageManager.localize("🚶 Walk around field boundary (coming soon)", "🚶 खेत की सीमा चलें (जल्द आ रहा)"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                )
            }

            // Inline soil
            HorizontalDivider()
            
            // Season crops per parcel
            Text("🌾 ${LanguageManager.localize("Crops", "फसलें")}", style = MaterialTheme.typography.labelLarge)
            val isHindi = LanguageManager.isHindi()
            
            Text(LanguageManager.localize("Kharif", "खरीफ"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("RICE" to if (isHindi) "धान" else "Rice", "MAIZE" to if (isHindi) "मक्का" else "Maize",
                    "SUGARCANE" to if (isHindi) "गन्ना" else "Sugarcane", "BAJRA" to if (isHindi) "बाजरा" else "Bajra",
                    "GROUNDNUT" to if (isHindi) "मूंगफली" else "Groundnut").forEach { (code, label) ->
                    FilterChip(selected = code in parcel.kharifCrops, onClick = { onUpdate(parcel.copy(kharifCrops = if (code in parcel.kharifCrops) parcel.kharifCrops - code else parcel.kharifCrops + code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }
            Text(LanguageManager.localize("Rabi", "रबी"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("WHEAT" to if (isHindi) "गेहूं" else "Wheat", "GRAM" to if (isHindi) "चना" else "Gram",
                    "MUSTARD" to if (isHindi) "सरसों" else "Mustard", "LENTIL" to if (isHindi) "मसूर" else "Lentil",
                    "POTATO" to if (isHindi) "आलू" else "Potato").forEach { (code, label) ->
                    FilterChip(selected = code in parcel.rabiCrops, onClick = { onUpdate(parcel.copy(rabiCrops = if (code in parcel.rabiCrops) parcel.rabiCrops - code else parcel.rabiCrops + code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }
            Text(LanguageManager.localize("Zaid", "ज़ायद"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("MOONG" to if (isHindi) "मूंग" else "Moong", "WATERMELON" to if (isHindi) "तरबूज" else "Watermelon",
                    "CUCUMBER" to if (isHindi) "खीरा" else "Cucumber").forEach { (code, label) ->
                    FilterChip(selected = code in parcel.zaidCrops, onClick = { onUpdate(parcel.copy(zaidCrops = if (code in parcel.zaidCrops) parcel.zaidCrops - code else parcel.zaidCrops + code)) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            HorizontalDivider()
            Text("🌍 ${LanguageManager.localize("Soil", "मिट्टी")}: ${inferredSoilTypeName.ifBlank { "—" }}", style = MaterialTheme.typography.labelLarge)

            Text(LanguageManager.localize("Texture", "बनावट"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                textureOptions.forEach { opt ->
                    FilterChip(selected = parcel.soilTexture == opt, onClick = { onUpdate(parcel.copy(soilTexture = opt)) },
                        label = { Text(opt.replace("_", " "), style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }

            Text(LanguageManager.localize("Color", "रंग"), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                colorOptions.forEach { opt ->
                    FilterChip(selected = parcel.soilColor == opt, onClick = { onUpdate(parcel.copy(soilColor = opt)) },
                        label = { Text(opt.replace("_", " "), style = MaterialTheme.typography.labelSmall) }, enabled = !isLocked)
                }
            }
        }
    }
}
