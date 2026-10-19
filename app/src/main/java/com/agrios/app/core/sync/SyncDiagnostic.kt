package com.agrios.app.core.sync

enum class SyncOutcome { RESPONSE, RESPONSE_UNCERTAIN, HTTP_FAILURE, SERVER_REJECTED, TRANSPORT_UNCERTAIN }
enum class SyncFailure { MATERIALIZATION_FAILED, DEPENDENCY_MISSING, OTHER_SERVER_FAILURE }
fun safeSyncFailure(code: String?): SyncFailure = when (code) {
    "MATERIALIZATION_FAILED" -> SyncFailure.MATERIALIZATION_FAILED
    "DEPENDENCY_MISSING" -> SyncFailure.DEPENDENCY_MISSING
    else -> SyncFailure.OTHER_SERVER_FAILURE
}
/** No arbitrary strings, exceptions, payloads, identifiers or credentials. */
data class SyncDiagnostic(val outcome: SyncOutcome, val accepted: Int = 0,
    val conflicts: Int = 0, val failed: Int = 0, val httpStatus: Int? = null,
    val failure: SyncFailure? = null)
