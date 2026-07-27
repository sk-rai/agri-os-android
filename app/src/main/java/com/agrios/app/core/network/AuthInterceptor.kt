package com.agrios.app.core.network

import android.util.Log
import com.agrios.app.data.local.dao.AuthDao
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that auto-injects auth headers on every request.
 * Extracts JWT, tenant_id, and user_id from local auth state.
 *
 * Required headers for backend:
 * - Authorization: Bearer <jwt>
 * - X-Tenant-ID: <tenant_id> (REQUIRED — backend returns 400 if missing/empty)
 * - X-Actor-ID: <user_id>
 */
class AuthInterceptor(private val authDao: AuthDao) : Interceptor {

    companion object {
        private const val TAG = "AuthInterceptor"
        // Default tenant for single-tenant pilot deployment.
        // Backend requires a non-empty X-Tenant-ID on every request.
        // If auth response didn't include tenant_id, use this fallback.
        const val DEFAULT_TENANT_ID = "default"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth injection only for login/device-auth endpoints. Authenticated
        // bootstrap endpoints such as /auth/mode-bootstrap still need headers.
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/otp/") || path.endsWith("/auth/device")) {
            return chain.proceed(originalRequest)
        }

        val authState = runBlocking { authDao.getAuthState() }

        val request = if (authState?.jwt != null) {
            val tenantId = authState.tenantId?.takeIf { it.isNotBlank() } ?: DEFAULT_TENANT_ID
            val actorId = authState.userId ?: ""

            if (authState.tenantId.isNullOrBlank()) {
                Log.w(TAG, "tenant_id is null/empty in auth state — using fallback '$DEFAULT_TENANT_ID'. Backend may have not returned tenant_id during login.")
            }

            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer ${authState.jwt}")
                .addHeader("X-Tenant-ID", tenantId)
                .addHeader("X-Actor-ID", actorId)
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}
