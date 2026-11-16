package com.agrios.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agrios.app.core.sync.QueueOwner
import com.agrios.app.data.local.entity.FarmerEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Opt-in isolated regression; instrumentation execution is not authorized by this change. */
class HomeOwnedFarmerCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hidesPreviousFarmerDuringSameTenantReplacement() = replacement(QueueOwner("tenant-a", "user-b"))

    @Test fun hidesPreviousFarmerDuringCrossTenantReplacement() = replacement(QueueOwner("tenant-b", "user-a"))

    private fun replacement(next: QueueOwner) {
        val previous = QueueOwner("tenant-a", "user-a")
        val a = farmer("a", previous)
        val b = farmer("b", next)
        val owner = mutableStateOf<QueueOwner?>(previous)
        val activeA = HomeFarmerResult(previous, a, ownerActive = true)
        val result = mutableStateOf<HomeFarmerResult?>(activeA)
        val profile = mutableListOf<String>()
        val availability = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    HomeOwnedFarmerCard(owner.value, result.value, { profile.add(it) }, { availability.add(it) })
                    if (result.value?.isEmptyFor(owner.value) == true) Text("Confirmed empty")
                }
            }
        }
        compose.onNodeWithText(a.displayName!!, substring = true).assertExists()
        compose.onNodeWithText(a.villageName!!, substring = true).assertExists()
        compose.onNodeWithText(a.mobileNumber, substring = true).assertExists()
        compose.onNodeWithTag("profile_${a.id}").performClick()
        compose.onNodeWithTag("parcel_availability_navigation").performClick()

        // Query-first ordering: Room observes departure before Home's auth collector.
        compose.runOnIdle { result.value = HomeFarmerResult(previous, null, ownerActive = false) }
        assertPreviousHidden(a)

        // Reset the input to exercise the opposite ordering with A's result still cached.
        compose.runOnIdle { result.value = activeA }
        compose.onNodeWithTag("profile_${a.id}").assertExists()
        compose.runOnIdle { owner.value = next }
        assertPreviousHidden(a)
        compose.runOnIdle {
            result.value = HomeFarmerResult(previous, a.copy(displayName = "Late farmer A"), ownerActive = true)
        }
        assertPreviousHidden(a)
        compose.onNodeWithText("Late farmer A", substring = true).assertDoesNotExist()
        // Even a current query tag cannot make a foreign-provenance record visible.
        compose.runOnIdle { result.value = HomeFarmerResult(next, a, ownerActive = true) }
        assertPreviousHidden(a)
        compose.runOnIdle { result.value = HomeFarmerResult(previous, null, ownerActive = true) }
        assertPreviousHidden(a)
        compose.runOnIdle { result.value = null }
        assertPreviousHidden(a)
        compose.runOnIdle { result.value = HomeFarmerResult(next, null, ownerActive = true) }
        compose.onNodeWithText("Confirmed empty").assertExists()
        compose.runOnIdle { result.value = HomeFarmerResult(next, b, ownerActive = true) }
        compose.onNodeWithText(b.displayName!!, substring = true).assertExists()
        compose.onNodeWithTag("profile_${a.id}").assertDoesNotExist()
        compose.onNodeWithTag("profile_${b.id}").performClick()
        compose.onNodeWithTag("parcel_availability_navigation").performClick()
        compose.runOnIdle {
            assertEquals(listOf(a.id, b.id), profile)
            assertEquals(listOf(a.id, b.id), availability)
        }
    }

    private fun assertPreviousHidden(a: FarmerEntity) {
        compose.onNodeWithText(a.displayName!!, substring = true).assertDoesNotExist()
        compose.onNodeWithText(a.villageName!!, substring = true).assertDoesNotExist()
        compose.onNodeWithText(a.mobileNumber, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("profile_${a.id}").assertDoesNotExist()
        compose.onNodeWithTag("parcel_availability_navigation").assertDoesNotExist()
        compose.onNodeWithText("Confirmed empty").assertDoesNotExist()
    }

    private fun farmer(label: String, owner: QueueOwner) = FarmerEntity(
        id = "farmer-$label", mobileNumber = "synthetic-contact-$label", villageId = "village-$label",
        villageName = "Synthetic village $label", displayName = "Synthetic farmer $label",
        createdAt = 1, updatedAt = 1, actorId = "synthetic-actor",
        originTenantId = owner.tenantId, originUserId = owner.userId,
    )
}
