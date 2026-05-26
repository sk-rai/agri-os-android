package com.agrios.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.core.util.LanguageManager

/**
 * First screen shown on app install.
 * Language preference persists and affects all subsequent screens.
 * Editable later from profile icon.
 */
@Composable
fun LanguageSetupScreen(
    onContinue: () -> Unit
) {
    var selectedLang by remember { mutableStateOf(LanguageManager.getLanguage()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🌾 Agri-OS",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Choose your language\nअपनी भाषा चुनें",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(48.dp))

        // Hindi option
        ElevatedCard(
            onClick = { selectedLang = "hi"; LanguageManager.setLanguage("hi") },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (selectedLang == "hi") MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedLang == "hi", onClick = { selectedLang = "hi"; LanguageManager.setLanguage("hi") })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("हिंदी", style = MaterialTheme.typography.titleLarge)
                    Text("Hindi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // English option
        ElevatedCard(
            onClick = { selectedLang = "en"; LanguageManager.setLanguage("en") },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (selectedLang == "en") MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedLang == "en", onClick = { selectedLang = "en"; LanguageManager.setLanguage("en") })
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("English", style = MaterialTheme.typography.titleLarge)
                    Text("अंग्रेज़ी", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                if (selectedLang == "hi") "आगे बढ़ें" else "Continue",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (selectedLang == "hi") "आप बाद में प्रोफ़ाइल से बदल सकते हैं"
            else "You can change this later from profile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
