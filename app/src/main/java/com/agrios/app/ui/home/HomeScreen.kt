package com.agrios.app.ui.home

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
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncManager
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.repository.ProfileHydrationRepository
import com.agrios.app.ui.components.SyncStatusBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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
        if (!hasProfile && !hydrationAttempted) {
            hydrationAttempted = true
            isHydratingProfile = true
            hydrationMessage = null
            try {
                val result = withContext(Dispatchers.IO) {
                    ProfileHydrationRepository(
                        context = AgriOsApp.instance,
                        db = db,
                        api = api
                    ).hydrateAfterLogin()
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
            val activeResponse = withContext(Dispatchers.IO) {
                api.getCropCycles(farmerId = farmerId, status = "ACTIVE")
            }
            val completedResponse = withContext(Dispatchers.IO) {
                api.getCropCycles(farmerId = farmerId, status = "COMPLETED")
            }

            if (activeResponse.isSuccessful) {
                activeCycles = activeResponse.body().orEmpty()
                activeCycles.forEach { CropCycleCache.upsert(AgriOsApp.instance, it) }
            } else {
                activeCycles = emptyList()
                if (activeResponse.code() != 405) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agri-OS") },
                actions = {
                    SyncStatusBadge(pendingCount = pendingCount, conflictCount = conflictCount)
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

            // Sync status card
            if (pendingCount > 0 || conflictCount > 0 || isSyncing || lastSyncMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (conflictCount > 0) MaterialTheme.colorScheme.errorContainer
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
                            if (lastSyncMessage != null) {
                                Text(lastSyncMessage!!, style = MaterialTheme.typography.bodySmall)
                            }
                            if (pendingCount > 0 || conflictCount > 0) {
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
