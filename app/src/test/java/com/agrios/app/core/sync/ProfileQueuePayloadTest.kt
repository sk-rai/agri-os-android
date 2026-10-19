package com.agrios.app.core.sync

import org.junit.Assert.*
import org.junit.Test

class ProfileQueuePayloadTest {
    @Test fun formSerializationKeepsContractFieldsWithoutInventingIdentity() {
        val serialized = profileQueuePayload(mapOf(
            "pin_code" to "560001", "assistance_mode" to "SELF_SERVICE",
            "project_id" to "synthetic-project", "display_name" to "synthetic-private-secret"))
        val presence = profileFieldPresence(serialized)
        assertTrue(presence.pin_code_present)
        assertTrue(presence.assistance_mode_present)
        assertTrue(presence.project_id_present)
        // Android normalizes project context, not a user_id field. The server
        // self-enrollment authorizer binds user_id to the verified principal.
        assertFalse(presence.user_id_present)
        assertFalse(presence.toString().contains("synthetic-private-secret"))
        println("{\"pin_code_present\":${presence.pin_code_present},\"assistance_mode_present\":${presence.assistance_mode_present},\"project_id_present\":${presence.project_id_present},\"user_id_present\":${presence.user_id_present}}")
    }

    @Test fun diagnosticCodeCannotEchoMessagesHeadersOrPayloads() {
        val unsafe = "Authorization Bearer device_key synthetic-private-secret"
        val record = SyncDiagnostic(SyncOutcome.SERVER_REJECTED, failed = 1, failure = safeSyncFailure(unsafe))
        assertEquals(SyncFailure.OTHER_SERVER_FAILURE, record.failure)
        listOf("Authorization", "Bearer", "device_key", "synthetic-private-secret").forEach { assertFalse(record.toString().contains(it)) }
    }
}
