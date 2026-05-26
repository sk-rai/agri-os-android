package com.agrios.app.core.network

import com.agrios.app.data.local.dao.AuthDao
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that auto-injects auth headers on every request.
 * Extracts JWT, tenant_id, and user_id from local auth state.
 */
class AuthInterceptor(private val authDao: AuthDao) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth injection for auth endpoints
        if (originalRequest.url.encodedPath.contains("/auth/")) {
            return chain.proceed(originalRequest)
        }

        val authState = runBlocking { authDao.getAuthState() }

        val request = if (authState?.jwt != null) {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer ${authState.jwt}")
                .addHeader("X-Tenant-ID", authState.tenantId ?: "")
                .addHeader("X-Actor-ID", authState.userId ?: "")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}
