package com.agrios.app.core.sync

/** Independently transcribed read-back literals. Never used as insert values. */
internal object V10FixtureExpected {
    val rows = mapOf(
        "auth_state" to one("id" to 1L, "user_id" to "fixture-a", "tenant_id" to "fixture-tenant",
            "role" to "FARMER", "is_authenticated" to 0L, "reauth_required" to 0L),
        "geography_states" to geo("state", "s"),
        "geography_districts" to geo("district", "d", "state_id" to "fixture-state"),
        "geography_blocks" to geo("block", "b", "district_id" to "fixture-district"),
        "geography_villages" to geo("village", "v", "block_id" to "fixture-block",
            "district_id" to "fixture-district"),
        "crop_categories" to one("id" to "fixture-category", "name" to "Synthetic category", "is_active" to 1L),
        "crops" to one("id" to "fixture-crop", "crop_code" to "FIXTURE", "canonical_name" to "Synthetic crop",
            "category_id" to "fixture-category", "season" to "FIXTURE", "is_active" to 1L),
        "farmers_local" to one("id" to "fixture-farmer", "mobile_number" to "synthetic-not-a-phone",
            "village_id" to "fixture-village", "display_name" to "Synthetic farmer", "assistance_mode" to "DEALER_ASSISTED",
            "sync_status" to "PENDING", "created_at" to 100L, "updated_at" to 200L, "actor_id" to "fixture-actor",
            "origin_tenant_id" to "fixture-tenant", "origin_user_id" to "fixture-a"),
        "parcels_local" to one("id" to "fixture-parcel", "farmer_id" to "fixture-farmer", "village_id" to "fixture-village",
            "reported_area" to 1.5, "reported_area_unit" to "BIGHA", "area_hectares" to 0.125,
            "geometry_source" to "NONE", "ownership_type" to "OWNED", "survey_number" to "", "sync_status" to "PENDING",
            "created_at" to 300L, "updated_at" to 400L, "actor_id" to "fixture-actor",
            "origin_tenant_id" to "fixture-tenant", "origin_user_id" to "fixture-a"),
        "soil_profiles_local" to one("id" to "fixture-soil", "parcel_id" to "fixture-parcel",
            "farmer_id" to "fixture-farmer", "ph" to 6.5, "nitrogen_n" to 1.25, "data_source" to "MANUAL",
            "sync_status" to "FAILED", "created_at" to 500L, "updated_at" to 600L, "actor_id" to "fixture-actor"),
        "cache_metadata" to one("entity_type" to "fixture-cache", "server_version" to "fixture-version",
            "last_synced_at" to 700L, "record_count" to 1L),
        "sync_queue" to listOf(
            queue("legacy", "PENDING", 0, null, "{}", null, "", 1, 801, false, null),
            queue("held", "FAILED", 3, 900, "opaque:\u00e9:e\u0301:\u0000:\r\n:end",
                "parent-z,parent-a,parent-z", " ", 2, 802, true, null),
            queue("claimed", "SYNCING", 1, null, "{\"fixture\":\"in-flight\"}", "", "error:\u0000:end",
                3, 803, true, "00000000-0000-4000-8000-000000000001")
        )
    )

    private fun one(vararg pairs: Pair<String, Any?>) = listOf(mapOf(*pairs))

    private fun geo(name: String, code: String, vararg parents: Pair<String, String>) =
        one("id" to "fixture-$name", "lgd_code" to "fixture-$code", "canonical_name" to "Synthetic $name",
            "is_active" to 1L, *parents)

    private fun queue(id: String, status: String, retry: Long, next: Long?, payload: String, dependencies: String?,
                      error: String, version: Long, created: Long, held: Boolean, claim: String?) = mapOf(
        "event_id" to "fixture-$id", "entity_type" to "FARMER", "entity_id" to "fixture-farmer",
        "operation" to "UPDATE", "max_retries" to 10L, "priority" to "HIGH",
        "sync_status" to status, "retry_count" to retry, "next_retry_after" to next,
        "payload" to payload, "dependency_ids" to dependencies, "last_error" to error, "version" to version,
        "created_at" to created, "origin_tenant_id" to if (held) "fixture-tenant" else null,
        "origin_user_id" to if (held) "fixture-a" else null, "dispatch_reserved" to if (held) 1L else 0L,
        "dispatch_claim_id" to claim
    )
}
