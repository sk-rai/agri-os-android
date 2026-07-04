package com.agrios.app.ui.parcel

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
import com.agrios.app.core.geo.GeoJson
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.UnitConverter
import com.agrios.app.core.util.VillageIdUtil
import com.agrios.app.data.local.entity.*
import com.agrios.app.ui.geo.GpsPointCaptureWidget
import com.agrios.app.ui.geo.GpsPolygonWalkingWidget
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelRegisterScreen(
    onBack: () -> Unit,
    preselectedFarmerId: String = "",
    onNavigateToSoilProfile: ((parcelId: String, farmerId: String) -> Unit)? = null
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // Farmer selection — pre-select if coming from farmer enrollment
    var selectedFarmerId by remember { mutableStateOf(preselectedFarmerId) }
    var selectedFarmerName by remember { mutableStateOf("") }
    val farmers by db.farmerDao().observeAll().collectAsState(initial = emptyList())
    var savedParcelId by remember { mutableStateOf("") }

    // Resolve pre-selected farmer name
    LaunchedEffect(preselectedFarmerId, farmers) {
        if (preselectedFarmerId.isNotEmpty() && selectedFarmerName.isEmpty()) {
            val farmer = farmers.find { it.id == preselectedFarmerId }
            if (farmer != null) {
                selectedFarmerName = farmer.displayName ?: farmer.mobileNumber
            }
        }
    }

    // Parcel form
    var reportedArea by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf("BIGHA") }
    var ownershipType by remember { mutableStateOf("OWNED") }
    var gpsMode by remember { mutableStateOf("NONE") } // NONE, PIN_DROP, GPS_WALK
    var centroidLat by remember { mutableStateOf("") }
    var centroidLng by remember { mutableStateOf("") }
    var geometryGeoJson by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gpsMode) {
        if (gpsMode == "NONE") {
            geometryGeoJson = null
            centroidLat = ""
            centroidLng = ""
        }
    }

    var isSaving by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Existing parcels for selected farmer
    val existingParcels by remember(selectedFarmerId) {
        if (selectedFarmerId.isNotEmpty()) {
            db.parcelDao().observeByFarmer(selectedFarmerId)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    val areaUnits = UnitConverter.getDisplayUnits()
    val ownershipTypes = listOf("OWNED", "LEASED", "SHARED")
    val gpsModes = listOf("NONE", "PIN_DROP", "GPS_WALK")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Labels.registerParcel) },
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
                        Text("✅ ${LanguageManager.localize("Parcel registered!", "भूखंड पंजीकृत!")}", style = MaterialTheme.typography.titleSmall)
                        Text(LanguageManager.localize("Saved locally.", "स्थानीय रूप से सहेजा।"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Primary: Add soil profile for this parcel
                Button(
                    onClick = { onNavigateToSoilProfile?.invoke(savedParcelId, selectedFarmerId) ?: onBack() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(LanguageManager.localize("🌍 Add Soil Profile", "🌍 मिट्टी प्रोफ़ाइल जोड़ें"))
                }

                Spacer(Modifier.height(8.dp))

                // Secondary: Register another parcel
                OutlinedButton(onClick = {
                    showSuccess = false
                    reportedArea = ""
                    gpsMode = "NONE"
                    centroidLat = ""
                    centroidLng = ""
                    savedParcelId = ""
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(LanguageManager.localize("Register Another Parcel", "एक और भूखंड पंजीकृत करें"))
                }

                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(LanguageManager.localize("Go to Home", "होम पर जाएं"))
                }
                return@Column
            }

            // --- Select Farmer ---
            Text("${LanguageManager.localize("Select Farmer", "किसान चुनें")} *", style = MaterialTheme.typography.titleSmall)

            if (selectedFarmerName.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text("👤 $selectedFarmerName", modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedFarmerId = ""; selectedFarmerName = "" }) {
                            Text(LanguageManager.localize("Change", "बदलें"))
                        }
                    }
                }

                // Show existing parcels for this farmer
                if (existingParcels.isNotEmpty()) {
                    Text(
                        "${LanguageManager.localize("Existing parcels", "मौजूदा भूखंड")}: ${existingParcels.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    existingParcels.forEach { parcel ->
                        Text(
                            "  • ${parcel.reportedArea} ${Labels.getUnitLabel(parcel.reportedAreaUnit)} (${Labels.getOwnershipLabel(parcel.ownershipType)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                if (farmers.isEmpty()) {
                    Text(
                        LanguageManager.localize("No farmers enrolled. Enroll a farmer first.", "कोई किसान नामांकित नहीं। पहले किसान नामांकन करें।"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    farmers.take(10).forEach { farmer ->
                        TextButton(
                            onClick = {
                                selectedFarmerId = farmer.id
                                selectedFarmerName = farmer.displayName ?: farmer.mobileNumber
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${farmer.displayName ?: "—"} (${farmer.mobileNumber})", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (selectedFarmerId.isEmpty()) return@Column

            HorizontalDivider()

            // --- Area ---
            OutlinedTextField(
                value = reportedArea,
                onValueChange = { reportedArea = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("${Labels.area} *") },
                placeholder = { Text("e.g. 3.5") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Unit selector with localized labels
            Text(LanguageManager.localize("Unit", "इकाई"), style = MaterialTheme.typography.labelLarge)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                areaUnits.forEach { unit ->
                    FilterChip(
                        selected = selectedUnit == unit,
                        onClick = { selectedUnit = unit },
                        label = { Text(Labels.getUnitLabel(unit)) }
                    )
                }
            }

            // Ownership
            Text(Labels.ownership, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ownershipTypes.forEach { type ->
                    FilterChip(
                        selected = ownershipType == type,
                        onClick = { ownershipType = type },
                        label = { Text(Labels.getOwnershipLabel(type)) }
                    )
                }
            }

            HorizontalDivider()

            // --- GPS Options ---
            Text("${Labels.gpsLocation} (${Labels.optional})", style = MaterialTheme.typography.titleSmall)

            gpsModes.forEach { mode ->
                val modeLabel = when (mode) {
                    "NONE" -> Labels.noGps
                    "PIN_DROP" -> Labels.pinDrop
                    "GPS_WALK" -> Labels.walkBoundary
                    else -> mode
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = gpsMode == mode,
                        onClick = { gpsMode = mode }
                    )
                    Text(
                        modeLabel,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (gpsMode == "PIN_DROP") {
                GpsPointCaptureWidget(
                    label = "GPS point",
                    value = geometryGeoJson,
                    enabled = true,
                    draftKey = "parcel:${selectedFarmerId}:pin_drop",
                    onValueChange = { geoJson ->
                        geometryGeoJson = geoJson
                        val point = GeoJson.parsePoint(geoJson)
                        centroidLat = point?.lat?.toString() ?: ""
                        centroidLng = point?.lng?.toString() ?: ""
                    }
                )
            }

            if (gpsMode == "GPS_WALK") {
                GpsPolygonWalkingWidget(
                    label = "GPS boundary walk",
                    value = geometryGeoJson,
                    enabled = true,
                    draftKey = "parcel:${selectedFarmerId}:gps_walk",
                    onValueChange = { geoJson -> geometryGeoJson = geoJson }
                )
            }

            // Error
            if (errorMessage != null) {
                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            // --- Submit ---
            Button(
                onClick = {
                    val area = reportedArea.toDoubleOrNull()
                    if (area == null || area <= 0) {
                        errorMessage = "📏 ${LanguageManager.localize("Enter valid area", "सही क्षेत्रफल दर्ज करें")}"
                        return@Button
                    }
                    errorMessage = null
                    isSaving = true

                    scope.launch {
                        val now = System.currentTimeMillis()
                        val parcelId = UUID.randomUUID().toString()
                        val authState = db.authDao().getAuthState()
                        val farmer = db.farmerDao().getById(selectedFarmerId)
                        val point = GeoJson.parsePoint(geometryGeoJson)
                        val lat = point?.lat ?: centroidLat.toDoubleOrNull()
                        val lng = point?.lng ?: centroidLng.toDoubleOrNull()
                        val geometryElement = geometryGeoJson?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }

                        val parcel = ParcelEntity(
                            id = parcelId,
                            farmerId = selectedFarmerId,
                            villageId = farmer?.villageId ?: "",
                            villageName = farmer?.villageName,
                            reportedArea = area,
                            reportedAreaUnit = selectedUnit,
                            areaHectares = UnitConverter.toHectares(area, selectedUnit),
                            geometrySource = gpsMode,
                            gpsLat = lat,
                            gpsLng = lng,
                            ownershipType = ownershipType,
                            syncStatus = SyncStatus.PENDING.name,
                            createdAt = now,
                            updatedAt = now,
                            actorId = authState?.userId ?: "unknown"
                        )
                        db.parcelDao().insert(parcel)

                        // Sync payload — handle manual village (non-UUID village_id)
                        val villageId = farmer?.villageId
                        val payload = Gson().toJson(mapOf(
                            "farmer_id" to selectedFarmerId,
                            "village_id" to VillageIdUtil.getSyncVillageId(villageId),
                            "village_name_manual" to VillageIdUtil.getSyncVillageNameManual(villageId, farmer?.villageName),
                            "reported_area" to area,
                            "reported_area_unit" to selectedUnit,
                            "ownership_type" to ownershipType,
                            "geometry_source" to gpsMode,
                            "geometry" to geometryElement,
                            "geojson" to geometryElement,
                            "centroid_lat" to lat,
                            "centroid_lng" to lng
                        ))

                        db.syncQueueDao().enqueue(SyncQueueEntity(
                            eventId = UUID.randomUUID().toString(),
                            entityType = "PARCEL",
                            entityId = parcelId,
                            operation = "CREATE",
                            payload = payload,
                            syncStatus = SyncStatus.PENDING.name,
                            priority = SyncPriority.HIGH.name,
                            dependencyIds = selectedFarmerId,
                            createdAt = now
                        ))

                        isSaving = false
                        showSuccess = true
                        savedParcelId = parcelId
                        // Trigger immediate background sync
                        com.agrios.app.core.sync.SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("${Labels.save} ${Labels.registerParcel}")
                }
            }

            Text(
                "📍 ${LanguageManager.localize("GPS can be added later. Area is sufficient for now.", "GPS बाद में जोड़ सकते हैं। अभी क्षेत्रफल पर्याप्त है।")}",
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
    // Simple wrapping row implementation
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement
    ) {
        content()
    }
}
