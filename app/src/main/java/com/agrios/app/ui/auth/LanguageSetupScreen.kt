package com.agrios.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.core.util.LanguageManager

/**
 * First screen shown on app install.
 * Language preference persists and affects backend-driven form labels.
 *
 * Native app chrome is currently English/Hindi. Additional regional language
 * codes are selectable so backend-driven form contracts can be validated with
 * exact language-code lookup plus English fallback.
 */
@Composable
fun LanguageSetupScreen(
    onContinue: () -> Unit
) {
    var selectedLang by remember { mutableStateOf(LanguageManager.getLanguage()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Agri-OS",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Choose your language",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(32.dp))

        LanguageManager.supportedLanguages.forEach { option ->
            ElevatedCard(
                onClick = {
                    selectedLang = option.code
                    LanguageManager.setLanguage(option.code)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (selectedLang == option.code) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedLang == option.code,
                        onClick = {
                            selectedLang = option.code
                            LanguageManager.setLanguage(option.code)
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(option.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(
                            option.helperText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                if (selectedLang == "hi") "Continue" else "Continue",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "You can change this later from profile",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
