package com.agrios.app.ui.home

import androidx.compose.foundation.clickable
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
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.agrios.app.core.sync.SyncManager
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.ui.components.SyncStatusBadge
import kotlinx.coroutines.launch
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

    // Trigger sync on screen entry
    LaunchedEffect(Unit) {
        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (hasProfile && farmer != null) {
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

                // Start Crop Cycle
                ElevatedCard(
                    onClick = onNavigateToCropCycle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(LanguageManager.localize("Start Crop Cycle", "फसल चक्र शुरू करें"), style = MaterialTheme.typography.titleSmall)
                            Text(LanguageManager.localize("Begin a new crop season", "नया फसल मौसम शुरू करें"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // TODO: Show active crop cycles list here when we have them

                // Add another parcel (secondary action)
                HorizontalDivider()
                TextButton(onClick = onNavigateToParcelRegister) {
                    Text("+ ${LanguageManager.localize("Add another land parcel", "एक और भूखंड जोड़ें")}")
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
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { runSyncNow() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(LanguageManager.localize("Sync Now", "अभी सिंक करें"))
                            }
                        }
                    }
                }
            }
        }
    }
}
