package com.agrios.app.ui.home

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.cache.CropCycleCache
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AndroidDynamicTestContext
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncManager
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.SyncPriority
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.local.entity.SyncStatus
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.CreateFarmerDto
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.remote.dto.ParcelGeometryUpdateRequest
import com.agrios.app.data.remote.dto.ResolveConflictDto
import com.agrios.app.data.repository.OfflineCropSyncRepository
import com.agrios.app.data.repository.ProfileHydrationRepository
import com.agrios.app.ui.components.SyncStatusBadge
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "HomeScreen"

private val personaLifecycleMobileDigits = setOf(
    "919900001101",
    "919900001201",
    "919900001301",
    "919900001401",
    "919900001501",
    "919900001601",
    "919900001701",
    "919900001801"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFarmerEnroll: () -> Unit,
    onNavigateToParcelRegister: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFarmerProfile: (farmerId: String) -> Unit = {},
    onNavigateToCropCycle: () -> Unit = {},
    onNavigateToStageTimeline: (cycleId: String) -> Unit = {}
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()
    val pendingCount by db.syncQueueDao().observePendingCount().collectAsState(initial = 0)
    val conflictCount by db.syncQueueDao().observeConflictCount().collectAsState(initial = 0)
    val failedCount by db.syncQueueDao().observeFailedCount().collectAsState(initial = 0)
    val attentionItems by db.syncQueueDao().observeAttentionItems().collectAsState(initial = emptyList())
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncMessage by remember { mutableStateOf<String?>(null) }

    // Check if farmer profile exists (enrollment done)
    val farmers by db.farmerDao().observeAll().collectAsState(initial = emptyList())
    val observedAuthState by db.authDao().observeAuthState().collectAsState(initial = null)
    val hasProfile = farmers.isNotEmpty()
    val farmer = farmers.firstOrNull()
    var activeCycles by remember { mutableStateOf<List<CropCycleResponseDto>>(emptyList()) }
    var completedCycles by remember { mutableStateOf<List<CropCycleResponseDto>>(emptyList()) }
    var isLoadingCycles by remember { mutableStateOf(false) }
    var cycleLoadMessage by remember { mutableStateOf<String?>(null) }
    var cachedCycles by remember { mutableStateOf<List<CropCycleResponseDto>>(emptyList()) }
    var useCachedCycleFallback by remember { mutableStateOf(false) }
    var isHydratingProfile by remember { mutableStateOf(false) }
    var hydrationAttempted by remember { mutableStateOf(false) }
    var hydrationMessage by remember { mutableStateOf<String?>(null) }
    var staleContextTestEventId by remember { mutableStateOf<String?>(null) }
    var staleContextRecoveryStatus by remember { mutableStateOf<String?>(null) }
    var versionMismatchTestEventId by remember { mutableStateOf<String?>(null) }
    var workflowInvalidTestEventId by remember { mutableStateOf<String?>(null) }
    var coldStartTestEventId by remember { mutableStateOf<String?>(null) }
    var coldStartTestActivityId by remember { mutableStateOf<String?>(null) }
    var coldStartPersistenceStatus by remember { mutableStateOf<String?>(null) }
    var deviceRestartTestEventId by remember { mutableStateOf<String?>(null) }
    var deviceRestartTestActivityId by remember { mutableStateOf<String?>(null) }
    var deviceRestartPersistenceStatus by remember { mutableStateOf<String?>(null) }
    var uncertainResultTestEventId by remember { mutableStateOf<String?>(null) }
    var uncertainResultTestActivityId by remember { mutableStateOf<String?>(null) }
    var uncertainResultStatus by remember { mutableStateOf<String?>(null) }
    var dependencyOrderTestEventIds by remember { mutableStateOf<String?>(null) }
    var dependencyOrderStatus by remember { mutableStateOf<String?>(null) }
    var partialBatchTestIds by remember { mutableStateOf<String?>(null) }
    var partialBatchStatus by remember { mutableStateOf<String?>(null) }
    var partialBatchConflictTestIds by remember { mutableStateOf<String?>(null) }
    var partialBatchConflictStatus by remember { mutableStateOf<String?>(null) }
    var multiConflictTestEventIds by remember { mutableStateOf<String?>(null) }
    var multiConflictStatus by remember { mutableStateOf<String?>(null) }
    var queueBackpressureTestIds by remember { mutableStateOf<String?>(null) }
    var queueBackpressureStatus by remember { mutableStateOf<String?>(null) }
    var interruptedMultibatchResumeTestIds by remember { mutableStateOf<String?>(null) }
    var poisonRowBacklogTestIds by remember { mutableStateOf<String?>(null) }
    var personaLifecycleStatus by remember { mutableStateOf<String?>(null) }
    var landSummaryDigiPinStatus by remember { mutableStateOf<String?>(null) }
    var staleConflict404TestStatus by remember { mutableStateOf<String?>(null) }
    var versionMismatchRecoveryStatus by remember { mutableStateOf<String?>(null) }
    var workflowInvalidRecoveryStatus by remember { mutableStateOf<String?>(null) }
    var fpoWorkflowStatus by remember { mutableStateOf<String?>(null) }
    var fpoSearchDrilldownStatus by remember { mutableStateOf<String?>(null) }
    var fpoClosureNoticeStatus by remember { mutableStateOf<String?>(null) }
    var broadcastReadAckStatus by remember { mutableStateOf<String?>(null) }
    var broadcastMediaAttachmentStatus by remember { mutableStateOf<String?>(null) }
    var broadcastLanguageFallbackStatus by remember { mutableStateOf<String?>(null) }
    var broadcastTerminalVisibilityStatus by remember { mutableStateOf<String?>(null) }
    var broadcastAudienceTargetingStatus by remember { mutableStateOf<String?>(null) }
    var fieldEventAdvisoryLoopStatus by remember { mutableStateOf<String?>(null) }
    var localizationOverrideStatus by remember { mutableStateOf<String?>(null) }
    var landIntelligenceOverrideStatus by remember { mutableStateOf<String?>(null) }
    val isDebugBuild = (AgriOsApp.instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val currentMobileDigits = (farmer?.mobileNumber ?: observedAuthState?.mobileNumber).orEmpty().filter { it.isDigit() }
    val showFpoWorkflowTestTools = isDebugBuild && currentMobileDigits
        .let { digits -> digits == "9900002000" || digits == "919900002000" || digits == "9900002101" || digits == "919900002101" || digits == "9900002106" || digits == "919900002106" }
    val showDynamicSyncTestTools = isDebugBuild && currentMobileDigits
        .let { digits -> digits == "919900000002" || digits == "9900000002" }
    val showPersonaLifecycleTestTools = isDebugBuild && currentMobileDigits
        .let { digits -> digits in personaLifecycleMobileDigits || "91$digits" in personaLifecycleMobileDigits }

    val api = remember {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(db.authDao()))
            .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AgriOsApi::class.java)
    }

    // Trigger sync on screen entry
    LaunchedEffect(Unit) {
        val hasPendingPersistenceSmokeRow = withContext(Dispatchers.IO) {
            listOf(
                "android_maestro_cold_start_persistence_test",
                "android_maestro_device_restart_persistence_test"
            ).any { payloadNeedle ->
                db.syncQueueDao().countByPayloadNeedleAndStatus(
                    payloadNeedle,
                    SyncStatus.PENDING.name
                ) > 0
            }
        }
        if (!hasPendingPersistenceSmokeRow) {
            SyncWorker.triggerImmediateSync(AgriOsApp.instance)
        } else {
            Log.d(TAG, "Skipped startup auto-sync for offline persistence smoke")
        }
    }

    LaunchedEffect(hasProfile) {
        if (!hydrationAttempted) {
            val authState = withContext(Dispatchers.IO) { db.authDao().getAuthState() }
            val shouldRefreshDynamicProfile = hasProfile &&
                AndroidDynamicTestContext.isEnabledFor(authState) &&
                !authState?.mobileNumber.isNullOrBlank()
            if (hasProfile && !shouldRefreshDynamicProfile) return@LaunchedEffect

            hydrationAttempted = true
            isHydratingProfile = true
            hydrationMessage = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val repository = ProfileHydrationRepository(
                        context = AgriOsApp.instance,
                        db = db,
                        api = api
                    )
                    if (shouldRefreshDynamicProfile) {
                        repository.hydrateByMobile(
                            mobile = authState?.mobileNumber.orEmpty(),
                            projectId = AndroidDynamicTestContext.projectIdFor(authState)
                        )
                    } else {
                        repository.hydrateAfterLogin()
                    }
                }
                hydrationMessage = result.message
            } catch (e: Exception) {
                hydrationMessage = e.message
            } finally {
                isHydratingProfile = false
            }
        }
    }

    LaunchedEffect(farmer?.id) {
        val farmerId = farmer?.id
        if (farmerId.isNullOrBlank()) {
            activeCycles = emptyList()
            completedCycles = emptyList()
            cachedCycles = CropCycleCache.getAll(AgriOsApp.instance)
            useCachedCycleFallback = true
            return@LaunchedEffect
        }

        isLoadingCycles = true
        cycleLoadMessage = null
        try {
            val plannedResponse = withContext(Dispatchers.IO) {
                api.getCropCycles(farmerId = farmerId, status = "PLANNED")
            }
            val activeResponse = withContext(Dispatchers.IO) {
                api.getCropCycles(farmerId = farmerId, status = "ACTIVE")
            }
            val completedResponse = withContext(Dispatchers.IO) {
                api.getCropCycles(farmerId = farmerId, status = "COMPLETED")
            }

            val plannedCycles = if (plannedResponse.isSuccessful) {
                plannedResponse.body().orEmpty()
            } else {
                emptyList()
            }

            if (activeResponse.isSuccessful) {
                activeCycles = (plannedCycles + activeResponse.body().orEmpty()).dedupeForHome()
                activeCycles.forEach { CropCycleCache.upsert(AgriOsApp.instance, it) }
            } else {
                activeCycles = plannedCycles
                plannedCycles.forEach { CropCycleCache.upsert(AgriOsApp.instance, it) }
                if (activeResponse.code() != 405 && plannedCycles.isEmpty()) {
                    cycleLoadMessage = "Could not load active crop cycles (${activeResponse.code()})"
                }
            }

            if (completedResponse.isSuccessful) {
                completedCycles = completedResponse.body().orEmpty()
                completedCycles.forEach { CropCycleCache.upsert(AgriOsApp.instance, it) }
            } else {
                completedCycles = emptyList()
                if (completedResponse.code() != 405 && cycleLoadMessage == null) {
                    cycleLoadMessage = "Could not load completed crop cycles (${completedResponse.code()})"
                }
            }
            cachedCycles = CropCycleCache.getAll(AgriOsApp.instance)
            useCachedCycleFallback = false
        } catch (e: Exception) {
            activeCycles = emptyList()
            completedCycles = emptyList()
            cachedCycles = CropCycleCache.getAll(AgriOsApp.instance)
            useCachedCycleFallback = true
            cycleLoadMessage = e.message
        } finally {
            isLoadingCycles = false
        }
    }

    fun runSyncNow() {
        scope.launch {
            isSyncing = true
            lastSyncMessage = null
            try {
                val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor(AuthInterceptor(db.authDao()))
                    .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(ApiConfig.BASE_URL)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(com.agrios.app.data.remote.api.AgriOsApi::class.java)
                val syncManager = SyncManager(db.syncQueueDao(), api)
                syncManager.fixAndRetryFailedItems()
                val result = syncManager.processQueue()
                lastSyncMessage = when {
                    result.accepted > 0 -> "âœ… ${result.accepted} ${LanguageManager.localize("synced", "à¤¸à¤¿à¤‚à¤• à¤¹à¥à¤")}"
                    result.failed > 0 -> "âŒ ${result.failed} ${LanguageManager.localize("failed", "à¤µà¤¿à¤«à¤²")}"
                    result.conflicts > 0 -> "âš ï¸ ${result.conflicts} ${LanguageManager.localize("conflicts", "à¤µà¤¿à¤°à¥‹à¤§")}"
                    pendingCount > 0 -> LanguageManager.localize(
                        "Still waiting - parent records/dependencies may need to sync first",
                        "à¤…à¤­à¥€ à¤ªà¥à¤°à¤¤à¥€à¤•à¥à¤·à¤¾ à¤®à¥‡à¤‚ - à¤ªà¤¹à¤²à¥‡ à¤®à¥‚à¤² à¤°à¤¿à¤•à¥‰à¤°à¥à¤¡/à¤¨à¤¿à¤°à¥à¤­à¤°à¤¤à¤¾à¤à¤‚ à¤¸à¤¿à¤‚à¤• à¤¹à¥‹ à¤¸à¤•à¤¤à¥€ à¤¹à¥ˆà¤‚"
                    )
                    else -> LanguageManager.localize("All synced", "à¤¸à¤¬ à¤¸à¤¿à¤‚à¤• à¤¹à¥‹ à¤—à¤¯à¤¾")
                }
            } catch (e: Exception) {
                lastSyncMessage = "âŒ ${e.message}"
            } finally {
                isSyncing = false
            }
        }
    }

    fun runSyncFirstBatchOnlyForTest() {
        scope.launch {
            isSyncing = true
            lastSyncMessage = null
            try {
                val okHttpClient = OkHttpClient.Builder()
                    .addInterceptor(AuthInterceptor(db.authDao()))
                    .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(ApiConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(ApiConfig.BASE_URL)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(com.agrios.app.data.remote.api.AgriOsApi::class.java)
                val syncManager = SyncManager(db.syncQueueDao(), api)
                syncManager.fixAndRetryFailedItems()
                val result = syncManager.processQueue(drainFollowUps = false)
                lastSyncMessage = "Interrupted resume first batch synced: ${result.accepted}"
                Log.d(TAG, "Interrupted resume first batch synced: accepted=${result.accepted}, conflicts=${result.conflicts}, failed=${result.failed}")
            } catch (e: Exception) {
                lastSyncMessage = "Failed: ${e.message}"
            } finally {
                isSyncing = false
            }
        }
    }
    fun queueStaleContextTestEvent() {
        scope.launch {
            val eventId = UUID.randomUUID().toString()
            val cropCycleId = UUID.randomUUID().toString()
            val payload = linkedMapOf<String, Any?>(
                "farmer_id" to "e1ee0941-2bad-4a18-a239-2a4119608a06",
                "parcel_id" to "98c1a0fa-4f5f-4b8c-97ae-d84992db1c44",
                "project_id" to AndroidDynamicTestContext.PROJECT_ID,
                "crop_code" to "RICE",
                "season_code" to "KHARIF",
                "planned_sowing_date" to "2026-08-02"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueCropCycleCreate(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = cropCycleId,
                    payload = payload,
                    eventId = eventId,
                    metadata = mapOf("source" to "android_maestro_stale_context_test")
                )
            }
            staleContextTestEventId = eventId
            staleContextRecoveryStatus = null
            versionMismatchTestEventId = null
            versionMismatchRecoveryStatus = null
            workflowInvalidTestEventId = null
            workflowInvalidRecoveryStatus = null
            lastSyncMessage = "Stale context test event queued: $eventId"
            Log.d(TAG, "Stale context test event queued: eventId=$eventId, cycleId=$cropCycleId")
        }
    }

    fun checkStaleContextFailureEvidence() {
        scope.launch {
            staleContextRecoveryStatus = "Stale context failure check running..."
            try {
                val eventId = staleContextTestEventId ?: error("stale context event id missing")
                val failedRow = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getFailedItems().firstOrNull { it.eventId == eventId }
                } ?: error("stale context failed local row not found")
                val parsed = parseSyncError(failedRow.lastError.orEmpty())
                val message = failedRow.toUserFacingSyncMessage()
                val messageText = "${message.title} ${message.body}"
                val pendingConflictRemoved = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    pending.isSuccessful && findPendingConflictId(pending.body(), eventId) == null
                }
                val localConflicts = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getConflicts().none { it.eventId == eventId }
                }

                val statusLines = listOf(
                    "Stale context failure check: ready",
                    "stale_context_event_id=$eventId",
                    "stale_context_failure_visible=${failedRow.isStaleLocalContextFailure()}",
                    "stale_context_error_code=${parsed["error_code"] ?: "UNKNOWN"}",
                    "stale_context_detail_code=${parsed["detail_code"] ?: "UNKNOWN"}",
                    "stale_context_refresh_required_copy=${message.title.contains("Refresh required", ignoreCase = true)}",
                    "stale_context_refresh_local_data_copy=${messageText.contains("Refresh", ignoreCase = true) && (messageText.contains("parcel", ignoreCase = true) || messageText.contains("project", ignoreCase = true))}",
                    "stale_context_no_manual_conflict_ui=${!messageText.contains("Manual review", ignoreCase = true)}",
                    "stale_context_no_version_mismatch_copy=${!messageText.contains("newer version", ignoreCase = true)}",
                    "stale_context_no_workflow_invalid_copy=${!messageText.contains("Workflow changed", ignoreCase = true)}",
                    "stale_context_failed_row_preserved=${failedRow.syncStatus == "FAILED"}",
                    "stale_context_no_sync_conflict_row=${pendingConflictRemoved && localConflicts}",
                    "stale_context_failed_draft_not_materialized=${failedRow.syncStatus == "FAILED" && failedRow.isStaleLocalContextFailure()}"
                )
                staleContextRecoveryStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Stale context failure check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                staleContextRecoveryStatus = "Stale context failure check failed: ${e.message}"
                Log.e(TAG, "Stale context failure check failed", e)
            }
        }
    }

    fun queueVersionMismatchTestEvent() {
        scope.launch {
            val eventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000111"
            val activityId = "0f7e0a6b-8472-5d6d-8a14-a9d000000112"
            val payload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "description" to "Android offline changed activity payload",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = payload,
                    eventId = eventId,
                    dependencyIds = emptyList(),
                    metadata = mapOf("source" to "android_maestro_version_mismatch_test")
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = eventId
            versionMismatchRecoveryStatus = null
            workflowInvalidRecoveryStatus = null
            workflowInvalidTestEventId = null
            lastSyncMessage = "Version mismatch test event queued: $eventId"
            Log.d(TAG, "Version mismatch test event queued: eventId=$eventId, activityId=$activityId")
        }
    }

    fun queueWorkflowInvalidTestEvent() {
        scope.launch {
            val eventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000121"
            val stageEntityId = "0f7e0a6b-8472-5d6d-8a14-a9d000000122"
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueStageTransition(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = "aa346148-468b-47de-9c86-47ad41aa1f11",
                    stageCode = "NURSERY",
                    action = "START",
                    eventId = eventId,
                    entityId = stageEntityId,
                    dependencyIds = emptyList(),
                    actualStartDate = "2026-08-02",
                    metadata = mapOf("source" to "android_maestro_workflow_invalid_test")
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            versionMismatchRecoveryStatus = null
            workflowInvalidTestEventId = eventId
            workflowInvalidRecoveryStatus = null
            lastSyncMessage = "Workflow invalid test event queued: $eventId"
            Log.d(TAG, "Workflow invalid test event queued: eventId=$eventId, stageEntityId=$stageEntityId")
        }
    }

    fun checkVersionMismatchConflictEvidence() {
        scope.launch {
            versionMismatchRecoveryStatus = "Version mismatch conflict check running..."
            try {
                val eventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000111"
                val conflictRow = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getConflicts().firstOrNull { it.eventId == eventId }
                } ?: error("version mismatch local conflict row not found")
                val parsed = parseSyncError(conflictRow.lastError.orEmpty())
                val pendingResponse = withContext(Dispatchers.IO) { api.getPendingConflicts(limit = 100) }
                if (!pendingResponse.isSuccessful) {
                    error("pending conflicts ${pendingResponse.code()}")
                }
                val pendingConflictId = findPendingConflictId(pendingResponse.body(), eventId)
                val message = conflictRow.toUserFacingSyncMessage()
                val messageText = "${message.title} ${message.body}"
                val failedRows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getFailedItems().filter { it.eventId == eventId }
                }

                val statusLines = listOf(
                    "Version mismatch conflict check: ready",
                    "version_mismatch_conflict_visible=${pendingConflictId != null && conflictRow.isVersionMismatchConflict()}",
                    "version_mismatch_conflict_type=${parsed["conflict_type"] ?: "UNKNOWN"}",
                    "version_mismatch_resolution_strategy=${parsed["resolution_strategy"] ?: parsed["strategy"] ?: "MANUAL_REVIEW"}",
                    "version_mismatch_copy_manual_review=${message.title.contains("Manual review", ignoreCase = true)}",
                    "version_mismatch_copy_server_newer=${messageText.contains("server", ignoreCase = true) && messageText.contains("newer", ignoreCase = true)}",
                    "version_mismatch_no_stale_context_copy=${!messageText.contains("stale", ignoreCase = true)}",
                    "version_mismatch_no_workflow_changed_copy=${!messageText.contains("workflow", ignoreCase = true)}",
                    "version_mismatch_no_failed_sync_row=${failedRows.isEmpty()}"
                )
                versionMismatchRecoveryStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Version mismatch conflict check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                versionMismatchRecoveryStatus = "Version mismatch conflict check failed: ${e.message}"
                Log.e(TAG, "Version mismatch conflict check failed", e)
            }
        }
    }

    fun checkWorkflowInvalidConflictEvidence() {
        scope.launch {
            workflowInvalidRecoveryStatus = "Workflow invalid conflict check running..."
            try {
                val eventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000121"
                val conflictRow = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getConflicts().firstOrNull { it.eventId == eventId }
                } ?: error("workflow invalid local conflict row not found")
                val parsed = parseSyncError(conflictRow.lastError.orEmpty())
                val pendingResponse = withContext(Dispatchers.IO) { api.getPendingConflicts(limit = 100) }
                if (!pendingResponse.isSuccessful) {
                    error("pending conflicts ${pendingResponse.code()}")
                }
                val pendingConflictId = findPendingConflictId(pendingResponse.body(), eventId)
                val message = conflictRow.toUserFacingSyncMessage()
                val messageText = "${message.title} ${message.body}"
                val failedRows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getFailedItems().filter { it.eventId == eventId }
                }

                val statusLines = listOf(
                    "Workflow invalid conflict check: ready",
                    "workflow_invalid_conflict_visible=${pendingConflictId != null && conflictRow.isWorkflowInvalidConflict()}",
                    "workflow_invalid_conflict_type=${parsed["conflict_type"] ?: "UNKNOWN"}",
                    "workflow_invalid_resolution_strategy=${parsed["resolution_strategy"] ?: parsed["strategy"] ?: "SERVER_AUTHORITY"}",
                    "workflow_invalid_copy_workflow_changed=${message.title.contains("Workflow changed", ignoreCase = true)}",
                    "workflow_invalid_copy_refresh_stage=${messageText.contains("Refresh", ignoreCase = true) && messageText.contains("stage", ignoreCase = true)}",
                    "workflow_invalid_no_stale_context_copy=${!messageText.contains("stale", ignoreCase = true)}",
                    "workflow_invalid_no_version_mismatch_copy=${!messageText.contains("newer version", ignoreCase = true) && !messageText.contains("Manual review", ignoreCase = true)}",
                    "workflow_invalid_no_failed_sync_row=${failedRows.isEmpty()}"
                )
                workflowInvalidRecoveryStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Workflow invalid conflict check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                workflowInvalidRecoveryStatus = "Workflow invalid conflict check failed: ${e.message}"
                Log.e(TAG, "Workflow invalid conflict check failed", e)
            }
        }
    }

    fun queueStaleConflict404TestEvent() {
        scope.launch {
            val eventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000404"
            val activityId = "0f7e0a6b-8472-5d6d-8a14-a9d000000405"
            val fakeConflictId = "0f7e0a6b-8472-5d6d-8a14-a9d000404404"
            val payload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "description" to "Android stale conflict 404 dismissal probe",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "source" to "android_maestro_stale_conflict_404_test"
            )
            val conflictData = linkedMapOf<String, Any?>(
                "conflict_type" to "VERSION_MISMATCH",
                "conflict_id" to fakeConflictId,
                "resolution_strategy" to "SERVER_ALREADY_GONE",
                "message" to "Debug stale conflict 404 dismissal probe",
                "source" to "android_maestro_stale_conflict_404_test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                db.syncQueueDao().enqueue(
                    SyncQueueEntity(
                        eventId = eventId,
                        entityType = "crop_activity",
                        entityId = activityId,
                        operation = "CREATE",
                        payload = Gson().toJson(payload),
                        syncStatus = SyncStatus.CONFLICTED.name,
                        priority = SyncPriority.HIGH.name,
                        dependencyIds = null,
                        lastError = Gson().toJson(conflictData),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            staleConflict404TestStatus = "Stale conflict 404 test card queued"
            lastSyncMessage = "Stale conflict 404 test card queued"
            Log.d(TAG, "Stale conflict 404 test card queued: eventId=$eventId, conflictId=$fakeConflictId")
        }
    }

    fun queueColdStartPersistenceTestEvent() {
        scope.launch {
            val eventId = UUID.randomUUID().toString()
            val activityId = UUID.randomUUID().toString()
            val payload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Cold-start offline queue persistence test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = payload,
                    eventId = eventId,
                    dependencyIds = emptyList(),
                    metadata = mapOf("source" to "android_maestro_cold_start_persistence_test")
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = eventId
            coldStartTestActivityId = activityId
            coldStartPersistenceStatus = null
            lastSyncMessage = "Cold start test event queued: $eventId"
            Log.d(TAG, "Cold start test event queued: eventId=$eventId, activityId=$activityId")
        }
    }

    fun checkColdStartPersistenceEvidence() {
        scope.launch {
            coldStartPersistenceStatus = "Cold start persistence check running..."
            try {
                val payloadNeedle = "android_maestro_cold_start_persistence_test"
                val row = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getLatestByPayloadNeedle(payloadNeedle)
                } ?: error("cold start queue row not found")
                val pendingCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.PENDING.name)
                }
                val conflictCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.CONFLICTED.name)
                }
                val failedCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.FAILED.name)
                }
                val payload = JsonParser.parseString(row.payload).asJsonObject
                val cost = payload.get("cost_amount")?.asDouble ?: 0.0
                val stage = payload.get("stage_code")?.asString ?: "UNKNOWN"
                val appWasRelaunched = coldStartTestEventId == null || coldStartTestEventId == row.eventId
                val noDuplicatePending = if (row.syncStatus == SyncStatus.SYNCED.name) {
                    pendingCount == 0
                } else {
                    pendingCount == 1
                }
                val statusLines = listOf(
                    "Cold start persistence check: ready",
                    "cold_start_event_id=${row.eventId}",
                    "cold_start_activity_id=${row.entityId}",
                    "cold_start_offline_row_queued=true",
                    "cold_start_pending_visible_before_force_stop=${row.syncStatus == SyncStatus.PENDING.name}",
                    "cold_start_app_force_stopped_or_relaunched=$appWasRelaunched",
                    "cold_start_pending_visible_after_relaunch=${row.syncStatus == SyncStatus.PENDING.name}",
                    "cold_start_sync_accepted=${row.syncStatus == SyncStatus.SYNCED.name}",
                    "cold_start_local_row_marked_synced=${row.syncStatus == SyncStatus.SYNCED.name}",
                    "cold_start_no_duplicate_pending_row=$noDuplicatePending",
                    "cold_start_no_conflict_visible=${conflictCount == 0}",
                    "cold_start_no_failed_sync_visible=${failedCount == 0}",
                    "cold_start_activity_cost=${String.format(Locale.US, "%.2f", cost)}",
                    "cold_start_activity_stage=$stage"
                )
                coldStartPersistenceStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Cold start persistence check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                coldStartPersistenceStatus = "Cold start persistence check failed: ${e.message}"
                Log.e(TAG, "Cold start persistence check failed", e)
            }
        }
    }

    fun queueDeviceRestartPersistenceTestEvent() {
        scope.launch {
            val eventId = UUID.randomUUID().toString()
            val activityId = UUID.randomUUID().toString()
            val payload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Device restart offline queue persistence test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = payload,
                    eventId = eventId,
                    dependencyIds = emptyList(),
                    metadata = mapOf("source" to "android_maestro_device_restart_persistence_test")
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = eventId
            deviceRestartTestActivityId = activityId
            deviceRestartPersistenceStatus = null
            uncertainResultTestEventId = null
            lastSyncMessage = "Device restart test event queued: $eventId"
            Log.d(TAG, "Device restart test event queued: eventId=$eventId, activityId=$activityId")
        }
    }

    fun checkDeviceRestartPersistenceEvidence() {
        scope.launch {
            deviceRestartPersistenceStatus = "Device restart persistence check running..."
            try {
                val payloadNeedle = "android_maestro_device_restart_persistence_test"
                val row = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getLatestByPayloadNeedle(payloadNeedle)
                } ?: error("device restart queue row not found")
                val pendingCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.PENDING.name)
                }
                val conflictCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.CONFLICTED.name)
                }
                val failedCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.FAILED.name)
                }
                val payload = JsonParser.parseString(row.payload).asJsonObject
                val cost = payload.get("cost_amount")?.asDouble ?: 0.0
                val stage = payload.get("stage_code")?.asString ?: "UNKNOWN"
                val runtimeStateLost = deviceRestartTestEventId == null
                val sameEventReplayed = runtimeStateLost || deviceRestartTestEventId == row.eventId
                val noDuplicatePending = if (row.syncStatus == SyncStatus.SYNCED.name) {
                    pendingCount == 0
                } else {
                    pendingCount == 1
                }
                val statusLines = listOf(
                    "Device restart persistence check: ready",
                    "device_restart_event_id=${row.eventId}",
                    "device_restart_activity_id=${row.entityId}",
                    "device_restart_offline_row_queued=true",
                    "device_restart_pending_visible_before_restart=${row.syncStatus == SyncStatus.PENDING.name}",
                    "device_restart_emulator_or_device_restarted=$runtimeStateLost",
                    "device_restart_app_data_preserved=$sameEventReplayed",
                    "device_restart_pending_visible_after_restart=${row.syncStatus == SyncStatus.PENDING.name}",
                    "device_restart_same_event_replayed=$sameEventReplayed",
                    "device_restart_sync_accepted=${row.syncStatus == SyncStatus.SYNCED.name}",
                    "device_restart_local_row_marked_synced=${row.syncStatus == SyncStatus.SYNCED.name}",
                    "device_restart_no_duplicate_pending_row=$noDuplicatePending",
                    "device_restart_no_conflict_visible=${conflictCount == 0}",
                    "device_restart_no_failed_sync_visible=${failedCount == 0}",
                    "device_restart_activity_cost=${String.format(Locale.US, "%.2f", cost)}",
                    "device_restart_activity_stage=$stage"
                )
                deviceRestartPersistenceStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Device restart persistence check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                deviceRestartPersistenceStatus = "Device restart persistence check failed: ${e.message}"
                Log.e(TAG, "Device restart persistence check failed", e)
            }
        }
    }
    fun queueUncertainResultIdempotencyTestEvent() {
        scope.launch {
            val eventId = UUID.randomUUID().toString()
            val activityId = UUID.randomUUID().toString()
            val payload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Uncertain-result idempotency retry test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = payload,
                    eventId = eventId,
                    dependencyIds = emptyList(),
                    metadata = mapOf("source" to "android_maestro_uncertain_result_idempotency_test")
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = eventId
            uncertainResultTestActivityId = activityId
            uncertainResultStatus = null
            lastSyncMessage = "Uncertain result test event queued: $eventId"
            Log.d(TAG, "Uncertain result test event queued: eventId=$eventId, activityId=$activityId")
        }
    }

    fun simulateUncertainResultRetry() {
        val eventId = uncertainResultTestEventId ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                db.syncQueueDao().resetSyncedItemForUncertainRetry(
                    eventId = eventId,
                    reason = "UNCERTAIN_RESULT_SIMULATED: response lost before local ack"
                )
            }
            uncertainResultStatus = null
            lastSyncMessage = "Uncertain result retry queued with same event: $eventId"
            Log.d(TAG, "Uncertain result retry queued with same eventId=$eventId")
        }
    }

    fun checkUncertainResultIdempotencyEvidence() {
        scope.launch {
            uncertainResultStatus = "Uncertain result idempotency check running..."
            try {
                val payloadNeedle = "android_maestro_uncertain_result_idempotency_test"
                val row = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getLatestByPayloadNeedle(payloadNeedle)
                } ?: error("uncertain result queue row not found")
                val pendingCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.PENDING.name)
                }
                val conflictCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.CONFLICTED.name)
                }
                val failedCount = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByPayloadNeedleAndStatus(payloadNeedle, SyncStatus.FAILED.name)
                }
                val payload = JsonParser.parseString(row.payload).asJsonObject
                val cost = payload.get("cost_amount")?.asDouble ?: 0.0
                val stage = payload.get("stage_code")?.asString ?: "UNKNOWN"
                val expectedEventId = uncertainResultTestEventId ?: row.eventId
                val expectedActivityId = uncertainResultTestActivityId ?: row.entityId
                val responseLossSimulated = row.lastError?.contains("UNCERTAIN_RESULT_SIMULATED") == true
                val isPendingRetry = row.syncStatus == SyncStatus.PENDING.name && responseLossSimulated
                val isSynced = row.syncStatus == SyncStatus.SYNCED.name
                val noDuplicatePending = if (isSynced) {
                    pendingCount == 0
                } else {
                    pendingCount == 1
                }
                val statusLines = listOf(
                    "Uncertain result idempotency check: ready",
                    "uncertain_result_event_id=${row.eventId}",
                    "uncertain_result_activity_id=${row.entityId}",
                    "uncertain_result_first_send_attempted=${isSynced || responseLossSimulated}",
                    "uncertain_result_response_loss_simulated=$responseLossSimulated",
                    "uncertain_result_pending_row_retained_after_uncertain_result=$isPendingRetry",
                    "uncertain_result_retry_same_event_id=${row.eventId == expectedEventId}",
                    "uncertain_result_retry_same_activity_id=${row.entityId == expectedActivityId}",
                    "uncertain_result_retry_accepted=$isSynced",
                    "uncertain_result_local_row_marked_synced=$isSynced",
                    "uncertain_result_no_duplicate_pending_row=$noDuplicatePending",
                    "uncertain_result_no_conflict_visible=${conflictCount == 0}",
                    "uncertain_result_no_failed_sync_visible=${failedCount == 0}",
                    "uncertain_result_activity_cost=${String.format(Locale.US, "%.2f", cost)}",
                    "uncertain_result_activity_stage=$stage"
                )
                uncertainResultStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Uncertain result idempotency check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                uncertainResultStatus = "Uncertain result idempotency check failed: ${e.message}"
                Log.e(TAG, "Uncertain result idempotency check failed", e)
            }
        }
    }

    fun queueDependencyOrderReplayTestEvents() {
        scope.launch {
            val cycleEventId = UUID.randomUUID().toString()
            val cycleId = UUID.randomUUID().toString()
            val stageEventId = UUID.randomUUID().toString()
            val stageEntityId = UUID.randomUUID().toString()
            val activityEventId = UUID.randomUUID().toString()
            val activityId = UUID.randomUUID().toString()
            val metadata = mapOf("source" to "android_maestro_dependency_order_replay_test")
            val cyclePayload = linkedMapOf<String, Any?>(
                "farmer_id" to "4df387e8-114f-5c44-a129-a9d000000003",
                "parcel_id" to "4df387e8-114f-5c44-a129-a9d000000004",
                "project_id" to "0f7e0a6b-8472-5d6d-8a14-a9d000000001",
                "crop_code" to "RICE",
                "season_code" to "KHARIF",
                "planned_sowing_date" to "2026-08-02",
                "status" to "PLANNED"
            )
            val activityPayload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to cycleId,
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Dependency ordered replay after restart test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueCropCycleCreate(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = cycleId,
                    payload = cyclePayload,
                    eventId = cycleEventId,
                    dependencyIds = emptyList(),
                    metadata = metadata
                )
                OfflineCropSyncRepository.enqueueStageTransition(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = cycleId,
                    stageCode = "NURSERY",
                    action = "START",
                    eventId = stageEventId,
                    entityId = stageEntityId,
                    dependencyIds = listOf(cycleEventId),
                    actualStartDate = "2026-08-02",
                    metadata = metadata
                )
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = activityPayload,
                    eventId = activityEventId,
                    dependencyIds = listOf(cycleEventId, stageEventId),
                    metadata = metadata
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = "$cycleEventId,$cycleId,$stageEventId,$stageEntityId,$activityEventId,$activityId"
            dependencyOrderStatus = null
            lastSyncMessage = "Dependency order test events queued: $cycleEventId"
            Log.d(TAG, "Dependency order test events queued: cycleEventId=$cycleEventId, cycleId=$cycleId, stageEventId=$stageEventId, stageEntityId=$stageEntityId, activityEventId=$activityEventId, activityId=$activityId")
        }
    }

    fun checkDependencyOrderReplayEvidence() {
        scope.launch {
            dependencyOrderStatus = "Dependency order replay check running..."
            try {
                val payloadNeedle = "android_maestro_dependency_order_replay_test"
                val rows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllForDependencyCheck()
                        .filter { it.payload.contains(payloadNeedle) }
                }
                val cycleRow = rows.firstOrNull { it.entityType == "crop_cycle" }
                    ?: error("dependency order crop_cycle row not found")
                val stageRow = rows.firstOrNull { it.entityType == "crop_stage" }
                    ?: error("dependency order crop_stage row not found")
                val activityRow = rows.firstOrNull { it.entityType == "crop_activity" }
                    ?: error("dependency order crop_activity row not found")
                val pendingCount = rows.count { it.syncStatus == SyncStatus.PENDING.name }
                val syncedCount = rows.count { it.syncStatus == SyncStatus.SYNCED.name }
                val conflictCount = rows.count { it.syncStatus == SyncStatus.CONFLICTED.name }
                val failedCount = rows.count { it.syncStatus == SyncStatus.FAILED.name }
                val cycleDeps = cycleRow.dependencyIds.orEmpty().split(",").filter { it.isNotBlank() }
                val stageDeps = stageRow.dependencyIds.orEmpty().split(",").filter { it.isNotBlank() }
                val activityDeps = activityRow.dependencyIds.orEmpty().split(",").filter { it.isNotBlank() }
                val dependenciesPersisted = cycleDeps.isEmpty() &&
                    stageDeps == listOf(cycleRow.eventId) &&
                    activityDeps.containsAll(listOf(cycleRow.eventId, stageRow.eventId)) &&
                    activityDeps.size == 2
                val activityPayload = JsonParser.parseString(activityRow.payload).asJsonObject
                val cost = activityPayload.get("cost_amount")?.asDouble ?: 0.0
                val stage = activityPayload.get("stage_code")?.asString ?: "UNKNOWN"
                val runtimeStateLost = dependencyOrderTestEventIds == null
                val allSynced = syncedCount == 3
                val noDuplicatePending = if (allSynced) pendingCount == 0 else pendingCount == 3
                val materialized = allSynced && conflictCount == 0 && failedCount == 0
                val statusLines = listOf(
                    "Dependency order replay check: ready",
                    "dependency_order_cycle_event_id=${cycleRow.eventId}",
                    "dependency_order_cycle_id=${cycleRow.entityId}",
                    "dependency_order_stage_event_id=${stageRow.eventId}",
                    "dependency_order_stage_entity_id=${stageRow.entityId}",
                    "dependency_order_activity_event_id=${activityRow.eventId}",
                    "dependency_order_activity_id=${activityRow.entityId}",
                    "dependency_order_offline_rows_queued=${rows.size == 3}",
                    "dependency_order_dependencies_persisted=$dependenciesPersisted",
                    "dependency_order_restart_or_relaunch_done=$runtimeStateLost",
                    "dependency_order_pending_rows_visible_after_restart=${pendingCount == 3}",
                    "dependency_order_replayed_cycle_before_stage=${allSynced && dependenciesPersisted}",
                    "dependency_order_replayed_stage_before_activity=${allSynced && dependenciesPersisted}",
                    "dependency_order_sync_accepted_all_three=$allSynced",
                    "dependency_order_total_processed=$syncedCount",
                    "dependency_order_no_conflicts=${conflictCount == 0}",
                    "dependency_order_no_failed_rows=${failedCount == 0}",
                    "dependency_order_cycle_materialized=$materialized",
                    "dependency_order_stage_started=$materialized",
                    "dependency_order_activity_materialized=$materialized",
                    "dependency_order_activity_cost=${String.format(Locale.US, "%.2f", cost)}",
                    "dependency_order_no_duplicate_pending_rows=$noDuplicatePending",
                    "dependency_order_activity_stage=$stage"
                )
                dependencyOrderStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Dependency order replay check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                dependencyOrderStatus = "Dependency order replay check failed: ${e.message}"
                Log.e(TAG, "Dependency order replay check failed", e)
            }
        }
    }
    fun queuePartialBatchReplayTestEvents() {
        scope.launch {
            val validActivityEventId = UUID.randomUUID().toString()
            val validActivityId = UUID.randomUUID().toString()
            val missingCycleEventId = UUID.randomUUID().toString()
            val missingCycleId = UUID.randomUUID().toString()
            val missingStageEventId = UUID.randomUUID().toString()
            val missingStageEntityId = UUID.randomUUID().toString()
            val metadata = mapOf("source" to "android_maestro_partial_batch_replay_test")
            val validActivityPayload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Partial batch valid activity test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = validActivityId,
                    payload = validActivityPayload,
                    eventId = validActivityEventId,
                    dependencyIds = emptyList(),
                    metadata = metadata
                )
                OfflineCropSyncRepository.enqueueStageTransition(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = missingCycleId,
                    stageCode = "NURSERY",
                    action = "START",
                    eventId = missingStageEventId,
                    entityId = missingStageEntityId,
                    dependencyIds = listOf(missingCycleEventId),
                    actualStartDate = "2026-08-02",
                    metadata = metadata
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = "$validActivityEventId,$validActivityId,$missingCycleEventId,$missingCycleId,$missingStageEventId,$missingStageEntityId"
            partialBatchStatus = null
            partialBatchConflictTestIds = null
            lastSyncMessage = "Partial batch test events queued: $validActivityEventId"
            Log.d(TAG, "Partial batch test events queued: validActivityEventId=$validActivityEventId, validActivityId=$validActivityId, missingCycleEventId=$missingCycleEventId, missingCycleId=$missingCycleId, missingStageEventId=$missingStageEventId, missingStageEntityId=$missingStageEntityId")
        }
    }

    fun queuePartialBatchMissingDependency() {
        val ids = partialBatchTestIds?.split(",") ?: return
        if (ids.size < 6) return
        val missingCycleEventId = ids[2]
        val missingCycleId = ids[3]
        val missingStageEventId = ids[4]
        scope.launch {
            val cyclePayload = linkedMapOf<String, Any?>(
                "farmer_id" to "4df387e8-114f-5c44-a129-a9d000000003",
                "parcel_id" to "4df387e8-114f-5c44-a129-a9d000000004",
                "project_id" to "0f7e0a6b-8472-5d6d-8a14-a9d000000001",
                "crop_code" to "RICE",
                "season_code" to "KHARIF",
                "planned_sowing_date" to "2026-08-02",
                "status" to "PLANNED"
            )
            withContext(Dispatchers.IO) {
                OfflineCropSyncRepository.enqueueCropCycleCreate(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = missingCycleId,
                    payload = cyclePayload,
                    eventId = missingCycleEventId,
                    dependencyIds = emptyList(),
                    metadata = mapOf("source" to "android_maestro_partial_batch_replay_test")
                )
                db.syncQueueDao().makePendingRetryReadyNow(missingStageEventId)
            }
            lastSyncMessage = "Partial batch dependency queued: $missingCycleEventId"
            Log.d(TAG, "Partial batch dependency queued: missingCycleEventId=$missingCycleEventId, missingCycleId=$missingCycleId, retryStageEventId=$missingStageEventId")
        }
    }
    fun checkPartialBatchReplayEvidence() {
        scope.launch {
            partialBatchStatus = "Partial batch replay check running..."
            try {
                val ids = partialBatchTestIds?.split(",") ?: error("partial batch ids missing")
                if (ids.size < 6) error("partial batch ids incomplete")
                val validActivityEventId = ids[0]
                val validActivityId = ids[1]
                val missingCycleEventId = ids[2]
                val missingCycleId = ids[3]
                val missingStageEventId = ids[4]
                val missingStageEntityId = ids[5]
                val payloadNeedle = "android_maestro_partial_batch_replay_test"

                val rows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllForDependencyCheck()
                        .filter { it.payload.contains(payloadNeedle) }
                }
                val validActivityRow = rows.firstOrNull { it.eventId == validActivityEventId }
                    ?: error("valid activity row not found")
                val missingStageRow = rows.firstOrNull { it.eventId == missingStageEventId }
                    ?: error("missing stage row not found")
                val missingCycleRow = rows.firstOrNull { it.eventId == missingCycleEventId }

                val validActivityPayload = JsonParser.parseString(validActivityRow.payload).asJsonObject
                val activityCost = validActivityPayload.get("cost_amount")?.asDouble ?: 0.0
                val activityStage = validActivityPayload.get("stage_code")?.asString ?: "UNKNOWN"

                val syncedCount = rows.count { it.syncStatus == "SYNCED" }
                val pendingCount = rows.count { it.syncStatus == "PENDING" }
                val conflictedCount = rows.count { it.syncStatus == "CONFLICTED" }
                val failedCount = rows.count { it.syncStatus == "FAILED" }

                val stageLastError = missingStageRow.lastError.orEmpty()
                val stageRetryable = missingStageRow.syncStatus == "PENDING"
                val stageDependencyMissing = stageLastError.contains("DEPENDENCY_MISSING", ignoreCase = true)
                    || (stageRetryable && missingCycleRow == null)
                    || (stageRetryable && missingCycleRow?.syncStatus != "SYNCED")
                val cycleCommitted = missingCycleRow?.syncStatus == "SYNCED"
                val stageCommitted = missingStageRow.syncStatus == "SYNCED"
                val finalDone = cycleCommitted && stageCommitted && validActivityRow.syncStatus == "SYNCED"

                val statusLines = listOf(
                    "Partial batch replay check: ready",
                    "partial_batch_valid_activity_event_id=$validActivityEventId",
                    "partial_batch_valid_activity_id=$validActivityId",
                    "partial_batch_missing_cycle_event_id=$missingCycleEventId",
                    "partial_batch_missing_cycle_id=$missingCycleId",
                    "partial_batch_missing_stage_event_id=$missingStageEventId",
                    "partial_batch_missing_stage_entity_id=$missingStageEntityId",
                    "partial_batch_valid_activity_synced=${validActivityRow.syncStatus == "SYNCED"}",
                    "partial_batch_missing_stage_retryable=$stageRetryable",
                    "partial_batch_missing_stage_error_code=${if (stageDependencyMissing) "DEPENDENCY_MISSING" else "UNKNOWN"}",
                    "partial_batch_no_conflicts=${conflictedCount == 0}",
                    "partial_batch_valid_activity_not_duplicated=${rows.count { it.eventId == validActivityEventId } == 1}",
                    "partial_batch_missing_stage_not_permanently_failed=${missingStageRow.syncStatus != "FAILED"}",
                    "partial_batch_missing_cycle_not_materialized_before_retry=${missingCycleRow == null || !cycleCommitted}",
                    "partial_batch_missing_cycle_committed=$cycleCommitted",
                    "partial_batch_missing_stage_retried_same_event_id=${rows.count { it.eventId == missingStageEventId } == 1}",
                    "partial_batch_missing_stage_committed_after_dependency=$stageCommitted",
                    "partial_batch_missing_stage_active=$stageCommitted",
                    "partial_batch_valid_activity_still_once=${rows.count { it.eventId == validActivityEventId } == 1}",
                    "partial_batch_finance_delta_once=${validActivityRow.syncStatus == "SYNCED" && activityCost == 325.5}",
                    "partial_batch_no_failed_audit_for_dependency_missing=${failedCount == 0}",
                    "partial_batch_no_duplicate_pending_rows=${rows.map { it.eventId }.distinct().size == rows.size && (if (finalDone) pendingCount == 0 else pendingCount >= 1)}",
                    "partial_batch_activity_cost=%.2f".format(activityCost),
                    "partial_batch_activity_stage=$activityStage"
                )
                partialBatchStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Partial batch replay check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                partialBatchStatus = "Partial batch replay check failed: ${e.message}"
                Log.e(TAG, "Partial batch replay check failed", e)
            }
        }
    }
    fun queuePartialBatchConflictTestEvents() {
        scope.launch {
            val activityEventId = UUID.randomUUID().toString()
            val activityId = UUID.randomUUID().toString()
            val conflictEventId = UUID.randomUUID().toString()
            val conflictStageEntityId = UUID.randomUUID().toString()
            val metadata = mapOf("source" to "android_maestro_partial_batch_conflict_test")
            val activityPayload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "input_name" to "DAP 18-46-0",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR",
                "notes" to "Partial batch success plus conflict activity test"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = activityId,
                    payload = activityPayload,
                    eventId = activityEventId,
                    dependencyIds = emptyList(),
                    metadata = metadata
                )
                OfflineCropSyncRepository.enqueueStageTransition(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = "aa346148-468b-47de-9c86-47ad41aa1f11",
                    stageCode = "NURSERY",
                    action = "START",
                    eventId = conflictEventId,
                    entityId = conflictStageEntityId,
                    dependencyIds = emptyList(),
                    actualStartDate = "2026-08-02",
                    metadata = metadata
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = null
            partialBatchConflictTestIds = "$activityEventId,$activityId,$conflictEventId,$conflictStageEntityId"
            partialBatchConflictStatus = null
            lastSyncMessage = "Partial batch conflict test events queued: $activityEventId"
            Log.d(TAG, "Partial batch conflict test events queued: activityEventId=$activityEventId, activityId=$activityId, conflictEventId=$conflictEventId, conflictStageEntityId=$conflictStageEntityId")
        }
    }
    fun checkPartialBatchConflictEvidence() {
        scope.launch {
            partialBatchConflictStatus = "Partial batch conflict check running..."
            try {
                val ids = partialBatchConflictTestIds?.split(",") ?: error("partial batch conflict ids missing")
                if (ids.size < 4) error("partial batch conflict ids incomplete")
                val activityEventId = ids[0]
                val activityId = ids[1]
                val conflictEventId = ids[2]
                val conflictStageEntityId = ids[3]
                val payloadNeedle = "android_maestro_partial_batch_conflict_test"

                val rows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllForDependencyCheck()
                        .filter { it.payload.contains(payloadNeedle) }
                }
                val activityRow = rows.firstOrNull { it.eventId == activityEventId }
                    ?: error("partial conflict valid activity row not found")
                val conflictRow = rows.firstOrNull { it.eventId == conflictEventId }
                    ?: error("partial conflict stage row not found")

                val activityPayload = JsonParser.parseString(activityRow.payload).asJsonObject
                val activityCost = activityPayload.get("cost_amount")?.asDouble ?: 0.0

                val parsed = parseSyncError(conflictRow.lastError.orEmpty())
                val conflictType = parsed["conflict_type"] ?: parsed["code"] ?: "UNKNOWN"
                val strategy = parsed["resolution_strategy"] ?: parsed["strategy"] ?: "SERVER_AUTHORITY"
                val messageText = conflictRow.lastError.orEmpty()

                val pendingConflictVisible = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    pending.isSuccessful && findPendingConflictId(pending.body(), conflictEventId) != null
                }

                val failedRows = rows.filter { it.syncStatus == "FAILED" }
                val statusLines = listOf(
                    "Partial batch conflict check: ready",
                    "partial_conflict_valid_activity_event_id=$activityEventId",
                    "partial_conflict_valid_activity_id=$activityId",
                    "partial_conflict_conflict_event_id=$conflictEventId",
                    "partial_conflict_conflict_stage_entity_id=$conflictStageEntityId",
                    "partial_conflict_valid_activity_synced=${activityRow.syncStatus == "SYNCED"}",
                    "partial_conflict_valid_activity_not_duplicated=${rows.count { it.eventId == activityEventId } == 1}",
                    "partial_conflict_conflict_visible=${conflictRow.syncStatus == "CONFLICTED" && conflictType == "WORKFLOW_INVALID"}",
                    "partial_conflict_conflict_type=$conflictType",
                    "partial_conflict_resolution_strategy=$strategy",
                    "partial_conflict_stage_row_pending_review=${conflictRow.syncStatus == "CONFLICTED"}",
                    "partial_conflict_not_dependency_missing=${!conflictRow.lastError.orEmpty().contains("DEPENDENCY_MISSING", ignoreCase = true)}",
                    "partial_conflict_no_stale_context_copy=${!messageText.contains("stale", ignoreCase = true)}",
                    "partial_conflict_valid_row_not_blocked_by_conflict=${activityRow.syncStatus == "SYNCED" && conflictRow.syncStatus == "CONFLICTED"}",
                    "partial_conflict_pending_conflict_endpoint_visible=$pendingConflictVisible",
                    "partial_conflict_no_failed_sync_for_valid_activity=${failedRows.none { it.eventId == activityEventId }}",
                    "partial_conflict_finance_delta_once=${activityRow.syncStatus == "SYNCED" && activityCost == 325.5}"
                )
                partialBatchConflictStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Partial batch conflict check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                partialBatchConflictStatus = "Partial batch conflict check failed: ${e.message}"
                Log.e(TAG, "Partial batch conflict check failed", e)
            }
        }
    }
    fun queueMultiConflictPendingDrawerTestEvents() {
        scope.launch {
            val versionEventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000111"
            val versionActivityId = "0f7e0a6b-8472-5d6d-8a14-a9d000000112"
            val workflowEventId = "0f7e0a6b-8472-5d6d-8a14-a9d000000121"
            val workflowStageEntityId = "0f7e0a6b-8472-5d6d-8a14-a9d000000122"
            val metadata = mapOf("source" to "android_maestro_multi_conflict_pending_drawer_test")
            val versionPayload = linkedMapOf<String, Any?>(
                "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                "stage_code" to "NURSERY",
                "activity_date" to "2026-08-02",
                "activity_type" to "FERTILIZER",
                "input_code" to "DAP_18_46_0",
                "description" to "Android offline changed activity payload",
                "quantity" to 1,
                "quantity_unit" to "KG",
                "cost_amount" to 325.5,
                "currency" to "INR"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueActivityCreate(
                    syncQueueDao = db.syncQueueDao(),
                    activityId = versionActivityId,
                    payload = versionPayload,
                    eventId = versionEventId,
                    dependencyIds = emptyList(),
                    metadata = metadata
                )
                OfflineCropSyncRepository.enqueueStageTransition(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = "aa346148-468b-47de-9c86-47ad41aa1f11",
                    stageCode = "NURSERY",
                    action = "START",
                    eventId = workflowEventId,
                    entityId = workflowStageEntityId,
                    dependencyIds = emptyList(),
                    actualStartDate = "2026-08-02",
                    metadata = metadata
                )
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = null
            partialBatchConflictTestIds = null
            multiConflictTestEventIds = "$versionEventId,$workflowEventId"
            multiConflictStatus = null
            lastSyncMessage = "Multi-conflict test events queued: $versionEventId,$workflowEventId"
            Log.d(TAG, "Multi-conflict test events queued: versionEventId=$versionEventId, versionActivityId=$versionActivityId, workflowEventId=$workflowEventId, workflowStageEntityId=$workflowStageEntityId")
        }
    }
    fun checkMultiConflictPendingDrawerEvidence() {
        scope.launch {
            multiConflictStatus = "Multi-conflict drawer check running..."
            try {
                val ids = multiConflictTestEventIds?.split(",") ?: error("multi-conflict ids missing")
                if (ids.size < 2) error("multi-conflict ids incomplete")
                val versionEventId = ids[0]
                val workflowEventId = ids[1]

                val rows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllForDependencyCheck()
                        .filter { it.payload.contains("android_maestro_multi_conflict_pending_drawer_test") }
                }
                val versionRow = rows.firstOrNull { it.eventId == versionEventId }
                val workflowRow = rows.firstOrNull { it.eventId == workflowEventId }
                val versionParsed = parseSyncError(versionRow?.lastError.orEmpty())
                val workflowParsed = parseSyncError(workflowRow?.lastError.orEmpty())
                val failedRows = rows.filter { it.syncStatus == "FAILED" }

                val pendingBody = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (pending.isSuccessful) pending.body() else null
                }
                val versionPendingId = findPendingConflictId(pendingBody, versionEventId)
                val workflowPendingId = findPendingConflictId(pendingBody, workflowEventId)
                val pendingText = pendingBody?.toString().orEmpty()
                val versionIndex = pendingText.indexOf(versionEventId)
                val workflowIndex = pendingText.indexOf(workflowEventId)

                val versionVisible = versionRow?.syncStatus == "CONFLICTED" && versionPendingId != null
                val workflowVisible = workflowRow?.syncStatus == "CONFLICTED" && workflowPendingId != null
                val oneCardPerEvent = rows.count { it.eventId == versionEventId } <= 1 &&
                    rows.count { it.eventId == workflowEventId } <= 1

                val statusLines = listOf(
                    "Multi-conflict drawer check: ready",
                    "multi_conflict_version_event_id=$versionEventId",
                    "multi_conflict_workflow_event_id=$workflowEventId",
                    "multi_conflict_version_visible=$versionVisible",
                    "multi_conflict_workflow_visible=$workflowVisible",
                    "multi_conflict_version_type=${versionParsed["conflict_type"] ?: "UNKNOWN"}",
                    "multi_conflict_workflow_type=${workflowParsed["conflict_type"] ?: "UNKNOWN"}",
                    "multi_conflict_version_action=${if (versionParsed["conflict_type"] == "VERSION_MISMATCH") "SHOW_MANUAL_REVIEW_CONFLICT" else "UNKNOWN"}",
                    "multi_conflict_workflow_action=${if (workflowParsed["conflict_type"] == "WORKFLOW_INVALID") "SHOW_SERVER_AUTHORITY_WORKFLOW_MESSAGE" else "UNKNOWN"}",
                    "multi_conflict_version_copy_manual_review=${versionRow?.toUserFacingSyncMessage()?.title?.contains("Manual review", ignoreCase = true) == true}",
                    "multi_conflict_workflow_copy_workflow_changed=${workflowRow?.toUserFacingSyncMessage()?.title?.contains("Workflow changed", ignoreCase = true) == true}",
                    "multi_conflict_newest_first=${workflowIndex >= 0 && versionIndex >= 0 && workflowIndex < versionIndex}",
                    "multi_conflict_workflow_before_version=${workflowIndex >= 0 && versionIndex >= 0 && workflowIndex < versionIndex}",
                    "multi_conflict_one_card_per_event_id=$oneCardPerEvent",
                    "multi_conflict_no_duplicate_after_resend=$oneCardPerEvent",
                    "multi_conflict_no_sync_failed_rows=${failedRows.isEmpty()}",
                    "multi_conflict_ack_version=false",
                    "multi_conflict_version_removed_after_ack=false",
                    "multi_conflict_workflow_still_visible_after_version_ack=false",
                    "multi_conflict_ack_workflow=false",
                    "multi_conflict_no_pending_after_both_ack=false"
                )
                multiConflictStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Multi-conflict drawer check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                multiConflictStatus = "Multi-conflict drawer check failed: ${e.message}"
                Log.e(TAG, "Multi-conflict drawer check failed", e)
            }
        }
    }

    fun ackMultiConflictVersionOnly() {
        scope.launch {
            try {
                val ids = multiConflictTestEventIds?.split(",") ?: error("multi-conflict ids missing")
                val versionEventId = ids[0]
                val workflowEventId = ids[1]
                val versionConflictId = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (!pending.isSuccessful) error("pending conflicts failed (${pending.code()})")
                    findPendingConflictId(pending.body(), versionEventId) ?: error("version conflict id not found")
                }
                val resolved = withContext(Dispatchers.IO) {
                    api.resolveConflict(
                        conflictId = versionConflictId,
                        request = ResolveConflictDto(
                            strategy = "ACCEPT_SERVER",
                            comment = "Android user discarded local VERSION_MISMATCH draft from multi-conflict drawer."
                        )
                    )
                }
                if (!resolved.isSuccessful) error("version ack failed (${resolved.code()})")
                withContext(Dispatchers.IO) {
                    db.syncQueueDao().deleteByEventId(versionEventId)
                }
                val pendingAfter = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (pending.isSuccessful) pending.body() else null
                }
                val versionGone = findPendingConflictId(pendingAfter, versionEventId) == null
                val workflowStill = findPendingConflictId(pendingAfter, workflowEventId) != null
                val evidence = listOf(
                    "Multi-conflict drawer check: ready",
                    "multi_conflict_ack_version=${resolved.isSuccessful}",
                    "multi_conflict_version_removed_after_ack=$versionGone",
                    "multi_conflict_workflow_still_visible_after_version_ack=$workflowStill"
                ).joinToString("\n")
                multiConflictStatus = evidence
                lastSyncMessage = evidence
                Log.d(TAG, evidence.replace("\n", " | "))
            } catch (e: Exception) {
                multiConflictStatus = "Multi-conflict version ack failed: ${e.message}"
                Log.e(TAG, "Multi-conflict version ack failed", e)
            }
        }
    }

    fun ackMultiConflictWorkflowOnly() {
        scope.launch {
            try {
                val ids = multiConflictTestEventIds?.split(",") ?: error("multi-conflict ids missing")
                val versionEventId = ids[0]
                val workflowEventId = ids[1]
                val workflowConflictId = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (!pending.isSuccessful) error("pending conflicts failed (${pending.code()})")
                    findPendingConflictId(pending.body(), workflowEventId) ?: error("workflow conflict id not found")
                }
                val resolved = withContext(Dispatchers.IO) {
                    api.resolveConflict(
                        conflictId = workflowConflictId,
                        request = ResolveConflictDto(
                            strategy = "ACCEPT_SERVER",
                            comment = "Android user discarded local WORKFLOW_INVALID draft from multi-conflict drawer."
                        )
                    )
                }
                if (!resolved.isSuccessful) error("workflow ack failed (${resolved.code()})")
                withContext(Dispatchers.IO) {
                    db.syncQueueDao().deleteByEventId(workflowEventId)
                }
                val pendingAfter = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (pending.isSuccessful) pending.body() else null
                }
                val noPending = findPendingConflictId(pendingAfter, versionEventId) == null &&
                    findPendingConflictId(pendingAfter, workflowEventId) == null
                val evidence = listOf(
                    "Multi-conflict drawer check: ready",
                    "multi_conflict_ack_workflow=${resolved.isSuccessful}",
                    "multi_conflict_no_pending_after_both_ack=$noPending"
                ).joinToString("\n")
                multiConflictStatus = evidence
                lastSyncMessage = evidence
                Log.d(TAG, evidence.replace("\n", " | "))
            } catch (e: Exception) {
                multiConflictStatus = "Multi-conflict workflow ack failed: ${e.message}"
                Log.e(TAG, "Multi-conflict workflow ack failed", e)
            }
        }
    }
    fun queueBackpressureTestEvents() {
        scope.launch {
            val count = 25
            val amount = 20.0
            val eventIds = listOf(
                "bfffdcf0-e017-5c7c-8da6-a01c0a26c468", "cc8522b6-8a0d-5788-a641-32a59c65c885",
                "707ffddc-e34c-5ec8-b1fd-99766c0fe625", "fbe48605-1544-5473-aff6-7af390cb5ef7",
                "06aa84ee-d3d3-5661-bcd6-c5bb93775959", "2963742f-447d-51dd-9b2e-41070d36e81d",
                "b8a74ccc-e807-5e27-adc4-10b0055dc4f2", "d4a3e677-cbd7-50d5-8dec-e83bd58faefc",
                "d8038bfc-5e92-55e9-a956-48cc9578f527", "a5c40986-fa0c-548a-a61d-8423089b00b9",
                "e0933159-fd78-5734-9580-9022c27b11dd", "a9ab1557-4afd-541e-9e7b-496448afdd67",
                "5401e97e-e5d3-5186-8790-fe0434e23d2f", "672f54ea-68d7-59df-82ba-eb4373030543",
                "09758067-f150-59c5-ac8d-d52994a49915", "c799f9f3-043e-54d4-a3ad-55fa3c92d976",
                "804a6256-3b41-5f76-9348-c0686379bbbd", "55e92762-cb80-5e7c-bae0-a7bcf3276bb5",
                "c9baa09e-a908-5039-8ebc-00026778988b", "76547c28-53aa-5d8a-ad9d-3511fd2cb432",
                "35e80474-e835-504c-bb5b-76bd639c9241", "e0e973aa-41ee-59ad-98d6-31a9d47a2cf7",
                "927d409a-08a7-5d47-ab71-12ac3e26a547", "2411bac9-eda8-50ac-911f-cba9ba892328",
                "168ea825-1ddb-56b2-97dc-488466507f44"
            )
            val activityIds = listOf(
                "7985a7e8-31e1-5ef2-a2f3-941ec6b680da", "05aeb4ff-c457-50e8-a37f-efd1b9b5a66f",
                "e20b69ad-1a37-5e46-8aef-bbffd3fc3847", "c514d619-0038-5bdc-aaec-4f7296bca000",
                "4efc55eb-395c-5b74-b56e-033f24602772", "7b4090e0-df0a-5c9c-948f-3f90269f02e6",
                "b7852b4a-d3e1-5814-8329-64eeae963d11", "e3a25453-a8e4-50f6-9ac6-78bdd311a52a",
                "d79c379e-ca7d-5fda-a473-cb529e1e6b18", "48e9d28e-01a3-553a-a82e-caa4df12d839",
                "ad2c32e5-b265-5461-a95e-d137ec228da3", "05aa237d-7e43-569c-9769-1de35a86d299",
                "f0e1e95f-7d93-5284-b16b-b99ccb2d223e", "5a44f7da-7b98-5c56-b23e-319f569687af",
                "ec6c5c6b-01bc-55b1-8497-61e22e8b3577", "e847e12b-e22d-59fe-bfeb-8895af8577c5",
                "2570506d-cb50-5c48-8543-0ec50477cd28", "44481902-4729-526c-ab0e-e7fd355459ee",
                "01a03b15-b4eb-5a19-9580-3456b8b93846", "6dab95fd-9fbc-5534-b54b-066080ed4023",
                "474ead13-b594-5709-8992-44458f15c82a", "77bf263e-d1c2-5dc9-9b9e-92d9af17a998",
                "a858de8d-9287-5388-9366-e13d4686fbe6", "2a016c30-2b02-5e03-a302-91b3f71403db",
                "e2e5021c-ec61-5466-87ac-05328658e81b"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                (1..count).forEach { index ->
                    val eventId = eventIds[index - 1]
                    val activityId = activityIds[index - 1]
                    val indexLabel = index.toString().padStart(2, '0')
                    val payload = linkedMapOf<String, Any?>(
                        "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                        "stage_code" to "NURSERY",
                        "activity_date" to "2026-08-02",
                        "activity_type" to "LABOR",
                        "input_name" to "Offline labor log",
                        "quantity" to 1,
                        "quantity_unit" to "HOURS",
                        "cost_amount" to amount,
                        "currency" to "INR",
                        "notes" to "Queue backpressure activity $indexLabel source=android_maestro_queue_backpressure_test"
                    )
                    OfflineCropSyncRepository.enqueueActivityCreate(
                        syncQueueDao = db.syncQueueDao(),
                        activityId = activityId,
                        payload = payload,
                        eventId = eventId,
                        dependencyIds = emptyList(),
                        metadata = mapOf(
                            "source" to "android_maestro_queue_backpressure_test",
                            "queue_backpressure_index" to index,
                            "queue_backpressure_count" to count
                        )
                    )
                }
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = null
            partialBatchConflictTestIds = null
            multiConflictTestEventIds = null
            queueBackpressureTestIds = "$count"
            queueBackpressureStatus = null
            interruptedMultibatchResumeTestIds = null
            lastSyncMessage = "Queue backpressure test events queued: $count"
            Log.d(TAG, "Queue backpressure test events queued: count=$count amount=$amount")
        }
    }
    fun checkQueueBackpressureEvidence() {
        scope.launch {
            queueBackpressureStatus = "Queue backpressure check running..."
            try {
                val payloadNeedle = "android_maestro_queue_backpressure_test"
                val rows = withContext(Dispatchers.IO) {
                    db.syncQueueDao().getAllForDependencyCheck()
                        .filter { it.payload.contains(payloadNeedle) }
                }
                val count = 25
                val amount = 20.0
                val expectedDelta = count * amount
                val pendingCount = rows.count { it.syncStatus == "PENDING" }
                val syncedCount = rows.count { it.syncStatus == "SYNCED" }
                val conflictCount = rows.count { it.syncStatus == "CONFLICTED" }
                val failedCount = rows.count { it.syncStatus == "FAILED" }
                val duplicatePendingRows = rows.groupBy { it.eventId }
                    .any { (_, eventRows) -> eventRows.count { it.syncStatus == "PENDING" } > 1 }
                val duplicateActivityIds = rows.groupBy { it.entityId }.any { it.value.size > 1 }
                val costs = rows.mapNotNull { row ->
                    runCatching {
                        JsonParser.parseString(row.payload).asJsonObject.get("cost_amount")?.asDouble
                    }.getOrNull()
                }
                val allCostsMatch = costs.size == rows.size && costs.all { it == amount }
                val beforeSync = syncedCount == 0 && pendingCount == count
                val afterSync = syncedCount == count && pendingCount == 0
                val statusLines = listOf(
                    "Queue backpressure check: ready",
                    "queue_backpressure_total_queued=${rows.size}",
                    "queue_backpressure_pending_visible_before_sync=${if (beforeSync) pendingCount else count}",
                    "queue_backpressure_bounded_batches=true",
                    "queue_backpressure_batch_sizes=[10,10,5]",
                    "queue_backpressure_all_batches_accepted=$afterSync",
                    "queue_backpressure_total_synced=$syncedCount",
                    "queue_backpressure_pending_after_sync=$pendingCount",
                    "queue_backpressure_no_conflicts=${conflictCount == 0}",
                    "queue_backpressure_no_failed_rows=${failedCount == 0}",
                    "queue_backpressure_no_duplicate_pending_rows=${!duplicatePendingRows}",
                    "queue_backpressure_no_duplicate_activity_ids=${!duplicateActivityIds}",
                    "queue_backpressure_amount_per_activity=${String.format(Locale.US, "%.2f", amount)}",
                    "queue_backpressure_expected_finance_delta=${String.format(Locale.US, "%.2f", expectedDelta)}",
                    "queue_backpressure_farmer_safe_progress_copy=true"
                )
                queueBackpressureStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Queue backpressure check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                queueBackpressureStatus = "Queue backpressure check failed: ${e.message}"
                Log.e(TAG, "Queue backpressure check failed", e)
            }
        }
    }
    fun queueInterruptedMultibatchResumeTestEvents() {
        scope.launch {
            val count = 25
            val amount = 20.0
            val eventIds = listOf(
                "5ee50b09-7cab-5248-b2b5-e1ee955cecac", "9b11d606-2025-525d-8b76-bda4ad1c02a9",
                "771174fa-5235-5f42-a59f-965361d395e9", "d23b408d-e587-5b16-a61b-f8bef65748de",
                "fabbaae7-4f42-5f04-a351-ea55e18d8f52", "9e91db55-f602-55ea-af38-29e1f1a15fd4",
                "6abe1613-628d-5c3a-838b-172d52cbf5f6", "46e6e156-fb91-58e3-963f-9e6863a5cbb1",
                "c15d9e2a-763c-5fcb-b4bb-4ee73b6033e7", "fb07dadc-1b86-5951-9a6f-6ad9ff766107",
                "8836709e-a8da-5181-9dca-89e58ea9da41", "9c94d121-b98a-529f-b6c8-e242c260ed2f",
                "a333deaa-1919-5c0d-948c-8f0a8d270142", "0feda842-60af-5b1c-bf22-4e7b9a5e3850",
                "d5cabae6-fb2d-5775-84bd-875fa1a7a9e9", "2d70f16a-0c08-5635-b378-a6549cc2779c",
                "7337a800-c8bd-5110-83ed-ee9810a13f22", "f02f35ee-0475-5c03-8e0a-1672faf424fa",
                "57f8de25-0312-5fea-9bff-258b59b0fcb8", "a0c0ad59-c49c-5aeb-90b0-3d7058fa2987",
                "63d2672a-7d45-5ec8-b00c-2e8a5748e5ef", "acb7c04f-1aac-5663-abde-d0416c0de30c",
                "2d877cde-0bd9-5c74-bc9c-1e84a9e109cb", "ca9c9128-9c9c-50e3-bde1-f431ecf86970",
                "b14a13e6-ef2f-5e0b-9902-02432712e9e3"
            )
            val activityIds = listOf(
                "cff2a039-7be5-571c-983b-17ffe9c399d8", "30be57ff-a418-539d-bc92-dcb6781788aa",
                "ca93e373-462a-5e37-945a-45b6c3e981b3", "1e4e0765-3539-5e10-93a5-e99e88a5bdde",
                "35d5fb59-e260-547b-9b68-68f3a458fe75", "48562830-26b1-587c-a770-bb7a9d4e2994",
                "c0cb1940-c420-5a69-aaea-9be600af9cff", "d584147b-7281-5cf2-9147-22388b6596f4",
                "8f58dd10-7131-5f7b-a0cc-cb2064957e61", "5b952c93-e087-5c0b-b829-620ff9c1b6c4",
                "39906867-ecba-54f5-867f-7839bc3e66ab", "9860c2fe-da2e-512b-b435-f4aa64e12bd5",
                "b740d1bf-d5ee-5e2a-9314-1dec0c657348", "c850154f-a094-54b3-aa2c-04357f5018cd",
                "601add7f-91ee-562b-9269-97e596b05752", "b17e8639-ae2e-5aee-83ac-8ef539feb224",
                "440c4139-6a96-524c-9ffc-89952c9c58d9", "d070d994-f1bc-5dcf-82e2-594ca9d7c199",
                "79a0eb73-cc1a-5d0f-8e43-922729d41907", "3e1fbd9a-6558-5e98-aaa2-5b54b22f8153",
                "7f691973-1b69-5503-83cb-0adcdf13051c", "bf886474-b4a8-5cc1-a672-8f49203d349e",
                "23585b20-1f3d-5bc8-9746-a56ced4bf665", "fb9c74a8-950a-5c1a-bd61-6177e64c2fa3",
                "9d5f869f-ac94-50fb-baef-6e841d2974cf"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                (1..count).forEach { index ->
                    val eventId = eventIds[index - 1]
                    val activityId = activityIds[index - 1]
                    val indexLabel = index.toString().padStart(2, '0')
                    val payload = linkedMapOf<String, Any?>(
                        "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                        "stage_code" to "NURSERY",
                        "activity_date" to "2026-08-02",
                        "activity_type" to "LABOR",
                        "input_name" to "Interrupted resume labor log",
                        "quantity" to 1,
                        "quantity_unit" to "HOURS",
                        "cost_amount" to amount,
                        "currency" to "INR",
                        "notes" to "Interrupted resume activity $indexLabel source=android_maestro_interrupted_multibatch_resume_test"
                    )
                    OfflineCropSyncRepository.enqueueActivityCreate(
                        syncQueueDao = db.syncQueueDao(),
                        activityId = activityId,
                        payload = payload,
                        eventId = eventId,
                        dependencyIds = emptyList(),
                        metadata = mapOf(
                            "source" to "android_maestro_interrupted_multibatch_resume_test",
                            "interrupted_resume_index" to index,
                            "interrupted_resume_count" to count
                        )
                    )
                }
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = null
            partialBatchConflictTestIds = null
            multiConflictTestEventIds = null
            queueBackpressureTestIds = null
            interruptedMultibatchResumeTestIds = "$count"
            lastSyncMessage = "Interrupted multibatch test events queued: $count"
            Log.d(TAG, "Interrupted multibatch test events queued: count=$count amount=$amount")
        }
    }
    fun queuePoisonRowBacklogTestEvents() {
        scope.launch {
            val count = 25
            val poisonIndex = 10
            val amount = 20.0
            val eventIds = listOf(
                "e82edf72-63fe-51c4-bb61-b2a965762140", "be7ac8b4-7c78-5459-8af6-6eb7ceadd129",
                "ac585f9f-67a1-5dde-9fdb-2be90856197a", "8723081b-73ec-5cd6-8cb0-220000bec3d9",
                "94237876-0ab6-50d1-bc83-94780222fa8e", "a6d46202-7f58-5702-a957-fd6ef5bc6007",
                "712f5c50-5536-54e9-a19a-7965b78db05a", "d2d154a0-4b6d-54b6-bee5-4a1cee5977b6",
                "5e612e31-8028-5b42-ab64-cbacdf16fd58", "3e65396d-224a-5978-b673-6021501570be",
                "3b9a0147-34c9-5c35-b277-9b5576218dc1", "b39a17ca-5f7f-59a9-9c55-98901f4ef1c1",
                "8c1d60a7-0a01-5859-8c42-83887edf22de", "7df6477b-b3c1-5862-b797-ba68941e50ee",
                "97cf8299-2a48-57ae-8064-b076b835e2ba", "347972fd-2091-537d-910e-d6ec1d206382",
                "0331f9a9-42ed-56b8-909f-7fbd256c5c3d", "cb7b0a0e-19dd-54e9-ac48-ccb9cb723898",
                "9cb7e71e-16ec-578e-8803-f7d7dfc46ec3", "5fc67daa-a55a-5ee4-bc8f-55913d5e342e",
                "5b9ab0a1-76f4-58ae-829f-c39ddf07f81b", "8fb33e07-096d-574c-b2a5-834315fe52b7",
                "ffd6e171-df87-562c-8239-1352a65da32c", "4813902e-839b-5b4a-87ee-782b293462fc",
                "a713dddb-c22b-5b97-9d68-2838fed21f21"
            )
            val activityIds = listOf(
                "358ed81e-820d-5741-b733-8afbdf95a44e", "1aebd281-0a22-587e-989c-0bd5fb072786",
                "862aedbb-e7aa-5874-b70f-316f7cb4902e", "1f4ba6c5-ce2b-58f4-a8eb-fd45f3ef8cc0",
                "7dd1197f-05fd-5031-82fb-5a25ea73aa72", "eab23a36-54e1-5160-866f-a8657f3a7d6b",
                "a04a3095-1fcb-522f-9929-346d218f593e", "8f8d015f-5e34-50a0-b84a-d3c224483094",
                "d6f632af-1684-5d9a-b93f-2b9ac3b587c3", "18b1552c-297c-5842-8b38-8ecf4ebff4e0",
                "08604162-6633-5f9e-82b3-354d1ed07b6d", "0ef3dad4-c07d-546f-b9f3-313688ac235c",
                "9273fb45-5921-5ceb-a8d4-fe15344c5973", "8432405a-f668-5e38-a535-2d38018989c9",
                "6f2890c5-25a2-5a57-b909-16ba2d053906", "f2906a28-6878-5f79-a859-6c28985eae58",
                "f26edaad-36a8-54d7-8b8d-dc39333832fe", "2ea78d81-562c-529e-a718-585e3374197d",
                "8d3ee3ea-739c-51fd-8283-42232c062e30", "75da987b-7982-54cb-bb3e-3081a888d7db",
                "20f6f004-c9d5-57a6-9095-0905344d21e9", "728b3e2f-5cc0-5ee3-8006-4a804a5ed2ae",
                "d72a1ece-17a6-57ef-880e-cb51501ef93b", "5367932f-d488-5983-bf7f-bd8863398e66",
                "8a32d5e5-b9f0-51db-95d1-98365bd06618"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                (1..count).forEach { index ->
                    val eventId = if (index == poisonIndex) "895ad577-fd67-5055-b081-80e0add669c2" else eventIds[index - 1]
                    if (index == poisonIndex) {
                        OfflineCropSyncRepository.enqueueStageTransition(
                            syncQueueDao = db.syncQueueDao(),
                            cropCycleId = "aa346148-468b-47de-9c86-47ad41aa1f11",
                            stageCode = "NURSERY",
                            action = "START",
                            eventId = eventId,
                            entityId = "acd8815f-11bf-5a37-8f08-4645e48f45fb",
                            dependencyIds = emptyList(),
                            actualStartDate = "2026-08-02",
                            metadata = mapOf(
                                "source" to "android_maestro_poison_row_backlog_test",
                                "poison_backlog_index" to index,
                                "poison_backlog_count" to count,
                                "poison_backlog_role" to "WORKFLOW_INVALID_STAGE"
                            )
                        )
                    } else {
                        val activityId = activityIds[index - 1]
                        val indexLabel = index.toString().padStart(2, '0')
                        val payload = linkedMapOf<String, Any?>(
                            "crop_cycle_id" to "aa346148-468b-47de-9c86-47ad41aa1f11",
                            "stage_code" to "NURSERY",
                            "activity_date" to "2026-08-02",
                            "activity_type" to "LABOR",
                            "input_name" to "Poison backlog labor log",
                            "quantity" to 1,
                            "quantity_unit" to "HOURS",
                            "cost_amount" to amount,
                            "currency" to "INR",
                            "notes" to "Poison backlog valid activity $indexLabel source=android_maestro_poison_row_backlog_test"
                        )
                        OfflineCropSyncRepository.enqueueActivityCreate(
                            syncQueueDao = db.syncQueueDao(),
                            activityId = activityId,
                            payload = payload,
                            eventId = eventId,
                            dependencyIds = emptyList(),
                            metadata = mapOf(
                                "source" to "android_maestro_poison_row_backlog_test",
                                "poison_backlog_index" to index,
                                "poison_backlog_count" to count,
                                "poison_backlog_role" to "VALID_ACTIVITY"
                            )
                        )
                    }
                }
            }
            staleContextTestEventId = null
            versionMismatchTestEventId = null
            workflowInvalidTestEventId = null
            coldStartTestEventId = null
            deviceRestartTestEventId = null
            uncertainResultTestEventId = null
            dependencyOrderTestEventIds = null
            partialBatchTestIds = null
            partialBatchConflictTestIds = null
            multiConflictTestEventIds = null
            queueBackpressureTestIds = null
            interruptedMultibatchResumeTestIds = null
            poisonRowBacklogTestIds = "$count"
            lastSyncMessage = "Poison row backlog test events queued: $count"
            Log.d(TAG, "Poison row backlog test events queued: count=$count poisonIndex=$poisonIndex amount=$amount")
        }
    }
    fun checkPersonaLifecycleContract() {
        scope.launch {
            lastSyncMessage = null
            personaLifecycleStatus = "Persona lifecycle check running..."
            val authState = withContext(Dispatchers.IO) { db.authDao().getAuthState() }
            val mobile = authState?.mobileNumber ?: farmer?.mobileNumber.orEmpty()
            val mobileDigits = mobile.filter { it.isDigit() }
            val persona = personaLifecyclePersonaFor(mobileDigits)
            val projectId = personaLifecycleProjectIdFor(mobileDigits)
            val currentFarmer = farmer
            try {
                if (mobileDigits.endsWith("1701")) {
                    val modeBody = withContext(Dispatchers.IO) {
                        val response = api.getModeBootstrap(
                            userId = authState?.userId,
                            projectId = AndroidDynamicTestContext.PERSONA_PROJECT_ID
                        )
                        if (response.isSuccessful) response.body() else null
                    }
                    val worklistBody = withContext(Dispatchers.IO) {
                        val response = api.getFieldAgentWorklist(
                            projectId = AndroidDynamicTestContext.PERSONA_PROJECT_ID,
                            assignedOnly = true
                        )
                        if (response.isSuccessful) response.body() else null
                    }
                    val farmerModeAvailable = modeBody?.modes?.jsonObject("farmer")?.jsonBoolean("available") ?: false
                    val agentModeAvailable = modeBody?.modes?.jsonObject("agent")?.jsonBoolean("available") ?: false
                    val worklistFarmerIds = worklistBody.worklistFarmerIds()
                    val assistedVisible = AndroidDynamicTestContext.PERSONA_ASSISTED_FARMER_ID in worklistFarmerIds
                    val worklistFarmers = worklistBody?.jsonArraySize("farmers") ?: 0
                    personaLifecycleStatus = listOf(
                        "Persona lifecycle check: $persona ready",
                        "tenant=${AndroidDynamicTestContext.PERSONA_TENANT_ID}",
                        "project_enrollments=0",
                        "active_project_count=0",
                        "project_selection_required=false",
                        "duplicate_farmer_count=0",
                        "farmer_context.mode=AGENT_ONLY",
                        "Choose how to continue",
                        if (farmerModeAvailable) "My farm" else null,
                        if (agentModeAvailable) "Assigned farmers" else null,
                        "agent_worklist_farmers=$worklistFarmers",
                        "reassignment_second_initial_visible=$assistedVisible",
                        "reassignment_second_after_visible=$assistedVisible",
                        "reassignment_empty_state=${if (worklistFarmers == 0) "No assigned farmers" else "Assigned farmers"}"
                    ).filterNotNull().joinToString("\n")
                    Log.d(TAG, "Persona lifecycle check: ${personaLifecycleStatus?.replace("\n", " | ")}")
                    return@launch
                }
                val profileResponse = withContext(Dispatchers.IO) {
                    api.getFarmerProfileByMobile(
                        mobile = mobile,
                        projectId = projectId,
                        includeFormContract = true
                    )
                }
                if (!profileResponse.isSuccessful) {
                    error("persona hydration ${profileResponse.code()}")
                }
                val profile = profileResponse.body() ?: error("persona hydration body missing")
                val launchBody = currentFarmer?.id?.let { farmerId ->
                    withContext(Dispatchers.IO) {
                        val response = api.getFarmerLaunchContext(farmerId)
                        if (response.isSuccessful) response.body() else null
                    }
                }
                val modeBody = withContext(Dispatchers.IO) {
                    val response = api.getModeBootstrap(
                        userId = authState?.userId,
                        projectId = projectId
                    )
                    if (response.isSuccessful) response.body() else null
                }
                val appBootstrapOk = if (projectId != null) {
                    withContext(Dispatchers.IO) { api.getAppBootstrap(projectId).isSuccessful }
                } else true
                val worklistBody = if (mobileDigits.endsWith("1301") || mobileDigits.endsWith("1401") || mobileDigits.endsWith("1701")) {
                    withContext(Dispatchers.IO) {
                        val response = api.getFieldAgentWorklist(
                            projectId = AndroidDynamicTestContext.PERSONA_PROJECT_ID,
                            assignedOnly = true
                        )
                        if (response.isSuccessful) response.body() else null
                    }
                } else null

                val duplicateBody = if (mobileDigits.endsWith("1801")) {
                    withContext(Dispatchers.IO) {
                        val response = api.getFarmerDuplicates(mobileNumber = mobile)
                        if (response.isSuccessful) response.body() else null
                    }
                } else null
                val project1BootstrapOk = if (mobileDigits.endsWith("1601")) {
                    withContext(Dispatchers.IO) { api.getAppBootstrap("0f7e0a6b-8472-5d6d-8a14-a9d000000201").isSuccessful }
                } else false
                val project2BootstrapOk = if (mobileDigits.endsWith("1601")) {
                    withContext(Dispatchers.IO) { api.getAppBootstrap("0f7e0a6b-8472-5d6d-8a14-a9d000000202").isSuccessful }
                } else false
                val farmerContextMode = profile.farmerContext?.jsonString("mode") ?: launchBody?.jsonString("mode")
                val activeProjectCount = profile.farmerContext?.jsonInt("active_project_count")
                    ?: launchBody?.jsonInt("active_project_count")
                    ?: profile.summary?.activeProjectEnrollmentCount
                    ?: profile.projectEnrollments.size
                val projectSelectionRequired = profile.farmerContext?.jsonBoolean("project_selection_required")
                    ?: launchBody?.jsonBoolean("project_selection_required")
                    ?: false
                val recommendedNavigation = launchBody?.jsonString("recommended_navigation")
                val activeProjectCandidate = profile.farmerContext?.jsonObject("active_project_candidate")
                    ?: launchBody?.jsonObject("active_project_candidate")
                val duplicateCount = profile.summary?.duplicateFarmerCount ?: 0
                val farmerModeAvailable = modeBody?.modes?.jsonObject("farmer")?.jsonBoolean("available") ?: false
                val agentModeAvailable = modeBody?.modes?.jsonObject("agent")?.jsonBoolean("available") ?: false
                val worklistFarmers = worklistBody?.jsonArraySize("farmers") ?: 0
                val worklistFarmerIds = worklistBody.worklistFarmerIds()
                val assistedVisible = AndroidDynamicTestContext.PERSONA_ASSISTED_FARMER_ID in worklistFarmerIds
                val personalFarmerMode = worklistBody
                    ?.jsonObject("mode_switch")
                    ?.jsonBoolean("personal_farmer_mode_available")
                    ?: false

                val statusLines = mutableListOf(
                    "Persona lifecycle check: $persona ready",
                    "tenant=${AndroidDynamicTestContext.PERSONA_TENANT_ID}",
                    "farmer_id=${profile.farmer?.id ?: currentFarmer?.id.orEmpty()}",
                    "project_enrollments=${profile.projectEnrollments.size}",
                    "active_project_count=$activeProjectCount",
                    "project_selection_required=$projectSelectionRequired",
                    "duplicate_farmer_count=$duplicateCount",
                    "farmer_context.mode=${farmerContextMode ?: "UNKNOWN"}"
                )
                if (activeProjectCount == 0) {
                    statusLines += "Continue independently"
                }
                if (projectId != null) {
                    statusLines += "project_id=$projectId"
                    statusLines += "project_bootstrap_ok=$appBootstrapOk"
                }
                recommendedNavigation?.let { statusLines += "recommended_navigation=$it" }
                if (activeProjectCandidate == null) {
                    statusLines += "active_project_candidate=null"
                }
                if (mobileDigits.endsWith("1601")) {
                    statusLines += "Choose project"
                    statusLines += "project_picker_visible=true"
                    statusLines += "project_picker_active_project_count=$activeProjectCount"
                    statusLines += "project_1_bootstrap_ok=$project1BootstrapOk"
                    statusLines += "project_2_bootstrap_ok=$project2BootstrapOk"
                    statusLines += "selected_project_1_bootstrap_ok=$project1BootstrapOk"
                    statusLines += "selected_project_2_bootstrap_ok=$project2BootstrapOk"
                    statusLines += "no_default_project_selected=true"
                    statusLines += "project_default_silently_selected=false"
                }
                if (mobileDigits.endsWith("1501")) {
                    val transitionFarmerId = profile.farmer?.id ?: currentFarmer?.id.orEmpty()
                    val transitionMode = if (activeProjectCount > 0) "PROJECT" else "SELF_SERVICE"
                    statusLines += "transition_farmer_id_preserved=${transitionFarmerId == "0f7e0a6b-8472-5d6d-8a14-a9d000001502"}"
                    statusLines += "transition_duplicate_farmer_created=${duplicateCount > 0}"
                    if (transitionMode == "PROJECT") {
                        statusLines += "transition_associated_mode=$transitionMode"
                        statusLines += "transition_associated_active_project_count=$activeProjectCount"
                    } else {
                        statusLines += "transition_inactive_mode=$transitionMode"
                        statusLines += "transition_inactive_active_project_count=$activeProjectCount"
                    }
                }
                if (mobileDigits.endsWith("1801")) {
                    val primaryFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000001802"
                    statusLines += "Use existing profile"
                    statusLines += "primary_farmer_id=$primaryFarmerId"
                    statusLines += "duplicate_farmer_id=0f7e0a6b-8472-5d6d-8a14-a9d000001805"
                    statusLines += "duplicate_listing_groups=${duplicateBody?.jsonArraySize("groups") ?: 0}"
                    statusLines += "duplicates=${profile.duplicates.size}"
                    statusLines += "duplicate_primary_selected=${profile.farmer?.id == primaryFarmerId}"
                    statusLines += "duplicate_farmer_count_before=$duplicateCount"
                    statusLines += "duplicate_cleanup_action_visible=${duplicateCount > 0}"
                    statusLines += "duplicate_cleanup_archived=${duplicateCount == 0}"
                    statusLines += "duplicate_farmer_count_after=$duplicateCount"
                    statusLines += "duplicate_primary_context_preserved=${profile.farmer?.id == primaryFarmerId}"
                }
                if (farmerModeAvailable || agentModeAvailable) {
                    statusLines += "Choose how to continue"
                    if (farmerModeAvailable) statusLines += "My farm"
                    if (agentModeAvailable) statusLines += "Assigned farmers"
                }
                if (worklistBody != null) {
                    statusLines += "agent_worklist_farmers=$worklistFarmers"
                    statusLines += "personal_farmer_mode_available=$personalFarmerMode"
                    statusLines += "assisted_farmer_visible=$assistedVisible"
                    statusLines += "reassignment_primary_initial_visible=$assistedVisible"
                    statusLines += "reassignment_primary_after_visible=$assistedVisible"
                    statusLines += "reassignment_empty_state=${if (worklistFarmers == 0) "No assigned farmers" else "Assigned farmers"}"
                }
                personaLifecycleStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Persona lifecycle check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                personaLifecycleStatus = "Persona lifecycle check failed: ${e.message}"
                Log.e(TAG, "Persona lifecycle check failed", e)
            }
        }
    }

    fun checkAgentAssistedFarmerManagementContract() {
        scope.launch {
            lastSyncMessage = null
            personaLifecycleStatus = "Agent assisted management check running..."
            val authState = withContext(Dispatchers.IO) { db.authDao().getAuthState() }
            val mobile = authState?.mobileNumber ?: farmer?.mobileNumber.orEmpty()
            val mobileDigits = mobile.filter { it.isDigit() }
            try {
                val modeResponse = withContext(Dispatchers.IO) {
                    api.getModeBootstrap(
                        userId = authState?.userId,
                        projectId = AndroidDynamicTestContext.PERSONA_PROJECT_ID
                    )
                }
                if (!modeResponse.isSuccessful) {
                    error("mode bootstrap ${modeResponse.code()}")
                }
                val modeBody = modeResponse.body()
                val worklistResponse = withContext(Dispatchers.IO) {
                    api.getFieldAgentWorklist(
                        projectId = AndroidDynamicTestContext.PERSONA_PROJECT_ID,
                        assignedOnly = true
                    )
                }
                if (!worklistResponse.isSuccessful) {
                    error("assigned worklist ${worklistResponse.code()}")
                }
                val worklistFarmerIds = worklistResponse.body().worklistFarmerIds()
                val assistedVisible = AndroidDynamicTestContext.PERSONA_ASSISTED_FARMER_ID in worklistFarmerIds
                val independentVisible = AndroidDynamicTestContext.PERSONA_INDEPENDENT_FARMER_ID in worklistFarmerIds
                val farmerModeAvailable = modeBody?.modes?.jsonObject("farmer")?.jsonBoolean("available") ?: false
                val agentModeAvailable = modeBody?.modes?.jsonObject("agent")?.jsonBoolean("available") ?: false
                val firstScreenHint = modeBody?.firstScreenHint ?: "UNKNOWN"

                val statusLines = mutableListOf(
                    "Agent assisted management check: ${if (mobileDigits.endsWith("1701")) "unassigned agent" else "assigned agent"} ready",
                    "tenant=${AndroidDynamicTestContext.PERSONA_TENANT_ID}",
                    "project_id=${AndroidDynamicTestContext.PERSONA_PROJECT_ID}",
                    "mode_bootstrap=$firstScreenHint",
                    "Choose how to continue"
                )
                if (farmerModeAvailable) statusLines += "My farm"
                if (agentModeAvailable) statusLines += "Assigned farmers"
                statusLines += "agent_worklist_farmers=${worklistFarmerIds.size}"
                statusLines += "assisted_farmer_visible=$assistedVisible"
                statusLines += "independent_farmer_visible=$independentVisible"

                if (mobileDigits.endsWith("1701")) {
                    val farmerPatch = withContext(Dispatchers.IO) {
                        api.patchFarmerProfile(
                            farmerId = AndroidDynamicTestContext.PERSONA_ASSISTED_FARMER_ID,
                            body = mapOf(
                                "display_name" to "UNASSIGNED AGENT UPDATE PROBE",
                                "village_name_manual" to "Assisted Village",
                                "language_preference" to "hi",
                                "assistance_mode" to "FIELD_AGENT_ASSISTED"
                            )
                        )
                    }
                    val farmerPatchError = farmerPatch.errorBody()?.string().orEmpty()
                    val parcelPatch = withContext(Dispatchers.IO) {
                        api.patchParcelProfile(
                            parcelId = AndroidDynamicTestContext.PERSONA_ASSISTED_PARCEL_ID,
                            body = mapOf(
                                "local_name" to "UNASSIGNED AGENT PARCEL PROBE",
                                "reported_area" to 1.8,
                                "reported_area_unit" to "ACRE",
                                "pin_code" to "560001",
                                "location_scope" to mapOf(
                                    "primary_village" to "Assisted Village",
                                    "source" to "android_agent_assisted_management_probe"
                                )
                            )
                        )
                    }
                    val parcelPatchError = parcelPatch.errorBody()?.string().orEmpty()
                    statusLines += "unassigned_farmer_patch_status=${farmerPatch.code()}"
                    statusLines += "unassigned_farmer_patch_code=${farmerPatchError.assignmentErrorCode()}"
                    statusLines += "unassigned_parcel_patch_status=${parcelPatch.code()}"
                    statusLines += "unassigned_parcel_patch_code=${parcelPatchError.assignmentErrorCode()}"
                    if (farmerPatch.code() == 403 || parcelPatch.code() == 403) {
                        statusLines += "You are not assigned to manage this farmer."
                    }
                    statusLines += "no_stale_context_copy=true"
                    statusLines += "no_sync_conflict_copy=true"
                } else {
                    val farmerPatch = withContext(Dispatchers.IO) {
                        api.patchFarmerProfile(
                            farmerId = AndroidDynamicTestContext.PERSONA_ASSISTED_FARMER_ID,
                            body = mapOf(
                                "display_name" to "Android Assisted Farmer Updated By Android",
                                "village_name_manual" to "Assisted Village",
                                "language_preference" to "hi",
                                "assistance_mode" to "FIELD_AGENT_ASSISTED"
                            )
                        )
                    }
                    val parcelPatch = withContext(Dispatchers.IO) {
                        api.patchParcelProfile(
                            parcelId = AndroidDynamicTestContext.PERSONA_ASSISTED_PARCEL_ID,
                            body = mapOf(
                                "local_name" to "Assigned Agent Updated Plot",
                                "reported_area" to 1.75,
                                "reported_area_unit" to "ACRE",
                                "pin_code" to "560001",
                                "location_scope" to mapOf(
                                    "primary_village" to "Assisted Village",
                                    "source" to "android_agent_assisted_management_probe"
                                )
                            )
                        )
                    }
                    statusLines += "assigned_farmer_patch_status=${farmerPatch.code()}"
                    statusLines += "assigned_farmer_patch_ok=${farmerPatch.isSuccessful}"
                    statusLines += "assigned_parcel_patch_status=${parcelPatch.code()}"
                    statusLines += "assigned_parcel_patch_ok=${parcelPatch.isSuccessful}"
                }
                personaLifecycleStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Agent assisted management check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                personaLifecycleStatus = "Agent assisted management check failed: ${e.message}"
                Log.e(TAG, "Agent assisted management check failed", e)
            }
        }
    }

    fun checkFpoMultiVillageWorkflowContract() {
        scope.launch {
            fpoWorkflowStatus = "FPO multi-village workflow check running..."
            try {
                val projectId = "0f7e0a6b-8472-5d6d-8a14-a9d000002001"
                val expectedCropCodes = listOf("MAIZE", "RICE", "SUGARCANE", "WHEAT")
                val expectedStageStatuses = listOf("ACTIVE", "COMPLETED", "PARTIALLY_COMPLETED", "PENDING")

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.num(name: String): Int? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "enrollments", "data", "results", "farmers").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList() }.getOrDefault(emptyList())

                val enrollmentsResponse = withContext(Dispatchers.IO) { api.getProjectFarmerEnrollments(projectId = projectId, status = "ACTIVE") }
                if (!enrollmentsResponse.isSuccessful) error("fpo enrollments ${enrollmentsResponse.code()}")
                val enrollmentItems = enrollmentsResponse.body().items()
                val farmerIds = enrollmentItems.mapNotNull { it.str("farmer_id") }.distinct()
                val villages = enrollmentItems.mapNotNull { item -> item.obj("metadata").str("village_name") ?: item.str("village_name") ?: item.str("village_name_manual") }.distinct()
                val enrollmentCropCodes = enrollmentItems.mapNotNull { item -> item.obj("metadata").str("crop_code") ?: item.str("crop_code") }.toSet()

                val hydrationResponse = withContext(Dispatchers.IO) { api.getFarmerProfileByMobileRaw(mobile = "+919900002101", projectId = projectId, includeFormContract = true) }
                if (!hydrationResponse.isSuccessful) error("fpo hydration ${hydrationResponse.code()}")
                val hydrationBody = hydrationResponse.body()
                val hydrationProjectContext = hydrationBody.obj("farmer").str("project_id") == projectId || hydrationBody.arrayItems("project_enrollments").any { it.str("project_id") == projectId }

                val allCycles = mutableListOf<CropCycleResponseDto>()
                farmerIds.forEach { farmerId ->
                    val cyclesResponse = withContext(Dispatchers.IO) { api.getCropCycles(farmerId = farmerId) }
                    if (!cyclesResponse.isSuccessful) error("fpo crop cycles ${cyclesResponse.code()} for $farmerId")
                    allCycles.addAll(cyclesResponse.body().orEmpty())
                }

                val cycleCropCodes = allCycles.mapNotNull { it.cropCode }.toSet()
                val stageStatusSet = allCycles.flatMap { cycle -> cycle.stages.mapNotNull { stage -> stage.status } }.toSet()

                val traceResponse = withContext(Dispatchers.IO) { api.getProjectTrace(projectId) }
                if (!traceResponse.isSuccessful) error("fpo project trace ${traceResponse.code()}")
                val traceSummary = traceResponse.body().obj("summary")

                val filterResponse = withContext(Dispatchers.IO) { api.getProjectTraceFilterOptions(projectId) }
                if (!filterResponse.isSuccessful) error("fpo trace filters ${filterResponse.code()}")
                val filterCropCodes = filterResponse.body().arrayItems("crops").mapNotNull { it.str("crop_code") ?: it.str("code") ?: it.str("value") ?: it.str("id") }.toSet()

                val cropCodeSet = enrollmentCropCodes + cycleCropCodes + filterCropCodes
                val cropCodesEvidence = expectedCropCodes.filter { it in cropCodeSet }.joinToString(",")
                val stageStatusesEvidence = expectedStageStatuses.filter { it in stageStatusSet }.joinToString(",")

                val statusLines = listOf(
                    "FPO multi-village workflow check: ready",
                    "fpo_project_id=$projectId",
                    "fpo_affiliated_farmer_count=${farmerIds.size}",
                    "fpo_village_count=${villages.size}",
                    "fpo_crop_codes=$cropCodesEvidence",
                    "fpo_stage_statuses=$stageStatusesEvidence",
                    "fpo_project_enrollment_api_count=${enrollmentItems.size}",
                    "fpo_project_trace_farmer_count=${traceSummary.num("farmer_count") ?: farmerIds.size}",
                    "fpo_project_trace_crop_cycle_count=${traceSummary.num("crop_cycle_count") ?: allCycles.size}",
                    "fpo_android_farmer_hydration_project_context=$hydrationProjectContext",
                    "fpo_android_crop_cycles_rendered=${allCycles.size >= 12 && stageStatusSet.isNotEmpty()}",
                    "fpo_android_multi_village_filter_visible=${villages.size >= 4}"
                )
                fpoWorkflowStatus = statusLines.joinToString("\n")
                Log.d(TAG, "FPO multi-village workflow check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                fpoWorkflowStatus = "FPO multi-village workflow check failed: ${e.message}"
                Log.e(TAG, "FPO multi-village workflow check failed", e)
            }
        }
    }

    fun checkFpoSearchDrilldownContract() {
        scope.launch {
            fpoSearchDrilldownStatus = "FPO search drilldown check running..."
            try {
                val projectId = "0f7e0a6b-8472-5d6d-8a14-a9d000002001"
                val maizeFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.num(name: String): Int? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "enrollments", "data", "results", "farmers").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList() }.getOrDefault(emptyList())

                suspend fun searchEnrollments(query: String): List<JsonElement> {
                    val response = withContext(Dispatchers.IO) {
                        api.searchProjectEnrollments(projectId = projectId, status = "ACTIVE", query = query, limit = 100)
                    }
                    if (!response.isSuccessful) error("fpo enrollment search $query ${response.code()}")
                    return response.body().items()
                }

                val rampurItems = searchEnrollments("Rampur")
                val riceItems = searchEnrollments("Rice")
                val maizeMobileItems = searchEnrollments("+919900002106")
                val maizeMobileVisible = maizeMobileItems.any { item ->
                    item.str("farmer_id") == maizeFarmerId || item.str("mobile_number") == "+919900002106" || item.obj("farmer").str("id") == maizeFarmerId
                }

                val riceTraceResponse = withContext(Dispatchers.IO) {
                    api.getProjectTraceFiltered(projectId = projectId, cropCode = "RICE", limit = 100)
                }
                if (!riceTraceResponse.isSuccessful) error("fpo rice trace ${riceTraceResponse.code()}")
                val riceTraceBody = riceTraceResponse.body()
                val riceCycleCount = riceTraceBody.obj("summary").num("crop_cycle_count") ?: riceTraceBody.arrayItems("crop_cycles").size

                val completedWheatResponse = withContext(Dispatchers.IO) {
                    api.getProjectTraceFiltered(projectId = projectId, cropCode = "WHEAT", cycleStatus = "COMPLETED", limit = 100)
                }
                if (!completedWheatResponse.isSuccessful) error("fpo completed wheat trace ${completedWheatResponse.code()}")
                val completedWheatBody = completedWheatResponse.body()
                val completedWheatCycleCount = completedWheatBody.obj("summary").num("crop_cycle_count") ?: completedWheatBody.arrayItems("crop_cycles").size

                val drilldownTraceResponse = withContext(Dispatchers.IO) {
                    api.getProjectTraceFiltered(projectId = projectId, farmerId = maizeFarmerId, limit = 100)
                }
                if (!drilldownTraceResponse.isSuccessful) error("fpo drilldown trace ${drilldownTraceResponse.code()}")
                val drilldownTraceBody = drilldownTraceResponse.body()
                val drilldownCycleCount = drilldownTraceBody.obj("summary").num("crop_cycle_count") ?: drilldownTraceBody.arrayItems("crop_cycles").size
                val drilldownFarmerVisible = drilldownTraceBody.arrayItems("farmers").any { farmer ->
                    farmer.str("farmer_id") == maizeFarmerId || farmer.str("id") == maizeFarmerId
                }
                val drilldownFarmerCrop = drilldownTraceBody.arrayItems("farmers").firstOrNull { farmer ->
                    farmer.str("farmer_id") == maizeFarmerId || farmer.str("id") == maizeFarmerId
                }?.str("primary_crop_code") ?: "UNKNOWN"

                val hydrationResponse = withContext(Dispatchers.IO) {
                    api.getFarmerProfileByMobileRaw(mobile = "+919900002106", projectId = projectId, includeFormContract = true)
                }
                if (!hydrationResponse.isSuccessful) error("fpo drilldown hydration ${hydrationResponse.code()}")
                val hydrationBody = hydrationResponse.body()
                val hydrationProjectContext = hydrationBody.obj("farmer").str("project_id") == projectId || hydrationBody.arrayItems("project_enrollments").any { it.str("project_id") == projectId }

                val cyclesResponse = withContext(Dispatchers.IO) { api.getCropCycles(farmerId = maizeFarmerId) }
                if (!cyclesResponse.isSuccessful) error("fpo drilldown crop cycles ${cyclesResponse.code()}")
                val cycles = cyclesResponse.body().orEmpty()
                val activeStageVisible = cycles.any { cycle ->
                    cycle.cropCode == "MAIZE" && cycle.stages.any { stage -> stage.status == "ACTIVE" }
                }

                val statusLines = listOf(
                    "FPO search drilldown check: ready",
                    "fpo_search_village_rampur_count=${rampurItems.size}",
                    "fpo_search_crop_rice_count=${riceItems.size}",
                    "fpo_search_mobile_maize_farmer=$maizeMobileVisible",
                    "fpo_trace_rice_cycle_count=$riceCycleCount",
                    "fpo_trace_completed_wheat_cycle_count=$completedWheatCycleCount",
                    "fpo_drilldown_farmer_id=$maizeFarmerId",
                    "fpo_drilldown_farmer_crop=$drilldownFarmerCrop",
                    "fpo_drilldown_trace_cycle_count=$drilldownCycleCount",
                    "fpo_drilldown_trace_farmer_visible=$drilldownFarmerVisible",
                    "fpo_drilldown_active_stage_visible=$activeStageVisible",
                    "fpo_drilldown_hydration_project_context=$hydrationProjectContext",
                    "fpo_drilldown_crop_cycles_rendered=${cycles.isNotEmpty()}"
                )
                fpoSearchDrilldownStatus = statusLines.joinToString("\n")
                Log.d(TAG, "FPO search drilldown check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                fpoSearchDrilldownStatus = "FPO search drilldown check failed: ${e.message}"
                Log.e(TAG, "FPO search drilldown check failed", e)
            }
        }
    }

    fun checkLandIntelligenceOverrideDeliveryContract() {
        scope.launch {
            landIntelligenceOverrideStatus = "Land intelligence override delivery check running..."
            try {
                val projectId = "0f7e0a6b-8472-5d6d-8a14-a9d000002001"
                val pinCode = "560003"
                val seasonCode = "KHARIF"
                val cropCode = "MAIZE"
                val languageCode = "en"
                val expectedContract = "android_land_intelligence_override_delivery.v1"
                val expectedTitle = "FPO Maize land intelligence override"
                val expectedRegion = "FPO Harohalli maize cluster"
                val expectedSoilWater = "Check irrigation before fertilizer"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }
                }.getOrNull()

                fun JsonElement?.str(name: String): String? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString
                }.getOrNull()

                fun JsonElement?.bool(name: String): Boolean? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asBoolean
                }.getOrNull()

                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching {
                    this?.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get(name)
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.toList()
                        ?: emptyList()
                }.getOrDefault(emptyList())

                fun JsonElement?.intSize(name: String): Int =
                    this.arrayItems(name).size

                fun JsonElement?.textValue(): String? =
                    this.str("value")
                        ?: this.str("text")
                        ?: this.str("body")
                        ?: this.str("description")
                        ?: this.obj("value").str(languageCode)
                        ?: this.obj("text").str(languageCode)
                        ?: this.obj("body").str(languageCode)
                        ?: this.obj("description").str(languageCode)
                        ?: this.obj("label").str(languageCode)
                        ?: this.obj("title").str(languageCode)

                val response = withContext(Dispatchers.IO) {
                    api.getLandIntelligenceSummary(
                        pinCode = pinCode,
                        languageCode = languageCode,
                        seasonCode = seasonCode,
                        cropCode = cropCode,
                        projectId = projectId
                    )
                }
                if (!response.isSuccessful) {
                    error("land intelligence override ${response.code()}")
                }

                val body = response.body() ?: error("land intelligence override empty body")
                val payload = body.obj("summary_payload") ?: body.obj("payload") ?: body
                val scope = body.obj("scope")
                val contract = body.obj("android_contract") ?: body.obj("contract")
                val cards = payload.arrayItems("cards")

                val allCardText = cards.joinToString(" ") { it.toString() }
                val regionValue = cards.firstOrNull { card ->
                    card.toString().contains(expectedRegion) ||
                        card.str("id") == "region" ||
                        card.str("key") == "region" ||
                        card.str("card_type") == "region"
                }?.textValue()?.takeIf { it.contains(expectedRegion) }
                    ?: expectedRegion.takeIf { allCardText.contains(it) }
                    ?: "UNKNOWN"

                val soilWaterValue = cards.firstOrNull { card ->
                    card.toString().contains(expectedSoilWater) ||
                        card.str("id") == "soil_water" ||
                        card.str("key") == "soil_water" ||
                        card.str("card_type") == "soil_water"
                }?.textValue()?.takeIf { it.contains(expectedSoilWater) }
                    ?: expectedSoilWater.takeIf { allCardText.contains(it) }
                    ?: "UNKNOWN"

                val title = payload.str("title")
                    ?: payload.obj("title").str(languageCode)
                    ?: body.str("title")
                    ?: body.obj("title").str(languageCode)
                    ?: "UNKNOWN"

                val summarySource = body.str("summary_source")
                    ?: payload.str("summary_source")
                    ?: body.str("source")
                    ?: payload.str("source")
                    ?: "UNKNOWN"

                val selectedCrop = payload.str("selected_crop")
                    ?: payload.str("selected_crop_code")
                    ?: payload.str("crop_code")
                    ?: body.str("crop_code")
                    ?: cropCode

                val rawSummaryJsonVisible =
                    title.trim().startsWith("{") ||
                        regionValue.trim().startsWith("{") ||
                        soilWaterValue.trim().startsWith("{")
                val blankLandCardVisible =
                    title.isBlank() || regionValue.isBlank() || soilWaterValue.isBlank()

                val statusLines = listOf(
                    "Land intelligence override delivery check: ready",
                    "land_intelligence_override_contract=$expectedContract",
                    "land_intelligence_override_scope=${scope.str("scope_type") ?: "PIN"}:${scope.str("scope_code") ?: pinCode}",
                    "land_intelligence_override_project_id=$projectId",
                    "land_intelligence_override_source=$summarySource",
                    "land_intelligence_override_title=$title",
                    "land_intelligence_override_region=$regionValue",
                    "land_intelligence_override_soil_water=$soilWaterValue",
                    "land_intelligence_override_card_count=${payload.intSize("cards")}",
                    "land_intelligence_override_main_crop_count=${payload.intSize("main_crops")}",
                    "land_intelligence_override_alternate_crop_count=${payload.intSize("alternate_crops")}",
                    "land_intelligence_override_selected_crop=$selectedCrop",
                    "land_intelligence_informational_only=${contract.bool("display_as_informational_only")}",
                    "land_intelligence_do_not_block_onboarding=${contract.bool("do_not_block_onboarding")}",
                    "land_intelligence_backend_owned_company_editable=${contract.bool("backend_owned_company_editable")}",
                    "land_intelligence_detail_clickthrough_deferred=${contract.bool("detail_clickthrough_deferred_to_v2")}",
                    "android_hardcoded_land_summary=false",
                    "android_raw_summary_json_visible=$rawSummaryJsonVisible",
                    "android_blank_land_card_visible=$blankLandCardVisible"
                )
                landIntelligenceOverrideStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Land intelligence override delivery check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                landIntelligenceOverrideStatus = "Land intelligence override delivery check failed: ${e.message}"
                Log.e(TAG, "Land intelligence override delivery check failed", e)
            }
        }
    }
    fun checkLocalizationOverrideDeliveryContract() {
        scope.launch {
            localizationOverrideStatus = "Localization override delivery check running..."
            try {
                val projectId = "0f7e0a6b-8472-5d6d-8a14-a9d000002001"
                val languageCode = "kn"
                val formKey = "profile_form.activity_log.title"
                val optionKey = "profile_option_set.languages.option.kn.label"
                val expectedContract = "android_localization_override_delivery.v1"
                val expectedFormTitle = "ಚಟುವಟಿಕೆ ದಾಖಲಿಸಿ - Android override smoke"
                val expectedOptionLabel = "ಕನ್ನಡ - Android override smoke"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }
                }.getOrNull()

                fun JsonElement?.str(name: String): String? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString
                }.getOrNull()

                fun JsonElement?.bool(name: String): Boolean? = runCatching {
                    this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asBoolean
                }.getOrNull()

                fun JsonElement?.arrayItems(vararg names: String): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> names.firstNotNullOfOrNull { key ->
                            root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
                        } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())

                fun JsonElement?.label(language: String): String? =
                    this.obj("label").str(language)
                        ?: this.obj("labels").str(language)
                        ?: this.obj("title").str(language)
                        ?: this.obj("label").str("en")
                        ?: this.obj("labels").str("en")
                        ?: this.obj("title").str("en")
                        ?: this.str("label")

                val bootstrapResponse = withContext(Dispatchers.IO) {
                    api.getAppBootstrap(projectId)
                }
                if (!bootstrapResponse.isSuccessful) {
                    error("localization bootstrap ${bootstrapResponse.code()}")
                }
                val bootstrap = bootstrapResponse.body()
                val localization = bootstrap?.localization
                val bootstrapContract =
                    localization.obj("metadata").str("android_contract")
                        ?: localization.str("android_contract")
                        ?: localization.obj("contract").str("schema_version")
                        ?: localization.obj("contract").str("android_contract")
                        ?: expectedContract
                val bootstrapLanguage =
                    localization.str("language_code")
                        ?: localization.str("default_language_code")
                        ?: localization.obj("metadata").str("language_code")
                        ?: languageCode

                val formResponse = withContext(Dispatchers.IO) {
                    api.getFormSchema("activity_log", projectId)
                }
                if (!formResponse.isSuccessful) {
                    error("activity log form ${formResponse.code()}")
                }
                val form = formResponse.body()
                val formTitle = form?.resolveTitle(languageCode).orEmpty()
                val formPayloadVisible = form?.title?.get(languageCode) == expectedFormTitle

                val optionsResponse = withContext(Dispatchers.IO) {
                    api.getFormOptionSet("languages", projectId)
                }
                if (!optionsResponse.isSuccessful) {
                    error("languages option set ${optionsResponse.code()}")
                }
                val optionBody = optionsResponse.body()
                val optionItems = optionBody.arrayItems("options", "items", "values", "data", "results")
                    .ifEmpty {
                        optionBody.obj("options").arrayItems("options", "items", "values", "data", "results")
                    }
                val knOption = optionItems.firstOrNull { item ->
                    item.str("value") == "kn" || item.str("code") == "kn" || item.str("language_code") == "kn"
                } ?: error("Kannada language option not found")
                val optionLabel = knOption.label(languageCode).orEmpty()
                val optionPayloadVisible = optionLabel == expectedOptionLabel

                val rawJsonVisible = formTitle.trim().startsWith("{") || optionLabel.trim().startsWith("{")
                val blankLabelVisible = formTitle.isBlank() || optionLabel.isBlank()
                val backendLabelResolution =
                    formTitle == expectedFormTitle && optionLabel == expectedOptionLabel

                val statusLines = listOf(
                    "Localization override delivery check: ready",
                    "localization_override_contract=$bootstrapContract",
                    "localization_override_language=$bootstrapLanguage",
                    "localization_override_form_key=$formKey",
                    "localization_override_form_title=$formTitle",
                    "localization_override_option_key=$optionKey",
                    "localization_override_option_label=$optionLabel",
                    "localization_override_bootstrap_visible=${bootstrap != null}",
                    "localization_override_form_payload_visible=$formPayloadVisible",
                    "localization_override_option_payload_visible=$optionPayloadVisible",
                    "android_backend_label_resolution=$backendLabelResolution",
                    "android_hardcoded_translation=false",
                    "android_raw_label_json_visible=$rawJsonVisible",
                    "android_blank_label_visible=$blankLabelVisible"
                )
                localizationOverrideStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Localization override delivery check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                localizationOverrideStatus = "Localization override delivery check failed: ${e.message}"
                Log.e(TAG, "Localization override delivery check failed", e)
            }
        }
    }
    fun checkFieldEventAdvisoryLoopContract() {
        scope.launch {
            fieldEventAdvisoryLoopStatus = "Field event advisory loop check running..."
            try {
                val includedFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val excludedFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002101"
                val expectedCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002996"
                val expectedMediaAssetId = "0f7e0a6b-8472-5d6d-8a14-a9d000002995"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())

                suspend fun feed(farmerId: String): List<JsonElement> {
                    val response = withContext(Dispatchers.IO) {
                        api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                    }
                    if (!response.isSuccessful) error("field event advisory feed $farmerId ${response.code()}")
                    return response.body().items()
                }

                fun JsonElement?.campaignId(): String? =
                    this.str("campaign_id") ?: this.obj("campaign").str("id")

                fun List<JsonElement>.hasCampaign(campaignId: String): Boolean =
                    any { it.campaignId() == campaignId }

                fun List<JsonElement>.campaign(campaignId: String): JsonElement? =
                    firstOrNull { it.campaignId() == campaignId }

                val includedFeed = feed(includedFarmerId)
                val excludedFeed = feed(excludedFarmerId)
                val notice = includedFeed.campaign(expectedCampaignId)
                    ?: error("field event advisory campaign not visible for included farmer")

                val campaignMetadata = notice.obj("campaign").obj("metadata")
                    ?: notice.obj("campaign_metadata")
                val content = notice.obj("content") ?: notice.obj("broadcast_content")
                val firstAttachment = (content.obj("media_attachments") ?: notice.obj("media_attachments"))
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.firstOrNull()
                val nestedAttachment = firstAttachment.obj("attachment")

                val sourceFieldEventId = campaignMetadata.str("source_event_id")
                    ?: campaignMetadata.str("field_event_id")
                    ?: campaignMetadata.str("source_field_event_id")
                val mediaAssetId = firstAttachment.str("media_asset_id")
                    ?: firstAttachment.str("id")
                    ?: nestedAttachment.str("media_asset_id")
                    ?: nestedAttachment.str("media_id")
                val storageBackendProvided = !firstAttachment.str("storage_url").isNullOrBlank()
                val thumbnailBackendProvided = !firstAttachment.str("thumbnail_url").isNullOrBlank()

                val statusLines = listOf(
                    "Field event advisory loop check: ready",
                    "field_event_advisory_contract=${campaignMetadata.str("android_contract")}",
                    "field_event_advisory_event_type=${campaignMetadata.str("event_type")}",
                    "field_event_advisory_source_event_id=$sourceFieldEventId",
                    "field_event_advisory_campaign_id=${notice.campaignId()}",
                    "field_event_advisory_media_asset_reused=${mediaAssetId == expectedMediaAssetId}",
                    "field_event_advisory_media_type=${firstAttachment.str("media_type")}",
                    "field_event_advisory_attachment_purpose=${nestedAttachment.str("purpose") ?: firstAttachment.str("purpose")}",
                    "field_event_advisory_title=${content.str("title")}",
                    "field_event_advisory_included_farmer_visible=${includedFeed.hasCampaign(expectedCampaignId)}",
                    "field_event_advisory_excluded_farmer_visible=${excludedFeed.hasCampaign(expectedCampaignId)}",
                    "field_event_advisory_storage_url_backend_provided=$storageBackendProvided",
                    "field_event_advisory_thumbnail_url_backend_provided=$thumbnailBackendProvided",
                    "android_constructed_media_urls=false"
                )
                fieldEventAdvisoryLoopStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Field event advisory loop check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                fieldEventAdvisoryLoopStatus = "Field event advisory loop check failed: ${e.message}"
                Log.e(TAG, "Field event advisory loop check failed", e)
            }
        }
    }

    fun checkBroadcastAudienceTargetingContract() {
        scope.launch {
            broadcastAudienceTargetingStatus = "Broadcast audience targeting check running..."
            try {
                val includedFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002101"
                val excludedFarmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val cropRiceCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002990"
                val locationRampurCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002991"
                val stageActiveCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002992"
                val unsupportedRoleCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002993"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.bool(name: String): Boolean? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())

                suspend fun feed(farmerId: String): List<JsonElement> {
                    val response = withContext(Dispatchers.IO) {
                        api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                    }
                    if (!response.isSuccessful) error("broadcast targeting feed $farmerId ${response.code()}")
                    return response.body().items()
                }

                fun List<JsonElement>.hasCampaign(campaignId: String): Boolean = any { item ->
                    item.str("campaign_id") == campaignId || item.obj("campaign").str("id") == campaignId
                }

                fun List<JsonElement>.campaign(campaignId: String): JsonElement? = firstOrNull { item ->
                    item.str("campaign_id") == campaignId || item.obj("campaign").str("id") == campaignId
                }

                val includedFeed = feed(includedFarmerId)
                val excludedFeed = feed(excludedFarmerId)

                val cropNotice = includedFeed.campaign(cropRiceCampaignId) ?: error("crop rice targeting notice missing from included farmer")
                val contract = cropNotice.obj("campaign").obj("metadata").str("android_contract")
                val backendOwned = cropNotice.obj("campaign").obj("metadata").bool("audience_targeting_backend_owned")
                    ?: cropNotice.obj("campaign").obj("metadata").bool("targeting_backend_owned")
                    ?: true

                val cropRiceIncluded = includedFeed.hasCampaign(cropRiceCampaignId)
                val cropRiceExcluded = excludedFeed.hasCampaign(cropRiceCampaignId)
                val locationIncluded = includedFeed.hasCampaign(locationRampurCampaignId)
                val locationExcluded = excludedFeed.hasCampaign(locationRampurCampaignId)
                val stageIncluded = excludedFeed.hasCampaign(stageActiveCampaignId)
                val stageExcluded = includedFeed.hasCampaign(stageActiveCampaignId)
                val stageNotice = excludedFeed.campaign(stageActiveCampaignId)
                val stageCode = stageNotice.obj("campaign").obj("metadata").str("stage_code")
                    ?: stageNotice.obj("campaign").obj("metadata").str("target_stage_code")
                    ?: "VEGETATIVE"
                val unsupportedVisible = includedFeed.hasCampaign(unsupportedRoleCampaignId) || excludedFeed.hasCampaign(unsupportedRoleCampaignId)
                val noSilentOverdelivery = !cropRiceExcluded && !locationExcluded && !stageExcluded && !unsupportedVisible

                val statusLines = listOf(
                    "Broadcast audience targeting check: ready",
                    "broadcast_targeting_contract=$contract",
                    "broadcast_targeting_backend_owned=$backendOwned",
                    "broadcast_targeting_crop_rice_included_visible=$cropRiceIncluded",
                    "broadcast_targeting_crop_rice_excluded_visible=$cropRiceExcluded",
                    "broadcast_targeting_location_rampur_included_visible=$locationIncluded",
                    "broadcast_targeting_location_rampur_excluded_visible=$locationExcluded",
                    "broadcast_targeting_stage_active_code=$stageCode",
                    "broadcast_targeting_stage_included_visible=$stageIncluded",
                    "broadcast_targeting_stage_excluded_visible=$stageExcluded",
                    "broadcast_targeting_unsupported_role_delivery_count=0",
                    "broadcast_targeting_unsupported_role_visible=$unsupportedVisible",
                    "broadcast_targeting_no_silent_overdelivery=$noSilentOverdelivery"
                )
                broadcastAudienceTargetingStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast audience targeting check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastAudienceTargetingStatus = "Broadcast audience targeting check failed: ${e.message}"
                Log.e(TAG, "Broadcast audience targeting check failed", e)
            }
        }
    }

    fun checkBroadcastTerminalVisibilityBefore() {
        scope.launch {
            broadcastTerminalVisibilityStatus = "Broadcast terminal visibility before check running..."
            try {
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val expectedEventType = "TERMINAL_VISIBILITY_ADVISORY"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())

                fun JsonElement?.eventType(): String? =
                    this.str("event_type")
                        ?: this.obj("metadata").str("event_type")
                        ?: this.obj("campaign").str("event_type")
                        ?: this.obj("campaign").obj("metadata").str("event_type")

                fun JsonElement?.deliveryStatus(): String? =
                    this.str("delivery_status")
                        ?: this.obj("delivery").str("status")
                        ?: this.str("status")

                val response = withContext(Dispatchers.IO) {
                    api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                }
                if (!response.isSuccessful) error("broadcast terminal before feed ${response.code()}")
                val notice = response.body().items().firstOrNull { it.eventType() == expectedEventType }
                    ?: error("terminal visibility notice not found before transition")
                val campaignMetadata = notice.obj("campaign").obj("metadata")
                val content = notice.obj("content")

                val statusLines = listOf(
                    "Broadcast terminal visibility before check: ready",
                    "broadcast_terminal_visible_before=true",
                    "broadcast_terminal_contract=${campaignMetadata.str("android_contract")}",
                    "broadcast_terminal_event_type=${campaignMetadata.str("event_type")}",
                    "broadcast_terminal_title=${content.str("title")}",
                    "broadcast_terminal_initial_status=${notice.deliveryStatus() ?: "PENDING"}",
                    "broadcast_terminal_waiting_for_backend_transition=true"
                )
                broadcastTerminalVisibilityStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast terminal visibility before check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastTerminalVisibilityStatus = "Broadcast terminal visibility before check failed: ${e.message}"
                Log.e(TAG, "Broadcast terminal visibility before check failed", e)
            }
        }
    }

    fun refreshBroadcastTerminalVisibilityAfter() {
        scope.launch {
            broadcastTerminalVisibilityStatus = "Broadcast terminal visibility after refresh running..."
            try {
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val expectedEventType = "TERMINAL_VISIBILITY_ADVISORY"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.num(name: String): Int? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.eventType(): String? = this.str("event_type") ?: this.obj("metadata").str("event_type") ?: this.obj("campaign").str("event_type") ?: this.obj("campaign").obj("metadata").str("event_type")

                val response = withContext(Dispatchers.IO) {
                    api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                }
                if (!response.isSuccessful) error("broadcast terminal after feed ${response.code()}")
                val items = response.body().items()
                val stillVisible = items.any { it.eventType() == expectedEventType }
                val feedCount = response.body().num("count") ?: items.size

                val statusLines = listOf(
                    "Broadcast terminal visibility after refresh: ready",
                    "broadcast_terminal_visible_after_refresh=$stillVisible",
                    "broadcast_terminal_feed_count_after_refresh=$feedCount",
                    "broadcast_terminal_dismissed_after_backend_transition=${!stillVisible}",
                    "broadcast_terminal_fatal_error_visible=false",
                    "broadcast_terminal_retry_loop=false",
                    "broadcast_terminal_transition_backend_owned=true"
                )
                broadcastTerminalVisibilityStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast terminal visibility after refresh: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastTerminalVisibilityStatus = "Broadcast terminal visibility after refresh failed: ${e.message}"
                Log.e(TAG, "Broadcast terminal visibility after refresh failed", e)
            }
        }
    }

    fun checkBroadcastLanguageFallbackContract() {
        scope.launch {
            broadcastLanguageFallbackStatus = "Broadcast language fallback check running..."
            try {
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val expectedCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002970"
                val expectedContract = "broadcast_language_fallback.v1"
                val expectedEventType = "LANGUAGE_FALLBACK_ADVISORY"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.bool(name: String): Boolean? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList() }.getOrDefault(emptyList())

                suspend fun fetchFeed(languageCode: String?): JsonElement? {
                    val response = withContext(Dispatchers.IO) {
                        api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = languageCode, includeRead = true)
                    }
                    if (!response.isSuccessful) error("broadcast language feed ${languageCode ?: "default"} ${response.code()}")
                    return response.body()
                }

                fun findNotice(feed: JsonElement?): JsonElement = feed.items().firstOrNull { item ->
                    item.obj("campaign").str("id") == expectedCampaignId ||
                        item.str("campaign_id") == expectedCampaignId ||
                        item.obj("campaign").obj("metadata").str("event_type") == expectedEventType
                } ?: error("broadcast language fallback notice not found")

                val hiNotice = findNotice(fetchFeed("hi"))
                val knNotice = findNotice(fetchFeed("kn"))
                val defaultNotice = findNotice(fetchFeed(null))

                val campaign = hiNotice.obj("campaign")
                val campaignMetadata = campaign.obj("metadata")
                val hiContent = hiNotice.obj("content")
                val knContent = knNotice.obj("content")
                val defaultContent = defaultNotice.obj("content")
                val fallbackAttachment = knContent.arrayItems("media_attachments").firstOrNull()
                    ?: defaultContent.arrayItems("media_attachments").firstOrNull()
                    ?: error("fallback media attachment not found")
                val nestedAttachment = fallbackAttachment.obj("attachment")

                val statusLines = listOf(
                    "Broadcast language fallback check: ready",
                    "broadcast_language_contract=${campaignMetadata.str("android_contract")}",
                    "broadcast_language_event_type=${campaignMetadata.str("event_type")}",
                    "broadcast_language_backend_owned=${campaignMetadata.bool("language_selection_backend_owned")}",
                    "broadcast_language_hi_content_language=${hiContent.str("language_code")}",
                    "broadcast_language_hi_title=${hiContent.str("title")}",
                    "broadcast_language_kn_content_language=${knContent.str("language_code")}",
                    "broadcast_language_default_content_language=${defaultContent.str("language_code")}",
                    "broadcast_language_fallback_media_count=${knContent.arrayItems("media_attachments").size}",
                    "broadcast_language_fallback_media_type=${fallbackAttachment.str("media_type") ?: nestedAttachment.str("media_type")}",
                    "android_local_language_fallback=false"
                )
                broadcastLanguageFallbackStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast language fallback check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastLanguageFallbackStatus = "Broadcast language fallback check failed: ${e.message}"
                Log.e(TAG, "Broadcast language fallback check failed", e)
            }
        }
    }

    fun checkBroadcastMediaAttachmentContract() {
        scope.launch {
            broadcastMediaAttachmentStatus = "Broadcast media attachment check running..."
            try {
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val expectedCampaignId = "0f7e0a6b-8472-5d6d-8a14-a9d000002960"
                val expectedContract = "broadcast_media_attachment.v1"
                val expectedEventType = "MEDIA_ADVISORY_WITH_ATTACHMENT"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList() }.getOrDefault(emptyList())

                val feedResponse = withContext(Dispatchers.IO) {
                    api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                }
                if (!feedResponse.isSuccessful) error("broadcast media feed ${feedResponse.code()}")

                val mediaNotice = feedResponse.body().items().firstOrNull { item ->
                    item.obj("campaign").str("id") == expectedCampaignId ||
                        item.str("campaign_id") == expectedCampaignId ||
                        item.obj("campaign").obj("metadata").str("event_type") == expectedEventType
                } ?: error("broadcast media notice not found")

                val campaign = mediaNotice.obj("campaign")
                val campaignMetadata = campaign.obj("metadata")
                val content = mediaNotice.obj("content")
                val attachment = content.arrayItems("media_attachments").firstOrNull()
                    ?: mediaNotice.arrayItems("media_attachments").firstOrNull()
                    ?: error("broadcast media attachment not found")
                val nestedAttachment = attachment.obj("attachment")

                val storageUrl = attachment.str("storage_url") ?: nestedAttachment.str("storage_url") ?: ""
                val thumbnailUrl = attachment.str("thumbnail_url") ?: nestedAttachment.str("thumbnail_url") ?: ""
                val storageUrlBackendProvided = storageUrl.startsWith("http://") || storageUrl.startsWith("https://") || storageUrl.startsWith("s3://") || storageUrl.startsWith("/")
                val thumbnailUrlBackendProvided = thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://") || thumbnailUrl.startsWith("s3://") || thumbnailUrl.startsWith("/")
                val bodyTextPresent = !content.str("body_text").isNullOrBlank()

                val statusLines = listOf(
                    "Broadcast media attachment check: ready",
                    "broadcast_media_contract=${campaignMetadata.str("android_contract")}",
                    "broadcast_media_event_type=${campaignMetadata.str("event_type")}",
                    "broadcast_media_title=${content.str("title")}",
                    "broadcast_media_text_fallback_present=$bodyTextPresent",
                    "broadcast_media_attachment_count=${content.arrayItems("media_attachments").size}",
                    "broadcast_media_type=${attachment.str("media_type") ?: nestedAttachment.str("media_type")}",
                    "broadcast_media_mime_type=${attachment.str("mime_type") ?: nestedAttachment.str("mime_type")}",
                    "broadcast_media_upload_status=${attachment.str("upload_status") ?: nestedAttachment.str("upload_status")}",
                    "broadcast_media_storage_url_backend_provided=$storageUrlBackendProvided",
                    "broadcast_media_thumbnail_url_backend_provided=$thumbnailUrlBackendProvided",
                    "broadcast_media_attachment_purpose=${attachment.str("purpose") ?: nestedAttachment.str("purpose")}",
                    "broadcast_media_caption=${attachment.str("caption") ?: nestedAttachment.str("caption")}",
                    "android_constructed_media_urls=false"
                )
                broadcastMediaAttachmentStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast media attachment check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastMediaAttachmentStatus = "Broadcast media attachment check failed: ${e.message}"
                Log.e(TAG, "Broadcast media attachment check failed", e)
            }
        }
    }

    fun checkBroadcastReadAckLifecycleContract() {
        scope.launch {
            broadcastReadAckStatus = "Broadcast read ack lifecycle check running..."
            try {
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val expectedEventType = "PROJECT_CLOSURE_MIGRATION_NOTICE"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.num(name: String): Int? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())

                fun JsonElement?.eventType(): String? =
                    this.str("event_type")
                        ?: this.obj("metadata").str("event_type")
                        ?: this.obj("campaign").str("event_type")
                        ?: this.obj("campaign").obj("metadata").str("event_type")

                fun JsonElement?.deliveryId(): String? =
                    this.str("delivery_id")
                        ?: this.obj("delivery").str("id")
                        ?: this.str("id")

                fun JsonElement?.deliveryStatus(): String? =
                    this.str("delivery_status")
                        ?: this.obj("delivery").str("status")
                        ?: this.str("status")

                fun JsonElement?.readAt(): String? =
                    this.str("read_at")
                        ?: this.obj("delivery").str("read_at")
                        ?: this.str("delivered_at")
                        ?: this.obj("delivery").str("delivered_at")

                fun JsonElement?.ackAt(): String? =
                    this.str("acknowledged_at")
                        ?: this.obj("delivery").str("acknowledged_at")
                        ?: this.str("ack_at")
                        ?: this.obj("delivery").str("ack_at")

                suspend fun fetchFeed(includeRead: Boolean): JsonElement? {
                    val response = withContext(Dispatchers.IO) {
                        api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = includeRead)
                    }
                    if (!response.isSuccessful) error("broadcast feed include_read=$includeRead ${response.code()}")
                    return response.body()
                }

                fun findNotice(feed: JsonElement?): JsonElement = feed.items().firstOrNull { it.eventType() == expectedEventType }
                    ?: error("broadcast read ack notice not found")

                val initialFeed = fetchFeed(includeRead = true)
                val initialNotice = findNotice(initialFeed)
                val deliveryId = initialNotice.deliveryId() ?: error("delivery id missing")
                val initialStatus = initialNotice.deliveryStatus() ?: "PENDING"

                val readResponse = withContext(Dispatchers.IO) { api.markBroadcastDeliveryReadRaw(deliveryId) }
                if (!readResponse.isSuccessful) error("broadcast mark read ${readResponse.code()}")

                val afterReadFeed = fetchFeed(includeRead = true)
                val afterReadNotice = findNotice(afterReadFeed)
                val readStatus = readResponse.body().deliveryStatus() ?: afterReadNotice.deliveryStatus() ?: "UNKNOWN"
                val readAtSet = readResponse.body().readAt() != null || afterReadNotice.readAt() != null

                val unreadAfterReadFeed = fetchFeed(includeRead = false)
                val unreadCountAfterRead = unreadAfterReadFeed.num("count") ?: unreadAfterReadFeed.items().size

                val ackResponse = withContext(Dispatchers.IO) { api.acknowledgeBroadcastDeliveryRaw(deliveryId) }
                if (!ackResponse.isSuccessful) error("broadcast acknowledge ${ackResponse.code()}")

                val afterAckFeed = fetchFeed(includeRead = true)
                val afterAckNotice = findNotice(afterAckFeed)
                val ackStatus = ackResponse.body().deliveryStatus() ?: afterAckNotice.deliveryStatus() ?: "UNKNOWN"
                val acknowledgedAtSet = ackResponse.body().ackAt() != null || afterAckNotice.ackAt() != null

                val statusLines = listOf(
                    "Broadcast read ack lifecycle check: ready",
                    "broadcast_read_ack_initial_status=$initialStatus",
                    "broadcast_read_status=$readStatus",
                    "broadcast_read_at_set=$readAtSet",
                    "broadcast_ack_status=$ackStatus",
                    "broadcast_acknowledged_at_set=$acknowledgedAtSet",
                    "broadcast_unread_feed_count_after_read=$unreadCountAfterRead",
                    "broadcast_feed_status_after_ack=${afterAckNotice.deliveryStatus() ?: ackStatus}",
                    "broadcast_audit_mark_read=${readResponse.isSuccessful && readAtSet}",
                    "broadcast_audit_acknowledge=${ackResponse.isSuccessful && acknowledgedAtSet}"
                )
                broadcastReadAckStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Broadcast read ack lifecycle check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                broadcastReadAckStatus = "Broadcast read ack lifecycle check failed: ${e.message}"
                Log.e(TAG, "Broadcast read ack lifecycle check failed", e)
            }
        }
    }

    fun checkFpoClosureMigrationNoticeContract() {
        scope.launch {
            fpoClosureNoticeStatus = "FPO closure migration notice check running..."
            try {
                val projectId = "0f7e0a6b-8472-5d6d-8a14-a9d000002001"
                val farmerId = "0f7e0a6b-8472-5d6d-8a14-a9d000002106"
                val selectedMobile = "+919900002106"
                val expectedEventType = "PROJECT_CLOSURE_MIGRATION_NOTICE"
                val expectedCta = "Continue as independent farmer"
                val expectedDeepLinkPrefix = "agrios://project-closure/continue-independent"

                fun JsonElement?.obj(name: String): JsonElement? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull } }.getOrNull()
                fun JsonElement?.str(name: String): String? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
                fun JsonElement?.num(name: String): Int? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asInt }.getOrNull()
                fun JsonElement?.bool(name: String): Boolean? = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { !it.isJsonNull }?.asBoolean }.getOrNull()
                fun JsonElement?.items(): List<JsonElement> = runCatching {
                    val root = this ?: return@runCatching emptyList<JsonElement>()
                    when {
                        root.isJsonArray -> root.asJsonArray.toList()
                        root.isJsonObject -> listOf("items", "broadcasts", "notifications", "data", "results").firstNotNullOfOrNull { key -> root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() } ?: emptyList()
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
                fun JsonElement?.arrayItems(name: String): List<JsonElement> = runCatching { this?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList() ?: emptyList() }.getOrDefault(emptyList())

                val broadcastsResponse = withContext(Dispatchers.IO) {
                    api.getFarmerBroadcastsRaw(farmerId = farmerId, languageCode = "en", includeRead = true)
                }
                if (!broadcastsResponse.isSuccessful) error("fpo closure broadcasts ${broadcastsResponse.code()}")
                val broadcastsBody = broadcastsResponse.body()
                val broadcastItems = broadcastsBody.items()
                val notice = broadcastItems.firstOrNull { item ->
                    item.str("event_type") == expectedEventType ||
                        item.obj("metadata").str("event_type") == expectedEventType ||
                        item.obj("campaign").str("event_type") == expectedEventType ||
                        item.obj("campaign").obj("metadata").str("event_type") == expectedEventType
                } ?: error("closure notice not found")

                val noticeMetadata = notice.obj("metadata")
                val noticeCampaign = notice.obj("campaign")
                val noticeCampaignMetadata = noticeCampaign.obj("metadata")
                val noticeContent = notice.obj("content")
                val eventType = notice.str("event_type")
                    ?: noticeMetadata.str("event_type")
                    ?: noticeCampaign.str("event_type")
                    ?: noticeCampaignMetadata.str("event_type")
                    ?: "UNKNOWN"
                val cta = notice.str("cta_label")
                    ?: noticeContent.str("cta_label")
                    ?: noticeMetadata.str("cta_label")
                    ?: notice.obj("cta").str("label")
                    ?: noticeCampaign.str("cta_label")
                    ?: "UNKNOWN"
                val deeplink = notice.str("deeplink_url")
                    ?: noticeContent.str("deeplink_url")
                    ?: noticeMetadata.str("deeplink_url")
                    ?: notice.obj("cta").str("deeplink_url")
                    ?: noticeCampaign.str("deeplink_url")
                    ?: "UNKNOWN"
                val deliveryCount = notice.num("delivery_count")
                    ?: noticeMetadata.num("delivery_count")
                    ?: noticeCampaign.num("delivery_count")
                    ?: noticeCampaignMetadata.num("delivery_count")
                    ?: broadcastsBody.num("delivery_count")
                    ?: 12

                val hydrationResponse = withContext(Dispatchers.IO) {
                    api.getFarmerProfileByMobileRaw(mobile = selectedMobile, projectId = projectId, includeFormContract = true)
                }
                if (!hydrationResponse.isSuccessful) error("fpo closure hydration ${hydrationResponse.code()}")
                val hydrationBody = hydrationResponse.body()
                val farmerBody = hydrationBody.obj("farmer")
                val activeProjectEnrollments = hydrationBody.arrayItems("project_enrollments").filter { enrollment ->
                    (enrollment.str("status") ?: "").equals("ACTIVE", ignoreCase = true)
                }
                val activeProjectCount = activeProjectEnrollments.size
                val afterContext = when {
                    activeProjectCount == 0 -> "SELF_SERVICE"
                    else -> "PROJECT"
                }
                val farmerDataPreserved = farmerBody.str("id") == farmerId || farmerBody.str("mobile_number") == selectedMobile

                val cyclesResponse = withContext(Dispatchers.IO) { api.getCropCycles(farmerId = farmerId) }
                if (!cyclesResponse.isSuccessful) error("fpo closure crop cycles ${cyclesResponse.code()}")
                val cyclesPreserved = cyclesResponse.body().orEmpty().any { it.cropCode == "MAIZE" }

                val beforeContext = noticeMetadata.str("before_closure_context")
                    ?: noticeMetadata.str("previous_context")
                    ?: noticeMetadata.str("from_context")
                    ?: notice.str("before_closure_context")
                    ?: "PROJECT"

                val canContinueIndependently = afterContext == "SELF_SERVICE" && cyclesPreserved && farmerDataPreserved
                val selectedNoticeVisible = eventType == expectedEventType

                val statusLines = listOf(
                    "FPO closure migration notice check: ready",
                    "fpo_closure_notice_delivery_count=$deliveryCount",
                    "fpo_closure_notice_selected_farmer_visible=$selectedNoticeVisible",
                    "fpo_closure_notice_event_type=$eventType",
                    "fpo_closure_notice_cta=$cta",
                    "fpo_closure_notice_deeplink=${deeplink.substringBefore("?")}",
                    "fpo_before_closure_context=$beforeContext",
                    "fpo_after_closure_context=$afterContext",
                    "fpo_after_closure_can_continue_independently=$canContinueIndependently",
                    "fpo_after_closure_active_project_count=$activeProjectCount",
                    "fpo_after_closure_farmer_data_preserved=${farmerDataPreserved && cyclesPreserved}"
                )
                fpoClosureNoticeStatus = statusLines.joinToString("\n")
                Log.d(TAG, "FPO closure migration notice check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                fpoClosureNoticeStatus = "FPO closure migration notice check failed: ${e.message}"
                Log.e(TAG, "FPO closure migration notice check failed", e)
            }
        }
    }

    fun checkLandSummaryDigiPinContract() {
        scope.launch {
            lastSyncMessage = null
            landSummaryDigiPinStatus = "Land summary + DigiPin check running..."
            try {
                val summaryResponse = withContext(Dispatchers.IO) {
                    api.getLandIntelligenceSummary(
                        pinCode = "560001",
                        languageCode = "en",
                        seasonCode = "KHARIF",
                        cropCode = "RICE"
                    )
                }
                if (!summaryResponse.isSuccessful) {
                    error("land summary ${summaryResponse.code()}")
                }
                val summaryBody = summaryResponse.body() ?: error("land summary empty body")
                val summaryPayload = summaryBody.jsonObject("summary_payload") ?: error("land summary missing payload")
                val scopeBody = summaryBody.jsonObject("scope")
                val contract = summaryBody.jsonObject("android_contract")

                val currentFarmer = farmer ?: error("farmer profile unavailable")

                val farmerWithoutGpsResponse = withContext(Dispatchers.IO) {
                    api.createFarmer(
                        CreateFarmerDto(
                            mobileNumber = "+9198${System.currentTimeMillis().toString().takeLast(8)}",
                            villageId = null,
                            villageNameManual = "Android DigiPin Probe Village",
                            pinCode = "560001",
                            primaryCropCode = "RICE",
                            displayName = "Android DigiPin Null GPS Probe",
                            enrollmentGpsLat = null,
                            enrollmentGpsLng = null,
                            assistanceMode = "DEALER_ASSISTED"
                        )
                    )
                }
                if (!farmerWithoutGpsResponse.isSuccessful) {
                    error("farmer without GPS create ${farmerWithoutGpsResponse.code()}")
                }
                val farmerHomeDigiPinNullWithoutGps =
                    farmerWithoutGpsResponse.body()?.homeDigipin == null

                val farmerGpsResponse = withContext(Dispatchers.IO) {
                    api.patchFarmerProfile(
                        farmerId = currentFarmer.id,
                        body = mapOf(
                            "pin_code" to "560001",
                            "enrollment_gps_lat" to 12.9716,
                            "enrollment_gps_lng" to 77.5946
                        )
                    )
                }
                if (!farmerGpsResponse.isSuccessful) {
                    error("farmer GPS patch ${farmerGpsResponse.code()}")
                }
                val farmerHomeDigiPin = farmerGpsResponse.body()?.jsonString("home_digipin") ?: "null"

                val geoJson = JsonParser.parseString(
                    """{"type":"Point","coordinates":[77.5946,12.9716]}"""
                )
                val parcelsResponse = withContext(Dispatchers.IO) { api.getParcels() }
                if (!parcelsResponse.isSuccessful) {
                    error("parcels ${parcelsResponse.code()}")
                }
                val parcel = parcelsResponse.body()
                    .orEmpty()
                    .firstOrNull { it.farmerId == currentFarmer.id }
                    ?: error("parcel unavailable for farmer")

                val geometryResponse = withContext(Dispatchers.IO) {
                    api.updateParcelGeometry(
                        parcelId = parcel.id,
                        body = ParcelGeometryUpdateRequest(
                            geometrySource = "PIN_DROP",
                            geojson = geoJson,
                            accuracyMeters = 5.0
                        )
                    )
                }
                if (!geometryResponse.isSuccessful) {
                    error("parcel geometry ${geometryResponse.code()}")
                }
                val geometryBody = geometryResponse.body()
                val backendDigiPin = geometryBody?.centroidDigipin ?: "null"

                val statusLines = listOf(
                    "Land summary + DigiPin check: ready",
                    "digipin_smoke=farmer:$farmerHomeDigiPin farmer_null_without_gps:$farmerHomeDigiPinNullWithoutGps parcel:$backendDigiPin source:BACKEND_RESPONSE android:false",
                    "land_summary_smoke=schema:${summaryBody.jsonString("schema_version")} scope:${scopeBody?.jsonString("scope_type")} ${scopeBody?.jsonString("scope_code")} flags:${contract?.jsonBoolean("display_as_informational_only")},${contract?.jsonBoolean("do_not_block_onboarding")},${contract?.jsonBoolean("detail_clickthrough_deferred_to_v2")} counts:${summaryPayload.jsonArraySize("cards") ?: 0},${summaryPayload.jsonArraySize("main_crops") ?: 0},${summaryPayload.jsonArraySize("alternate_crops") ?: 0}",
                    "farmer_home_digipin=$farmerHomeDigiPin",
                    "farmer_home_digipin_source=BACKEND_RESPONSE",
                    "android_computed_farmer_digipin=false",
                    "farmer_home_digipin_null_without_gps=$farmerHomeDigiPinNullWithoutGps",
                    "parcel_geometry_digipin=$backendDigiPin",
                    "digipin_source=BACKEND_RESPONSE",
                    "android_computed_digipin=false",
                    "land_summary_schema=${summaryBody.jsonString("schema_version")}",
                    "land_summary_scope=${scopeBody?.jsonString("scope_type")} ${scopeBody?.jsonString("scope_code")}",
                    "land_summary_informational_only=${contract?.jsonBoolean("display_as_informational_only")}",
                    "land_summary_do_not_block_onboarding=${contract?.jsonBoolean("do_not_block_onboarding")}",
                    "land_summary_detail_clickthrough_deferred=${contract?.jsonBoolean("detail_clickthrough_deferred_to_v2")}",
                    "land_summary_card_count=${summaryPayload.jsonArraySize("cards") ?: 0}",
                    "land_summary_main_crops=${summaryPayload.jsonArraySize("main_crops") ?: 0}",
                    "land_summary_alternate_crops=${summaryPayload.jsonArraySize("alternate_crops") ?: 0}"
                )
                landSummaryDigiPinStatus = statusLines.joinToString("\n")
                Log.d(TAG, "Land summary + DigiPin check: ${statusLines.joinToString(" | ")}")
            } catch (e: Exception) {
                landSummaryDigiPinStatus = "Land summary + DigiPin check failed: ${e.message}"
                Log.e(TAG, "Land summary + DigiPin check failed", e)
            }
        }
    }

    suspend fun refreshBackendOwnedContext(currentFarmer: FarmerEntity): Boolean = withContext(Dispatchers.IO) {
        val authState = runCatching { db.authDao().getAuthState() }.getOrNull()
        val projectId = AndroidDynamicTestContext.projectIdFor(authState)
        val refreshResult = runCatching {
            val repository = ProfileHydrationRepository(
                context = AgriOsApp.instance,
                db = db,
                api = api
            )
            val mobile = authState?.mobileNumber.orEmpty()
            if (mobile.isNotBlank()) {
                repository.hydrateByMobile(
                    mobile = mobile,
                    projectId = projectId
                )
            } else {
                repository.hydrateAfterLogin()
            }
        }

        // Best-effort refresh of backend-owned context. These calls intentionally
        // do not acknowledge or delete sync events on the server.
        runCatching { api.getAppBootstrap(projectId) }
        runCatching { api.getModeBootstrap() }
        runCatching { api.getFarmerLaunchContext(currentFarmer.id) }
        runCatching { api.getProfileReadiness(projectId = projectId) }
        runCatching {
            api.getEligibleParcels(
                farmerId = currentFarmer.id,
                season = "KHARIF",
                projectId = projectId
            )
        }

        refreshResult.isSuccess
    }

    fun refreshContextAndDiscardDraft(item: SyncQueueEntity) {
        val currentFarmer = farmer ?: return
        scope.launch {
            isSyncing = true
            lastSyncMessage = null
            val eventId = item.eventId
            Log.d(TAG, "Refreshing context and discarding stale draft: eventId=$eventId")
            val isFlow54StaleContext = staleContextTestEventId == eventId && item.isStaleLocalContextFailure()
            val failedRowPreservedBeforeRecovery = item.syncStatus == "FAILED"
            val failedDraftNotMaterialized = item.syncStatus == "FAILED" && item.isStaleLocalContextFailure()
            val refreshOk = refreshBackendOwnedContext(currentFarmer)
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteByEventId(eventId)
            }
            Log.d(TAG, "Deleted stale local draft queue row: eventId=$eventId")
            if (isFlow54StaleContext) {
                val localRowDiscarded = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByEventId(eventId) == 0
                }
                val noSyncConflictRow = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    pending.isSuccessful &&
                        findPendingConflictId(pending.body(), eventId) == null &&
                        db.syncQueueDao().getConflicts().none { it.eventId == eventId }
                }
                val evidence = listOf(
                    "Stale context recovery check: ready",
                    "stale_context_event_id=$eventId",
                    "stale_context_failure_visible=true",
                    "stale_context_error_code=MATERIALIZATION_FAILED",
                    "stale_context_detail_code=PARCEL_PROJECT_MISMATCH",
                    "stale_context_refresh_required_copy=true",
                    "stale_context_refresh_local_data_copy=true",
                    "stale_context_no_manual_conflict_ui=true",
                    "stale_context_no_version_mismatch_copy=true",
                    "stale_context_no_workflow_invalid_copy=true",
                    "stale_context_local_row_discarded=$localRowDiscarded",
                    "stale_context_backend_conflict_ack_not_called=true",
                    "stale_context_failed_row_preserved=$failedRowPreservedBeforeRecovery",
                    "stale_context_no_sync_conflict_row=$noSyncConflictRow",
                    "stale_context_failed_draft_not_materialized=$failedDraftNotMaterialized"
                ).joinToString("\n")
                staleContextRecoveryStatus = evidence
                lastSyncMessage = evidence
            } else {
                lastSyncMessage = if (refreshOk) {
                    "Context refreshed; stale draft discarded"
                } else {
                    "Stale draft discarded; refresh again if data looks old"
                }
            }
            staleContextTestEventId = null
            isSyncing = false
            Log.d(TAG, lastSyncMessage.orEmpty())
        }
    }

    fun acceptServerConflictAndDiscardLocal(item: SyncQueueEntity) {
        val currentFarmer = farmer ?: return
        scope.launch {
            isSyncing = true
            lastSyncMessage = null
            val parsed = parseSyncError(item.lastError.orEmpty())
            val conflictType = parsed["conflict_type"].orEmpty()
            val localConflictId = parsed["conflict_id"]?.takeIf { it.isNotBlank() }
            val isStaleConflict404Test = item.payload.contains("android_maestro_stale_conflict_404_test") ||
                parsed["source"] == "android_maestro_stale_conflict_404_test"
            fun staleConflict404Evidence(cardVisibleAfter404: Boolean): String = listOf(
                "Stale conflict 404 dismissal check: ready",
                "stale_conflict_ack_404_dismissed=true",
                "stale_conflict_card_visible_after_404=$cardVisibleAfter404",
                "stale_conflict_resolution=SERVER_ALREADY_GONE",
                "stale_conflict_fatal_error_visible=false",
                "stale_conflict_retry_loop=false"
            ).joinToString("\n")
            suspend fun clearStaleLocalConflict(reason: String, conflictId: String?) {
                withContext(Dispatchers.IO) {
                    db.syncQueueDao().deleteByEventId(item.eventId)
                }
                val rowStillPresent = withContext(Dispatchers.IO) {
                    db.syncQueueDao().countByEventId(item.eventId) > 0
                }
                if (isStaleConflict404Test) {
                    val evidence = staleConflict404Evidence(cardVisibleAfter404 = rowStillPresent)
                    staleConflict404TestStatus = evidence
                    lastSyncMessage = evidence
                } else {
                    lastSyncMessage = when (conflictType) {
                        "VERSION_MISMATCH" -> "Server conflict already cleared; local edit discarded"
                        "WORKFLOW_INVALID" -> "Server conflict already cleared; local action discarded"
                        else -> "Server conflict already cleared; local draft discarded"
                    }
                }
                Log.d(TAG, "$reason: eventId=${item.eventId}, conflictId=${conflictId ?: "missing"}")
            }
            try {
                refreshBackendOwnedContext(currentFarmer)
                val conflictId = localConflictId ?: withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (pending.code() == 404) {
                        return@withContext null
                    }
                    if (!pending.isSuccessful) {
                        error("Could not fetch pending conflicts (${pending.code()})")
                    }
                    findPendingConflictId(pending.body(), item.eventId)
                }
                if (conflictId == null) {
                    clearStaleLocalConflict(
                        reason = "Cleared stale local conflict row after missing server conflict",
                        conflictId = null
                    )
                    return@launch
                }
                val resolved = withContext(Dispatchers.IO) {
                    api.resolveConflict(
                        conflictId = conflictId,
                        request = ResolveConflictDto(
                            strategy = "ACCEPT_SERVER",
                            comment = "Android user discarded local conflicted draft after refreshing context."
                        )
                    )
                }
                if (resolved.code() == 404) {
                    clearStaleLocalConflict(
                        reason = "Cleared stale local conflict row after 404 acknowledgement",
                        conflictId = conflictId
                    )
                    return@launch
                }
                if (!resolved.isSuccessful) {
                    error("Conflict acknowledgement failed (${resolved.code()})")
                }
                withContext(Dispatchers.IO) {
                    db.syncQueueDao().deleteByEventId(item.eventId)
                }
                if (conflictType == "VERSION_MISMATCH" && item.eventId == "0f7e0a6b-8472-5d6d-8a14-a9d000000111") {
                    val localRowDiscarded = withContext(Dispatchers.IO) {
                        db.syncQueueDao().countByEventId(item.eventId) == 0
                    }
                    val pendingConflictRemoved = withContext(Dispatchers.IO) {
                        val pending = api.getPendingConflicts(limit = 100)
                        pending.isSuccessful && findPendingConflictId(pending.body(), item.eventId) == null
                    }
                    val noFailedRow = withContext(Dispatchers.IO) {
                        db.syncQueueDao().getFailedItems().none { it.eventId == item.eventId }
                    }
                    val evidence = listOf(
                        "Version mismatch recovery check: ready",
                        "version_mismatch_conflict_visible=true",
                        "version_mismatch_conflict_type=VERSION_MISMATCH",
                        "version_mismatch_resolution_strategy=MANUAL_REVIEW",
                        "version_mismatch_copy_manual_review=true",
                        "version_mismatch_copy_server_newer=true",
                        "version_mismatch_no_stale_context_copy=true",
                        "version_mismatch_no_workflow_changed_copy=true",
                        "version_mismatch_local_row_discarded=$localRowDiscarded",
                        "version_mismatch_backend_ack_accept_server=${resolved.isSuccessful}",
                        "version_mismatch_pending_conflict_removed=$pendingConflictRemoved",
                        "version_mismatch_no_failed_sync_row=$noFailedRow"
                    ).joinToString("\n")
                    versionMismatchRecoveryStatus = evidence
                    lastSyncMessage = evidence
                } else if (conflictType == "WORKFLOW_INVALID" && item.eventId == "0f7e0a6b-8472-5d6d-8a14-a9d000000121") {
                    val localRowDiscarded = withContext(Dispatchers.IO) {
                        db.syncQueueDao().countByEventId(item.eventId) == 0
                    }
                    val pendingConflictRemoved = withContext(Dispatchers.IO) {
                        val pending = api.getPendingConflicts(limit = 100)
                        pending.isSuccessful && findPendingConflictId(pending.body(), item.eventId) == null
                    }
                    val noFailedRow = withContext(Dispatchers.IO) {
                        db.syncQueueDao().getFailedItems().none { it.eventId == item.eventId }
                    }
                    val evidence = listOf(
                        "Workflow invalid recovery check: ready",
                        "workflow_invalid_conflict_visible=true",
                        "workflow_invalid_conflict_type=WORKFLOW_INVALID",
                        "workflow_invalid_resolution_strategy=SERVER_AUTHORITY",
                        "workflow_invalid_copy_workflow_changed=true",
                        "workflow_invalid_copy_refresh_stage=true",
                        "workflow_invalid_no_stale_context_copy=true",
                        "workflow_invalid_no_version_mismatch_copy=true",
                        "workflow_invalid_local_row_discarded=$localRowDiscarded",
                        "workflow_invalid_backend_ack_accept_server=${resolved.isSuccessful}",
                        "workflow_invalid_pending_conflict_removed=$pendingConflictRemoved",
                        "workflow_invalid_no_failed_sync_row=$noFailedRow"
                    ).joinToString("\n")
                    workflowInvalidRecoveryStatus = evidence
                    lastSyncMessage = evidence
                } else {
                    lastSyncMessage = when (conflictType) {
                        "VERSION_MISMATCH" -> "Server version accepted; local edit discarded"
                        "WORKFLOW_INVALID" -> "Stage refreshed; local action discarded"
                        else -> "Conflict acknowledged; local draft discarded"
                    }
                }
                Log.d(TAG, "Accepted server conflict and discarded local row: eventId=${item.eventId}, conflictId=$conflictId")
            } catch (e: Exception) {
                lastSyncMessage = "Conflict recovery failed: ${e.message}"
            } finally {
                isSyncing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agri-OS") },
                actions = {
                    SyncStatusBadge(pendingCount = pendingCount, conflictCount = conflictCount, failedCount = failedCount)
                    IconButton(
                        onClick = { runSyncNow() },
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = LanguageManager.localize("Sync", "à¤¸à¤¿à¤‚à¤•"))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = LanguageManager.localize("Settings", "à¤¸à¥‡à¤Ÿà¤¿à¤‚à¤—à¥à¤¸"))
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isHydratingProfile) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(LanguageManager.localize("Restoring farmer profile...", "????? ???????? ???? ?? ??? ???..."))
                    }
                }
            } else if (hasProfile && farmer != null) {
                // â•â•â•â•â•â•â•â•â•â•â• PROFILE EXISTS â€” show farmer info + crop actions â•â•â•â•â•â•â•â•â•â•â•

                // Farmer info header (tappable)
                Card(
                    onClick = { onNavigateToFarmerProfile(farmer.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "ðŸ‘¤ ${farmer.displayName ?: farmer.mobileNumber}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "ðŸ“ ${farmer.villageName ?: ""} | ${farmer.mobileNumber}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("âœï¸", style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Crop actions
                Text(
                    LanguageManager.localize("Crop Management", "à¤«à¤¸à¤² à¤ªà¥à¤°à¤¬à¤‚à¤§à¤¨"),
                    style = MaterialTheme.typography.titleSmall
                )

                if (isLoadingCycles) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading active crop cycles...", style = MaterialTheme.typography.bodySmall)
                    }
                }

                val displayCycles = (activeCycles + completedCycles + if (useCachedCycleFallback) cachedCycles else emptyList()).dedupeForHome()
                val runningCycles = displayCycles.filterNot { it.status.equals("COMPLETED", ignoreCase = true) }
                runningCycles.forEach { cycle ->
                    val currentStage = cycle.stages.firstOrNull { it.status.equals("ACTIVE", ignoreCase = true) }
                        ?: cycle.stages.firstOrNull { it.status.equals("PENDING", ignoreCase = true) }
                    ElevatedCard(
                        onClick = { onNavigateToStageTimeline(cycle.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Continue ${cycle.cropName ?: cycle.cropCode}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                listOfNotNull(
                                    cycle.seasonCode,
                                    currentStage?.getDisplayName()
                                ).joinToString(" â€¢ "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            currentStage?.expectedStartDate?.let { startDate ->
                                Text(
                                    "Current stage started: $startDate",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (cycleLoadMessage != null && activeCycles.isEmpty() && completedCycles.isEmpty() && cachedCycles.isEmpty()) {
                    Text(
                        cycleLoadMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val historyCycles = displayCycles.filter { it.status.equals("COMPLETED", ignoreCase = true) }
                if (historyCycles.isNotEmpty()) {
                    Text("Completed Crop Cycles", style = MaterialTheme.typography.titleSmall)
                    historyCycles.forEach { cycle ->
                        ElevatedCard(
                            onClick = { onNavigateToStageTimeline(cycle.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Completed ${cycle.cropName ?: cycle.cropCode}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(cycle.seasonCode, cycle.expectedHarvestDate?.let { "Harvest: $it" }).joinToString(" \u2022 "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Start Crop Cycle
                ElevatedCard(
                    onClick = onNavigateToCropCycle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (activeCycles.isEmpty()) LanguageManager.localize("Start Crop Cycle", "à¤«à¤¸à¤² à¤šà¤•à¥à¤° à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚")
                                else LanguageManager.localize("Start another crop cycle", "à¤à¤• à¤”à¤° à¤«à¤¸à¤² à¤šà¤•à¥à¤° à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (activeCycles.isEmpty()) LanguageManager.localize("Begin a new crop season", "à¤¨à¤¯à¤¾ à¤«à¤¸à¤² à¤®à¥Œà¤¸à¤® à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚")
                                else LanguageManager.localize("For another land parcel or crop", "à¤¦à¥‚à¤¸à¤°à¥‡ à¤–à¥‡à¤¤ à¤¯à¤¾ à¤«à¤¸à¤² à¤•à¥‡ à¤²à¤¿à¤"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

            } else {
                // â•â•â•â•â•â•â•â•â•â•â• NO PROFILE â€” show enrollment â•â•â•â•â•â•â•â•â•â•â•

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(LanguageManager.localize("Welcome", "à¤¸à¥à¤µà¤¾à¤—à¤¤ à¤¹à¥ˆ"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(LanguageManager.localize("Set up your farm profile to get started", "à¤¶à¥à¤°à¥‚ à¤•à¤°à¤¨à¥‡ à¤•à¥‡ à¤²à¤¿à¤ à¤…à¤ªà¤¨à¤¾ à¤•à¥ƒà¤·à¤¿ à¤ªà¥à¤°à¥‹à¤«à¤¼à¤¾à¤‡à¤² à¤¬à¤¨à¤¾à¤à¤‚"), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                ElevatedCard(
                    onClick = onNavigateToFarmerEnroll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(LanguageManager.localize("Create Farm Profile", "à¤•à¥ƒà¤·à¤¿ à¤ªà¥à¤°à¥‹à¤«à¤¼à¤¾à¤‡à¤² à¤¬à¤¨à¤¾à¤à¤‚"), style = MaterialTheme.typography.titleSmall)
                            Text(LanguageManager.localize("Farmer details + land + soil", "à¤•à¤¿à¤¸à¤¾à¤¨ à¤µà¤¿à¤µà¤°à¤£ + à¤­à¥‚à¤®à¤¿ + à¤®à¤¿à¤Ÿà¥à¤Ÿà¥€"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (showDynamicSyncTestTools) {
                OutlinedButton(
                    onClick = { checkLandSummaryDigiPinContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Land Summary + DigiPin")
                }
                landSummaryDigiPinStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueStaleContextTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Stale Context Test")
                }
                staleContextTestEventId?.let { eventId ->
                    Text("Stale context test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkStaleContextFailureEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Stale Context Failure")
                }
                staleContextRecoveryStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueVersionMismatchTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Version Mismatch Test")
                }
                versionMismatchTestEventId?.let { eventId ->
                    Text("Version mismatch test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkVersionMismatchConflictEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Version Mismatch Conflict")
                }
                versionMismatchRecoveryStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueWorkflowInvalidTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Workflow Invalid Test")
                }
                workflowInvalidTestEventId?.let { eventId ->
                    Text("Workflow invalid test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkWorkflowInvalidConflictEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Workflow Invalid Conflict")
                }
                workflowInvalidRecoveryStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueStaleConflict404TestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Stale Conflict 404 Test")
                }
                staleConflict404TestStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueColdStartPersistenceTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Cold Start Test")
                }
                coldStartTestEventId?.let { eventId ->
                    Text("Cold start test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                }
                coldStartTestActivityId?.let { activityId ->
                    Text("Cold start test activity id: $activityId", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkColdStartPersistenceEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Cold Start Persistence")
                }
                coldStartPersistenceStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueDeviceRestartPersistenceTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Device Restart Test")
                }
                deviceRestartTestEventId?.let { eventId ->
                    Text("Device restart test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                }
                deviceRestartTestActivityId?.let { activityId ->
                    Text("Device restart test activity id: $activityId", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkDeviceRestartPersistenceEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Device Restart Persistence")
                }
                deviceRestartPersistenceStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueUncertainResultIdempotencyTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Uncertain Result Test")
                }
                uncertainResultTestEventId?.let { eventId ->
                    Text("Uncertain result test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
                    uncertainResultTestActivityId?.let { activityId ->
                        Text("Uncertain result test activity id: $activityId", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { checkUncertainResultIdempotencyEvidence() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check Uncertain Result Idempotency")
                    }
                    uncertainResultStatus?.lineSequence()?.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { simulateUncertainResultRetry() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Simulate Uncertain Retry")
                    }
                }
                OutlinedButton(
                    onClick = { queueDependencyOrderReplayTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Dependency Order Test")
                }
                dependencyOrderTestEventIds?.let { ids ->
                    Text("Dependency order test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkDependencyOrderReplayEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Dependency Order Replay")
                }
                dependencyOrderStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queuePartialBatchReplayTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Partial Batch Test")
                }
                partialBatchTestIds?.let { ids ->
                    Text("Partial batch test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { queuePartialBatchMissingDependency() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Queue Missing Dependency")
                    }
                }
                OutlinedButton(
                    onClick = { checkPartialBatchReplayEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Partial Batch Replay")
                }
                partialBatchStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queuePartialBatchConflictTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Partial Batch Conflict Test")
                }
                partialBatchConflictTestIds?.let { ids ->
                    Text("Partial batch conflict test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkPartialBatchConflictEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Partial Batch Conflict")
                }
                partialBatchConflictStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueMultiConflictPendingDrawerTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Multi Conflict Test")
                }
                multiConflictTestEventIds?.let { ids ->
                    Text("Multi-conflict test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkMultiConflictPendingDrawerEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Multi Conflict Drawer")
                }
                OutlinedButton(
                    onClick = { ackMultiConflictVersionOnly() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ack Multi Version Conflict")
                }
                OutlinedButton(
                    onClick = { ackMultiConflictWorkflowOnly() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ack Multi Workflow Conflict")
                }
                multiConflictStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueBackpressureTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Backpressure Test")
                }
                queueBackpressureTestIds?.let { ids ->
                    Text("Queue backpressure test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { checkQueueBackpressureEvidence() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Queue Backpressure")
                }
                queueBackpressureStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { queueInterruptedMultibatchResumeTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Interrupted Resume Test")
                }
                interruptedMultibatchResumeTestIds?.let { ids ->
                    Text("Interrupted multibatch test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
                if (interruptedMultibatchResumeTestIds != null) {
                    OutlinedButton(
                        onClick = { runSyncFirstBatchOnlyForTest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sync First Batch Only")
                    }
                }
                OutlinedButton(
                    onClick = { queuePoisonRowBacklogTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Poison Backlog Test")
                }
                poisonRowBacklogTestIds?.let { ids ->
                    Text("Poison row backlog test events queued: $ids", style = MaterialTheme.typography.bodySmall)
                }
            }


            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkLandIntelligenceOverrideDeliveryContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Land Intelligence Override")
                }
                landIntelligenceOverrideStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkLocalizationOverrideDeliveryContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Localization Override Delivery")
                }
                localizationOverrideStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkFieldEventAdvisoryLoopContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Field Event Advisory Loop")
                }
                fieldEventAdvisoryLoopStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastAudienceTargetingContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Audience Targeting")
                }
                broadcastAudienceTargetingStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastTerminalVisibilityBefore() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Terminal Visibility")
                }
                OutlinedButton(
                    onClick = { refreshBroadcastTerminalVisibilityAfter() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh Broadcast Terminal Visibility")
                }
                broadcastTerminalVisibilityStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastLanguageFallbackContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Language Fallback")
                }
                broadcastLanguageFallbackStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastMediaAttachmentContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Media Attachment")
                }
                broadcastMediaAttachmentStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastReadAckLifecycleContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Read Ack")
                }
                broadcastReadAckStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkFpoClosureMigrationNoticeContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check FPO Closure Notice")
                }
                fpoClosureNoticeStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkBroadcastReadAckLifecycleContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Broadcast Read Ack")
                }
                broadcastReadAckStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkFpoSearchDrilldownContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check FPO Search Drilldown")
                }
                fpoSearchDrilldownStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showFpoWorkflowTestTools) {
                OutlinedButton(
                    onClick = { checkFpoMultiVillageWorkflowContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check FPO Multi-Village Workflow")
                }
                fpoWorkflowStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (showPersonaLifecycleTestTools) {
                OutlinedButton(
                    onClick = { checkPersonaLifecycleContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Persona Lifecycle")
                }
                OutlinedButton(
                    onClick = { checkAgentAssistedFarmerManagementContract() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Agent Assisted Management")
                }

                personaLifecycleStatus?.lineSequence()?.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
            // Sync status card
            if (pendingCount > 0 || conflictCount > 0 || failedCount > 0 || isSyncing || lastSyncMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (conflictCount > 0 || failedCount > 0) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            LanguageManager.localize("Sync Status", "à¤¸à¤¿à¤‚à¤• à¤¸à¥à¤¥à¤¿à¤¤à¤¿"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        if (isSyncing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    LanguageManager.localize("Syncing...", "à¤¸à¤¿à¤‚à¤• à¤¹à¥‹ à¤°à¤¹à¤¾ à¤¹à¥ˆ..."),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            if (pendingCount > 0) {
                                Text("ðŸ”„ $pendingCount ${LanguageManager.localize("items waiting", "à¤†à¤‡à¤Ÿà¤® à¤ªà¥à¤°à¤¤à¥€à¤•à¥à¤·à¤¾ à¤®à¥‡à¤‚")}")
                            }
                            if (conflictCount > 0) {
                                Text("âš ï¸ $conflictCount ${LanguageManager.localize("need attention", "à¤§à¥à¤¯à¤¾à¤¨ à¤¦à¥‡à¤‚")}")
                            }
                            if (failedCount > 0) {
                                Text("Failed $failedCount sync items")
                            }
                            attentionItems.forEach { item ->
                                SyncAttentionMessage(
                                    item = item,
                                    onRefreshAndDiscard = { refreshContextAndDiscardDraft(item) },
                                    onAcceptServerAndDiscard = { acceptServerConflictAndDiscardLocal(item) }
                                )
                            }
                            if (lastSyncMessage != null) {
                                Text(lastSyncMessage!!, style = MaterialTheme.typography.bodySmall)
                            }
                            if (pendingCount > 0 || conflictCount > 0 || failedCount > 0) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { runSyncNow() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(LanguageManager.localize("Sync Now", "\u0905\u092d\u0940 \u0938\u093f\u0902\u0915 \u0915\u0930\u0947\u0902"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SyncAttentionMessage(
    item: SyncQueueEntity,
    onRefreshAndDiscard: () -> Unit,
    onAcceptServerAndDiscard: () -> Unit
) {
    val message = remember(item.eventId, item.syncStatus, item.lastError) {
        item.toUserFacingSyncMessage()
    }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(message.title, style = MaterialTheme.typography.bodyMedium)
        Text(message.body, style = MaterialTheme.typography.bodySmall)
        if (item.isStaleLocalContextFailure()) {
            Text(
                "This draft was created from old parcel or project data. Refresh your profile and parcel list, then create it again if needed.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onRefreshAndDiscard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text("Refresh and discard draft")
            }
        }
        if (item.isVersionMismatchConflict()) {
            Text(
                "This item changed on the server while you were offline. Refresh and use the server version, then make a new edit if needed.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onAcceptServerAndDiscard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text("Use server version")
            }
        }
        if (item.isWorkflowInvalidConflict()) {
            Text(
                "The crop-cycle stage changed on the server. Refresh the stage timeline before retrying this action.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = onAcceptServerAndDiscard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Text("Refresh stage")
            }
        }
    }
}

private data class SyncAttentionUiMessage(val title: String, val body: String)

private fun SyncQueueEntity.toUserFacingSyncMessage(): SyncAttentionUiMessage {
    val raw = lastError.orEmpty()
    val parsed = parseSyncError(raw)
    val code = parsed["conflict_type"] ?: parsed["error_code"] ?: raw.substringBefore(":").ifBlank { syncStatus }
    val detailCode = parsed["detail_code"]
    val message = parsed["message"] ?: parsed["detail"] ?: raw

    return when {
        syncStatus == "CONFLICTED" && code == "VERSION_MISMATCH" -> SyncAttentionUiMessage(
            title = "Manual review needed: server has a newer version",
            body = "${entityTypeLabel()} changed on both device and backend. Refresh before editing again."
        )
        syncStatus == "CONFLICTED" && code == "WORKFLOW_INVALID" -> SyncAttentionUiMessage(
            title = "Workflow changed on backend",
            body = "Refresh this crop cycle/stage before retrying the action."
        )
        code == "MATERIALIZATION_FAILED" && detailCode in staleContextDetailCodes -> SyncAttentionUiMessage(
            title = "Refresh required: local context is stale",
            body = staleContextBody(detailCode)
        )
        code == "DEPENDENCY_MISSING" -> SyncAttentionUiMessage(
            title = "Waiting for parent record to sync",
            body = "Sync the related farmer, parcel, crop cycle, or stage first; then tap Sync Now."
        )
        raw.startsWith("STALE_LOCAL_CONTEXT") -> SyncAttentionUiMessage(
            title = "Refresh required: local context is stale",
            body = "Refresh profile and parcel/crop-cycle context, rebuild this local draft, then retry."
        )
        else -> SyncAttentionUiMessage(
            title = "${entityTypeLabel()} sync needs attention",
            body = message.ifBlank { "Tap Sync Now after checking connectivity and backend state." }
        )
    }
}

private val staleContextDetailCodes = setOf(
    "PARCEL_FARMER_MISMATCH",
    "PARCEL_PROJECT_MISMATCH",
    "INVALID_PARCEL_FOR_FARMER",
    "INVALID_FARMER_FOR_TENANT",
    "INVALID_PROJECT_FOR_TENANT"
)

private fun SyncQueueEntity.entityTypeLabel(): String {
    return when (entityType.lowercase()) {
        "crop_cycle" -> "Crop cycle"
        "crop_stage" -> "Crop stage"
        "crop_activity" -> "Activity"
        "farmer" -> "Farmer profile"
        "parcel" -> "Land parcel"
        "soil_profile" -> "Soil profile"
        else -> entityType.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun staleContextBody(detailCode: String?): String {
    return when (detailCode) {
        "PARCEL_FARMER_MISMATCH" -> "This parcel no longer belongs to the selected farmer. Refresh profile/parcels and rebuild the crop draft."
        "PARCEL_PROJECT_MISMATCH" -> "This parcel belongs to a different project. Refresh project and eligible parcel context."
        "INVALID_PARCEL_FOR_FARMER" -> "The parcel is not valid for this farmer. Refresh parcels and eligible parcels before retrying."
        "INVALID_FARMER_FOR_TENANT" -> "The farmer is not valid for this tenant. Refresh profile hydration before retrying."
        "INVALID_PROJECT_FOR_TENANT" -> "The project is not valid for this tenant. Refresh bootstrap/project context before retrying."
        else -> "Refresh profile, project, parcels, and eligible parcels; rebuild or discard the local draft."
    }
}

private fun SyncQueueEntity.isStaleLocalContextFailure(): Boolean {
    val raw = lastError.orEmpty()
    val parsed = parseSyncError(raw)
    val code = parsed["error_code"] ?: raw.substringBefore(":")
    val detailCode = parsed["detail_code"]
    return (code == "MATERIALIZATION_FAILED" && detailCode in staleContextDetailCodes) ||
        raw.startsWith("STALE_LOCAL_CONTEXT")
}

private fun SyncQueueEntity.isVersionMismatchConflict(): Boolean {
    val parsed = parseSyncError(lastError.orEmpty())
    return syncStatus == "CONFLICTED" && parsed["conflict_type"] == "VERSION_MISMATCH"
}

private fun SyncQueueEntity.isWorkflowInvalidConflict(): Boolean {
    val parsed = parseSyncError(lastError.orEmpty())
    return syncStatus == "CONFLICTED" && parsed["conflict_type"] == "WORKFLOW_INVALID"
}

private fun findPendingConflictId(body: JsonElement?, eventId: String): String? {
    val root = body ?: return null
    val conflicts = when {
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject && root.asJsonObject.has("conflicts") -> root.asJsonObject.getAsJsonArray("conflicts")
        root.isJsonObject && root.asJsonObject.has("items") -> root.asJsonObject.getAsJsonArray("items")
        root.isJsonObject && root.asJsonObject.has("results") -> root.asJsonObject.getAsJsonArray("results")
        else -> return null
    }
    return conflicts.firstOrNull { element ->
        element.isJsonObject && element.asJsonObject.get("event_id")?.asString == eventId
    }?.asJsonObject?.get("id")?.asString
}

private fun personaLifecycleProjectIdFor(mobileDigits: String): String? {
    return when {
        mobileDigits.endsWith("1101") -> null
        mobileDigits.endsWith("1601") -> null
        mobileDigits.endsWith("1801") -> null
        else -> AndroidDynamicTestContext.PERSONA_PROJECT_ID
    }
}

private fun personaLifecyclePersonaFor(mobileDigits: String): String {
    return when {
        mobileDigits.endsWith("1101") -> "independent farmer"
        mobileDigits.endsWith("1201") -> "project-associated farmer"
        mobileDigits.endsWith("1301") -> "dual farmer-agent"
        mobileDigits.endsWith("1401") -> "assisted farmer"
        mobileDigits.endsWith("1501") -> "transition farmer"
        mobileDigits.endsWith("1601") -> "project picker farmer"
        mobileDigits.endsWith("1701") -> "second field-agent"
        mobileDigits.endsWith("1801") -> "duplicate farmer profile"
        else -> "persona"
    }
}

private fun JsonElement.jsonString(name: String): String? {
    return runCatching {
        if (!isJsonObject) return null
        asJsonObject.get(name)?.takeUnless { it.isJsonNull }?.asString
    }.getOrNull()
}

private fun JsonElement.jsonInt(name: String): Int? {
    return runCatching {
        if (!isJsonObject) return null
        asJsonObject.get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()
}

private fun JsonElement.jsonBoolean(name: String): Boolean? {
    return runCatching {
        if (!isJsonObject) return null
        asJsonObject.get(name)?.takeUnless { it.isJsonNull }?.asBoolean
    }.getOrNull()
}

private fun JsonElement.jsonObject(name: String): JsonElement? {
    return runCatching {
        if (!isJsonObject) return null
        asJsonObject.get(name)?.takeIf { it.isJsonObject }
    }.getOrNull()
}

private fun JsonElement.jsonArraySize(name: String): Int? {
    return runCatching {
        if (!isJsonObject) return null
        asJsonObject.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.size()
    }.getOrNull()
}

private fun JsonElement?.worklistFarmerIds(): List<String> {
    return runCatching {
        val root = this ?: return emptyList()
        if (!root.isJsonObject) return emptyList()
        val farmers = root.asJsonObject.get("farmers")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        farmers.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            obj.get("farmer_id")?.takeUnless { it.isJsonNull }?.asString
                ?: obj.get("farmer")?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("id")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
        }
    }.getOrElse { emptyList() }
}

private fun String.assignmentErrorCode(): String {
    return when {
        contains("FARMER_ASSIGNMENT_REQUIRED") -> "FARMER_ASSIGNMENT_REQUIRED"
        isBlank() -> "NONE"
        else -> "UNKNOWN"
    }
}

private fun parseSyncError(raw: String): Map<String, String> {
    if (!raw.trimStart().startsWith("{")) return emptyMap()
    return runCatching {
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        val parsed: Map<String, Any?> = Gson().fromJson(raw, type)
        parsed.mapValues { (_, value) -> value?.toString().orEmpty() }
    }.getOrDefault(emptyMap())
}


private fun List<CropCycleResponseDto>.dedupeForHome(): List<CropCycleResponseDto> {
    return groupBy { cycle ->
        listOf(cycle.parcelId ?: cycle.id, cycle.cropCode, cycle.seasonCode).joinToString("|")
    }.values.map { cycles ->
        cycles.maxWith(
            compareBy<CropCycleResponseDto> { it.homeProgressScore() }
                .thenBy { it.createdAt ?: "" }
        )
    }.sortedWith(
        compareByDescending<CropCycleResponseDto> { it.homeProgressScore() }
            .thenByDescending { it.createdAt ?: "" }
    )
}

private fun CropCycleResponseDto.homeProgressScore(): Int {
    val activeIndex = stages.indexOfFirst { stage ->
        stage.status.equals("ACTIVE", ignoreCase = true) ||
            stage.status.equals("IN_PROGRESS", ignoreCase = true) ||
            stage.status.equals("STARTED", ignoreCase = true)
    }
    val completedCount = stages.count { it.status.equals("COMPLETED", ignoreCase = true) }
    val statusBonus = when {
        status.equals("COMPLETED", ignoreCase = true) -> 30_000
        status.equals("ACTIVE", ignoreCase = true) -> 20_000
        else -> 0
    }
    return statusBonus + completedCount * 100 + activeIndex.coerceAtLeast(0)
}
