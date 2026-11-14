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
import com.agrios.app.core.sync.NewQueueOrigin
import com.agrios.app.core.sync.OwnershipContainment
import com.agrios.app.core.sync.UnsupportedOwnershipAction
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    onNavigateToParcel: ((parcelId: String) -> Unit)? = null,
    onNavigateToAddParcel: ((farmerId: String) -> Unit)? = null,
    onNavigateToSoilProfile: ((parcelId: String, farmerId: String) -> Unit)? = null,
    onNavigateToLandRead: ((parcelId: String) -> Unit)? = null
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()
    var origin by remember(db, farmerId) { mutableStateOf<NewQueueOrigin?>(null) }
    LaunchedEffect(db, farmerId) { origin = NewQueueOrigin.capture(db.authDao()) }
    val formOrigin = origin
    if (formOrigin == null) {
        Text("An authenticated origin session is required. Reopen this screen after signing in.")
        return
    }

    val authState by db.authDao().observeAuthState().collectAsState(initial = null)
    val epoch by formOrigin.sessionEpoch.collectAsState()
    if (!formOrigin.matchesObservedSession(authState, epoch)) {
        Text("Profile session is unavailable or has changed. Reopen this screen after signing in.")
        return
    }
    val farmer by remember(db, farmerId, formOrigin.owner) {
        db.farmerDao().observeOwnedById(farmerId, formOrigin.owner.tenantId, formOrigin.owner.userId)
    }.collectAsState(initial = null)
    val parcels by remember(db, farmerId, formOrigin.owner) {
        db.parcelDao().observeOwnedByFarmer(farmerId, formOrigin.owner.tenantId, formOrigin.owner.userId)
    }.collectAsState(initial = emptyList())
    val soilProfiles by remember(db, farmerId, formOrigin.owner) {
        db.soilProfileDao().observeOwnedByFarmer(farmerId, formOrigin.owner.tenantId, formOrigin.owner.userId)
    }.collectAsState(initial = emptyList())

    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editAadhaar by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // Edit state for parcel survey numbers
    var editingParcelId by remember { mutableStateOf<String?>(null) }
    var editSurveyNumber by remember { mutableStateOf("") }

    var geometryMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(farmer?.id) {
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
                    IconButton(onClick = onBack, modifier = Modifier.testTag("profile_back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                Text(OwnershipContainment.SOURCE_UNAVAILABLE)
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
                                    try {
                                        try {
                                            if (!formOrigin.isCurrentSession()) return@launch
                                            formOrigin.commit(db) {
                                                formOrigin.requireSource(db.farmerDao().getById(farmerId))
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
                                                db.syncQueueDao().enqueue(formOrigin.bindNewEvent(SyncQueueEntity(
                                                    eventId = UUID.randomUUID().toString(),
                                                    entityType = "FARMER",
                                                    entityId = farmerId,
                                                    operation = "UPDATE",
                                                    payload = payload,
                                                    syncStatus = SyncStatus.PENDING.name,
                                                    priority = SyncPriority.MEDIUM.name,
                                                    createdAt = System.currentTimeMillis()
                                                )))
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            formOrigin.publishIfCurrent {
                                                saveMessage = "Profile save blocked: original source ownership is not established. No automatic retry."
                                            }
                                            return@launch
                                        }

                                        // A committed edit must attempt scheduling before any suspending feedback check.
                                        val scheduled = try {
                                            SyncWorker.triggerImmediateSync(AgriOsApp.instance, formOrigin)
                                            true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            false
                                        }
                                        formOrigin.publishIfCurrent {
                                            isEditing = false
                                            saveMessage = if (scheduled) LanguageManager.localize("✅ Saved", "✅ सहेजा")
                                                else "Saved locally. Sync scheduling failed; do not submit again."
                                        }
                                    } finally { isSaving = false }
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
            onNavigateToAddParcel?.let { navigate ->
                OutlinedButton(
                    onClick = { navigate(farmerId) },
                    modifier = Modifier.fillMaxWidth().testTag("profile_add_parcel")
                ) {
                    Text(LanguageManager.localize("Add Land Parcel", "भूमि भूखंड जोड़ें"))
                }
            }

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
                    Card(modifier = Modifier.fillMaxWidth().testTag("parcel_${parcel.id}")) {
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
                                            try {
                                                if (!formOrigin.isCurrentSession()) return@launch
                                                formOrigin.commit(db) {
                                                    val source = db.parcelDao().getById(parcel.id)
                                                    formOrigin.requireSource(source)
                                                    check(source?.farmerId == farmerId) { "QUEUE_SOURCE_RELATION_MISMATCH" }
                                                    check(db.parcelDao().updateOwnedSurveyNumber(
                                                        parcel.id, formOrigin.owner.tenantId, formOrigin.owner.userId,
                                                        editSurveyNumber.ifBlank { null }, System.currentTimeMillis()
                                                    ) == 1) { "LOCAL_SOURCE_UPDATE_NOT_CONFIRMED" }
                                                }
                                                formOrigin.publishIfCurrent {
                                                    editingParcelId = null
                                                    saveMessage = "Survey number saved on this device only. No synchronization was queued."
                                                }
                                            } catch (e: CancellationException) { throw e
                                            } catch (_: Exception) {
                                                formOrigin.publishIfCurrent { saveMessage = OwnershipContainment.SOURCE_UNAVAILABLE }
                                            }
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
                            onNavigateToLandRead?.let { navigate ->
                                OutlinedButton(onClick = { navigate(parcel.id) }, modifier = Modifier.testTag("land_read_${parcel.id}")) {
                                    Text("Land and soil guidance")
                                }
                            }
                            val soilCount = soilProfiles.count { it.parcelId == parcel.id }
                            Text(
                                "🌍 ${LanguageManager.localize("Soil profiles", "मिट्टी प्रोफ़ाइल")}: $soilCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            onNavigateToSoilProfile?.let { navigate ->
                                OutlinedButton(
                                    onClick = { navigate(parcel.id, farmerId) },
                                    modifier = Modifier.fillMaxWidth().testTag("soil_form_${parcel.id}")
                                ) {
                                    Text(LanguageManager.localize("Add Soil Profile", "à¤®à¤¿à¤Ÿà¥à¤Ÿà¥€ à¤ªà¥à¤°à¥‹à¤«à¤¼à¤¾à¤‡à¤² à¤œà¥‹à¤¡à¤¼à¥‡à¤‚"))
                                }
                            }


                            Text(
                                "${LanguageManager.localize("Geometry", "GPS")}: ${parcel.geometrySource ?: "NONE"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // No GPS widget/draft is opened while this path is unsupported.
                            OutlinedButton(onClick = {
                                OwnershipContainment.block(UnsupportedOwnershipAction.PROFILE_GPS) { geometryMessage = it }
                            }) {
                                Text("Update GPS")
                            }

                            if (geometryMessage != null) {
                                Text(
                                    geometryMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }                        }
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
