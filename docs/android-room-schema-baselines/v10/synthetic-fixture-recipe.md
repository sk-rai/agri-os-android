# Synthetic Room v10 migration fixture recipe

Design for review only. No database or migration has been run. Use the
[reviewed v10 export](com.agrios.app.core.database.AppDatabase/10.json)
with SHA-256 `EB8C48552F9C665B6B191697E2A6D547BC4C179246CDD3DE1A3864FA0228ACC6`.
Construct v10 directly; do not derive it by running migrations or opening
a current or historical application database.

## Literal data

Every unspecified nullable column is SQL NULL. Every required column is
listed explicitly, including SQL defaults. Quoted strings below use JSON
string notation decoded once; integers and binary-exact REALs are bound
as their specified types. Use no constructor defaults.

| Table | One row, except for the queue defined below |
| --- | --- |
| auth_state | id=1; user_id="fixture-a"; tenant_id="fixture-tenant"; role="FARMER"; is_authenticated=0; reauth_required=0. |
| geography_states | id="fixture-state"; lgd_code="fixture-s"; canonical_name="Synthetic state"; is_active=1. |
| geography_districts | id="fixture-district"; lgd_code="fixture-d"; state_id="fixture-state"; canonical_name="Synthetic district"; is_active=1. |
| geography_blocks | id="fixture-block"; lgd_code="fixture-b"; district_id="fixture-district"; canonical_name="Synthetic block"; is_active=1. |
| geography_villages | id="fixture-village"; lgd_code="fixture-v"; block_id="fixture-block"; district_id="fixture-district"; canonical_name="Synthetic village"; is_active=1. |
| crop_categories | id="fixture-category"; name="Synthetic category"; is_active=1. |
| crops | id="fixture-crop"; crop_code="FIXTURE"; canonical_name="Synthetic crop"; category_id="fixture-category"; season="FIXTURE"; is_active=1. |
| farmers_local | id="fixture-farmer"; mobile_number="synthetic-not-a-phone"; village_id="fixture-village"; display_name="Synthetic farmer"; assistance_mode="DEALER_ASSISTED"; sync_status="PENDING"; created_at=100; updated_at=200; actor_id="fixture-actor"; origin_tenant_id="fixture-tenant"; origin_user_id="fixture-a". |
| parcels_local | id="fixture-parcel"; farmer_id="fixture-farmer"; village_id="fixture-village"; reported_area=1.5; reported_area_unit="BIGHA"; area_hectares=0.125; geometry_source="NONE"; ownership_type="OWNED"; survey_number=""; sync_status="PENDING"; created_at=300; updated_at=400; actor_id="fixture-actor"; origin_tenant_id="fixture-tenant"; origin_user_id="fixture-a". |
| soil_profiles_local | id="fixture-soil"; parcel_id="fixture-parcel"; farmer_id="fixture-farmer"; ph=6.5; nitrogen_n=1.25; data_source="MANUAL"; sync_status="FAILED"; created_at=500; updated_at=600; actor_id="fixture-actor". |
| cache_metadata | entity_type="fixture-cache"; server_version="fixture-version"; last_synced_at=700; record_count=1. |

All three queue rows have entity_type="FARMER", entity_id="fixture-farmer",
operation="UPDATE", max_retries=10 and priority="HIGH".

| event_id | sync_status | retry_count | next_retry_after | payload | dependency_ids | last_error | version | created_at | origin_tenant_id | origin_user_id | dispatch_reserved | dispatch_claim_id |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| "fixture-legacy" | "PENDING" | 0 | NULL | "{}" | NULL | "" | 1 | 801 | NULL | NULL | 0 | NULL |
| "fixture-held" | "FAILED" | 3 | 900 | "opaque:\u00e9:e\u0301:\u0000:\r\n:end" | "parent-z,parent-a,parent-z" | " " | 2 | 802 | "fixture-tenant" | "fixture-a" | 1 | NULL |
| "fixture-claimed" | "SYNCING" | 1 | NULL | "{\"fixture\":\"in-flight\"}" | "" | "error:\u0000:end" | 3 | 803 | "fixture-tenant" | "fixture-a" | 1 | "00000000-0000-4000-8000-000000000001" |

These three event states are materialization inputs, not proof of a prior
dispatch. Authentication is inactive and all credential fields stay NULL.
Expect exactly 14 entity rows and 172 stored field values, including NULLs.

## Construction and independent checks

After separate implementation approval, reuse the reviewed disposable
schema-construction pattern: pinned DDL and setup allowlist, closed table
names, exact placeholder substitution, bound values, and one creation
transaction. Set SQLite `PRAGMA user_version = 10` within that transaction,
before commit; the Room setup statements do not set it. Use a new
canonical case directory under the approved host
root. Permit only `synthetic-v10.db` and its exact journal, WAL, SHM and
lock names; route helper locks into the case. Check the entire inventory
before opening or deleting anything. Reopen for validation with an
existing-file-only read-only engine path. Reject corruption without
deletion or fallback. After confirmed closure, remove only validated
case-owned files; preserve failed cases and report any incomplete cleanup.
No retained binary fixture is required.

Keep validation expectations separate from resolved insert arguments and
generated rows. On the existing-file-only read-only reopen, independently
query `PRAGMA user_version` and require exactly one result with value 10.
Separately read `room_master_table` and require exactly one row: ID 42
and `identity_hash` exactly equal to the reviewed v10 export's declared
identity hash. Reject a mismatch without repairing either value.
Check the independent table and column inventory against
the reviewed export, SQLite storage types and NULLs, row IDs and exact
counts, and all 172 literal values against this recipe. Check TEXT using
SQLite-side UTF-8 byte read-back, not string normalization. Before any
later upgrade, capture an unfiltered typed snapshot of all twelve tables.
The later test must observe an actual `10 → 11` upgrade on the existing
file; stamping v10 metadata is not evidence that migration ran. The
generated v11 validation must precede a second full comparison
against that snapshot and the literal expectations, with no intervening
auth, recovery, queue, or cache writes. Cache tables must initially be
empty. Fresh-v11 parity and normal reopen are later migration tests.

Review the recipe before writing a constructor. Construction execution,
the version bump, and migration execution remain separately gated.
Home automatic restore remains disabled.
`PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.
