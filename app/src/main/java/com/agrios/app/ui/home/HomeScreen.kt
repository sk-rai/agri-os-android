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
import com.agrios.app.data.local.entity.SyncQueueEntity
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.remote.dto.ResolveConflictDto
import com.agrios.app.data.repository.OfflineCropSyncRepository
import com.agrios.app.data.repository.ProfileHydrationRepository
import com.agrios.app.ui.components.SyncStatusBadge
import com.google.gson.Gson
import com.google.gson.JsonElement
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
    var versionMismatchTestEventId by remember { mutableStateOf<String?>(null) }
    var workflowInvalidTestEventId by remember { mutableStateOf<String?>(null) }
    var coldStartTestEventId by remember { mutableStateOf<String?>(null) }
    var deviceRestartTestEventId by remember { mutableStateOf<String?>(null) }
    var uncertainResultTestEventId by remember { mutableStateOf<String?>(null) }
    var dependencyOrderTestEventIds by remember { mutableStateOf<String?>(null) }
    var partialBatchTestIds by remember { mutableStateOf<String?>(null) }
    var partialBatchConflictTestIds by remember { mutableStateOf<String?>(null) }
    var multiConflictTestEventIds by remember { mutableStateOf<String?>(null) }
    var queueBackpressureTestIds by remember { mutableStateOf<String?>(null) }
    val showDynamicSyncTestTools = (AgriOsApp.instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && farmer?.mobileNumber
        ?.filter { it.isDigit() }
        ?.let { digits -> digits == "919900000002" || digits == "9900000002" } == true

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
        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
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
                    result.accepted > 0 -> "✅ ${result.accepted} ${LanguageManager.localize("synced", "सिंक हुए")}"
                    result.failed > 0 -> "❌ ${result.failed} ${LanguageManager.localize("failed", "विफल")}"
                    result.conflicts > 0 -> "⚠️ ${result.conflicts} ${LanguageManager.localize("conflicts", "विरोध")}"
                    pendingCount > 0 -> LanguageManager.localize(
                        "Still waiting - parent records/dependencies may need to sync first",
                        "अभी प्रतीक्षा में - पहले मूल रिकॉर्ड/निर्भरताएं सिंक हो सकती हैं"
                    )
                    else -> LanguageManager.localize("All synced", "सब सिंक हो गया")
                }
            } catch (e: Exception) {
                lastSyncMessage = "❌ ${e.message}"
            } finally {
                isSyncing = false
            }
        }
    }

    fun queueStaleContextTestEvent() {
        val farmerId = farmer?.id ?: return
        scope.launch {
            val eventId = UUID.randomUUID().toString()
            val cropCycleId = UUID.randomUUID().toString()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val payload = linkedMapOf<String, Any?>(
                "farmer_id" to farmerId,
                "parcel_id" to "98c1a0fa-4f5f-4b8c-97ae-d84992db1c44",
                "project_id" to AndroidDynamicTestContext.PROJECT_ID,
                "crop_code" to "RICE",
                "season_code" to "KHARIF",
                "planned_sowing_date" to today,
                "status" to "PLANNED"
            )
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteDynamicSyncTestRows()
                OfflineCropSyncRepository.enqueueCropCycleCreate(
                    syncQueueDao = db.syncQueueDao(),
                    cropCycleId = cropCycleId,
                    payload = payload,
                    eventId = eventId,
                    metadata = mapOf("android_flow" to "stale_context_test")
                )
            }
            staleContextTestEventId = eventId
            lastSyncMessage = "Stale context test event queued: $eventId"
            Log.d(TAG, "Stale context test event queued: eventId=$eventId, cycleId=$cropCycleId")
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
            workflowInvalidTestEventId = eventId
            lastSyncMessage = "Workflow invalid test event queued: $eventId"
            Log.d(TAG, "Workflow invalid test event queued: eventId=$eventId, stageEntityId=$stageEntityId")
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
            lastSyncMessage = "Cold start test event queued: $eventId"
            Log.d(TAG, "Cold start test event queued: eventId=$eventId, activityId=$activityId")
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
            uncertainResultTestEventId = null
            lastSyncMessage = "Device restart test event queued: $eventId"
            Log.d(TAG, "Device restart test event queued: eventId=$eventId, activityId=$activityId")
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
            lastSyncMessage = "Uncertain result retry queued with same event: $eventId"
            Log.d(TAG, "Uncertain result retry queued with same eventId=$eventId")
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
            lastSyncMessage = "Dependency order test events queued: $cycleEventId"
            Log.d(TAG, "Dependency order test events queued: cycleEventId=$cycleEventId, cycleId=$cycleId, stageEventId=$stageEventId, stageEntityId=$stageEntityId, activityEventId=$activityEventId, activityId=$activityId")
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
            lastSyncMessage = "Partial batch conflict test events queued: $activityEventId"
            Log.d(TAG, "Partial batch conflict test events queued: activityEventId=$activityEventId, activityId=$activityId, conflictEventId=$conflictEventId, conflictStageEntityId=$conflictStageEntityId")
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
            lastSyncMessage = "Multi-conflict test events queued: $versionEventId,$workflowEventId"
            Log.d(TAG, "Multi-conflict test events queued: versionEventId=$versionEventId, versionActivityId=$versionActivityId, workflowEventId=$workflowEventId, workflowStageEntityId=$workflowStageEntityId")
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
            lastSyncMessage = "Queue backpressure test events queued: $count"
            Log.d(TAG, "Queue backpressure test events queued: count=$count amount=$amount")
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
            val refreshOk = refreshBackendOwnedContext(currentFarmer)
            withContext(Dispatchers.IO) {
                db.syncQueueDao().deleteByEventId(eventId)
            }
            Log.d(TAG, "Deleted stale local draft queue row: eventId=$eventId")
            lastSyncMessage = if (refreshOk) {
                "Context refreshed; stale draft discarded"
            } else {
                "Stale draft discarded; refresh again if data looks old"
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
            try {
                refreshBackendOwnedContext(currentFarmer)
                val conflictId = withContext(Dispatchers.IO) {
                    val pending = api.getPendingConflicts(limit = 100)
                    if (!pending.isSuccessful) {
                        error("Could not fetch pending conflicts (${pending.code()})")
                    }
                    findPendingConflictId(pending.body(), item.eventId)
                        ?: error("Pending conflict not found for ${item.eventId}")
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
                if (!resolved.isSuccessful) {
                    error("Conflict acknowledgement failed (${resolved.code()})")
                }
                withContext(Dispatchers.IO) {
                    db.syncQueueDao().deleteByEventId(item.eventId)
                }
                lastSyncMessage = when (conflictType) {
                    "VERSION_MISMATCH" -> "Server version accepted; local edit discarded"
                    "WORKFLOW_INVALID" -> "Stage refreshed; local action discarded"
                    else -> "Conflict acknowledged; local draft discarded"
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
                            Icon(Icons.Default.Refresh, contentDescription = LanguageManager.localize("Sync", "सिंक"))
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = LanguageManager.localize("Settings", "सेटिंग्स"))
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
                // ═══════════ PROFILE EXISTS — show farmer info + crop actions ═══════════

                // Farmer info header (tappable)
                Card(
                    onClick = { onNavigateToFarmerProfile(farmer.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "👤 ${farmer.displayName ?: farmer.mobileNumber}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "📍 ${farmer.villageName ?: ""} | ${farmer.mobileNumber}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("✏️", style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Crop actions
                Text(
                    LanguageManager.localize("Crop Management", "फसल प्रबंधन"),
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
                                ).joinToString(" • "),
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
                                if (activeCycles.isEmpty()) LanguageManager.localize("Start Crop Cycle", "फसल चक्र शुरू करें")
                                else LanguageManager.localize("Start another crop cycle", "एक और फसल चक्र शुरू करें"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (activeCycles.isEmpty()) LanguageManager.localize("Begin a new crop season", "नया फसल मौसम शुरू करें")
                                else LanguageManager.localize("For another land parcel or crop", "दूसरे खेत या फसल के लिए"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

            } else {
                // ═══════════ NO PROFILE — show enrollment ═══════════

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(LanguageManager.localize("Welcome", "स्वागत है"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(LanguageManager.localize("Set up your farm profile to get started", "शुरू करने के लिए अपना कृषि प्रोफ़ाइल बनाएं"), style = MaterialTheme.typography.bodyMedium)
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
                            Text(LanguageManager.localize("Create Farm Profile", "कृषि प्रोफ़ाइल बनाएं"), style = MaterialTheme.typography.titleSmall)
                            Text(LanguageManager.localize("Farmer details + land + soil", "किसान विवरण + भूमि + मिट्टी"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (showDynamicSyncTestTools) {
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
                    onClick = { queueVersionMismatchTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Version Mismatch Test")
                }
                versionMismatchTestEventId?.let { eventId ->
                    Text("Version mismatch test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
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
                    onClick = { queueColdStartPersistenceTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Cold Start Test")
                }
                coldStartTestEventId?.let { eventId ->
                    Text("Cold start test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
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
                OutlinedButton(
                    onClick = { queueUncertainResultIdempotencyTestEvent() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Uncertain Result Test")
                }
                uncertainResultTestEventId?.let { eventId ->
                    Text("Uncertain result test event queued: $eventId", style = MaterialTheme.typography.bodySmall)
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
                    onClick = { queuePartialBatchConflictTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Partial Batch Conflict Test")
                }
                partialBatchConflictTestIds?.let { ids ->
                    Text("Partial batch conflict test events queued: $ids", style = MaterialTheme.typography.bodySmall)
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
                    onClick = { queueBackpressureTestEvents() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Queue Backpressure Test")
                }
                queueBackpressureTestIds?.let { ids ->
                    Text("Queue backpressure test events queued: $ids", style = MaterialTheme.typography.bodySmall)
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
                            LanguageManager.localize("Sync Status", "सिंक स्थिति"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(4.dp))
                        if (isSyncing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    LanguageManager.localize("Syncing...", "सिंक हो रहा है..."),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            if (pendingCount > 0) {
                                Text("🔄 $pendingCount ${LanguageManager.localize("items waiting", "आइटम प्रतीक्षा में")}")
                            }
                            if (conflictCount > 0) {
                                Text("⚠️ $conflictCount ${LanguageManager.localize("need attention", "ध्यान दें")}")
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
