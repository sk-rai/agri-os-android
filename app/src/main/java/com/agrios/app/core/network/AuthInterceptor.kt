package com.agrios.app.core.network

import com.agrios.app.core.auth.SessionExpiryCoordinator
import com.agrios.app.data.local.dao.AuthDao
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Backend-scoped header injection and application-owned, bounded 401 recovery.
 * Extracts JWT, tenant_id, and user_id from local auth state.
 *
 * Required headers for backend:
 * - Authorization: Bearer <jwt>
 * - X-Tenant-ID: <tenant_id> (REQUIRED — backend returns 400 if missing/empty)
 * - X-Actor-ID: <user_id>
 */
class AuthInterceptor(private val authDao: AuthDao,
    private val expiry: SessionExpiryCoordinator? = SessionExpiryCoordinator.registeredFor(authDao)) : Interceptor {

    companion object {
        // Legacy callers still reference this value; it is NOT an auth-header fallback.
        const val DEFAULT_TENANT_ID = "default"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth injection only for login/device-auth endpoints. Authenticated
        // bootstrap endpoints such as /auth/mode-bootstrap still need headers.
        if (!SessionRoutePolicy.backendRequest(originalRequest) || SessionRoutePolicy.authentication(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val protected = SessionRoutePolicy.protected(originalRequest)
        val selected = originalRequest.tag(com.agrios.app.core.sync.QueueDispatchLease::class.java)
        if (selected != null && (expiry !== selected.coordinator ||
                !runBlocking { selected.coordinator.isCurrentLease(selected.selected) })) {
            return Response.Builder().request(originalRequest).protocol(Protocol.HTTP_1_1).code(409)
                .message("QUEUE_SESSION_CHANGED").body("".toResponseBody()).build()
        }
        val lease = selected?.selected ?: runBlocking { expiry?.requestLease() }
        val authState = runBlocking { lease?.state ?: if (expiry == null) authDao.getAuthState() else null }
            ?.takeIf { it.isAuthenticated && !it.reauthRequired && !it.jwt.isNullOrBlank() &&
                !it.userId.isNullOrBlank() && !it.tenantId.isNullOrBlank() }

        if (protected && expiry != null && authState == null) {
            runBlocking { expiry.requireLogin() }
            return Response.Builder().request(originalRequest).protocol(Protocol.HTTP_1_1).code(401)
                .message("SESSION_REQUIRED").body("".toResponseBody()).build()
        }

        val request = if (authState?.jwt != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer ${authState.jwt}")
                .header("X-Tenant-ID", authState.tenantId!!)
                .header("X-Actor-ID", authState.userId!!)
                .build()
        } else {
            originalRequest.newBuilder().removeHeader("Authorization").removeHeader("X-Tenant-ID").removeHeader("X-Actor-ID").build()
        }

        // Exactly one proceed; no refresh, retry, anonymous or tenant-scope fallback.
        val response = chain.proceed(request)
        if (protected && lease != null && response.code == 401) runBlocking { expiry?.unauthorized(lease) }
        return response
    }
}
