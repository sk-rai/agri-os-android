package com.agrios.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agrios.app.data.local.entity.SyncStatus

/**
 * Rural-optimized sync status indicator.
 * Uses text + color (no extended icon dependency).
 */
@Composable
fun SyncStatusIndicator(
    status: SyncStatus,
    modifier: Modifier = Modifier
) {
    val (emoji, label, color) = getSyncStatusConfig(status)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SyncStatusBadge(pendingCount: Int, conflictCount: Int, failedCount: Int = 0) {
    if (pendingCount == 0 && conflictCount == 0 && failedCount == 0) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        if (pendingCount > 0) {
            Text(
                "Waiting $pendingCount",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF1565C0)
            )
        }
        if (pendingCount > 0 && (conflictCount > 0 || failedCount > 0)) {
            Spacer(Modifier.width(8.dp))
        }
        if (conflictCount > 0) {
            Text(
                "Attention $conflictCount",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE65100)
            )
        }
        if (conflictCount > 0 && failedCount > 0) {
            Spacer(Modifier.width(8.dp))
        }
        if (failedCount > 0) {
            Text(
                "Failed $failedCount",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFC62828)
            )
        }
    }
}
private data class SyncStatusConfig(
    val emoji: String,
    val label: String,
    val color: Color
)

private fun getSyncStatusConfig(status: SyncStatus): SyncStatusConfig {
    return when (status) {
        SyncStatus.PENDING -> SyncStatusConfig("🟢", "Saved", Color(0xFF1565C0))
        SyncStatus.SYNCING -> SyncStatusConfig("🔄", "Syncing", Color(0xFF1565C0))
        SyncStatus.CONFLICTED -> SyncStatusConfig("⚠️", "Attention", Color(0xFFE65100))
        SyncStatus.FAILED -> SyncStatusConfig("❌", "Failed", Color(0xFFC62828))
        SyncStatus.SYNCED -> SyncStatusConfig("✅", "Synced", Color(0xFF2E7D32))
    }
}
