package com.agrios.app.ui.cropcycle

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.remote.dto.CycleRecommendedActivityDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "StageActivities"

/**
 * Shows recommended activities for the current active stage as a checklist.
 * Tapping a recommendation navigates to the activity log form pre-filled.
 * Also has a "Custom Activity" button for logging activities not in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageActivitiesScreen(
    cycleId: String,
    selectedStageCode: String? = null,
    onBack: () -> Unit,
    onLogActivity: (prefillData: Map<String, String>) -> Unit,
    onLogCustomActivity: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val lang = if (LanguageManager.isHindi()) "hi" else "en"

    var cycle by remember { mutableStateOf<CropCycleResponseDto?>(null) }
    var allRecommendations by remember { mutableStateOf<List<CycleRecommendedActivityDto>>(emptyList()) }
    var loggedActivities by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var financeSummary by remember { mutableStateOf<CycleFinanceRead?>(null) }
    val readStatuses = remember(cycleId) { mutableStateMapOf<String, Int>() }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cycleId) {
        isLoading = true
        error = null
        withContext(Dispatchers.IO) {
            try {
                val okHttp = com.agrios.app.data.repository.authenticatedReadClientBuilder()
                    .followRedirects(false).followSslRedirects(false)
                    .addInterceptor(AuthInterceptor(db.authDao()))
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(ApiConfig.BASE_URL)
                    .client(okHttp)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AgriOsApi::class.java)

                // Load cycle
                val cycleResp = api.getCropCycle(cycleId)
                fun record(route: String, status: Int) {
                    readStatuses[route] = status
                    Log.d(TAG, "CycleRead(route=$route, status=$status)")
                }
                record("cycle_detail", cycleResp.code())
                if (cycleResp.isSuccessful) {
                    cycle = checkNotNull(cycleResp.body())
                    check(cycle?.id == cycleId)

                    // Load cycle-aware recommended activities with backend-calculated dates
                    val recResp = api.getRecommendedActivities(cycleId)
                    record("recommendations", recResp.code())
                    if (recResp.isSuccessful) {
                        val body = checkNotNull(recResp.body())
                        check(body.cycleId == cycleId)
                        allRecommendations = body.recommendedActivities
                    } else {
                        error("Recommendations unavailable (HTTP ${recResp.code()})")
                    }

                    // Fetch logged activities for this cycle
                    try {
                        val activitiesUrl = "${ApiConfig.BASE_URL}crop-cycles/$cycleId/activities"
                        val activitiesReq = okhttp3.Request.Builder().url(activitiesUrl).build()
                        val activitiesResp = okHttp.newCall(activitiesReq).execute()
                        activitiesResp.use { response ->
                            record("activities", response.code)
                            check(response.isSuccessful) { "Activities unavailable (HTTP ${response.code})" }
                            val body = checkNotNull(response.body?.string())
                            loggedActivities = checkNotNull(com.google.gson.Gson().fromJson<List<Map<String, Any?>>>(
                                body, object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                            ))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw IllegalStateException("Activity history could not be loaded")
                    }

                    // Fetch backend finance summaries for Android-readable totals.
                    try {
                        fun readMap(route: String, url: String): com.google.gson.JsonObject {
                            return okHttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                                record(route, resp.code)
                                check(resp.isSuccessful) { "Finance unavailable (HTTP ${resp.code})" }
                                val body = checkNotNull(resp.body?.string())
                                com.google.gson.JsonParser.parseString(body).asJsonObject
                            }
                        }
                        val stageCost = readMap("stage_cost", "${ApiConfig.BASE_URL}crop-cycles/$cycleId/stage-cost-summary")
                        val profitLoss = readMap("profit_loss", "${ApiConfig.BASE_URL}crop-cycles/$cycleId/profit-loss-summary")
                        val tenant = checkNotNull(db.authDao().getAuthState()?.tenantId)
                        financeSummary = parseCycleFinance(stageCost, profitLoss, cycleId, tenant)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw IllegalStateException("Finance summaries could not be loaded")
                    }
                } else {
                    error = "HTTP ${cycleResp.code()}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Unable to load activity reads. Check your session or access and try again."
            } finally {
                isLoading = false
            }
        }
    }

    // Prefer the stage explicitly tapped from the timeline; fallback to active stage.
    val targetStage = cycle?.stages?.find { it.code == selectedStageCode }
        ?: cycle?.stages?.find { it.status == "IN_PROGRESS" || it.status == "ACTIVE" || it.status == "STARTED" }
    val targetStageCode = targetStage?.code
    val stageStartDate = targetStage?.expectedStartDate
    val isReadOnly = !canLogStageActivity(cycle?.status, targetStage?.status)

    // Get cycle-aware recommendations for the selected/target stage.
    val recommendations = allRecommendations.filter { it.stageCode == targetStageCode }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${targetStage?.getDisplayName() ?: LanguageManager.localize("Activities", "गतिविधियाँ")}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("activities_back")) { Icon(Icons.Default.ArrowBack, "Back") }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator()
                    Text(LanguageManager.localize("Loading...", "लोड हो रहा..."))
                }
                error != null -> {
                    Text("❌ $error", color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    if (AgriOsApp.instance.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        readStatuses.forEach { (route, status) ->
                            Text("$route: HTTP $status", Modifier.testTag("cycle_read_$route"))
                        }
                        if (financeSummary != null) {
                            Text("stage_cost_schema=crop_cycle_stage_cost_summary.v1")
                            Text("profit_loss_schema=crop_cycle_profit_loss_summary.v1")
                        }
                    }
                    Text("Cycle recommendations: ${allRecommendations.size}", Modifier.testTag("cycle_recommendation_count"))
                    Text("Stage recommendations: ${recommendations.size}", Modifier.testTag("stage_recommendation_count"))
                    // Stage info header
                    if (targetStage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "🌱 ${targetStage.getDisplayName()} — ${cycle?.cropCode ?: ""}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text("${targetStage.code}: ${targetStage.status}", Modifier.testTag("activities_stage_state"))
                                if (isReadOnly) Text("Read only — activity logging is unavailable for this stage.",
                                    Modifier.testTag("stage_read_only"))
                                if (stageStartDate != null) {
                                    Text(
                                        "Expected start: $stageStartDate",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    financeSummary?.let { summary ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Finance Summary", style = MaterialTheme.typography.titleSmall)
                                Text("Activities logged: ${summary.activityCount}", style = MaterialTheme.typography.bodySmall)
                                Text("Actual expense: ₹${summary.actualExpense}", modifier = Modifier.testTag("finance_actual_expense"), style = MaterialTheme.typography.bodySmall)
                                Text("P&L total expenses: ₹${summary.totalExpenses}", modifier = Modifier.testTag("finance_total_expenses"), style = MaterialTheme.typography.bodySmall)
                                Text("P&L total income: ₹${summary.totalIncome}", Modifier.testTag("finance_total_income"))
                                Text("Profit/Loss: ₹${summary.profitOrLoss}", Modifier.testTag("finance_profit_loss"))
                            }
                        }
                    }

                    if (loggedActivities.isEmpty()) {
                        Text("No activities recorded", Modifier.testTag("activities_empty"))
                    } else {
                        Text("Logged Activities", style = MaterialTheme.typography.titleSmall)
                        loggedActivities.forEach { activity ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(activity["input_name"]?.toString().orEmpty(), modifier = Modifier.testTag("activity_${activity["id"]}"), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "₹${activity["cost_amount"] ?: "0.00"} • ${activity["quantity"] ?: ""} ${activity["quantity_unit"] ?: ""} • ${activity["activity_date"] ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    activity["stage_code"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                                        Text("Stage: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (recommendations.isNotEmpty()) {
                        Text(
                            "📋 ${LanguageManager.localize("Recommended Activities", "अनुशंसित गतिविधियाँ")}",
                            style = MaterialTheme.typography.titleSmall
                        )

                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                        recommendations.forEachIndexed { index, rec ->
                            val recommendedDate = rec.recommendedDate ?: calculateRecommendedDate(stageStartDate, rec.dayOffset)
                            val isOverdue = recommendedDate != null && recommendedDate < today

                            // Check if this recommendation has been logged
                            val isLogged = loggedActivities.any { activity ->
                                val loggedInput = activity["input_name"]?.toString() ?: ""
                                val loggedType = activity["activity_type"]?.toString() ?: ""
                                loggedInput.contains(rec.inputName, ignoreCase = true) ||
                                    (loggedType == rec.activityType && loggedInput.isNotBlank() && rec.inputName.contains(loggedInput, ignoreCase = true))
                            }
                            val loggedEntry = if (isLogged) {
                                loggedActivities.find { activity ->
                                    val loggedInput = activity["input_name"]?.toString() ?: ""
                                    loggedInput.contains(rec.inputName, ignoreCase = true)
                                }
                            } else null

                            val icon = when {
                                isLogged -> "✅"
                                rec.isCritical && isOverdue -> "🚨"
                                isOverdue -> "⚠️"
                                rec.isCritical -> "❗"
                                else -> "📅"
                            }
                            val desc = rec.description?.get(lang) ?: rec.description?.get("en") ?: ""

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recommendation_card_${rec.stageCode}_$index")
                                    .clickable(enabled = !isReadOnly && !isLogged) {
                                        if (!isReadOnly && !isLogged) {
                                            // Pre-fill the activity form
                                            val prefill = mutableMapOf(
                                                "activity_type" to rec.activityType,
                                                "input_name" to rec.inputName,
                                                "stage_code" to rec.stageCode
                                            )
                                            if (rec.typicalQuantity != null) {
                                                prefill["quantity_hint"] = rec.typicalQuantity
                                            }
                                            if (rec.typicalCostPerAcre != null) {
                                                prefill["cost_hint"] = rec.typicalCostPerAcre.toString()
                                            }
                                            prefill["activity_date"] = rec.recommendedDate ?: recommendedDate ?: today
                                            onLogActivity(prefill)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isLogged -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        isOverdue -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("$icon ", style = MaterialTheme.typography.bodyMedium)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                rec.inputName,
                                                modifier = Modifier.testTag("recommendation_${rec.stageCode}_$index"),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            if (isLogged && loggedEntry != null) {
                                                val loggedCost = loggedEntry["cost_amount"]?.toString() ?: ""
                                                val loggedQty = loggedEntry["quantity"]?.toString() ?: ""
                                                val loggedDate = loggedEntry["activity_date"]?.toString() ?: ""
                                                Text(
                                                    "₹$loggedCost • $loggedQty • $loggedDate",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            } else {
                                                Text(
                                                    "${LanguageManager.localize("Day", "दिन")} ${rec.dayOffset}${if (recommendedDate != null) " ($recommendedDate)" else ""}" +
                                                        "${if (rec.typicalQuantity != null) " • ${rec.typicalQuantity}" else ""}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (desc.isNotBlank() && !isLogged) {
                                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (!isReadOnly && !isLogged) Text("→", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            LanguageManager.localize("No recommendations available for this stage", "इस चरण के लिए कोई अनुशंसा उपलब्ध नहीं"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    if (isReadOnly) {
                        Text(
                            "Activities are view only for this stage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Custom activity button
                        OutlinedButton(
                            onClick = { if (!isReadOnly) onLogCustomActivity() },
                            modifier = Modifier.fillMaxWidth().testTag("log_custom_activity")
                        ) {
                            Text(LanguageManager.localize("+ Log Custom Activity", "+ Log Custom Activity"))
                        }
                    }
                }
            }
        }
    }
}

private fun calculateRecommendedDate(stageStartDate: String?, dayOffset: Int): String? {
    if (stageStartDate == null) return null
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val start = sdf.parse(stageStartDate) ?: return null
        val cal = Calendar.getInstance()
        cal.time = start
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        sdf.format(cal.time)
    } catch (_: Exception) {
        null
    }
}
