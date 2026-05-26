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
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.core.util.UnitConverter
import com.agrios.app.data.local.entity.*
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParcelRegisterScreen(onBack: () -> Unit) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    // Farmer selection
    var selectedFarmerId by remember { mutableStateOf("") }
    var selectedFarmerName by remember { mutableStateOf("") }
    val farmers by db.farmerDao().observeAll().collectAsState(initial = emptyList())

    // Parcel form
    var reportedArea by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf("BIGHA") }
    var ownershipType by remember { mutableStateOf("OWNED") }
    var gpsMode by remember { mutableStateOf("NONE") } // NONE, PIN_DROP, GPS_WALK
    var centroidLat by remember { mutableStateOf("") }
    var centroidLng by remember { mutableStateOf("") }

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
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    showSuccess = false
                    reportedArea = ""
                    gpsMode = "NONE"
                    centroidLat = ""
                    centroidLng = ""
                }) {
                    Text(Labels.addParcel)
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

            // GPS coordinate input (for PIN_DROP)
            if (gpsMode == "PIN_DROP") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = centroidLat,
                        onValueChange = { centroidLat = it },
                        label = { Text("Lat") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = centroidLng,
                        onValueChange = { centroidLng = it },
                        label = { Text("Lng") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    LanguageManager.localize("Tip: Use phone's GPS or enter manually", "सुझाव: फोन का GPS उपयोग करें या मैन्युअल दर्ज करें"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (gpsMode == "GPS_WALK") {
                Text(
                    LanguageManager.localize("🚶 Walk around your field boundary. GPS will record the path.", "🚶 अपने खेत की सीमा के चारों ओर चलें। GPS रास्ता रिकॉर्ड करेगा।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                // GPS walk will be implemented with location services
                Text(
                    LanguageManager.localize("(Coming soon - use Pin Drop for now)", "(जल्द आ रहा है - अभी पिन ड्रॉप उपयोग करें)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        val lat = centroidLat.toDoubleOrNull()
                        val lng = centroidLng.toDoubleOrNull()

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

                        // Sync payload
                        val payload = Gson().toJson(mapOf(
                            "farmer_id" to selectedFarmerId,
                            "village_id" to (farmer?.villageId ?: ""),
                            "reported_area" to area,
                            "reported_area_unit" to selectedUnit,
                            "ownership_type" to ownershipType,
                            "geometry_source" to gpsMode,
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
