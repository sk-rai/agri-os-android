# Synthetic v10 construction-only host test

Source review only. The constructor uses the hash-pinned v10 export and
[reviewed literal recipe](../../../docs/android-room-schema-baselines/v10/synthetic-fixture-recipe.md).
It binds values parsed from the recipe, then validates the stored values
against a separately transcribed Kotlin expectation set. Input pins bind the
UTF-8 LF bytes; Windows Git checkout CRLF is restored to LF before checking
the pins, while JSON string escapes inside data are unchanged.

The test exclusively creates an empty file in a fresh disposable case.
sqlite4java `open(false)` opens that existing file once for creation in an
explicit transaction. There is no AndroidX open helper, callback retry,
or Android context deletion path. TEXT values bind as UTF-8 bytes and are
cast to SQLite TEXT to preserve embedded NUL. Only a successful commit
and confirmed closure permit the second open, direct sqlite4java
`openReadonly()`, after checking the complete case inventory.
It checks SQLite version, Room identity, schema objects and
all stored rows. It does not open generated Room, migrate, or load the
production Application. Errors retain the case; cleanup failures report
incomplete cleanup. The success marker is emitted after checked deletion.

After separate execution approval, use the already provisioned guarded
Windows offline host environment from the [harness notes](README.md).
Run only the following exact test filter:

```powershell
$previous = $env:OWNERSHIP_V10_CONSTRUCTION
try {
    $env:OWNERSHIP_V10_CONSTRUCTION = 'APPROVED_SYNTHETIC_V10_CONSTRUCTION'
    .\gradlew.bat :app:testDebugUnitTest -PownershipRoomHost=true --tests com.agrios.app.core.sync.V10FixtureConstructionHostTest --offline '-Dorg.gradle.java.installations.auto-download=false' --init-script tools/ownership-host-task-guard.gradle
} finally {
    $env:OWNERSHIP_V10_CONSTRUCTION = $previous
}
```

Require one executed test, no skips, a fresh passing XML report, and one
`V10_CONSTRUCTION_VERIFIED` completion marker. Scan captured output and
XML with counts only. Do not run any migration, APK or device task.
`PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.
