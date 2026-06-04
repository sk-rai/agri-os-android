package com.agrios.app.core.auth

import android.provider.Settings
import android.util.Log
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.DeviceAuthDto
import com.agrios.app.data.remote.dto.OtpRequestDto
import com.agrios.app.data.remote.dto.OtpVerifyDto
import kotlinx.coroutines.flow.Flow
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Auth flow:
 * 1. First launch: OTP → verify → JWT + device_key stored
 * 2. Subsequent launches: device_key + device_id → JWT (no SMS)
 * 3. Phone change: re-do OTP flow
 */
class AuthRepository(
    private val api: AgriOsApi,
    private val authDao: AuthDao,
    private val deviceId: String,
    private val deviceName: String
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    fun observeAuthState(): Flow<AuthStateEntity?> = authDao.observeAuthState()

    suspend fun getAuthState(): AuthStateEntity? = authDao.getAuthState()

    suspend fun isAuthenticated(): Boolean {
        val state = authDao.getAuthState()
        return state?.isAuthenticated == true && state.jwt != null
    }

    /**
     * Step 1: Request OTP for mobile number.
     * Returns dev_otp in dev builds for auto-fill.
     */
    suspend fun requestOtp(mobileNumber: String): Result<String?> {
        return try {
            val response = api.requestOtp(OtpRequestDto(mobileNumber))
            if (response.isSuccessful) {
                // Return dev_otp if present (for dev/testing builds)
                Result.success(response.body()?.devOtp)
            } else {
                Result.failure(Exception("OTP request failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 2: Verify OTP → receive JWT + device_key
     */
    suspend fun verifyOtp(mobileNumber: String, otpCode: String): Result<AuthStateEntity> {
        return try {
            val response = api.verifyOtp(
                OtpVerifyDto(
                    mobileNumber = mobileNumber,
                    otpCode = otpCode,
                    deviceId = deviceId,
                    deviceName = deviceName
                )
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                Log.d(TAG, "Auth response: userId=${body.userId}, tenantId=${body.tenantId}, role=${body.role}")

                // If backend didn't return tenant_id, try to extract from JWT claims
                val tenantId = body.tenantId ?: extractTenantFromJwt(body.accessToken)
                if (body.tenantId == null) {
                    Log.w(TAG, "tenant_id not in auth response body, extracted from JWT: $tenantId")
                }

                val authState = AuthStateEntity(
                    jwt = body.accessToken,
                    deviceKey = body.deviceKey,
                    userId = body.userId,
                    tenantId = tenantId,
                    role = body.role,
                    mobileNumber = mobileNumber,
                    isAuthenticated = true
                )
                authDao.saveAuthState(authState)
                Result.success(authState)
            } else {
                Result.failure(Exception("OTP verification failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Subsequent launches: use device_key for silent auth (no SMS needed)
     */
    suspend fun refreshWithDeviceKey(): Result<AuthStateEntity> {
        val currentState = authDao.getAuthState()
        val deviceKey = currentState?.deviceKey
            ?: return Result.failure(Exception("No device key stored"))

        return try {
            val response = api.authenticateDevice(
                DeviceAuthDto(deviceKey = deviceKey, deviceId = deviceId)
            )
            if (response.isSuccessful) {
                val body = response.body()!!
                Log.d(TAG, "Device auth response: userId=${body.userId}, tenantId=${body.tenantId}, role=${body.role}")

                // If backend didn't return tenant_id, try JWT or keep existing
                val tenantId = body.tenantId
                    ?: extractTenantFromJwt(body.accessToken)
                    ?: currentState.tenantId

                val authState = AuthStateEntity(
                    jwt = body.accessToken,
                    deviceKey = deviceKey,
                    userId = body.userId,
                    tenantId = tenantId,
                    role = body.role,
                    mobileNumber = currentState.mobileNumber,
                    isAuthenticated = true
                )
                authDao.saveAuthState(authState)
                Result.success(authState)
            } else {
                // Device key rejected — need fresh OTP
                Result.failure(Exception("Device auth failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            // Network error — continue with cached auth if available
            if (currentState?.isAuthenticated == true) {
                Result.success(currentState)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun logout() {
        authDao.clearAuth()
    }

    /**
     * Extract tenant_id from JWT payload claims.
     * JWT format: header.payload.signature (base64url encoded)
     * The payload typically contains: {"sub": "...", "tenant_id": "...", ...}
     */
    private fun extractTenantFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val claims: Map<String, Any?> = Gson().fromJson(
                payload, object : TypeToken<Map<String, Any?>>() {}.type
            )
            // Try common claim names for tenant
            val tenantId = claims["tenant_id"] as? String
                ?: claims["tid"] as? String
                ?: claims["tenant"] as? String
            Log.d(TAG, "JWT claims keys: ${claims.keys}, extracted tenant_id=$tenantId")
            tenantId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract tenant from JWT: ${e.message}")
            null
        }
    }
}
