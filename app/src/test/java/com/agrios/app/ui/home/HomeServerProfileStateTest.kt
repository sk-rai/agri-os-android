package com.agrios.app.ui.home

import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.local.entity.SelfProfileCacheFarmerEntity
import com.agrios.app.data.repository.SelfProfileProjection
import org.junit.Test

class HomeServerProfileStateTest {
    private val a = session()
    private val result = SelfProfileProjection(
        SelfProfileCacheFarmerEntity("tenant", "user", "farmer", "Fixture projection", null),
        emptyList(), emptyList()
    )

    @Test fun bothDeliveryOrdersHideDataAcrossUserTenantAndEpochChanges() {
        val replacements = listOf(session(user = "other"), session(tenant = "other"), session(epoch = 2), null)
        for (replacement in replacements) {
            val resultFirst = HomeServerProfileState(a)
            resultFirst.publish(resultFirst.begin(), a, result)
            check(resultFirst.visible(a)?.projection == result)
            check(resultFirst.visible(replacement) == null)

            val authFirst = HomeServerProfileState(a)
            val oldTap = authFirst.begin()
            authFirst.publish(oldTap, replacement, result)
            authFirst.publish(oldTap, replacement, message = HomeServerProfileMessage.FAILED)
            check(authFirst.visible(a) == null && authFirst.visible(replacement) == null)
        }
    }

    @Test fun newTapImmediatelyHidesOldResultAndFailureLeavesProjectionEmpty() {
        val state = HomeServerProfileState(a)
        check(state.visible(a) == null)
        val old = state.begin()
        state.publish(old, a, result)
        val current = state.begin()
        check(state.visible(a) == null)
        state.publish(old, a, result)
        check(state.visible(a) == null)
        state.publish(current, a, message = HomeServerProfileMessage.NOT_FOUND)
        check(state.visible(a)?.message == HomeServerProfileMessage.NOT_FOUND)
        check(state.visible(a)?.projection == null)
    }

    @Test fun obsoleteFailureCannotClearNewerResultAndDismissInvalidatesPendingWork() {
        val state = HomeServerProfileState(a)
        val first = state.begin()
        val second = state.begin()
        state.publish(second, a, result)
        state.publish(first, a, message = HomeServerProfileMessage.FAILED)
        check(state.visible(a)?.projection == result)
        val pending = state.begin()
        state.dismiss()
        state.publish(pending, a, result)
        check(state.visible(a) == null)
    }

    @Test fun foreignProjectionIsNotPublishedEvenWithCurrentSessionAndGeneration() {
        val state = HomeServerProfileState(a)
        val foreign = result.copy(farmer = result.farmer.copy(userId = "other"))
        state.publish(state.begin(), a, foreign)
        check(state.visible(a) == null)
    }

    private fun session(tenant: String = "tenant", user: String = "user", epoch: Long = 1) =
        HomeServerProfileSession(
            AuthStateEntity(jwt = "synthetic-home-profile-session", deviceKey = null, userId = user,
                tenantId = tenant, role = "FARMER", mobileNumber = null, isAuthenticated = true), epoch
        )
}
