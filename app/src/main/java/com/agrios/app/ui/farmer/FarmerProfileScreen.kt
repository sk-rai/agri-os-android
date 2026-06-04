package com.agrios.app.ui.farmer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.*
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmerProfileScreen(
    farmerId: String,
    onBack: () -> Unit,
    onNavigateToParcel: ((parcelId: String) -> Unit)? = null
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    var farmer by remember { mutableStateOf<FarmerEntity?>(null) }
    val parcels by db.parcelDao().observeByFarmer(farmerId).collectAsState(initial = emptyList())
    val soilProfiles by db.soilProfileDao().observeByFarmer(farmerId).collectAsState(initial = emptyList())

    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editAadhaar by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Edit state for parcel survey numbers
    var editingParcelId by remember { mutableStateOf<String?>(null) }
    var editSurveyNumber by remember { mutableStateOf("") }

    LaunchedEffect(farmerId) {
        farmer = db.farmerDao().getById(farmerId)
        farmer?.let {
            editName = it.displayName ?: ""
            editAadhaar = it.aadhaarNumber ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.localize("Farmer Profile", "किसान प्रोफ़ाइल")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { isEditing = !isEditing }) {
                        Icon(Icons.Default.Edit, contentDescription = LanguageManager.localize("Edit", "संपादित"))
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
            if (farmer == null) {
                Text(LanguageManager.localize("Loading...", "लोड हो रहा है..."))
                return@Column
            }

            val f = farmer!!

            // ═══════════════════════════
            // FARMER DETAILS
            // ═══════════════════════════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        LanguageManager.localize("👤 Farmer Details", "👤 किसान विवरण"),
                        style = MaterialTheme.typography.titleSmall
                    )

                    // Name
                    if (isEditing) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text(Labels.farmerName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        ProfileRow(LanguageManager.localize("Name", "नाम"), f.displayName ?: "—")
                    }

                    ProfileRow(LanguageManager.localize("Mobile", "मोबाइल"), f.mobileNumber)
                    ProfileRow(LanguageManager.localize("Village", "गाँव"), f.villageName ?: f.villageId)

                    // Aadhaar
                    if (isEditing) {
                        OutlinedTextField(
                            value = editAadhaar,
                            onValueChange = { newVal ->
                                // Only digits, max 12
                                editAadhaar = newVal.filter { it.isDigit() }.take(12)
                            },
                            label = { Text(LanguageManager.localize("Aadhaar Number", "आधार नंबर")) },
                            placeholder = { Text("XXXX XXXX XXXX") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(LanguageManager.localize("12 digits, stored securely", "12 अंक, सुरक्षित रूप से संग्रहित"))
                            }
                        )
                    } else {
                        val maskedAadhaar = if (!f.aadhaarNumber.isNullOrBlank() && f.aadhaarNumber.length == 12) {
                            "XXXX-XXXX-${f.aadhaarNumber.takeLast(4)}"
                        } else {
                            f.aadhaarNumber ?: "—"
                        }
                        ProfileRow(LanguageManager.localize("Aadhaar", "आधार"), maskedAadhaar)
                    }

                    // Sync status
                    val statusEmoji = when (f.syncStatus) {
                        SyncStatus.SYNCED.name -> "✅"
                        SyncStatus.PENDING.name -> "🔄"
                        SyncStatus.FAILED.name -> "❌"
                        else -> "⚠️"
                    }
                    ProfileRow(LanguageManager.localize("Sync", "सिंक"), "$statusEmoji ${f.syncStatus}")

                    // Save button (edit mode)
                    if (isEditing) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                isSaving = true
                                scope.launch {
                                    db.farmerDao().updateProfile(
                                        id = farmerId,
                                        name = editName.ifBlank { null },
                                        aadhaar = editAadhaar.ifBlank { null },
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    // Queue sync event for the update
                                    val payload = Gson().toJson(mapOf(
                                        "display_name" to editName.ifBlank { null },
                                        "aadhaar_number" to editAadhaar.ifBlank { null }
                                    ))
                                    db.syncQueueDao().enqueue(SyncQueueEntity(
                                        eventId = UUID.randomUUID().toString(),
                                        entityType = "FARMER",
                                        entityId = farmerId,
                                        operation = "UPDATE",
                                        payload = payload,
                                        syncStatus = SyncStatus.PENDING.name,
                                        priority = SyncPriority.MEDIUM.name,
                                        createdAt = System.currentTimeMillis()
                                    ))
                                    SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                                    farmer = db.farmerDao().getById(farmerId)
                                    isEditing = false
                                    isSaving = false
                                    saveMessage = LanguageManager.localize("✅ Saved", "✅ सहेजा")
                                }
                            },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(LanguageManager.localize("Save Changes", "बदलाव सहेजें"))
                        }
                    }
                    if (saveMessage != null) {
                        Text(saveMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            HorizontalDivider()

            // ═══════════════════════════
            // PARCELS
            // ═══════════════════════════
            Text(
                "📍 ${LanguageManager.localize("Land Parcels", "भूमि भूखंड")} (${parcels.size})",
                style = MaterialTheme.typography.titleSmall
            )

            if (parcels.isEmpty()) {
                Text(
                    LanguageManager.localize("No parcels registered yet", "कोई भूखंड पंजीकृत नहीं"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                parcels.forEach { parcel ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${parcel.reportedArea} ${Labels.getUnitLabel(parcel.reportedAreaUnit)} (${Labels.getOwnershipLabel(parcel.ownershipType)})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                val pStatusEmoji = when (parcel.syncStatus) {
                                    SyncStatus.SYNCED.name -> "✅"
                                    SyncStatus.PENDING.name -> "🔄"
                                    else -> "⚠️"
                                }
                                Text(pStatusEmoji)
                            }

                            // Survey number
                            if (editingParcelId == parcel.id) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = editSurveyNumber,
                                        onValueChange = { editSurveyNumber = it },
                                        label = { Text(LanguageManager.localize("Survey/Khasra No.", "सर्वे/खसरा नं.")) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = {
                                        scope.launch {
                                            db.parcelDao().updateSurveyNumber(
                                                parcel.id,
                                                editSurveyNumber.ifBlank { null },
                                                System.currentTimeMillis()
                                            )
                                            editingParcelId = null
                                        }
                                    }) { Text("✓") }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${LanguageManager.localize("Survey No.", "सर्वे नं.")}: ${parcel.surveyNumber ?: "—"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        editingParcelId = parcel.id
                                        editSurveyNumber = parcel.surveyNumber ?: ""
                                    }) {
                                        Text(LanguageManager.localize("Edit", "संपादित"), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }

                            // Soil profile count for this parcel
                            val soilCount = soilProfiles.count { it.parcelId == parcel.id }
                            Text(
                                "🌍 ${LanguageManager.localize("Soil profiles", "मिट्टी प्रोफ़ाइल")}: $soilCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
