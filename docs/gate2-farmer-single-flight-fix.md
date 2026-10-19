# Gate 2 farmer single-flight correction — verified

The original implementation checkpoint below was host-only. Later separately
approved device and backend checkpoints are summarized at the end. The existing
farmer and committed event must not be recreated.

## Observed facts and confidence

One user Save; farmer `107d7261-ca96-4e95-bb1a-8eb837f3388b`; queued event
`1a68ec4c-9d48-4b53-acff-7551e10e10ff`. Backend-owner read-only verification
found one durable farmer and one ACTIVE enrollment, final event COMMITTED, no
conflict, and an entity-linked COMMIT / FAILED / COMMIT audit sequence. Failure:
MATERIALIZATION_FAILED, DATABASE_UNIQUE_CONFLICT_FARMER_MOBILE.

Commit audit metadata does not embed the event ID. Do not claim all three audit
rows are independently event-ID-linked, or strict server exactly-once processing.
The Android code permits overlapping drains; tests deterministically reproduce
the vulnerable pre-claim selection window and verify it is now serialized. Which
historical triggers/HTTP attempts produced each audit is not established.

## Complete current-lane call graph

```text
DynamicFormScreen Save.onClick (no enrollment ViewModel/direct farmer POST)
  -> validate + normalizeProfileSubmitPayload
     -> withDynamicProjectId; farmer form retains pin_code/assistance_mode
  -> saveProfileFormLocally(FARMER) -> FarmerDao.insert
  -> SyncQueueDao.enqueue(FARMER CREATE, generated event UUID)
  -> SyncWorker.triggerImmediateSync
     -> unique one-time work: agrios_sync_worker_immediate
     -> SyncWorker.doWork -> ProfileSyncRepair -> failed-item repair -> processQueue

AgriOsApp.onCreate -> enqueuePeriodicSync (agrios_sync_worker, separate work name)
  -> SyncWorker.doWork -> same pipeline

HomeScreen LaunchedEffect(Unit), on each Home entry/re-entry
  -> triggerImmediateSync -> same worker pipeline

HomeScreen.runSyncNow (user action)
  -> new SyncManager -> fixAndRetryFailedItems -> processQueue

HomeScreen.runSyncFirstBatchOnlyForTest (existing debug action, unused here)
  -> new SyncManager -> fixAndRetryFailedItems -> bounded processQueue(false)

SyncManager.processQueue
  -> getPendingBatch -> dependency read/filter -> mark SYNCING -> api.syncEvents
  -> accepted: markSynced; conflict: markConflicted; failed: failed/retry state
  -> accepted dependents: follow-up queue pass
  -> transport uncertainty: original row/id retained with backoff
```

The legacy FarmerEnrollScreen and UnifiedEnrollmentScreen also insert/queue
profile work and trigger the same worker; they were not the active backend-driven
form lane. There is no separate direct farmer POST in this lane. Activity/cycle
form branches have their own APIs but were not executed and are not enrollment.
Room queue-count collectors render UI; no queue-count collector starts a drain.
Home's entry effect runs on re-entry, not on every ordinary recomposition.

## Proven race mechanisms in the original code

1. Each SyncManager instance selected PENDING rows before a later suspended
   dependency read and non-atomic SYNCING update. No common lock protected the
   read/select/send/acknowledge window. Two callers could select the same row.
2. Periodic and immediate work names differ. Unique immediate work does not
   exclude periodic work or Home's direct manual drain.
3. Immediate scheduling used REPLACE, which cancels a running worker. Cancellation
   cannot undo a request already received by the server. Catch-all exception
   handling could re-arm its row while another caller proceeded.
4. Worker startup and Home called fixAndRetryFailedItems; it reset unchanged
   failed rows to PENDING as well as genuinely repaired rows. This could re-arm
   a durable MATERIALIZATION_FAILED row. Attribution of the observed13s replay
   to that path is a hypothesis, not a proven historical trace.
5. Sync HTTP clients used OkHttp's default automatic retry policy. This is a
   possible additional replay path, not proof of the observed sequence's cause.
6. Save used a disabled state but had no immediate click-entry guard or
   repository/transaction intent latch. The observed single Save is not evidence
   of double tapping or duplicate form events.

## Implemented correction

- QueueSingleFlight.applicationQueue: process-wide Mutex shared by all normal
  SyncManager instances. It serializes selection, recovery, repair, network and
  acknowledgement for the application Room queue. This is stronger than a
  per-account lock; it retains no token, mobile or principal as a key. It does not
  redesign account switching or claim cross-process/server exactly-once behavior.
