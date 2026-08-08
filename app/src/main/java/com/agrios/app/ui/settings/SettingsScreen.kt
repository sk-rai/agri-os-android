package com.agrios.app.ui.settings

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
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.local.entity.AuthStateEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val db = AgriOsApp.instance.database
    val scope = rememberCoroutineScope()

    var authState by remember { mutableStateOf<AuthStateEntity?>(null) }
    var farmerCount by remember { mutableStateOf(0) }
    var parcelCount by remember { mutableStateOf(0) }
    var syncedCount by remember { mutableStateOf(0) }
    val pendingCount by db.syncQueueDao().observePendingCount().collectAsState(initial = 0)
    val conflictCount by db.syncQueueDao().observeConflictCount().collectAsState(initial = 0)

    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authState = db.authDao().getAuthState()
        farmerCount = db.farmerDao().getCount()
        parcelCount = db.parcelDao().getCount()
        syncedCount = db.syncQueueDao().getSyncedCount()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LanguageManager.localize("Settings", "सेटिंग्स")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
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
            // ═══════════════════════════
            // USER INFO
            // ═══════════════════════════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        LanguageManager.localize("👤 Account", "👤 खाता"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingsRow(LanguageManager.localize("Mobile", "मोबाइल"), authState?.mobileNumber ?: "—")
                    SettingsRow(LanguageManager.localize("Role", "भूमिका"), authState?.role ?: "—")
                    SettingsRow(LanguageManager.localize("User ID", "उपयोगकर्ता ID"), authState?.userId?.take(8)?.plus("...") ?: "—")
                }
            }

            // ═══════════════════════════
            // LANGUAGE
            // ═══════════════════════════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        LanguageManager.localize("🌐 Language", "🌐 भाषा"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    val currentLanguage = LanguageManager.supportedLanguages
                        .firstOrNull { it.code == LanguageManager.getLanguage() }
                        ?: LanguageManager.supportedLanguages.first()
                    Text(
                        "Current: ${currentLanguage.displayName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LanguageManager.supportedLanguages.forEach { option ->
                            FilterChip(
                                selected = LanguageManager.getLanguage() == option.code,
                                onClick = {
                                    LanguageManager.setLanguage(option.code)
                                },
                                label = { Text(option.displayName) }
                            )
                        }
                    }
                    Text(
                        LanguageManager.localize(
                            "⚠️ Restart app to apply language change fully",
                            "⚠️ भाषा बदलाव पूरी तरह लागू करने के लिए ऐप पुनः शुरू करें"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ═══════════════════════════
            // SYNC STATS
            // ═══════════════════════════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        LanguageManager.localize("📊 Data & Sync", "📊 डेटा और सिंक"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingsRow(LanguageManager.localize("Farmers enrolled", "किसान नामांकित"), "$farmerCount")
                    SettingsRow(LanguageManager.localize("Parcels registered", "भूखंड पंजीकृत"), "$parcelCount")
                    SettingsRow(LanguageManager.localize("Items synced", "सिंक किए गए"), "$syncedCount")
                    SettingsRow(LanguageManager.localize("Pending sync", "लंबित सिंक"), "$pendingCount")
                    if (conflictCount > 0) {
                        SettingsRow(LanguageManager.localize("Conflicts", "विरोध"), "⚠️ $conflictCount")
                    }
                }
            }

            // ═══════════════════════════
            // LOGOUT
            // ═══════════════════════════
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(LanguageManager.localize("Logout", "लॉगआउट"))
            }

            Text(
                LanguageManager.localize(
                    "⚠️ Unsynced data will be lost on logout",
                    "⚠️ लॉगआउट पर बिना सिंक डेटा खो जाएगा"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(LanguageManager.localize("Confirm Logout", "लॉगआउट पुष्टि")) },
            text = {
                if (pendingCount > 0) {
                    Text(LanguageManager.localize(
                        "You have $pendingCount unsynced items. They will be lost. Continue?",
                        "आपके पास $pendingCount बिना सिंक आइटम हैं। वे खो जाएंगे। जारी रखें?"
                    ))
                } else {
                    Text(LanguageManager.localize("Are you sure?", "क्या आप निश्चित हैं?"))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    scope.launch {
                        db.authDao().clearAuth()
                        onLogout()
                    }
                }) {
                    Text(LanguageManager.localize("Logout", "लॉगआउट"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(LanguageManager.localize("Cancel", "रद्द"))
                }
            }
        )
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
