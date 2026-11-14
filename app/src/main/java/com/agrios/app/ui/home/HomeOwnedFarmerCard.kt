package com.agrios.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.agrios.app.core.sync.QueueOwner
import com.agrios.app.data.local.entity.FarmerEntity

/** No emission means loading. Only an active query may confirm an empty profile. */
internal data class HomeFarmerResult(
    val queriedOwner: QueueOwner,
    val record: FarmerEntity?,
    val ownerActive: Boolean,
) {
    fun visibleFarmer(owner: QueueOwner?): FarmerEntity? = record?.takeIf {
        ownerActive && owner != null && queriedOwner == owner &&
            it.originTenantId == owner.tenantId && it.originUserId == owner.userId
    }

    fun isEmptyFor(owner: QueueOwner?): Boolean = ownerActive && owner != null && queriedOwner == owner && record == null
}

@Composable
internal fun HomeOwnedFarmerCard(
    owner: QueueOwner?,
    result: HomeFarmerResult?,
    onProfile: (String) -> Unit,
    onAvailability: (String) -> Unit,
) {
    val farmer = result?.visibleFarmer(owner) ?: return
    OutlinedButton(
        onClick = { onAvailability(farmer.id) },
        modifier = Modifier.testTag("parcel_availability_navigation"),
    ) { Text("Parcel availability") }
    Card(
        onClick = { onProfile(farmer.id) },
        modifier = Modifier.fillMaxWidth().testTag("profile_${farmer.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ðŸ‘¤ ${farmer.displayName ?: farmer.mobileNumber}", style = MaterialTheme.typography.titleMedium)
                Text("ðŸ“ ${farmer.villageName ?: ""} | ${farmer.mobileNumber}", style = MaterialTheme.typography.bodySmall)
            }
            Text("âœï¸", style = MaterialTheme.typography.titleMedium)
        }
    }
}
