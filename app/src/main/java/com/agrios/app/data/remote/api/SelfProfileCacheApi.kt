package com.agrios.app.data.remote.api

import com.agrios.app.core.sync.QueueDispatchLease
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Tag

/** The dormant legacy hydration routes cannot populate the owner-scoped cache. */
internal interface SelfProfileCacheApi {
    @Streaming
    @GET("farmers/me/profile")
    suspend fun get(@Tag lease: QueueDispatchLease): Response<ResponseBody>
}
