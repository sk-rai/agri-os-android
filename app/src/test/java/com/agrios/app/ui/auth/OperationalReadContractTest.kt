package com.agrios.app.ui.auth

import com.agrios.app.data.repository.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OperationalReadContractTest {
    @Test fun denialCannotBecomeEmptyWorklist() {
        val empty = JsonParser.parseString("""{"schema_version":"field_agent_worklist.v1","tenant_id":"android-persona-lifecycle-test","farmers":[]}""")
        assertNotNull(assignedRows(ReadResult("assigned_work", 200, 1, empty), "android-persona-lifecycle-test"))
        assertNull(assignedRows(ReadResult("assigned_work", 403, 1, empty), "android-persona-lifecycle-test"))
        assertNull(assignedRows(ReadResult("assigned_work", 401, 1, empty), "android-persona-lifecycle-test"))
        assertNull(assignedRows(ReadResult("assigned_work", 200, 1, empty), "android-dynamic-test"))
        assertNull(assignedRows(ReadResult("assigned_work", 200, 1, JsonParser.parseString("{}")), "android-persona-lifecycle-test"))
        assertNull(assignedRows(ReadResult("assigned_work", 200, 1, empty), "android-persona-lifecycle-test", "project-a"))
        empty.asJsonObject.add("filters", JsonParser.parseString("""{"project_id":"project-a"}"""))
        assertNotNull(assignedRows(ReadResult("assigned_work", 200, 1, empty), "android-persona-lifecycle-test", "project-a"))
    }

    @Test fun visibleLabelsUseBackendMapsNotRawJson() {
        val map = JsonParser.parseString("""{"en":"Backend land guidance","hi":"Backend Hindi label"}""")
        assertEquals("Backend Hindi label", backendLabel(map, "hi"))
        assertEquals("Backend land guidance", backendLabel(map, "kn"))
        assertEquals("", backendLabel(JsonParser.parseString("[]"), "en"))
    }

    @Test fun failureCopySeparatesSessionDenialMissingAndNetwork() {
        assertTrue(readFailureCopy(401).contains("session"))
        assertTrue(readFailureCopy(403).contains("Access denied"))
        assertTrue(readFailureCopy(404).contains("unavailable"))
        assertTrue(readFailureCopy(null).contains("connection"))
    }

    @Test fun diagnosticStateDoesNotExposePayload() {
        val result = ReadResult("profile", 200, 1, JsonParser.parseString("""{"private":"synthetic-private-payload"}"""))
        assertFalse(result.toString().contains("synthetic-private-payload"))
        assertFalse(result.toString().contains("private"))
    }

    @Test fun availabilityRejectsWrongFarmerAndDenial() {
        val body = JsonParser.parseString("""[{"farmer_id":"farmer-a","parcel_id":"parcel-a","eligibility_status":"HAS_ACTIVE_CYCLE"}]""")
        assertEquals(1, availabilityRows(ReadResult("eligible_parcels", 200, 1, body), "farmer-a")?.size)
        assertNull(availabilityRows(ReadResult("eligible_parcels", 200, 1, body), "farmer-b"))
        assertNull(availabilityRows(ReadResult("eligible_parcels", 403, 1, body), "farmer-a"))
        assertNull(availabilityRows(ReadResult("eligible_parcels", 200, 1, JsonParser.parseString("{}")), "farmer-a"))
    }

    @Test fun summaryRequiresInformationalContractAndReadableBackendCards() {
        val body = JsonParser.parseString("""{"schema_version":"land_intelligence_summary.v1","tenant_id":"test","android_contract":{"display_as_informational_only":true,"do_not_block_onboarding":true},"summary_payload":{"title":{"en":"Land summary"},"cards":[{"title":{"en":"Region"},"value":{"en":"Backend region"}}]}}""")
        assertNotNull(landSummaryPayload(ReadResult("land_summary", 200, 1, body), "test", "kn"))
        assertNull(landSummaryPayload(ReadResult("land_summary", 401, 1, body), "test", "kn"))
        assertNull(landSummaryPayload(ReadResult("land_summary", 200, 1, body), "other", "kn"))
        body.asJsonObject.getAsJsonObject("android_contract").addProperty("do_not_block_onboarding", false)
        assertNull(landSummaryPayload(ReadResult("land_summary", 200, 1, body), "test", "en"))
    }

    @Test fun guidanceIsBackendTextAndRejectsWrongTenant() {
        val body = JsonParser.parseString("""{"schema_version":"land_intelligence_context.v1","tenant_id":"test","soil_capture_guidance":{"message":"Backend field guidance"}}""")
        assertEquals("Backend field guidance", landGuidance(ReadResult("land_context", 200, 1, body), "test"))
        assertNull(landGuidance(ReadResult("land_context", 200, 1, body), "other"))
        assertNull(landGuidance(ReadResult("land_context", 403, 1, body), "test"))
    }
}
