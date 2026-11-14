package com.agrios.app.data.local.entity

/** Nonsecret original local provenance, never server authorization. NULL means unproven. */
interface LocallyOwnedRecord {
    val originTenantId: String?
    val originUserId: String?
}

/** A cache refresh/upsert must neither erase provenance nor claim a legacy record. */
internal fun requireUnchangedLocalOrigin(previous: LocallyOwnedRecord?, next: LocallyOwnedRecord) {
    if (previous != null) {
        check(previous.originTenantId == next.originTenantId && previous.originUserId == next.originUserId) {
            "LOCAL_SOURCE_OWNER_IMMUTABLE"
        }
    }
}
