package com.agrios.app.ui.farmer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.*
import com.agrios.app.ui.components.SearchableDropdown
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FarmerEnrollScreen(onBack: () -> Unit) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // Form state
    var mobileNumber by remember { mutableStateOf("+91") }
    var displayName by remember { mutableStateOf("") }
    var pinCode by remember { mutableStateOf("") }

    // Cascading geography
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

    // Season-wise crops
    var selectedKharif by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRabi by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedZaid by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isSaving by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load states
    LaunchedEffect(Unit) {
        db.geographyCacheDao().getAllStates().collect { states = it }
    }

    // Load districts when state changes
    LaunchedEffect(selectedStateId) {
        if (selectedStateId.isNotEmpty()) {
            db.geographyCacheDao().getDistrictsByState(selectedStateId).collect { 
                android.util.Log.d("FarmerEnroll", "Districts loaded for state $selectedStateId: ${it.size} items")
                districts = it 
            }
        } else {
            districts = emptyList()
        }
    }

    // Load blocks when district changes (on-demand from API if not cached)
    LaunchedEffect(selectedDistrictId) {
        if (selectedDistrictId.isNotEmpty()) {
            db.geographyCacheDao().getBlocksByDistrict(selectedDistrictId).collect { 
                if (it.isEmpty()) {
                    // Not cached yet — download from API
                    android.util.Log.d("FarmerEnroll", "Blocks not cached for district $selectedDistrictId, downloading...")
                    val okHttpClient = okhttp3.OkHttpClient.Builder()
                        .addInterceptor(com.agrios.app.core.network.AuthInterceptor(db.authDao()))
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val api = retrofit2.Retrofit.Builder()
                        .baseUrl(com.agrios.app.core.network.ApiConfig.BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                        .build()
                        .create(com.agrios.app.data.remote.api.AgriOsApi::class.java)
                    val repo = com.agrios.app.data.repository.MasterDataRepository(api, db.geographyCacheDao())
                    repo.downloadBlocksForDistrict(selectedDistrictId)
                    // Re-query after download
                    db.geographyCacheDao().getBlocksByDistrict(selectedDistrictId).collect { blocks = it }
                } else {
                    blocks = it
                }
            }
        } else {
            blocks = emptyList()
        }
    }

    // Load villages when district is selected (district-wide, not block-scoped)
    LaunchedEffect(selectedDistrictId) {
        if (selectedDistrictId.isNotEmpty()) {
            // First check if we have cached villages for this district
            db.geographyCacheDao().getVillagesByDistrict(selectedDistrictId).collect { cachedVillages ->
                if (cachedVillages.isEmpty()) {
                    android.util.Log.d("FarmerEnroll", "No villages cached for district $selectedDistrictId, downloading...")
                    val okHttpClient = okhttp3.OkHttpClient.Builder()
                        .addInterceptor(com.agrios.app.core.network.AuthInterceptor(db.authDao()))
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val api = retrofit2.Retrofit.Builder()
                        .baseUrl(com.agrios.app.core.network.ApiConfig.BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                        .build()
                        .create(com.agrios.app.data.remote.api.AgriOsApi::class.java)
                    val repo = com.agrios.app.data.repository.MasterDataRepository(api, db.geographyCacheDao())
                    repo.downloadVillagesForDistrict(selectedDistrictId)
                    // Re-collect after download
                    db.geographyCacheDao().getVillagesByDistrict(selectedDistrictId).collect { villages = it }
                } else {
                    android.util.Log.d("FarmerEnroll", "Loaded ${cachedVillages.size} villages for district $selectedDistrictId")
                    villages = cachedVillages
                }
            }
        } else {
            villages = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Labels.enrollFarmer) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = Labels.back)
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
            if (showSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ ${LanguageManager.localize("Farmer enrolled!", "किसान नामांकित!")}", style = MaterialTheme.typography.titleSmall)
                        Text(LanguageManager.localize("Saved locally.", "स्थानीय रूप से सहेजा।"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    showSuccess = false; mobileNumber = "+91"; displayName = ""; pinCode = ""
                    selectedStateId = ""; selectedDistrictId = ""
                    // Keep block selection - it's informational
                    selectedVillageId = ""; selectedVillageName = ""; isManualVillage = false
                    selectedKharif = emptySet(); selectedRabi = emptySet(); selectedZaid = emptySet()
                }) { Text(LanguageManager.localize("Enroll Another", "एक और नामांकन")) }
                return@Column
            }

            // --- Mobile Number ---
            OutlinedTextField(
                value = mobileNumber,
                onValueChange = { if (it.length <= 13) mobileNumber = it },
                label = { Text("${Labels.mobileNumber} *") },
                placeholder = { Text("+91XXXXXXXXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // --- Name ---
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("${Labels.farmerName} (${Labels.optional})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // --- Address (Cascading Geography) ---
            Text(LanguageManager.localize("Address", "पता"), style = MaterialTheme.typography.titleSmall)

            // State
            SearchableDropdown(
                label = Labels.selectState,
                items = states.map { it.id to it.canonicalName },
                selectedId = selectedStateId,
                onSelect = { id, _ ->
                    selectedStateId = id
                    selectedDistrictId = ""; selectedBlockId = ""; selectedVillageId = ""
                    pinCode = ""
                }
            )

            // District
            if (selectedStateId.isNotEmpty()) {
                SearchableDropdown(
                    label = Labels.selectDistrict,
                    items = districts.map { it.id to it.canonicalName },
                    selectedId = selectedDistrictId,
                    onSelect = { id, _ ->
                        selectedDistrictId = id
                        selectedBlockId = ""; selectedVillageId = ""
                        pinCode = ""
                    }
                )
            }

            // Block/Tehsil (optional - for informational purposes)
            if (selectedDistrictId.isNotEmpty()) {
                SearchableDropdown(
                    label = Labels.selectBlock,
                    items = blocks.map { it.id to it.canonicalName },
                    selectedId = selectedBlockId,
                    onSelect = { id, _ ->
                        selectedBlockId = id
                    }
                )
                Text(
                    "ℹ️ ${LanguageManager.localize("Block is optional. Village search covers entire district.", "ब्लॉक वैकल्पिक है। गाँव खोज पूरे जिले को कवर करती है।")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Village (district-wide search, appears after district selection)
            if (selectedDistrictId.isNotEmpty()) {
                if (isManualVillage) {
                    OutlinedTextField(
                        value = selectedVillageName,
                        onValueChange = { selectedVillageName = it },
                        label = { Text("${Labels.selectVillage} (${LanguageManager.localize("manual", "मैन्युअल")})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { isManualVillage = false; selectedVillageName = "" }) {
                        Text(LanguageManager.localize("Select from list instead", "सूची से चुनें"))
                    }
                } else {
                    SearchableDropdown(
                        label = Labels.selectVillage,
                        items = villages.map { it.id to it.canonicalName },
                        selectedId = selectedVillageId,
                        allowManualEntry = true,
                        onSelect = { id, name ->
                            selectedVillageId = id; selectedVillageName = name; isManualVillage = false
                            // Auto-populate PIN code from village data
                            val village = villages.find { it.id == id }
                            val pinCodes = village?.pinCodes
                            if (!pinCodes.isNullOrBlank() && pinCodes != "[]" && pinCodes != "null") {
                                // Parse JSON array and take first pin code
                                val cleaned = pinCodes.replace("[", "").replace("]", "").replace("\"", "").trim()
                                if (cleaned.isNotBlank()) pinCode = cleaned.split(",").first().trim()
                            }
                        },
                        onManualEntry = { name ->
                            isManualVillage = true; selectedVillageName = name; selectedVillageId = "manual_$name"
                        }
                    )
                }
            }

            // --- PIN Code (auto-populated or manual) ---
            if (selectedVillageId.isNotEmpty() || isManualVillage) {
                OutlinedTextField(
                    value = pinCode,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinCode = it },
                    label = { Text("${LanguageManager.localize("PIN Code", "पिन कोड")} (${Labels.optional})") },
                    placeholder = { Text("e.g. 283104") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (pinCode.isNotEmpty()) {
                            Text(LanguageManager.localize("Auto-filled from village data", "गाँव डेटा से भरा गया"))
                        }
                    }
                )
            }

            HorizontalDivider()

            // --- Season-wise Crops ---
            Text(Labels.cropsByseason, style = MaterialTheme.typography.titleSmall)

            SeasonCropSelector(seasonLabel = Labels.kharif, selectedCrops = selectedKharif,
                onToggle = { selectedKharif = if (it in selectedKharif) selectedKharif - it else selectedKharif + it })

            SeasonCropSelector(seasonLabel = Labels.rabi, selectedCrops = selectedRabi,
                onToggle = { selectedRabi = if (it in selectedRabi) selectedRabi - it else selectedRabi + it })

            SeasonCropSelector(seasonLabel = Labels.zaid, selectedCrops = selectedZaid,
                onToggle = { selectedZaid = if (it in selectedZaid) selectedZaid - it else selectedZaid + it })

            // Error
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // --- Submit ---
            Button(
                onClick = {
                    if (mobileNumber.length < 12) {
                        errorMessage = "📞 ${LanguageManager.localize("Enter valid mobile number", "सही मोबाइल नंबर दर्ज करें")}"; return@Button
                    }
                    if (selectedDistrictId.isEmpty()) {
                        errorMessage = "📍 ${LanguageManager.localize("Select district", "जिला चुनें")}"; return@Button
                    }
                    if (selectedVillageId.isEmpty() && !isManualVillage) {
                        errorMessage = "📍 ${LanguageManager.localize("Select village", "गाँव चुनें")}"; return@Button
                    }
                    if (isManualVillage && selectedVillageName.isBlank()) {
                        errorMessage = "📍 ${LanguageManager.localize("Enter village name", "गाँव का नाम दर्ज करें")}"; return@Button
                    }
                    errorMessage = null; isSaving = true

                    scope.launch {
                        val existing = db.farmerDao().getByMobile(mobileNumber)
                        if (existing != null) {
                            errorMessage = "👤 ${LanguageManager.localize("Already enrolled", "पहले से नामांकित")}"; isSaving = false; return@launch
                        }

                        val now = System.currentTimeMillis()
                        val farmerId = UUID.randomUUID().toString()
                        val authState = db.authDao().getAuthState()
                        val villageId = if (isManualVillage) "manual_${selectedVillageName}" else selectedVillageId

                        db.farmerDao().insert(FarmerEntity(
                            id = farmerId, mobileNumber = mobileNumber, villageId = villageId,
                            villageName = selectedVillageName, primaryCropCode = selectedKharif.firstOrNull(),
                            displayName = displayName.ifBlank { null }, assistanceMode = "DEALER_ASSISTED",
                            syncStatus = SyncStatus.PENDING.name, createdAt = now, updatedAt = now,
                            actorId = authState?.userId ?: "unknown"
                        ))

                        val payload = Gson().toJson(mapOf(
                            "mobile_number" to mobileNumber,
                            "village_id" to (if (isManualVillage) null else selectedVillageId),
                            "village_name_manual" to (if (isManualVillage) selectedVillageName else null),
                            "pin_code" to pinCode.ifBlank { null },
                            "display_name" to displayName.ifBlank { null },
                            "crops_by_season" to mapOf("KHARIF" to selectedKharif.toList(), "RABI" to selectedRabi.toList(), "ZAID" to selectedZaid.toList()),
                            "language_preference" to LanguageManager.getLanguage(),
                            "assistance_mode" to "DEALER_ASSISTED"
                        ))

                        db.syncQueueDao().enqueue(SyncQueueEntity(
                            eventId = UUID.randomUUID().toString(), entityType = "FARMER", entityId = farmerId,
                            operation = "CREATE", payload = payload, syncStatus = SyncStatus.PENDING.name,
                            priority = SyncPriority.HIGH.name, createdAt = now
                        ))

                        isSaving = false; showSuccess = true
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(Labels.save)
            }

            Text(
                "🟢 ${LanguageManager.localize("Saves on phone, syncs later", "फोन पर सहेजा, बाद में सिंक")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonCropSelector(seasonLabel: String, selectedCrops: Set<String>, onToggle: (String) -> Unit) {
    val isHindi = LanguageManager.isHindi()
    val cropOptions = when {
        seasonLabel.contains("Kharif") || seasonLabel.contains("खरीफ") ->
            listOf("RICE" to if (isHindi) "धान" else "Rice", "MAIZE" to if (isHindi) "मक्का" else "Maize",
                "SUGARCANE" to if (isHindi) "गन्ना" else "Sugarcane", "BAJRA" to if (isHindi) "बाजरा" else "Bajra",
                "GROUNDNUT" to if (isHindi) "मूंगफली" else "Groundnut")
        seasonLabel.contains("Rabi") || seasonLabel.contains("रबी") ->
            listOf("WHEAT" to if (isHindi) "गेहूं" else "Wheat", "GRAM" to if (isHindi) "चना" else "Gram",
                "MUSTARD" to if (isHindi) "सरसों" else "Mustard", "LENTIL" to if (isHindi) "मसूर" else "Lentil",
                "POTATO" to if (isHindi) "आलू" else "Potato")
        else ->
            listOf("MOONG" to if (isHindi) "मूंग" else "Moong", "WATERMELON" to if (isHindi) "तरबूज" else "Watermelon",
                "CUCUMBER" to if (isHindi) "खीरा" else "Cucumber")
    }

    Column {
        Text(seasonLabel, style = MaterialTheme.typography.labelMedium)
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cropOptions.forEach { (code, label) ->
                FilterChip(selected = code in selectedCrops, onClick = { onToggle(code) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
}
