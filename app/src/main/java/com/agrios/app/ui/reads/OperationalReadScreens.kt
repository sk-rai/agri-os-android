package com.agrios.app.ui.reads

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.AndroidDynamicTestContext
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.repository.*
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun readRepository() = remember { AuthenticatedReadsRepository(AgriOsApp.instance.database.authDao()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        TextButton(onClick = onBack, modifier = Modifier.testTag("read_back")) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ReadStatus(result: ReadResult?) {
    if (result == null) { CircularProgressIndicator(); return }
    if (!result.successful) Text(readFailureCopy(result.status), Modifier.testTag("read_error"))
    val app = AgriOsApp.instance
    if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        Text("${result.diagnostic.route}: HTTP ${result.status ?: result.diagnostic.failureCategory?.name.orEmpty()}", Modifier.testTag("status_${result.diagnostic.route}"))
        if (result.status == null) Text(result.diagnostic.toJson(), Modifier.testTag("diagnostic_${result.diagnostic.route}"))
    }
}

@Composable
fun LandAndSoilReadScreen(farmerId: String, parcelId: String, onBack: () -> Unit) {
    val repository = readRepository()
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ReadResult?>(null) }
    var soil by remember { mutableStateOf<ReadResult?>(null) }
    var context by remember { mutableStateOf<ReadResult?>(null) }
    var summary by remember { mutableStateOf<ReadResult?>(null) }
    var pin by remember { mutableStateOf("") }
    var project by remember { mutableStateOf<String?>(null) }
    var tenant by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var identityValid by remember { mutableStateOf(false) }
    val language = LanguageManager.backendLabelLanguage()
    LaunchedEffect(farmerId, parcelId) {
        val auth = withContext(Dispatchers.IO) { AgriOsApp.instance.database.authDao().getAuthState() }
        project = AndroidDynamicTestContext.projectIdFor(auth)
        tenant = auth?.tenantId.orEmpty()
        val loaded = repository.profile()
        profile = loaded
        val root = loaded.value?.takeIf { it.isJsonObject }?.asJsonObject
        val farmer = root?.get("farmer")?.takeIf { it.isJsonObject }?.asJsonObject
        val parcels = root?.get("parcels")?.takeIf { it.isJsonArray }?.asJsonArray
        val parcel = parcels?.firstOrNull { it.asJsonObject.stringValue("id") == parcelId }?.asJsonObject
        identityValid = loaded.successful && root?.stringValue("tenant_id") == tenant && farmer?.stringValue("id") == farmerId && parcel != null
        if (identityValid) {
            pin = parcel?.stringValue("pin_code") ?: farmer?.stringValue("pin_code").orEmpty()
            soil = repository.soil(parcelId)
        }
    }
    ReadPage("Land and soil", onBack) {
        ReadStatus(profile)
        if (profile?.successful == true && !identityValid) Text("The requested parcel does not match your profile.", Modifier.testTag("read_error"))
        if (identityValid) {
            val root = profile!!.value!!.asJsonObject
            Text(root.getAsJsonObject("farmer").stringValue("display_name").orEmpty(), Modifier.testTag("land_farmer_$farmerId"))
            Text("Parcel", Modifier.testTag("land_parcel_$parcelId"))
            ReadStatus(soil)
            if (soil?.successful == true) {
                val rows = soil?.value?.takeIf { it.isJsonArray }?.asJsonArray
                if (rows == null) Text("Invalid soil profile response", Modifier.testTag("read_error"))
                else {
                    Text("Soil profiles: ${rows.size()}", Modifier.testTag("soil_profile_count"))
                    if (rows.size() == 0) Text("No soil profiles recorded", Modifier.testTag("soil_empty"))
                    rows.forEach { row ->
                        val item = row.asJsonObject
                        Text("Soil profile: ${item.stringValue("soil_type_code") ?: "Manual observation"}")
                        item.get("ph")?.takeIf { !it.isJsonNull }?.let { Text("pH: ${it.asString}") }
                    }
                }
            }
            OutlinedTextField(value = pin, enabled = !busy, onValueChange = {
                pin = it.filter(Char::isDigit).take(6); context = null; summary = null
            },
                label = { Text("Land PIN code") }, modifier = Modifier.testTag("land_pin"))
            Button(enabled = pin.length == 6 && !busy, modifier = Modifier.testTag("load_land_guidance"), onClick = {
                val requestedPin = pin
                busy = true; context = null; summary = null
                scope.launch {
                    try {
                        context = repository.context(requestedPin, project)
                        summary = repository.summary(requestedPin, project, language)
                    } finally { busy = false }
                }
            }) { Text("View land guidance") }
            // Navigation remains operable regardless of guidance availability.
            if (context != null) {
                ReadStatus(context)
                if (context?.successful == true) {
                    val guidance = landGuidance(context!!, tenant)
                    if (guidance != null) Text(guidance, Modifier.testTag("land_backend_guidance"))
                    else Text("Invalid land context response", Modifier.testTag("read_error"))
                }
            }
            if (summary != null) {
                ReadStatus(summary)
                if (summary?.successful == true) {
                    val body = summary!!.value!!.takeIf { it.isJsonObject }?.asJsonObject
                    val contract = body?.get("android_contract")?.takeIf { it.isJsonObject }?.asJsonObject
                    val payload = landSummaryPayload(summary!!, tenant, language)
                    if (payload == null) Text("Invalid land summary response", Modifier.testTag("read_error"))
                    else {
                        if (contract?.get("display_as_informational_only")?.asBoolean == true) Text("Informational guidance only", Modifier.testTag("land_informational"))
                        if (contract?.get("do_not_block_onboarding")?.asBoolean == true) Text("Guidance does not block onboarding", Modifier.testTag("land_nonblocking"))
                        Text(backendLabel(payload.get("title"), language), Modifier.testTag("land_summary_title"))
                        payload.getAsJsonArray("cards")?.forEachIndexed { index, element ->
                            val card = element.asJsonObject
                            Card(Modifier.fillMaxWidth().testTag("land_summary_card_$index")) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(backendLabel(card.get("title"), language))
                                    Text(backendLabel(card.get("value"), language))
                                    Text(backendLabel(card.get("detail"), language))
                                }
                            }
                        }
                        payload.getAsJsonArray("caveats")?.forEach { Text(backendLabel(it, language)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ParcelAvailabilityReadScreen(farmerId: String, onBack: () -> Unit) {
    val repository = readRepository()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ReadResult?>(null) }
    var season by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ReadPage("Parcel availability", onBack) {
        OutlinedTextField(season, { season = it.uppercase().filter(Char::isLetter).take(24); result = null }, enabled = !busy, label = { Text("Season code") }, modifier = Modifier.testTag("eligibility_season"))
        Button(enabled = season.isNotBlank() && !busy, modifier = Modifier.testTag("load_availability"), onClick = {
            busy = true; result = null
            scope.launch {
                try { result = repository.eligibility(farmerId, season, AndroidDynamicTestContext.projectIdFor(AgriOsApp.instance.database.authDao())) }
                finally { busy = false }
            }
        }) { Text("Check availability") }
        if (busy || result != null) ReadStatus(result)
        if (result?.successful == true) {
            val rows = availabilityRows(result!!, farmerId)
            if (rows == null) Text("Invalid parcel availability response", Modifier.testTag("read_error"))
            else if (rows.isEmpty()) Text("No parcels available")
            else rows.forEach { row ->
                val id = row.stringValue("parcel_id") ?: row.stringValue("id").orEmpty()
                Card(Modifier.fillMaxWidth().testTag("availability_$id")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.stringValue("survey_number") ?: "Parcel")
                        Text(row.stringValue("eligibility_status").orEmpty(), Modifier.testTag("availability_status_$id"))
                        val cycle = row.get("active_cycle")?.takeIf { it.isJsonObject }?.asJsonObject
                        cycle?.let {
                            Text("${it.stringValue("crop_code")} / ${it.stringValue("season_code")}", Modifier.testTag("occupied_cycle_${it.stringValue("id")}"))
                            Text("Sowing: ${it.stringValue("planned_sowing_date") ?: "Not recorded"}", Modifier.testTag("occupied_sowing_$id"))
                            Text("An existing cycle occupies this parcel. No new cycle is created here.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssignedWorkReadScreen(projectId: String?, onBack: () -> Unit, onFarmer: (String) -> Unit) {
    val repository = readRepository()
    var result by remember { mutableStateOf<ReadResult?>(null) }
    var tenant by remember { mutableStateOf("") }
    LaunchedEffect(projectId) {
        tenant = withContext(Dispatchers.IO) { AgriOsApp.instance.database.authDao().getAuthState()?.tenantId.orEmpty() }
        result = repository.assigned(projectId)
    }
    ReadPage("Assigned farmers", onBack) {
        Text("Field-agent assigned work")
        projectId?.let { Text("Project: $it", Modifier.testTag("assigned_project")) }
        ReadStatus(result)
        if (result?.successful == true) {
            val rows = assignedRows(result!!, tenant, projectId)
            if (rows == null) Text("Invalid assigned-work response", Modifier.testTag("read_error"))
            else if (rows.isEmpty()) Text("No assigned farmers", Modifier.testTag("assigned_empty"))
            else rows.forEach { row ->
                val farmer = row.getAsJsonObject("farmer")
                val id = farmer.stringValue("id")!!
                OutlinedCard(onClick = { onFarmer(id) }, modifier = Modifier.fillMaxWidth().testTag("assigned_farmer_$id")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(farmer.stringValue("display_name") ?: "Assigned farmer")
                        Text("Parcels: ${row.get("parcel_count")?.asInt ?: 0}")
                        Text("Active crop cycles: ${row.get("active_crop_cycle_count")?.asInt ?: 0}")
                        Text("View farmer context")
                    }
                }
            }
        }
    }
}

@Composable
fun AssignedFarmerReadScreen(farmerId: String, onBack: () -> Unit) {
    val repository = readRepository()
    var result by remember { mutableStateOf<ReadResult?>(null) }
    var tenant by remember { mutableStateOf("") }
    LaunchedEffect(farmerId) {
        tenant = withContext(Dispatchers.IO) { AgriOsApp.instance.database.authDao().getAuthState()?.tenantId.orEmpty() }
        result = repository.assignedFarmer(farmerId)
    }
    ReadPage("Assisted farmer", onBack) {
        ReadStatus(result)
        if (result?.successful == true) {
            val root = result?.value?.takeIf { it.isJsonObject }?.asJsonObject
            val farmer = root?.get("farmer")?.takeIf { it.isJsonObject }?.asJsonObject
            if (root?.stringValue("schema_version") != "farmer_launch_context.v1" || root.stringValue("tenant_id") != tenant || farmer?.stringValue("id") != farmerId) {
                Text("Invalid farmer context response", Modifier.testTag("read_error"))
            } else {
                Text(farmer.stringValue("display_name") ?: "Assigned farmer", Modifier.testTag("assisted_identity_$farmerId"))
                Text("Active projects: ${root.get("active_project_count")?.asInt ?: 0}")
                root.getAsJsonArray("project_enrollments")?.forEach { element ->
                    val enrollment = element.asJsonObject
                    Text("Project: ${enrollment.stringValue("project_id").orEmpty()}")
                    Text("Enrollment: ${enrollment.stringValue("status").orEmpty()}")
                }
                Text("Read-only assisted farmer context. No profile or assignment changes.")
            }
        }
    }
}
