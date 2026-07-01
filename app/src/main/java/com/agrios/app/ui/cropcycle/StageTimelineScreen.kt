package com.agrios.app.ui.cropcycle

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.cache.CropCycleCache
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.remote.dto.CropStageDto
import com.agrios.app.data.remote.dto.CropTemplateDto
import com.agrios.app.data.remote.dto.RecommendedActivityDto
import com.agrios.app.data.remote.dto.StageUpdateDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "StageTimeline"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageTimelineScreen(
    cycleId: String,
    onBack: () -> Unit,
    onNavigateToActivityLog: ((cycleId: String, stageCode: String) -> Unit)? = null
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    var cycle by remember { mutableStateOf<CropCycleResponseDto?>(null) }
    var template by remember { mutableStateOf<CropTemplateDto?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    // Build API client
    fun buildApi(): AgriOsApi {
        val okHttp = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao()))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgriOsApi::class.java)
    }

    // Load cycle data
    LaunchedEffect(cycleId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val api = buildApi()
                val response = api.getCropCycle(cycleId)
                if (response.isSuccessful) {
                    cycle = response.body()
                    cycle?.let { CropCycleCache.upsert(AgriOsApp.instance, it) }
                    // Fetch template for recommended activities
                    val cropCode = cycle?.cropCode
                    val season = cycle?.seasonCode
                    if (cropCode != null) {
                        try {
                            val templateResp = api.getCropTemplate(cropCode, season)
                            if (templateResp.isSuccessful) {
                                template = templateResp.body()
                                Log.d(TAG, "Template loaded: ${template?.cropCode}, ${template?.stages?.size} stages")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Template fetch failed (non-critical): ${e.message}")
                        }
                    }
                } else {
                    error = "HTTP ${response.code()}"
                }
            } catch (e: Exception) {
                error = e.message
                Log.e(TAG, "Load failed", e)
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(cycle?.cropName ?: cycle?.cropCode ?: LanguageManager.localize("Crop Cycle", "फसल चक्र"))
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
                cycle != null -> {
                    val c = cycle!!
                    val isCycleCompleted = c.status.equals("COMPLETED", ignoreCase = true)

                    // Header info
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "${c.cropName ?: c.cropCode} — ${c.seasonCode}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (c.plannedSowingDate != null) {
                                Text(
                                    "${LanguageManager.localize("Sowing", "बुवाई")}: ${c.plannedSowingDate}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (c.expectedHarvestDate != null) {
                                Text(
                                    "${LanguageManager.localize("Harvest", "कटाई")}: ${c.expectedHarvestDate}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "Status: ${c.status}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCycleCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                            )
                            if (!isCycleCompleted && c.inferredCurrentStage != null) {
                                Text(
                                    "Current stage: ${c.inferredCurrentStage}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isCycleCompleted) {
                                Text(
                                    "Completed crop cycle - view only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Action message
                    if (actionMessage != null) {
                        Text(actionMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }

                    // Stage timeline
                    Text(
                        LanguageManager.localize("Stages", "चरण"),
                        style = MaterialTheme.typography.titleSmall
                    )

                    c.stages.forEachIndexed { index, stage ->
                        // Get recommended activities for this stage from template
                        val templateStage = template?.stages?.find { it.code == stage.code }
                        val recommendations = templateStage?.recommendedActivities ?: emptyList()

                        StageTimelineItem(
                            stage = stage,
                            isLast = index == c.stages.lastIndex,
                            recommendations = recommendations,
                            stageStartDate = stage.expectedStartDate,
                            isCycleCompleted = isCycleCompleted,
                            onAdvance = { newStatus ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val api = buildApi()
                                            val stageId = stage.id ?: stage.code
                                            val resp = api.updateStage(
                                                cycleId = cycleId,
                                                stageId = stageId,
                                                request = StageUpdateDto(action = newStatus)
                                            )
                                            if (resp.isSuccessful) {
                                                val updatedCycle = resp.body()?.cropCycle
                                                if (updatedCycle != null) {
                                                    cycle = updatedCycle
                                                    CropCycleCache.upsert(AgriOsApp.instance, updatedCycle)
                                                } else {
                                                    val reloadResp = api.getCropCycle(cycleId)
                                                    if (reloadResp.isSuccessful) {
                                                        cycle = reloadResp.body()
                                                        cycle?.let { CropCycleCache.upsert(AgriOsApp.instance, it) }
                                                    }
                                                }
                                                actionMessage = "Updated ${stage.getDisplayName()} -> $newStatus"
                                            } else {
                                                actionMessage = "❌ ${resp.code()}: ${resp.message()}"
                                            }
                                        } catch (e: Exception) {
                                            actionMessage = "❌ ${e.message}"
                                        }
                                    }
                                }
                            },
                            onLogActivity = {
                                onNavigateToActivityLog?.invoke(cycleId, stage.code)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageTimelineItem(
    stage: CropStageDto,
    isLast: Boolean,
    recommendations: List<RecommendedActivityDto> = emptyList(),
    stageStartDate: String? = null,
    isCycleCompleted: Boolean = false,
    onAdvance: (status: String) -> Unit,
    onLogActivity: () -> Unit = {}
) {
    val stageColor = try {
        Color(android.graphics.Color.parseColor(stage.color ?: "#9E9E9E"))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.outline
    }

    val statusIcon = when (stage.status) {
        "COMPLETED" -> "✅"
        "IN_PROGRESS", "ACTIVE", "STARTED" -> "🔵"
        "SKIPPED" -> "⏭️"
        else -> "⚪"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline dot + line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        when (stage.status) {
                            "COMPLETED" -> Color(0xFF4CAF50)
                            "IN_PROGRESS", "ACTIVE", "STARTED" -> Color(0xFF2196F3)
                            "SKIPPED" -> Color(0xFF9E9E9E)
                            else -> stageColor.copy(alpha = 0.3f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (stage.status == "COMPLETED") {
                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                } else {
                    Text("${stage.order}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp)
                        .background(stageColor.copy(alpha = 0.3f))
                )
            }
        }

        // Stage content
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, bottom = 16.dp)) {
            Text(
                "$statusIcon ${stage.getDisplayName()}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (stage.expectedStartDate != null) {
                Text(
                    "${stage.expectedStartDate} — ${stage.expectedEndDate ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (stage.durationDays > 0) {
                Text(
                    "${stage.durationDays} ${LanguageManager.localize("days", "दिन")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Description
            val desc = stage.description
            if (!desc.isNullOrBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Action buttons
            val isActive = !isCycleCompleted && (stage.status == "IN_PROGRESS" || stage.status == "ACTIVE" || stage.status == "STARTED")
            val isCompleted = stage.status.equals("COMPLETED", ignoreCase = true)
            if ((!isCycleCompleted && stage.status == "PENDING") || isActive || isCompleted) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isCycleCompleted && stage.status == "PENDING") {
                        OutlinedButton(
                            onClick = { onAdvance("START") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(LanguageManager.localize("Start", "????"), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = { onAdvance("SKIP") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(LanguageManager.localize("Skip", "??????"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (isActive) {
                        Button(
                            onClick = { onAdvance("COMPLETE") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(LanguageManager.localize("Complete", "????"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (isActive || isCompleted) {
                        OutlinedButton(
                            onClick = { onLogActivity() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (isCompleted) LanguageManager.localize("View Activities", "View Activities")
                                else LanguageManager.localize("Activities", "Activities"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
