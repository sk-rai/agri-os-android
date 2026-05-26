package com.agrios.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.AgriOsApp
import com.agrios.app.core.sync.SyncWorker
import com.agrios.app.core.util.Labels
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.ui.components.SyncStatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFarmerEnroll: () -> Unit,
    onNavigateToParcelRegister: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val pendingCount by db.syncQueueDao().observePendingCount().collectAsState(initial = 0)
    val conflictCount by db.syncQueueDao().observeConflictCount().collectAsState(initial = 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agri-OS") },
                actions = {
                    SyncStatusBadge(pendingCount = pendingCount, conflictCount = conflictCount)
                    IconButton(onClick = {
                        SyncWorker.triggerImmediateSync(AgriOsApp.instance)
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync")
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
            // Welcome card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        LanguageManager.localize("Welcome", "स्वागत है"),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        LanguageManager.localize("Manage farmers and parcels", "किसानों और भूखंडों का प्रबंधन करें"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Quick actions
            Text(
                LanguageManager.localize("Quick Actions", "त्वरित कार्य"),
                style = MaterialTheme.typography.titleSmall
            )

            // Enroll Farmer
            ElevatedCard(
                onClick = onNavigateToFarmerEnroll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(Labels.enrollFarmer, style = MaterialTheme.typography.titleSmall)
                        Text(
                            LanguageManager.localize("Add new farmer", "नया किसान जोड़ें"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Register Parcel
            ElevatedCard(
                onClick = onNavigateToParcelRegister,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(Labels.registerParcel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            LanguageManager.localize("Add land parcel for farmer", "किसान के लिए भूखंड जोड़ें"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Sync status
            if (pendingCount > 0 || conflictCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (conflictCount > 0) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(LanguageManager.localize("Sync Status", "सिंक स्थिति"), style = MaterialTheme.typography.titleSmall)
                        if (pendingCount > 0) Text("🔄 $pendingCount ${LanguageManager.localize("items waiting", "आइटम प्रतीक्षा में")}")
                        if (conflictCount > 0) Text("⚠️ $conflictCount ${LanguageManager.localize("need attention", "ध्यान दें")}")
                    }
                }
            }
        }
    }
}
