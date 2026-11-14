package com.agrios.app.core.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Low-volume, single-use authentication must not depend on an older idle socket. */
internal fun authenticationHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(ApiConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(ApiConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    // Close a connection when its call releases it. Do not retry an uncertain
    // verification POST: a later user action starts a different, explicit call.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .build()
