# Synthetic Room v10 to v11 hydration-cache migration

The migration sub-slice added three empty cache tables and two owner-scoped
unique indexes. The separate storage slice described below adds a cache DAO
and store, with no API or UI caller. Home automatic restore remains disabled.

The separately filtered migration case reuses the pinned v10 constructor
and independent literal validator. It takes an unfiltered, typed snapshot
of all twelve historical tables, comparing TEXT at the SQLite byte
boundary and REAL by bits. It requires a generated v11 Room delegate and
an observed transactional upgrade from 10, with the actual registered
migration. Callback failures stay sticky across helper retries. Existing
upgrade connections must report v10 before that callback and v11 when
the framework reopens the connection afterward. Existing
case inventory and file checks bracket path resolution; creation, downgrade,
corruption recovery, and Android context deletion are rejected.

Before any auth, recovery, queue, or cache writes, the test compares all
historical rows against the validated v10 snapshot, checks all three cache
tables are empty, and compares the resulting metadata, columns, keys,
foreign keys and indexes against a separately generated fresh v11 database.
Normal reopen requires no upgrade and repeats the preservation and
schema checks. Only a successfully validated and closed case is cleaned.

Historical v9/v10 migration, claim and process cases are not current-schema
targets. Do not use a wildcard test filter or run those cases against
v11. Their previously accepted evidence remains version-specific. The
construction-only v10 test remains independent of generated Room. A
generated v11 schema artifact remains a separate output. The cache
DAO/store behavioral tests described below are included in the storage
slice. Independent review accepted the guarded offline host run: 5 tests,
0 failures, 0 errors, and 0 skips. Retained XML SHA-256:
`7CDC33BBEF67F35A82DED7B6B3DE4C61DC09027CE21F45E457621CBE6BEAA7F1`.
Input hash stability was operator-reported, not established by the XML.

After separate execution approval, run only the migration class in the
provisioned, guarded offline host environment:

```powershell
$previous = $env:OWNERSHIP_V10_TO_V11_MIGRATION
try {
    $env:OWNERSHIP_V10_TO_V11_MIGRATION = 'APPROVED_SYNTHETIC_V10_TO_V11_MIGRATION'
    .\gradlew.bat :app:testDebugUnitTest -PownershipRoomHost=true --tests com.agrios.app.core.sync.V10ToV11HydrationMigrationHostTest --offline '-Dorg.gradle.java.installations.auto-download=false' --init-script tools/ownership-host-task-guard.gradle
} finally {
    $env:OWNERSHIP_V10_TO_V11_MIGRATION = $previous
}
```

Require one executed testcase, a fresh passing XML report, and one
`V10_V11_MIGRATION_VERIFIED` marker after cleanup.

## Cache storage behavior (separate host filter)

`SelfProfileCacheRoomHostTest` uses fresh in-memory Room and no network.
It exercises owner and epoch changes, exact repeats, partial projections,
same-response parent requirements, reparent rejection, actual SQLite
foreign key and uniqueness constraints, and rollback after a late
collision or a forced soil insert failure. The storage boundary expects
an already verified server projection; no production API or UI caller
is introduced. The DAO is internal; guarded store methods are the
production access path. A later UI must still suppress stale results.

After separate approval, use the same offline host guard and
`-PownershipRoomHost=true` with the exact
`--tests com.agrios.app.core.sync.SelfProfileCacheRoomHostTest` filter.
Do not set the migration execution gate for this run.

## Unwired self-profile API boundary (separate host filter)

The new refresh boundary captures one lease, sends only the tagged
`GET /farmers/me/profile` request, and passes a validated projection to
the lease-bound cache store. It is not called by Home or the old
hydration path. A 404 never writes or deletes anything. The parser
rejects ambiguous or invalid success responses and bounds the streamed
success body, nesting and node count. It does not consume deferred fields
as cache data. Callback results are status-only; a future UI still needs
its own session-checked publication boundary.

After separate approval, use the same guarded offline command, unset the
migration gate, and filter to
`--tests com.agrios.app.core.sync.SelfProfileCacheRefreshHostTest`.
The Retrofit/OkHttp case terminates in a fake application interceptor;
it must not open a socket. Other cases use a fake API and generated
in-memory Room, without device work or live requests. Independent review
accepted the retained host report: 6 tests, 0 failures, 0 errors, 0 skips,
and 0 bounded privacy matches. XML SHA-256:
`5CB5DD7285D07BBC14688151521538BA22959A9783B84C35C953B8B2761DFE81`.
Offline flags and unchanged input hashes were operator-reported.

`PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.
