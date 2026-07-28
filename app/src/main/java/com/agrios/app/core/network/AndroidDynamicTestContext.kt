package com.agrios.app.core.network

import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity

/**
 * Dedicated backend-configured profile-form test context.
 *
 * This is intentionally narrow: default tenant remains legacy/gated off.
 * The dynamic profile form context is selected only for the dedicated test
 * tenant or the dedicated Maestro/mobile test number.
 */
object AndroidDynamicTestContext {
    const val TENANT_ID = "android-dynamic-test"
    const val PROJECT_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000000001"
    private const val TEST_MOBILE_DIGITS = "919900000002"

    fun isEnabledFor(authState: AuthStateEntity?): Boolean {
        val tenantMatches = authState?.tenantId == TENANT_ID
        val mobileDigits = authState?.mobileNumber
            ?.filter { it.isDigit() }
            .orEmpty()
        val mobileMatches = mobileDigits == TEST_MOBILE_DIGITS ||
            mobileDigits == TEST_MOBILE_DIGITS.removePrefix("91")
        return tenantMatches || mobileMatches
    }

    fun effectiveTenantId(authState: AuthStateEntity?, fallbackTenantId: String): String {
        return if (isEnabledFor(authState)) TENANT_ID
        else authState?.tenantId?.takeIf { it.isNotBlank() } ?: fallbackTenantId
    }

    fun projectIdFor(authState: AuthStateEntity?): String? {
        return if (isEnabledFor(authState)) PROJECT_ID else null
    }

    suspend fun projectIdFor(authDao: AuthDao): String? {
        return projectIdFor(authDao.getAuthState())
    }
}

