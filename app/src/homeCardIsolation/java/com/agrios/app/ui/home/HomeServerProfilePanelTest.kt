package com.agrios.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.agrios.app.core.sync.QueueOwner
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.local.entity.FarmerEntity
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.repository.SelfProfileProjection
import org.junit.Rule
import org.junit.Test

class HomeServerProfilePanelTest {
    @get:Rule val compose = createComposeRule()

    private val initial = session()
    private val observed = mutableStateOf(initial)
    private val renderSession = mutableStateOf<HomeServerProfileSession?>(initial)
    private val requests = mutableListOf<Request>()
    private class Request(
        val session: HomeServerProfileSession,
        val complete: (SelfProfileProjection?, HomeServerProfileMessage?) -> Unit
    )

    @Test fun explicitTapsClearPriorResultAndDoNotChangeLocalCard() {
        mount()
        tap(1)
        complete(0, "First server projection")
        compose.onNodeWithText("First server projection").assertExists()
        assertLocalCard()
        tap(2)
        assertNoProjection()
        complete(1, message = HomeServerProfileMessage.NOT_FOUND)
        compose.onNodeWithText("No linked server profile found.").assertExists()
        complete(0, "Late server projection")
        compose.onNodeWithText("Late server projection").assertDoesNotExist()
        assertNoProjection()
        assertLocalCard()
    }

    @Test fun blocksPreviousTenantResults() = replacement(session(tenant = "tenant-b"))
    @Test fun blocksPreviousUserResults() = replacement(session(user = "user-b"))
    @Test fun blocksPreviousEpochResultsWithIdenticalAuth() = replacement(session(epoch = 2))

    private fun replacement(next: HomeServerProfileSession) {
        mount()
        tap(1)
        complete(0, "First server projection")
        compose.onNodeWithText("First server projection").assertExists()
        // A render guard observes a mismatch before the request's scope is replaced.
        compose.runOnIdle { renderSession.value = null }
        assertNoProjection()
        compose.runOnIdle { renderSession.value = initial }
        tap(2)
        compose.runOnIdle {
            observed.value = next
            renderSession.value = next
        }
        assertNoProjection()
        tap(3, next)
        complete(1, "Late server projection")
        complete(1, message = HomeServerProfileMessage.FAILED)
        compose.onNodeWithText("Late server projection").assertDoesNotExist()
        compose.onNodeWithText("Server profile unavailable.").assertDoesNotExist()
        assertNoProjection()
        complete(2, "Replacement server projection")
        compose.onNodeWithText("Replacement server projection").assertExists()
        assertLocalCard()
    }

    private fun mount() {
        compose.setContent {
            val auth = observed.value.auth
            val owner = QueueOwner(checkNotNull(auth.tenantId), checkNotNull(auth.userId))
            val local = FarmerEntity("local", "synthetic-not-a-phone", "local-village", displayName = "Local draft",
                createdAt = 1, updatedAt = 1, actorId = owner.userId, originTenantId = owner.tenantId, originUserId = owner.userId)
            MaterialTheme {
                Column {
                    HomeOwnedFarmerCard(owner, HomeFarmerResult(owner, local, ownerActive = true), {}, {})
                    HomeServerProfileRequestPanel(observed.value, renderSession.value) { session, complete ->
                        requests.add(Request(session, complete))
                    }
                }
            }
        }
        assertLocalCard()
        assertNoProjection()
        compose.runOnIdle { check(requests.isEmpty()) }
    }

    private fun tap(count: Int, expected: HomeServerProfileSession = initial) {
        compose.onNodeWithText("View server profile").performClick()
        compose.runOnIdle { check(requests.size == count && requests.last().session == expected) }
    }

    private fun complete(index: Int, title: String? = null, message: HomeServerProfileMessage? = null) {
        compose.runOnIdle {
            val request = requests[index]
            val record = title?.let {
                val auth = request.session.auth
                SelfProfileProjection(SelfProfileCacheFarmerEntity(checkNotNull(auth.tenantId),
                    checkNotNull(auth.userId), "server", it, null), emptyList(), emptyList())
            }
            request.complete(record, message)
        }
    }

    private fun assertNoProjection() = compose.onNodeWithText("Cached server profile · read only").assertDoesNotExist()
    private fun assertLocalCard() = compose.onNodeWithText("Local draft", substring = true).assertExists()

    private fun session(tenant: String = "tenant-a", user: String = "user-a", epoch: Long = 1) =
        HomeServerProfileSession(AuthStateEntity(jwt = "synthetic-compose-profile-session", deviceKey = null,
            userId = user, tenantId = tenant, role = "FARMER", mobileNumber = null, isAuthenticated = true), epoch)
}
