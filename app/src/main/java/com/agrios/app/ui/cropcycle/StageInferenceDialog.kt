package com.agrios.app.ui.cropcycle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agrios.app.core.util.LanguageManager
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import com.agrios.app.data.remote.dto.CropStageDto

/**
 * Dialog shown after crop cycle creation when the sowing date is in the past.
 * Asks the farmer whether to start from the inferred current stage or from the beginning
 * (to retroactively record earlier stage activities/costs).
 */
@Composable
fun StageInferenceDialog(
    cycle: CropCycleResponseDto,
    onStartFromInferred: () -> Unit,
    onStartFromBeginning: () -> Unit,
    onDismiss: () -> Unit
) {
    val inferredStage = cycle.stages.find { it.code == cycle.inferredCurrentStage }
    val inferredStageName = inferredStage?.getDisplayName() ?: cycle.inferredCurrentStage ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(LanguageManager.localize("Crop Stage Detection", "फसल चरण पहचान"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    LanguageManager.localize(
                        "Based on your sowing date (${cycle.plannedSowingDate}), your ${cycle.cropName ?: cycle.cropCode} crop should currently be at:",
                        "आपकी बुवाई तिथि (${cycle.plannedSowingDate}) के आधार पर, आपकी ${cycle.cropName ?: cycle.cropCode} फसल वर्तमान में इस चरण पर होनी चाहिए:"
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "📍 $inferredStageName",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Text(
                    LanguageManager.localize(
                        "Would you like to start tracking from this stage, or from the beginning to also record activities and costs from earlier stages?",
                        "क्या आप इस चरण से ट्रैकिंग शुरू करना चाहते हैं, या शुरू से शुरू करके पहले के चरणों की गतिविधियाँ और खर्च भी दर्ज करना चाहते हैं?"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onStartFromInferred) {
                Text(LanguageManager.localize(
                    "Start from $inferredStageName",
                    "$inferredStageName से शुरू करें"
                ))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onStartFromBeginning) {
                Text(LanguageManager.localize(
                    "Start from beginning",
                    "शुरू से शुरू करें"
                ))
            }
        }
    )
}
