# Synthetic Room v10 to v11 hydration-cache migration

Source review only. This slice adds three empty cache tables and two
owner-scoped unique indexes. It does not add a cache DAO, hydration writer,
API admission, or UI wiring. Home automatic restore remains disabled.

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
generated v11 schema artifact and behavioral tests of the future cache
DAO remain separate outputs; this patch does not claim either.

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
`PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.