- Public processQueue and failed-item repair use that boundary. Worker uses
  processScheduledQueue so ProfileSyncRepair and the drain share it. Recursive
  follow-up passes call a private locked method, never reacquire the Mutex.
- Each HTTP batch and acknowledgement is NonCancellable while holding the lock;
  caller cancellation is observed before another batch. Existing HTTP timeouts
  remain unchanged. Thus cancellation cannot release this process's boundary
  while its own HTTP call remains active. Process death can still leave remote
  uncertainty and requires backend idempotency.
- First queue entry per coordinator/process recovers abandoned SYNCING to
  PENDING, preserving the original ID/payload/backoff. Accepted rows are not
  recovered. A new process can resume pending work; no retry generates a new ID.
- Retry transitions require SYNCING; markFailed cannot overwrite SYNCED.
  Missing acknowledgements remain retryable rather than stranded or accepted.
- Unchanged permanent failed rows stay FAILED; legacy repair re-arms a row only
  when it actually corrects a recognized malformed payload/entity ID. Failure
  categories are retained. No MATERIALIZATION_FAILED is converted to acceptance.
- Immediate workers use APPEND_OR_REPLACE instead of REPLACE. Triggers queue
  follow-up work without cancelling an active worker; the shared Mutex still
  protects races with periodic/manual drains.
- SyncWorker and both Home drain clients explicitly disable OkHttp automatic
  retries. Connect/read/write timeout values are unchanged.
- Dynamic profile forms use ProfileSubmissionOnce around a Room transaction
  containing entity insertion plus enqueue (and any associated local geometry
  event). A successful local intent is cached through scheduling errors and
  recomposition; repeated activation cannot generate another profile event.
  An immediate isSaving/saveSuccess guard supplements the repository-level latch.
  The latch's lifetime is a remembered form intent, not an account-wide prohibition
  on legitimate future records. Process-resume uses the durable queue.
- Sync logs emit only enum/scalar diagnostics, no payload/header/body/mobile or
  exception text. Related form/options error logs and displayed errors use fixed
  copy. Generic manual Sync UI does not call failed/conflicted rows 'All synced'.

## Sanitized field-presence and backend gap

The production queue serializer is exercised by a synthetic unit test; only
booleans are printed:

```json
{"pin_code_present":true,"assistance_mode_present":true,"project_id_present":true,"user_id_present":false}
```

This verifies field preservation for the supplied form-map shape, not a new
capture of the old live request. Source tracing plus the backend-owner checkpoint
report establish the old submission included pin_code and assistance_mode.
The Android normalizer does not inject user_id. Existing backend self-enrollment
authorization binds user_id to the verified principal; no Android identity field
or scope workaround has been added.

The checkpoint's backend materializer did not map pin_code or assistance_mode:
PIN persisted NULL and farmer/enrollment method ASSISTED despite SELF_SERVICE.
Those are separate backend contract gaps, not fixed by single-flight. Backend
work requires its own approval; do not reset/recreate this farmer to correct them.

## Tests and verification

Final host result:33 tests, zero failures/errors (13 new tests plus20 existing).
Normal debug APK build passed. Test XML sentinel/credential scan: zero matches.
No connected/device privacy test was run. APK SHA256:
`E4C7F473C27E23FCB024EDBCA332AE5136866883FCAB435010B96D256112B79C`.
Reports: `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/test-results/testDebugUnitTest/`. Generated reports/APK are not proposed
Git content. Diff whitespace checks passed, with existing CRLF warnings.

Fake API + in-memory DAO double, deterministic coroutine scheduler and clock:
- simultaneous selection while row is still PENDING;
- immediate vs worker, including repair blocked behind an active drain;
- default shared boundary across independent manager instances;
- cancellation during HTTP keeps lock until acknowledgement;
- repeated Save intent creates one local event;
- rollback can be explicitly retried; completed local intent cannot enqueue twice;
- pending and abandoned SYNCING work resumes after coordinator recreation;
- committed row is not resubmitted;
- transport uncertainty retains ID/backoff and replays the same event;
- permanent failure remains FAILED across startup;
- missing acknowledgement remains pending/retryable;
-25 distinct IDs drain in order as10+10+5.

