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
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cycleId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
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
                    .create(AgriOsApi::class.java)

                // Load cycle
                val cycleResp = api.getCropCycle(cycleId)
                if (cycleResp.isSuccessful) {
                    cycle = cycleResp.body()

                    // Load cycle-aware recommended activities with backend-calculated dates
                    val recResp = api.getRecommendedActivities(cycleId)
                    Log.d(TAG, "Recommended activities response: ${recResp.code()}, body null? ${recResp.body() == null}")
                    if (recResp.isSuccessful) {
                        allRecommendations = recResp.body()?.recommendedActivities ?: emptyList()
                        Log.d(TAG, "Recommended activities loaded: ${allRecommendations.size}")
                    } else {
                        Log.w(TAG, "Recommended activities fetch failed: ${recResp.code()} ${recResp.errorBody()?.string()}")
                    }

                    // Fetch logged activities for this cycle
                    try {
                        val activitiesUrl = "${ApiConfig.BASE_URL}crop-cycles/$cycleId/activities"
                        val activitiesReq = okhttp3.Request.Builder().url(activitiesUrl).build()
                        val activitiesResp = okHttp.newCall(activitiesReq).execute()
                        if (activitiesResp.isSuccessful) {
                            val body = activitiesResp.body?.string()
                            if (body != null) {
                                loggedActivities = com.google.gson.Gson().fromJson(
                                    body, object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                                )
                                Log.d(TAG, "Logged activities: ${loggedActivities.size}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch logged activities: ${e.message}")
                    }
                } else {
                    error = "HTTP ${cycleResp.code()}"
                }
            } catch (e: Exception) {
                error = e.message
                Log.e(TAG, "Load failed", e)
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
    val isReadOnly = cycle?.status.equals("COMPLETED", ignoreCase = true) ||
        targetStage?.status.equals("COMPLETED", ignoreCase = true)

    // Get cycle-aware recommendations for the selected/target stage.
    val recommendations = allRecommendations.filter { it.stageCode == targetStageCode }

    // Debug
    LaunchedEffect(cycle, allRecommendations, selectedStageCode) {
        Log.d(TAG, "Selected stage: code='$selectedStageCode'; target code='$targetStageCode', status='${targetStage?.status}'")
        Log.d(TAG, "Recommendation stage codes: ${allRecommendations.map { it.stageCode }.distinct()}")
        Log.d(TAG, "Recommendations count for '$targetStageCode': ${recommendations.size}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${targetStage?.getDisplayName() ?: LanguageManager.localize("Activities", "गतिविधियाँ")}"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
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
                                if (stageStartDate != null) {
                                    Text(
                                        "${LanguageManager.localize("Started", "शुरू")}: $stageStartDate",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

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
                                    .clickable(enabled = !isReadOnly && !isLogged) {
                                        if (!isLogged) {
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
                                        if (!isLogged) Text("→", style = MaterialTheme.typography.titleMedium)
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
                            "Completed stage - activities are view only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Custom activity button
                        OutlinedButton(
                            onClick = onLogCustomActivity,
                            modifier = Modifier.fillMaxWidth()
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
