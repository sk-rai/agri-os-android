package com.agrios.app.core.sync

/** Terminal local disposition of a correlated response. Uncertain rows are not settled. */
enum class QueueClaimOutcome(val status: String) { ACCEPTED("SYNCED"), CONFLICT("CONFLICTED"), REJECTED("FAILED") }

class QueueClaimSettlement(val eventId: String, val outcome: QueueClaimOutcome, val error: String? = null) {
    override fun toString() = "QueueClaimSettlement(REDACTED)"
}

internal class QueueClaimMismatch : IllegalStateException("QUEUE_CLAIM_PRECONDITION_FAILED")
