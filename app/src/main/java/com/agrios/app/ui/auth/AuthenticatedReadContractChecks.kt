package com.agrios.app.ui.auth

import android.content.pm.ApplicationInfo
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.agrios.app.AgriOsApp
import com.agrios.app.core.network.ApiConfig
import com.agrios.app.core.network.AuthInterceptor
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Debug-only contract evidence, NOT screen coverage. Never emits bodies/credentials. */
@Composable
fun AuthenticatedReadContractChecks() {
    val app = AgriOsApp.instance
    if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    var eligible by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("Contract checks not run") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val auth = withContext(Dispatchers.IO) { app.database.authDao().getAuthState() }
        eligible = auth?.tenantId == "android-dynamic-test" && auth.userId in setOf(
            "192b6593-aee6-4b12-8ac6-7d3caeffabd6", "4df387e8-114f-5c44-a129-a9d000000002")
    }
    if (!eligible) return
    Column {
        OutlinedButton(enabled = !busy, modifier = Modifier.testTag("authenticated_contract_checks"), onClick = {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    try { runContractChecks() }
                    catch (e: SafeContractFailure) { e.message.orEmpty() }
                    catch (_: Exception) { "Contract checks failed: session, schema or network error" }
                }
                busy = false
            }
        }) { Text("Check authenticated read contracts (not UI coverage)") }
        result.lineSequence().forEach { Text(it) }
    }
}

private suspend fun runContractChecks(): String {
    val db = AgriOsApp.instance.database
    val auth = checkNotNull(db.authDao().getAuthState())
    val claims = JsonParser.parseString(String(Base64.decode(checkNotNull(auth.jwt).split('.')[1], Base64.URL_SAFE))).asJsonObject
    // Comparison only: no claims or headers ever leave memory. Backend validates JWT.
    val identityMatch = claims.get("sub")?.asString == auth.userId &&
        claims.get("tenant_id")?.asString == auth.tenantId && auth.tenantId == "android-dynamic-test"
    check(identityMatch)
    val farmer = checkNotNull(db.farmerDao().getFirst())
    val parcel = checkNotNull(db.parcelDao().getAll().firstOrNull { it.farmerId == farmer.id })
    val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(db.authDao()))
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    val lines = mutableListOf<String>()
    fun get(route: String, path: String, expected: Set<Int> = setOf(200)): Pair<Int, JsonElement?> {
        val start = System.nanoTime()
        return client.newCall(Request.Builder().url(ApiConfig.BASE_URL + path).get().build()).execute().use {
            val status = it.code
            lines += "contract_route=$route status=$status duration_ms=${(System.nanoTime()-start)/1_000_000} identity_match=$identityMatch"
            if (status !in expected) throw SafeContractFailure(lines.joinToString("\n") + "\nContract checks failed")
            status to if (status == 200) JsonParser.parseString(checkNotNull(it.body?.string())) else null
        }
    }
    val project = "0f7e0a6b-8472-5d6d-8a14-a9d000000001"
    val catalog = checkNotNull(get("workflow_catalog", "workflow-catalog/enabled-crop-workflows?project_id=$project&crop_code=RICE&season=KHARIF").second).asJsonObject
    val workflows = catalog.getAsJsonArray("workflows")
    check(catalog.get("schema_version")?.asString == "1.0.0" && workflows.size() > 0)
    val version = workflows.first().asJsonObject.get("workflow_template_version_id").asString
    check(workflows.first().asJsonObject.get("status").asString == "PUBLISHED")
    val preview = checkNotNull(get("workflow_preview", "workflow-catalog/workflow-preview/$version?project_id=$project").second).asJsonObject
    check(preview.get("schema_version")?.asString == "1.0.0" && preview.getAsJsonObject("android_preview").getAsJsonArray("stages").size() > 0)
    val list = checkNotNull(get("enrichment_list", "soil-profiles/enrichments?parcel_id=${parcel.id}").second).asJsonArray
    val summary = checkNotNull(get("enrichment_summary", "soil-profiles/enrichments/summary?parcel_id=${parcel.id}").second).asJsonObject
    check(validEnrichmentSummary(summary, auth.tenantId.orEmpty(), list.size()))
    val latest = get("enrichment_latest", "soil-profiles/enrichments/latest?parcel_id=${parcel.id}", if (list.size() == 0) setOf(404) else setOf(200))
    lines += "contract_schema_counts_valid=true workflow_count=${workflows.size()} enrichment_count=${list.size()}"
    lines += "contract_expected_empty_enrichment=${list.size() == 0 && latest.first == 404}"
    lines += "Contract checks passed (not UI coverage)"
    return lines.joinToString("\n")
}

private class SafeContractFailure(message: String) : IllegalStateException(message)

internal fun validEnrichmentSummary(summary: com.google.gson.JsonObject, tenant: String, count: Int): Boolean =
    runCatching {
        summary.get("schema_version")?.asString == "soil_enrichment_summary.v1" &&
            summary.get("tenant_id")?.asString == tenant &&
            summary.get("snapshot_count")?.asInt == count
    }.getOrDefault(false)
