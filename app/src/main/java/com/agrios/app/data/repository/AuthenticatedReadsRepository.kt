package com.agrios.app.data.repository

import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.ConnectionPool
import java.net.InetSocketAddress
import java.net.Proxy
import android.system.ErrnoException
import android.system.OsConstants
import android.content.pm.ApplicationInfo
import android.util.Log
import com.agrios.app.AgriOsApp
import java.util.concurrent.TimeUnit

/** Scoped to preserved operational reads; do not change shared/auth/sync clients. */
internal fun authenticatedReadClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
    // Discard idle sockets before the local backend's five-second keep-alive expiry.
    .connectionPool(ConnectionPool(5, 2, TimeUnit.SECONDS))
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)

/** Operational GETs only. No response/header logging, persistence, or public fallback. */
class AuthenticatedReadsRepository(authDao: AuthDao) {
    private val client = authenticatedReadClientBuilder().addInterceptor(AuthInterceptor(authDao))
        .eventListenerFactory { call ->
            val tracker = call.request().tag(ReadTransportTrace::class.java)
            object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) { tracker?.dnsStart() }
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { tracker?.connectStart() }
                override fun secureConnectStart(call: Call) { tracker?.tlsStart() }
                override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { tracker?.connectEnd() }
                override fun connectionAcquired(call: Call, connection: okhttp3.Connection) { tracker?.connectionAcquired() }
                override fun requestHeadersStart(call: Call) { tracker?.writing() }
                override fun responseHeadersStart(call: Call) { tracker?.reading() }
                override fun responseBodyStart(call: Call) { tracker?.reading() }
            }
        }
        .build()

    private suspend fun get(route: String, path: String, query: Map<String, String?> = emptyMap()): ReadResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val tracker = ReadTransportTrace()
        var receivedStatus: Int? = null
        fun elapsed() = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
        fun recorded(result: ReadResult): ReadResult {
            val measured = result.copy(transportPhase = tracker.phase, connectionReused = tracker.connectionReused)
            if (AgriOsApp.instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.i("AuthenticatedRead", measured.diagnostic.toJson())
            }
            return measured
        }
        try {
            val url = (ApiConfig.BASE_URL + path).toHttpUrl().newBuilder().apply {
                query.forEach { (key, value) -> if (!value.isNullOrBlank()) addQueryParameter(key, value) }
            }.build()
            client.newCall(Request.Builder().url(url).tag(ReadTransportTrace::class.java, tracker).get().build()).execute().use { response ->
                receivedStatus = response.code
                val value = if (response.isSuccessful) runCatching {
                    JsonParser.parseString(checkNotNull(response.body?.string()))
                }.getOrNull() else null
                ReadResult(route, response.code, elapsed(), value)
            }.let(::recorded)
        } catch (e: CancellationException) {
            recorded(ReadResult(route, receivedStatus, elapsed(), null, ReadFailureCategory.CANCELLED, readExceptionCodes(e)))
            throw e
        } catch (e: Exception) {
            var cause: Throwable? = e
            var socket = SocketEvidence.NONE
            repeat(12) {
                val item = cause
                if (item is ErrnoException) {
                    if (item.errno == OsConstants.ECONNREFUSED) socket = SocketEvidence.REFUSED
                    if (item.errno == OsConstants.ECONNRESET) socket = SocketEvidence.RESET
                }
                cause = item?.cause
            }
            recorded(ReadResult(route, receivedStatus, elapsed(), null, classifyReadFailure(e, tracker.phase, socket), readExceptionCodes(e)))
        }
    }

    suspend fun profile() = get("profile", "farmers/me/profile")
    suspend fun soil(parcelId: String) = get("soil_profiles", "soil-profiles", mapOf("parcel_id" to parcelId))
    suspend fun context(pin: String, project: String?) = get("land_context", "profile/land-intelligence-context", mapOf("pin_code" to pin, "project_id" to project))
    suspend fun summary(pin: String, project: String?, language: String) = get("land_summary", "profile/land-intelligence-summary", mapOf("pin_code" to pin, "project_id" to project, "language_code" to language))
    suspend fun eligibility(farmerId: String, season: String, project: String?) = get("eligible_parcels", "crop-cycles/eligible-parcels", mapOf("farmer_id" to farmerId, "season" to season, "project_id" to project))
    suspend fun assigned(project: String?) = get("assigned_work", "field-agent/worklist", mapOf("project_id" to project, "assigned_only" to "true"))
    suspend fun assignedFarmer(id: String): ReadResult {
        java.util.UUID.fromString(id)
        return get("assigned_farmer", "farmers/$id/launch-context")
    }
}

