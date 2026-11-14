package com.agrios.app.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** Backend-origin scoped, exact reference exceptions. Unknown API routes fail protected. */
internal object SessionRoutePolicy {
    private val backend = ApiConfig.BASE_URL.toHttpUrl()
    private val publicReads = setOf("app-config/bootstrap", "master-data/geography/states",
        "master-data/geography/districts", "master-data/geography/blocks", "master-data/geography/villages",
        "master-data/geography/villages/search", "master-data/geography/hierarchy-profile",
        "master-data/geography/villages/by-pin-code", "master-data/crops", "master-data/crops/categories",
        "forms/profile-contract", "forms/options", "forms/metadata/season-land-units",
        "soil-profiles/enrichments/source-contract")
    fun backendRequest(request: Request) = request.url.scheme == backend.scheme &&
        request.url.host == backend.host && request.url.port == backend.port &&
        request.url.encodedPath.startsWith(backend.encodedPath)
    fun authentication(request: Request): Boolean = request.url.encodedPath.removePrefix(backend.encodedPath) in
        setOf("auth/otp/request", "auth/otp/verify", "auth/device")
    fun protected(request: Request): Boolean {
        if (!backendRequest(request) || authentication(request)) return false
        val path = request.url.encodedPath.removePrefix(backend.encodedPath)
        if (request.method != "GET") return true
        return !(path in publicReads || Regex("forms/[A-Za-z0-9_-]+").matches(path) ||
            Regex("forms/options/[A-Za-z0-9_-]+").matches(path) ||
            Regex("crop-cycles/templates/[A-Za-z0-9_-]+").matches(path) ||
            Regex("soil-profiles/infer/[^/]+").matches(path))
    }
}
