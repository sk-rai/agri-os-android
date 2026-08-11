package com.agrios.app.core.network

import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity

/**
 * Dedicated backend-configured Android test contexts.
 *
 * These lanes are intentionally narrow: default tenant remains legacy/gated off.
 * Dedicated tenants are selected only for their Maestro/mobile test numbers.
 */
object AndroidDynamicTestContext {
    const val TENANT_ID = "android-dynamic-test"
    const val PROJECT_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000000001"
    const val PERSONA_TENANT_ID = "android-persona-lifecycle-test"
    const val PERSONA_PROJECT_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000000201"
    const val PERSONA_ASSISTED_FARMER_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000001402"
    const val PERSONA_ASSISTED_PARCEL_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000001403"
    const val PERSONA_INDEPENDENT_FARMER_ID = "0f7e0a6b-8472-5d6d-8a14-a9d000001102"
    private const val TEST_MOBILE_DIGITS = "919900000002"
    private val PERSONA_MOBILE_DIGITS = setOf(
        "919900001101",
        "919900001201",
        "919900001301",
        "919900001401",
        "919900001501",
        "919900001601",
        "919900001701",
        "919900001801"
    )

    fun isEnabledFor(authState: AuthStateEntity?): Boolean {
        val tenantMatches = authState?.tenantId == TENANT_ID
        val mobileDigits = authState?.mobileNumber
            ?.filter { it.isDigit() }
            .orEmpty()
        val mobileMatches = mobileDigits == TEST_MOBILE_DIGITS ||
            mobileDigits == TEST_MOBILE_DIGITS.removePrefix("91")
        return tenantMatches || mobileMatches
    }

    fun isPersonaLifecycleEnabledFor(authState: AuthStateEntity?): Boolean {
        val tenantMatches = authState?.tenantId == PERSONA_TENANT_ID
        val mobileDigits = authState?.mobileNumber
            ?.filter { it.isDigit() }
            .orEmpty()
        val mobileMatches = mobileDigits in PERSONA_MOBILE_DIGITS ||
            "91$mobileDigits" in PERSONA_MOBILE_DIGITS
        return tenantMatches || mobileMatches
    }

    fun isAnyBackendContractTestEnabledFor(authState: AuthStateEntity?): Boolean {
        return isEnabledFor(authState) || isPersonaLifecycleEnabledFor(authState)
    }

    fun effectiveTenantId(authState: AuthStateEntity?, fallbackTenantId: String): String {
        return when {
            isEnabledFor(authState) -> TENANT_ID
            isPersonaLifecycleEnabledFor(authState) -> PERSONA_TENANT_ID
            else -> authState?.tenantId?.takeIf { it.isNotBlank() } ?: fallbackTenantId
        }
    }

    fun projectIdFor(authState: AuthStateEntity?): String? {
        return when {
            isEnabledFor(authState) -> PROJECT_ID
            isPersonaLifecycleEnabledFor(authState) -> {
                val mobileDigits = authState?.mobileNumber?.filter { it.isDigit() }.orEmpty()
                if (mobileDigits.endsWith("1101") || mobileDigits.endsWith("1601") || mobileDigits.endsWith("1801")) null
                else PERSONA_PROJECT_ID
            }
            else -> null
        }
    }

    suspend fun projectIdFor(authDao: AuthDao): String? {
        return projectIdFor(authDao.getAuthState())
    }
}