data class ReadResult(val route: String, val status: Int?, val durationMs: Long, val value: JsonElement?, val failureCategory: ReadFailureCategory? = null,
                      val exceptionCodes: List<ReadExceptionCode> = emptyList(), val transportPhase: ReadPhase = ReadPhase.UNKNOWN,
                      val connectionReused: Boolean? = null) {
    val successful: Boolean get() = status == 200 && value != null && !value.isJsonNull
    // Prevent accidental diagnostic rendering of operational payloads.
    val diagnostic: ReadDiagnostic get() {
        val expected = ReadDiagnostic.EXPECTED_SCHEMAS[route]
        val matches = expected?.let { value?.takeIf { it.isJsonObject }?.asJsonObject?.stringValue("schema_version") == it }
        return ReadDiagnostic(route, status, durationMs, failureCategory, matches, exceptionCodes, transportPhase, connectionReused)
    }
    override fun toString() = diagnostic.toJson()
}

fun readFailureCopy(status: Int?): String = when (status) {
    401 -> "Your session has expired. Sign in again from Settings."
    403 -> "Access denied. This account cannot view this information."
    404 -> "This information is unavailable or not shared with this account."
    200 -> "The server response could not be read."
    else -> "Unable to load information. Check your connection and try again."
}

fun JsonObject.stringValue(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
fun backendLabel(value: JsonElement?, language: String): String = when {
    value == null || value.isJsonNull -> ""
    value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
    value.isJsonObject -> value.asJsonObject.stringValue(language) ?: value.asJsonObject.stringValue("en").orEmpty()
    else -> ""
}

fun assignedRows(result: ReadResult, tenant: String, project: String? = null): List<JsonObject>? = runCatching {
    check(result.successful)
    val root = result.value!!.asJsonObject
    check(root.stringValue("schema_version") == "field_agent_worklist.v1" && root.stringValue("tenant_id") == tenant)
    if (!project.isNullOrBlank()) check(root.getAsJsonObject("filters").stringValue("project_id") == project)
    val rows = root.getAsJsonArray("farmers").map { it.asJsonObject }
    check(rows.all { it.getAsJsonObject("farmer").stringValue("id") != null })
    rows
}.getOrNull()

fun landSummaryPayload(result: ReadResult, tenant: String, language: String): JsonObject? = runCatching {
    check(result.successful)
    val root = result.value!!.asJsonObject
    check(root.stringValue("schema_version") == "land_intelligence_summary.v1" && root.stringValue("tenant_id") == tenant)
    val contract = root.getAsJsonObject("android_contract")
    check(contract.get("display_as_informational_only").asBoolean && contract.get("do_not_block_onboarding").asBoolean)
    val payload = root.getAsJsonObject("summary_payload")
    check(backendLabel(payload.get("title"), language).isNotBlank())
    val cards = payload.getAsJsonArray("cards")
    check(cards.size() > 0 && cards.all {
        val card = it.asJsonObject
        backendLabel(card.get("title"), language).isNotBlank() && backendLabel(card.get("value"), language).isNotBlank()
    })
    payload
}.getOrNull()

fun availabilityRows(result: ReadResult, farmerId: String): List<JsonObject>? = runCatching {
    check(result.successful)
    val rows = result.value!!.asJsonArray.map { it.asJsonObject }
    check(rows.all { row -> row.stringValue("farmer_id") == farmerId && row.stringValue("parcel_id") != null && row.stringValue("eligibility_status") != null })
    rows
}.getOrNull()

fun landGuidance(result: ReadResult, tenant: String): String? = runCatching {
    check(result.successful)
    val root = result.value!!.asJsonObject
    check(root.stringValue("schema_version") == "land_intelligence_context.v1" && root.stringValue("tenant_id") == tenant)
    root.getAsJsonObject("soil_capture_guidance").stringValue("message")?.takeIf { it.isNotBlank() }
}.getOrNull()
