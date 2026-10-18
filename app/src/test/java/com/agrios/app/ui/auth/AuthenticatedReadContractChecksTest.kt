package com.agrios.app.ui.auth

import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedReadContractChecksTest {
    @Test fun emptySummaryNeedsSchemaTenantAndCount() {
        val summary = JsonParser.parseString("""{"schema_version":"soil_enrichment_summary.v1","tenant_id":"android-dynamic-test","snapshot_count":0}""").asJsonObject
        assertTrue(validEnrichmentSummary(summary, "android-dynamic-test", 0))
        assertFalse(validEnrichmentSummary(summary, "android-persona-lifecycle-test", 0))
        assertFalse(validEnrichmentSummary(summary, "android-dynamic-test", 1))
        summary.remove("schema_version")
        assertFalse(validEnrichmentSummary(summary, "android-dynamic-test", 0))
    }

    @Test fun errorObjectIsNotEmptySuccess() {
        val denied = JsonParser.parseString("""{"detail":"Forbidden"}""").asJsonObject
        assertFalse(validEnrichmentSummary(denied, "android-dynamic-test", 0))
    }
}
