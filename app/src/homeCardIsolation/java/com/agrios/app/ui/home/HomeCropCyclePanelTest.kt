package com.agrios.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.agrios.app.core.auth.SessionLease
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.remote.dto.CropCycleResponseDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeCropCyclePanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hidesOldCyclesDuringSameTenantReplacement() = replacement("tenant-a", "user-b")

    @Test fun hidesOldCyclesDuringCrossTenantReplacement() = replacement("tenant-b", "user-a")

    private fun replacement(tenant: String, user: String) {
        val a = scope("tenant-a", "user-a", "farmer-a")
        val b = scope(tenant, user, "farmer-b")
        val activeA = cycle("a-active", a, "ACTIVE", "Synthetic active A")
        val completedA = cycle("a-completed", a, "COMPLETED", "Synthetic completed A")
        val activeB = cycle("b-active", b, "ACTIVE", "Synthetic active B")
        val completedB = cycle("b-completed", b, "COMPLETED", "Synthetic completed B")
        val leaseA = SessionLease(a.session, 1)
        val leaseB = SessionLease(b.session, 2)
        val current = mutableStateOf(a)
        val result = mutableStateOf(HomeCycleResult(a, leaseA, listOf(activeA, completedA)))
        val navigated = mutableListOf<String>()

        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HomeCropCyclePanel(current.value, result.value, isLoadingCycles = false,
                        localize = { english, _ -> english },
                        onTimeline = { navigated.add(it) }, onStartCycle = {})
                }
            }
        }
        compose.onNodeWithText("Continue Synthetic active A").assertExists()
        compose.onNodeWithText("Completed Synthetic completed A").assertExists()
        compose.onNodeWithTag("cycle_a-active").performScrollTo().performClick()
        compose.onNodeWithTag("cycle_a-completed").performScrollTo().performClick()

        compose.runOnIdle { current.value = b }
        assertOldCyclesHidden()
        compose.runOnIdle { result.value = HomeCycleResult(a, leaseA, listOf(completedA, activeA)) }
        assertOldCyclesHidden()
        compose.runOnIdle {
            result.value = HomeCycleResult(b, leaseB, emptyList(), message = "Unable to load crop cycles.")
        }
        assertOldCyclesHidden()
        compose.onNodeWithText("Unable to load crop cycles.").assertExists()

        compose.runOnIdle { result.value = HomeCycleResult(b, leaseB, listOf(activeB, completedB)) }
        assertOldCyclesHidden()
        compose.onNodeWithText("Unable to load crop cycles.").assertDoesNotExist()
        compose.onNodeWithText("Continue Synthetic active B").assertExists()
        compose.onNodeWithText("Completed Synthetic completed B").assertExists()
        compose.onNodeWithTag("cycle_b-active").performScrollTo().performClick()
        compose.onNodeWithTag("cycle_b-completed").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(activeA.id, completedA.id, activeB.id, completedB.id), navigated) }
    }

    private fun assertOldCyclesHidden() {
        compose.onNodeWithText("Continue Synthetic active A").assertDoesNotExist()
        compose.onNodeWithText("Completed Synthetic completed A").assertDoesNotExist()
        compose.onNodeWithTag("cycle_a-active").assertDoesNotExist()
        compose.onNodeWithTag("cycle_a-completed").assertDoesNotExist()
    }

    private fun scope(tenant: String, user: String, farmer: String) = HomeCycleScope(
        AuthStateEntity(jwt = "synthetic-session-$user", deviceKey = null, userId = user, tenantId = tenant,
            role = "FARMER", mobileNumber = null, isAuthenticated = true),
        farmer
    )

    private fun cycle(id: String, scope: HomeCycleScope, status: String, name: String) =
        CropCycleResponseDto(id = id, farmerId = scope.farmerId, status = status,
            cropName = name, cropCode = "FIXTURE", seasonCode = "FIXTURE")
}
