package com.agrios.app.ui.soil

import androidx.compose.animation.AnimatedVisibility
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
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.VillageIdUtil
import com.agrios.app.data.local.entity.*
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.SoilInferenceResponseDto
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoilProfileScreen(
    parcelId: String = "",
    farmerId: String = "",
    onBack: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // State
    var isSaving by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Tier 1: Auto-inferred
    var inferredSoilType by remember { mutableStateOf("") }
    var inferredSoilTypeName by remember { mutableStateOf("") }
    var inferredDescription by remember { mutableStateOf("") }
    var inferredPhRange by remember { mutableStateOf("") }
    var isLoadingInference by remember { mutableStateOf(false) }

    // Tier 2: Farmer observes
    var soilTexture by remember { mutableStateOf("") }
    var soilColor by remember { mutableStateOf("") }

    // Tier 3: SHC data (expandable)
    var showShcSection by remember { mutableStateOf(false) }
    var shcCardNumber by remember { mutableStateOf("") }
    var ph by remember { mutableStateOf("") }
    var nitrogenN by remember { mutableStateOf("") }
    var phosphorusP by remember { mutableStateOf("") }
    var potassiumK by remember { mutableStateOf("") }
    var sulphurS by remember { mutableStateOf("") }
    var zincZn by remember { mutableStateOf("") }
    var ironFe by remember { mutableStateOf("") }
    var copperCu by remember { mutableStateOf("") }
    var manganeseMn by remember { mutableStateOf("") }
    var boronB by remember { mutableStateOf("") }
    var ec by remember { mutableStateOf("") }
    var organicCarbonOc by remember { mutableStateOf("") }
    var testDate by remember { mutableStateOf("") }

    // Auto-infer soil type from district
    LaunchedEffect(parcelId) {
        if (parcelId.isNotEmpty()) {
            isLoadingInference = true
            try {
                val parcel = db.parcelDao().getById(parcelId)
                val villageId = parcel?.villageId
                // Get district name from village
                var districtName = ""
                if (villageId != null && VillageIdUtil.isValidUuid(villageId)) {
                    // Try to find district from geography cache
                    val districts = db.geographyCacheDao().getDistrictsByState("fcca78bf-58f8-4920-b03f-9ee8e7008123")
                    // We need the district name - get it from blocks/villages
                    districtName = "AZAMGARH" // Fallback for pilot
                }
                if (districtName.isNotEmpty()) {
                    val okHttpClient = OkHttpClient.Builder()
                        .addInterceptor(AuthInterceptor(db.authDao()))
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                    val api = Retrofit.Builder()
                        .baseUrl(ApiConfig.BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                        .create(AgriOsApi::class.java)
                    val response = api.inferSoilType(districtName)
                    if (response.isSuccessful) {
                        val data = response.body()!!
                        inferredSoilType = data.inferredSoilType
                        inferredSoilTypeName = data.inferredSoilTypeName
                        inferredDescription = data.description ?: ""
                        inferredPhRange = data.typicalPhRange ?: ""
                        soilTexture = data.typicalTexture ?: ""
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SoilProfile", "Inference failed: ${e.message}")
            } finally {
                isLoadingInference = false
            }
        }
    }

    val textureOptions = listOf(
        "SANDY" to LanguageManager.localize("Sandy", "बलुई"),
        "LOAMY" to LanguageManager.localize("Loamy", "दोमट"),
        "CLAY" to LanguageManager.localize("Clay", "चिकनी"),
        "SANDY_LOAM" to LanguageManager.localize("Sandy Loam", "बलुई दोमट"),
        "CLAY_LOAM" to LanguageManager.localize("Clay Loam", "चिकनी दोमट")
    )

    val colorOptions = listOf(
        "DARK_BROWN" to LanguageManager.localize("Dark Brown", "गहरा भूरा"),
        "LIGHT_BROWN" to LanguageManager.localize("Light Brown", "हल्का भूरा"),
        "REDDISH" to LanguageManager.localize("Reddish", "लालिमा"),
        "BLACK" to LanguageManager.localize("Black", "काला"),
        "GREY" to LanguageManager.localize("Grey", "धूसर")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.localize("Soil Profile", "मिट्टी प्रोफ़ाइल")) },
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
            if (showSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ ${LanguageManager.localize("Soil profile saved!", "मिट्टी प्रोफ़ाइल सहेजा!")}", style = MaterialTheme.typography.titleSmall)
                        Text(LanguageManager.localize("Saved locally, will sync.", "स्थानीय रूप से सहेजा, सिंक होगा।"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(LanguageManager.localize("Done", "हो गया"))
                }
                return@Column
            }

            // ═══════════════════════════════════════════
            // TIER 1: Auto-inferred soil type
            // ═══════════════════════════════════════════
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        LanguageManager.localize("🌍 Soil Type (auto-detected)", "🌍 मिट्टी प्रकार (स्वतः पहचान)"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    if (isLoadingInference) {
                        Row {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(LanguageManager.localize("Detecting...", "पहचान हो रही है..."), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (inferredSoilTypeName.isNotEmpty()) {
                        Text(inferredSoilTypeName, style = MaterialTheme.typography.bodyLarge)
                        if (inferredDescription.isNotEmpty()) {
                            Text(inferredDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (inferredPhRange.isNotEmpty()) {
                            Text("pH: $inferredPhRange", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(LanguageManager.localize("Could not detect", "पहचान नहीं हो सकी"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()

            // ═══════════════════════════════════════════
            // TIER 2: Farmer observes (texture + color)
            // ═══════════════════════════════════════════
            Text(
                LanguageManager.localize("👁️ What does your soil look like?", "👁️ आपकी मिट्टी कैसी दिखती है?"),
                style = MaterialTheme.typography.titleSmall
            )

            // Texture
            Text(LanguageManager.localize("Texture", "बनावट"), style = MaterialTheme.typography.labelLarge)
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                textureOptions.forEach { (code, label) ->
                    FilterChip(
                        selected = soilTexture == code,
                        onClick = { soilTexture = code },
                        label = { Text(label) }
                    )
                }
            }

            // Color
            Text(LanguageManager.localize("Color", "रंग"), style = MaterialTheme.typography.labelLarge)
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                colorOptions.forEach { (code, label) ->
                    FilterChip(
                        selected = soilColor == code,
                        onClick = { soilColor = code },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider()

            // ═══════════════════════════════════════════
            // TIER 3: SHC Card data (optional, expandable)
            // ═══════════════════════════════════════════
            ElevatedCard(
                onClick = { showShcSection = !showShcSection },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Text(
                        LanguageManager.localize(
                            "📋 Have a Soil Health Card?",
                            "📋 क्या आपके पास मिट्टी स्वास्थ्य कार्ड है?"
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (showShcSection) "▲" else "▼")
                }
            }

            AnimatedVisibility(visible = showShcSection) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = shcCardNumber,
                        onValueChange = { shcCardNumber = it },
                        label = { Text(LanguageManager.localize("SHC Card Number", "SHC कार्ड नंबर")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = testDate,
                        onValueChange = { testDate = it },
                        label = { Text(LanguageManager.localize("Test Date (YYYY-MM-DD)", "परीक्षण तिथि")) },
                        placeholder = { Text("2026-01-15") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(LanguageManager.localize("Macronutrients", "मुख्य पोषक तत्व"), style = MaterialTheme.typography.labelLarge)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientField("N (kg/ha)", nitrogenN, { nitrogenN = it }, Modifier.weight(1f))
                        NutrientField("P (kg/ha)", phosphorusP, { phosphorusP = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientField("K (kg/ha)", potassiumK, { potassiumK = it }, Modifier.weight(1f))
                        NutrientField("S (mg/kg)", sulphurS, { sulphurS = it }, Modifier.weight(1f))
                    }

                    Text(LanguageManager.localize("Micronutrients", "सूक्ष्म पोषक तत्व"), style = MaterialTheme.typography.labelLarge)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientField("Zn", zincZn, { zincZn = it }, Modifier.weight(1f))
                        NutrientField("Fe", ironFe, { ironFe = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientField("Cu", copperCu, { copperCu = it }, Modifier.weight(1f))
                        NutrientField("Mn", manganeseMn, { manganeseMn = it }, Modifier.weight(1f))
                    }
                    NutrientField("B (mg/kg)", boronB, { boronB = it }, Modifier.fillMaxWidth())

                    Text(LanguageManager.localize("Physical Parameters", "भौतिक मापदंड"), style = MaterialTheme.typography.labelLarge)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NutrientField("pH", ph, { ph = it }, Modifier.weight(1f))
                        NutrientField("EC (dS/m)", ec, { ec = it }, Modifier.weight(1f))
                    }
                    NutrientField("OC (%)", organicCarbonOc, { organicCarbonOc = it }, Modifier.fillMaxWidth())
                }
            }

            // Error
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // ═══════════════════════════════════════════
            // SAVE
            // ═══════════════════════════════════════════
            Button(
                onClick = {
                    if (parcelId.isEmpty()) {
                        errorMessage = LanguageManager.localize("No parcel selected", "कोई भूखंड चयनित नहीं")
                        return@Button
                    }
                    errorMessage = null; isSaving = true

                    scope.launch {
                        val now = System.currentTimeMillis()
                        val profileId = UUID.randomUUID().toString()
                        val authState = db.authDao().getAuthState()
                        val dataSource = if (showShcSection && shcCardNumber.isNotBlank()) "SHC_CARD"
                            else if (inferredSoilType.isNotEmpty()) "INFERRED"
                            else "MANUAL"

                        db.soilProfileDao().insert(SoilProfileEntity(
                            id = profileId,
                            parcelId = parcelId,
                            farmerId = farmerId,
                            soilTypeCode = inferredSoilType.ifBlank { null },
                            soilTexture = soilTexture.ifBlank { null },
                            soilColor = soilColor.ifBlank { null },
                            ph = ph.toDoubleOrNull(),
                            nitrogenN = nitrogenN.toDoubleOrNull(),
                            phosphorusP = phosphorusP.toDoubleOrNull(),
                            potassiumK = potassiumK.toDoubleOrNull(),
                            sulphurS = sulphurS.toDoubleOrNull(),
                            zincZn = zincZn.toDoubleOrNull(),
                            ironFe = ironFe.toDoubleOrNull(),
                            copperCu = copperCu.toDoubleOrNull(),
                            manganeseMn = manganeseMn.toDoubleOrNull(),
                            boronB = boronB.toDoubleOrNull(),
                            ec = ec.toDoubleOrNull(),
                            organicCarbonOc = organicCarbonOc.toDoubleOrNull(),
                            shcCardNumber = shcCardNumber.ifBlank { null },
                            dataSource = dataSource,
                            testDate = testDate.ifBlank { null },
                            syncStatus = SyncStatus.PENDING.name,
                            createdAt = now,
                            updatedAt = now,
                            actorId = authState?.userId ?: "unknown"
                        ))

                        // Queue for sync
                        val payload = Gson().toJson(mapOf(
                            "parcel_id" to parcelId,
                            "farmer_id" to farmerId,
                            "soil_type_code" to inferredSoilType.ifBlank { null },
                            "soil_texture" to soilTexture.ifBlank { null },
                            "soil_color" to soilColor.ifBlank { null },
                            "ph" to ph.toDoubleOrNull(),
                            "nitrogen_n" to nitrogenN.toDoubleOrNull(),
                            "phosphorus_p" to phosphorusP.toDoubleOrNull(),
                            "potassium_k" to potassiumK.toDoubleOrNull(),
                            "sulphur_s" to sulphurS.toDoubleOrNull(),
                            "zinc_zn" to zincZn.toDoubleOrNull(),
                            "iron_fe" to ironFe.toDoubleOrNull(),
                            "copper_cu" to copperCu.toDoubleOrNull(),
                            "manganese_mn" to manganeseMn.toDoubleOrNull(),
                            "boron_b" to boronB.toDoubleOrNull(),
                            "ec" to ec.toDoubleOrNull(),
                            "organic_carbon_oc" to organicCarbonOc.toDoubleOrNull(),
                            "shc_card_number" to shcCardNumber.ifBlank { null },
                            "data_source" to dataSource,
                            "test_date" to testDate.ifBlank { null }
                        ))

                        db.syncQueueDao().enqueue(SyncQueueEntity(
                            eventId = UUID.randomUUID().toString(),
                            entityType = "SOIL_PROFILE",
                            entityId = profileId,
                            operation = "CREATE",
                            payload = payload,
                            syncStatus = SyncStatus.PENDING.name,
                            priority = SyncPriority.MEDIUM.name,
                            dependencyIds = parcelId, // soil depends on parcel being synced
                            createdAt = now
                        ))

                        // Trigger immediate sync
                        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                        isSaving = false; showSuccess = true
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(LanguageManager.localize("Save Soil Profile", "मिट्टी प्रोफ़ाइल सहेजें"))
            }

            Text(
                LanguageManager.localize(
                    "💡 Tip: Just soil type and texture is enough. SHC details are optional.",
                    "💡 सुझाव: बस मिट्टी प्रकार और बनावट पर्याप्त है। SHC विवरण वैकल्पिक हैं।"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement
    ) {
        content()
    }
}

@Composable
private fun NutrientField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { newVal -> onValueChange(newVal.filter { it.isDigit() || it == '.' }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