Payload and diagnostic tests verify only field-presence flags leave diagnostics;
synthetic token/header/message/payload sentinels are not emitted. Existing host
auth/privacy tests remain included. SQLite/WorkManager/device integration was not
executed: DAO SQL is compile-checked by Room/KSP; fake tests do not prove SQLite
durability or platform scheduling. Existing Room schema-export and Kotlin
deprecation/unchecked-cast warnings remain, without build failures.

## Original proposed device checkpoint — subsequently executed

Do not rerun66b-authenticated-fresh-enrollment.yaml or its Save helper: the farmer
already exists. First review this diff, confirm backend contract-gap ownership and
separately approve installing the APK / starting the app (startup may drain any
existing pending queue work). The following read-only checkpoint requires the
existing session and never clears local data, requests OTP, taps Save/Sync Now,
or creates parcel/soil/cycle data:

```powershell
$device = 'emulator-5556'
$evidence = Join-Path $env:TEMP ('agrios-authenticated-reads\gate2-existing-checkpoint-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
& 'C:\Users\SANTOSH\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s $device install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'Installation failed' }
& 'C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat' --device $device test `
  --debug-output $evidence --test-output-dir $evidence `
  -e GATE2_FARMER_ID=107d7261-ca96-4e95-bb1a-8eb837f3388b `
  maestro\66b-existing-farmer-single-flight-checkpoint.yaml
$flowExit = $LASTEXITCODE
```

This checks existing identity hydration, not a fresh enrollment race or backend
field correction. Exact-once audit confirmation still needs separate read-only
backend verification. Authentication expiry must stop the checkpoint, not start
an unapproved login. Never reset/apply fixtures to enable this command.

Original proposed commit message:
`fix: serialize sync drains and deduplicate profile save intents`

## Exact correction file scope

Modified:
- `app/build.gradle.kts`
- `app/src/main/java/com/agrios/app/core/sync/SyncManager.kt`
- `app/src/main/java/com/agrios/app/core/sync/SyncWorker.kt`
- `app/src/main/java/com/agrios/app/core/sync/ProfileSyncRepair.kt`
- `app/src/main/java/com/agrios/app/data/local/dao/SyncQueueDao.kt`
- `app/src/main/java/com/agrios/app/ui/dynamicform/DynamicFormScreen.kt`
- `app/src/main/java/com/agrios/app/ui/dynamicform/DynamicFormRenderer.kt`
- `app/src/main/java/com/agrios/app/ui/home/HomeScreen.kt`

New:
- `app/src/main/java/com/agrios/app/core/sync/QueueSingleFlight.kt`
- `app/src/main/java/com/agrios/app/core/sync/SyncDiagnostic.kt`
- `app/src/main/java/com/agrios/app/core/sync/ProfileQueuePayload.kt`
- `app/src/test/java/com/agrios/app/core/sync/SyncSingleFlightTest.kt`
- `app/src/test/java/com/agrios/app/core/sync/ProfileQueuePayloadTest.kt`
- `maestro/66b-existing-farmer-single-flight-checkpoint.yaml`
- `docs/gate2-farmer-single-flight-fix.md`

Preserved/excluded: pre-existing 66b fresh-enrollment scripts, flows17/38,
cleanup SQL, XML/patch exports, oddly named response files and docs/pitch.
No backend files, fixture data or auth scope were changed.

## Subsequent approved verification and publication

The existing-farmer read-only checkpoint passed on Pixel_6 / emulator-5556,
API 34, preserving the session and farmer ID. It did not generate another farmer
submission or prove device-side concurrent processing. Host fake tests remain
the deterministic proof of the local race correction.

The backend owner subsequently committed and deployed fee542b, repaired the
reviewed farmer/enrollment fields without reset, and verified PIN 560001 and SELF.
Those historical mapping gaps are no longer current blockers. Parcel PIN uses
the supported location_scope.pin_code representation; do not mistake its null
dedicated column for lost submitted PIN data.

The later one-shot parcel and manual-soil Android phase passed. Backend read-only
verification found one COMMITTED CREATE and one event-linked commit audit for
each, no failures/conflicts, and one durable parcel/soil profile. This is observed
submission evidence, not a general server exactly-once guarantee.

Latest combined host verification: 46 tests passed, zero failures/errors; debug
APK built. The pending-cycle read-only UI correction was published separately as
66a7dc5. Its device evidence remains valid for the tested dirty-tree APK. This
safety commit packages the already-reviewed queue and shared-form corrections;
unrelated edits and experimental one-shot enrollment scripts remain excluded.
