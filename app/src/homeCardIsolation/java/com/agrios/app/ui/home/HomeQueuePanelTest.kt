package com.agrios.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agrios.app.core.sync.OwnerQueueSummary
import com.agrios.app.core.sync.QueueOwner
import com.agrios.app.data.local.entity.SyncQueueEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeQueuePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hidesOldQueueDuringSameTenantReplacement() = replacement(QueueOwner("tenant-a", "user-b"))

    @Test fun hidesOldQueueDuringCrossTenantReplacement() = replacement(QueueOwner("tenant-b", "user-a"))

    private fun replacement(next: QueueOwner) {
        val previous = QueueOwner("tenant-a", "user-a")
        val aDraft = draft("draft-a", previous, "PARCEL_FARMER_MISMATCH")
        val held = aDraft.copy(eventId = "held-a", dispatchReserved = true)
        val aSummary = OwnerQueueSummary(previous.tenantId, previous.userId, 3, 1, 0, 1, 7)
        val bDraft = draft("draft-b", next, "PARCEL_PROJECT_MISMATCH")
        val unknown = draft("draft-unknown", null, "INVALID_PROJECT_FOR_TENANT")
        val owner = mutableStateOf(previous)
        val summary = mutableStateOf(aSummary)
        val attention = mutableStateOf(listOf(aDraft, held))
        val discarded = mutableListOf<String>()
        val serverActions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HomeQueuePanel(owner.value, summary.value, attention.value, false, null,
                    { english, _ -> english }, {}, { discarded.add(it.eventId) }, { serverActions.add(it.eventId) })
            }
        }
        compose.onNodeWithText("Synced 7").assertExists()
        compose.onNodeWithText("3 items waiting", substring = true).assertExists()
        compose.onNodeWithText("Failed 1 sync items").assertExists()
        compose.onNodeWithText("no longer belongs to the selected farmer", substring = true).assertExists()
        compose.onNodeWithText("Held for investigation").assertExists()
        // Held has the same draft error but must not contribute an action.
        compose.onAllNodesWithText("Discard local draft").assertCountEquals(1)
        compose.onNodeWithText("Use server version").assertDoesNotExist()
        compose.onNodeWithText("Refresh stage").assertDoesNotExist()

        compose.runOnIdle { owner.value = next }
        assertCheckingWithoutOldQueue()
        compose.runOnIdle {
            summary.value = aSummary.copy(synced = 8)
            attention.value = listOf(aDraft.copy(eventId = "late-a"), held)
        }
        assertCheckingWithoutOldQueue()

        compose.runOnIdle {
            summary.value = OwnerQueueSummary(next.tenantId, next.userId, 0, 1, 0, 0, 2)
            attention.value = listOf(aDraft, unknown, held, bDraft)
        }
        compose.onNodeWithText("Checking this account's queue status.").assertDoesNotExist()
        compose.onNodeWithText("Synced 2").assertExists()
        assertOldQueueHidden()
        compose.onNodeWithText("belongs to a different project", substring = true).assertExists()
        compose.onNodeWithText("project is not valid for this tenant", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("Discard local draft").assertCountEquals(1)
        compose.onNodeWithText("Discard local draft").performClick()
        compose.runOnIdle {
            assertEquals(listOf(bDraft.eventId), discarded)
            assertEquals(emptyList<String>(), serverActions)
        }
    }

    private fun assertCheckingWithoutOldQueue() {
        compose.onNodeWithText("Checking this account's queue status.").assertExists()
        assertOldQueueHidden()
        compose.onNodeWithText("Failed 1 sync items").assertDoesNotExist()
        compose.onNodeWithText("Discard local draft").assertDoesNotExist()
        compose.onNodeWithText("Sync Now").assertDoesNotExist()
    }

    private fun assertOldQueueHidden() {
        compose.onNodeWithText("Synced 7").assertDoesNotExist()
        compose.onNodeWithText("Synced 8").assertDoesNotExist()
        compose.onNodeWithText("3 items waiting", substring = true).assertDoesNotExist()
        compose.onNodeWithText("no longer belongs to the selected farmer", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Held for investigation").assertDoesNotExist()
        compose.onNodeWithText("Use server version").assertDoesNotExist()
        compose.onNodeWithText("Refresh stage").assertDoesNotExist()
    }

    private fun draft(id: String, owner: QueueOwner?, detail: String) = SyncQueueEntity(
        id, "crop_cycle", "synthetic-$id", "CREATE", "{}", "FAILED",
        lastError = """{"error_code":"MATERIALIZATION_FAILED","detail_code":"$detail"}""",
        createdAt = 1, originTenantId = owner?.tenantId, originUserId = owner?.userId,
    )
}
