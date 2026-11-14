package com.agrios.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agrios.app.data.remote.dto.CropCycleResponseDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCropCyclePanel(
    scope: HomeCycleScope?,
    result: HomeCycleResult?,
    isLoadingCycles: Boolean,
    localize: (String, String) -> String,
    onTimeline: (String) -> Unit,
    onStartCycle: () -> Unit,
) {
    val visibleCycleResult = result?.visibleFor(scope)
    // Crop actions
    Text(
        localize("Crop Management", "à¤«à¤¸à¤² à¤ªà¥à¤°à¤¬à¤‚à¤§à¤¨"),
        style = MaterialTheme.typography.titleSmall
    )

    if (isLoadingCycles) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading active crop cycles...", style = MaterialTheme.typography.bodySmall)
        }
    }

    val displayCycles = visibleCycleResult?.cycles.orEmpty().dedupeForHome()
    val runningCycles = displayCycles.filterNot { it.status.equals("COMPLETED", ignoreCase = true) }
    runningCycles.forEach { cycle ->
        val currentStage = cycle.stages.firstOrNull { it.status.equals("ACTIVE", ignoreCase = true) }
            ?: cycle.stages.firstOrNull { it.status.equals("PENDING", ignoreCase = true) }
        ElevatedCard(
            onClick = { onTimeline(cycle.id) },
            modifier = Modifier.fillMaxWidth().testTag("cycle_${cycle.id}"),
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

    if (displayCycles.isEmpty()) visibleCycleResult?.message?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    val historyCycles = displayCycles.filter { it.status.equals("COMPLETED", ignoreCase = true) }
    if (historyCycles.isNotEmpty()) {
        Text("Completed Crop Cycles", style = MaterialTheme.typography.titleSmall)
        historyCycles.forEach { cycle ->
            ElevatedCard(
                onClick = { onTimeline(cycle.id) },
                modifier = Modifier.fillMaxWidth().testTag("cycle_${cycle.id}")
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
        onClick = onStartCycle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    if (runningCycles.isEmpty()) localize("Start Crop Cycle", "à¤«à¤¸à¤² à¤šà¤•à¥à¤° à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚")
                    else localize("Start another crop cycle", "à¤à¤• à¤”à¤° à¤«à¤¸à¤² à¤šà¤•à¥à¤° à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚"),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (runningCycles.isEmpty()) localize("Begin a new crop season", "à¤¨à¤¯à¤¾ à¤«à¤¸à¤² à¤®à¥Œà¤¸à¤® à¤¶à¥à¤°à¥‚ à¤•à¤°à¥‡à¤‚")
                    else localize("For another land parcel or crop", "à¤¦à¥‚à¤¸à¤°à¥‡ à¤–à¥‡à¤¤ à¤¯à¤¾ à¤«à¤¸à¤² à¤•à¥‡ à¤²à¤¿à¤"),
                    style = MaterialTheme.typography.bodySmall
                )
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
