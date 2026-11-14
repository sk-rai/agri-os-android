package com.agrios.app.core.auth

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

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
    private val deviceName: String,
    private val expiry: SessionExpiryCoordinator? = SessionExpiryCoordinator.registeredFor(authDao)
) {

    fun observeAuthState(): Flow<AuthStateEntity?> = authDao.observeAuthState()

    suspend fun getAuthState(): AuthStateEntity? = authDao.getAuthState()

    suspend fun isAuthenticated(): Boolean {
        val state = authDao.getAuthState()
        return state?.isAuthenticated == true && !state.reauthRequired && !state.jwt.isNullOrBlank()
    }

    /**
     * Step 1: Request OTP for mobile number.
     * Returns dev_otp in dev builds for auto-fill.
     */
    suspend fun requestOtp(mobileNumber: String): Result<String?> {
        return try {
            val response = api.requestOtp(OtpRequestDto(mobileNumber))
            if (response.isSuccessful) {
                val devOtp = response.body()?.devOtp
                // Return dev_otp if present (for dev/testing builds)
                Result.success(devOtp)
            } else {
                response.errorBody()?.close()
                Result.failure(Exception("OTP request failed: ${response.code()}"))
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            Result.failure(IllegalStateException("AUTHENTICATION_UNAVAILABLE"))
        }
    }

    /**
     * Step 2: Verify OTP → receive JWT + device_key
     */
    suspend fun verifyOtp(mobileNumber: String, otpCode: String): Result<AuthStateEntity> {
        var attempt: AuthAttempt? = null
        var requestStarted = false
        var responseReceived = false
        var persistenceStarted = false
        return try {
            // Navigation observes this owner, not the repository result. Never
            // consume an OTP or silently save a session without that owner.
            val owner = expiry ?: return Result.failure(OtpVerificationUnavailable(OtpFailureCategory.OWNER_UNAVAILABLE))
            if (!owner.owns(authDao)) return Result.failure(OtpVerificationUnavailable(OtpFailureCategory.OWNER_MISMATCH))
            attempt = owner.beginAuthentication()
            requestStarted = true
            val response = api.verifyOtp(
                OtpVerifyDto(
                    mobileNumber = mobileNumber,
                    otpCode = otpCode,
                    deviceId = deviceId,
                    deviceName = deviceName
                )
            )
            responseReceived = true
            if (response.isSuccessful) {
                val body = response.body() ?: throw OtpVerificationUnavailable(OtpFailureCategory.INVALID_RESPONSE)

                // If backend didn't return tenant_id, try to extract from JWT claims
                val tenantId = body.tenantId ?: extractTenantFromJwt(body.accessToken)

                val authState = AuthStateEntity(
                    jwt = body.accessToken,
                    deviceKey = body.deviceKey,
                    userId = body.userId,
                    tenantId = tenantId,
                    role = body.role,
                    mobileNumber = mobileNumber,
                    isAuthenticated = true
                )
                persistenceStarted = true
                val decision = owner.completeAuthenticationDecision(attempt, authState)
                if (decision.completion != AuthenticationCompletion.ACCEPTED)
                    throw AuthenticationRejected(decision.completion, decision.comparison)
                Result.success(authState)
            } else {
                if (attempt != null) expiry?.authenticationFailed(attempt)
                response.errorBody()?.close()
                Result.failure(OtpHttpRejected(response.code()))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (attempt != null) expiry?.authenticationFailed(attempt) }
            throw e
        } catch (e: AuthenticationRejected) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            Result.failure(e)
        } catch (e: OtpVerificationUnavailable) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            Result.failure(e)
        } catch (_: com.google.gson.JsonParseException) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            Result.failure(OtpVerificationUnavailable(OtpFailureCategory.INVALID_RESPONSE))
        } catch (e: IOException) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            val category = if (!requestStarted || persistenceStarted) OtpFailureCategory.STORAGE_UNAVAILABLE
                else OtpFailureCategory.TRANSPORT_UNAVAILABLE
            Result.failure(OtpVerificationUnavailable(category,
                if (category == OtpFailureCategory.TRANSPORT_UNAVAILABLE) otpTransportCode(e) else null))
        } catch (_: Exception) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            val category = when {
                !requestStarted || persistenceStarted -> OtpFailureCategory.STORAGE_UNAVAILABLE
                responseReceived -> OtpFailureCategory.INVALID_RESPONSE
                else -> OtpFailureCategory.UNEXPECTED_FAILURE
            }
            Result.failure(OtpVerificationUnavailable(category))
        }
    }

    /**
     * Subsequent launches: use device_key for silent auth (no SMS needed)
     */
    suspend fun refreshWithDeviceKey(): Result<AuthStateEntity> {
        val currentState = authDao.getAuthState()
        if (currentState?.reauthRequired == true) return Result.failure(IllegalStateException("OTP_REQUIRED"))
        val deviceKey = currentState?.deviceKey
            ?: return Result.failure(Exception("No device key stored"))

        var attempt: AuthAttempt? = null
        var deviceResponseReceived = false

        return try {
            val owner = checkNotNull(expiry) { "AUTHENTICATION_OWNER_UNAVAILABLE" }
            check(owner.owns(authDao)) { "AUTHENTICATION_OWNER_MISMATCH" }
            attempt = owner.beginAuthentication()
            val response = api.authenticateDevice(
                DeviceAuthDto(deviceKey = deviceKey, deviceId = deviceId)
            )
            deviceResponseReceived = true
            if (response.isSuccessful) {
                val body = response.body()!!

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
                check(owner.completeAuthentication(attempt, authState)) { "AUTHENTICATION_REJECTED" }
                Result.success(authState)
            } else {
                if (attempt != null) expiry?.authenticationFailed(attempt)
                response.errorBody()?.close()
                // Device key rejected — need fresh OTP
                Result.failure(Exception("Device auth failed: ${response.code()}"))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (attempt != null) expiry?.authenticationFailed(attempt) }
            throw e
        } catch (_: IOException) {
            if (deviceResponseReceived) {
                if (attempt != null) expiry?.authenticationFailed(attempt)
                return Result.failure(IllegalStateException("AUTHENTICATION_UNAVAILABLE"))
            }
            // Offline reuse still has to publish through the same owner. A bare
            // Result.success leaves AuthViewModel waiting forever for navigation.
            val latest = authDao.getAuthState()
            try {
                if (expiry?.owns(authDao) == true && attempt != null && latest == currentState &&
                    expiry.completeAuthentication(attempt, currentState)) {
                    Result.success(currentState)
                } else Result.failure(IllegalStateException("AUTHENTICATION_UNAVAILABLE"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.failure(IllegalStateException("AUTHENTICATION_UNAVAILABLE"))
            }
        } catch (_: Exception) {
            if (attempt != null) expiry?.authenticationFailed(attempt)
            Result.failure(IllegalStateException("AUTHENTICATION_UNAVAILABLE"))
        }
    }

    suspend fun logout() {
        if (expiry != null) expiry.logout() else authDao.clearAuth()
    }

    suspend fun cancelAuthentication() { expiry?.cancelAuthentication() }

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
            tenantId
        } catch (e: Exception) {
            null
        }
    }
}
