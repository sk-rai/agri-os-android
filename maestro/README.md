# Agri-OS Android Maestro smoke tests

These flows automate the emulator checks we have been doing manually: login/profile hydration, Home history, parcel eligibility, farmer profile parcel/GPS status, and backend-driven form guidance.

## Prerequisites

1. Backend is running and reachable from the emulator:

   ```powershell
   curl.exe -sS http://localhost:8000/api/v1/forms/crop_cycle_create
   ```

2. Android debug APK is installed, or let Maestro install/launch the current app from Android Studio/Gradle.
3. Emulator is running and responsive.
4. Maestro is installed.

   Windows install options are usually easiest through WSL or PowerShell package tooling. Verify with:

   ```powershell
   maestro --version
   ```

## Recommended run order

From repo root:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
.\maestro\run-smoke.ps1
```

Or run individual flows:

```powershell
maestro test maestro\00-login-hydrate-existing-profile.yaml
maestro test maestro\01-home-history-smoke.yaml
maestro test maestro\02-farmer-profile-parcel-geometry.yaml
maestro test maestro\03-start-crop-eligible-parcel.yaml
maestro test maestro\05-completed-cycle-view-only.yaml
```

Optional / more fragile while backend forms continue changing:

```powershell
maestro test maestro\04-dynamic-land-intelligence-guidance.yaml
maestro test maestro\06-dynamic-farmer-submit.yaml
maestro test maestro\07-dynamic-parcel-submit.yaml
maestro test maestro\08-dynamic-crop-cycle-create.yaml
maestro test maestro\09-dynamic-soil-profile-submit.yaml
maestro test maestro\10-offline-crop-cycle-create-queue.yaml
maestro test maestro\11-offline-stage-start-queue.yaml
maestro test maestro\12-offline-activity-log-queue.yaml
maestro test maestro\13-activity-finance-summary-smoke.yaml
maestro test maestro\14-stale-context-sync-failure.yaml
maestro test maestro\15-version-mismatch-conflict.yaml
maestro test maestro\16-workflow-invalid-conflict.yaml
maestro test maestro\17-stale-context-recovery.yaml
maestro test maestro\18-version-mismatch-recovery.yaml
maestro test maestro\19-workflow-invalid-recovery.yaml
maestro test maestro\20-cold-start-activity-persistence.yaml
maestro test maestro\21-device-restart-activity-persistence.yaml
maestro test maestro\21b-device-restart-activity-replay-after-restart.yaml
maestro test maestro\22-uncertain-result-idempotency.yaml
maestro test maestro\23-dependency-order-replay.yaml
maestro test maestro\24-partial-batch-replay.yaml
maestro test maestro\25-partial-batch-conflict.yaml
maestro test maestro\26-multi-conflict-pending-drawer.yaml
maestro test maestro\27-queue-backpressure.yaml
maestro test maestro\28a-interrupted-multibatch-first-batch.yaml
maestro test maestro\28b-interrupted-multibatch-resume.yaml
maestro test maestro\29-poison-row-backlog.yaml
```

`04-dynamic-land-intelligence-guidance.yaml` requires backend app bootstrap to enable profile dynamic forms:

```json
"backend_driven_farmer_forms": true,
"backend_driven_parcel_forms": true,
"backend_driven_soil_forms": true
```

If those flags are `false`, Android correctly falls back to the legacy enrollment/profile screens and this flow should not be expected to pass.

`06-dynamic-farmer-submit.yaml` also requires the backend dynamic profile test context to be reset before each repeatable run:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/seed_android_dynamic_profile_test_context.py --reset --apply
```

It verifies the first backend-driven profile submit gate: login with `9900000002`, render the backend Farmer Registration form, fill the minimum profile details, show land-intelligence guidance from PIN `560001`, and save the farmer locally for sync.

`07-dynamic-parcel-submit.yaml` starts from the hydrated dynamic test farmer, opens Farmer Profile, continues into the backend Land Parcel form, fills a minimum parcel, and saves it. The key backend contract under test is that Android queues/syncs parcel location as an object:

```json
{
  "location_scope": {
    "scope_type": "SINGLE_VILLAGE",
    "village_name_manual": "Android Dynamic Test Village",
    "pin_code": "560001"
  }
}
```

Android must not send `"location_scope": "SINGLE_VILLAGE"`.

`08-dynamic-crop-cycle-create.yaml` starts from the hydrated dynamic test farmer and creates a Rice/KHARIF crop cycle for an eligible parcel. It validates that Android uses the dynamic tenant/project lane for eligible parcels and includes `project_id` during crop-cycle create. This flow depends on the profile/parcel state produced by `06` and `07`; after a backend reset, run `06 -> 07 -> 08`. It is intentionally stateful: after a successful create, backend should block that parcel for the same season/year as `HAS_ACTIVE_CYCLE`, so reset the backend dynamic context before repeating a clean create run.

`09-dynamic-soil-profile-submit.yaml` starts from the hydrated dynamic test farmer + parcel and submits a backend-driven Soil Profile for the parcel. After a backend reset, run `06 -> 07`, wait for sync/hydration to include the parcel, then run `09`. It validates the Android side of the farmer -> parcel -> soil profile replay chain; WSL should verify `entity_type=SOIL_PROFILE` plus `farmer_id`, `parcel_id`, `project_id`, `data_source`, `ph`, `organic_carbon_oc`, and `boron_b`.

`10-offline-crop-cycle-create-queue.yaml` validates the offline crop-cycle create fallback. Run it only after `06 -> 07` have produced a synced farmer + parcel. The flow opens Start Crop Cycle while backend is online, selects parcel/season/crop, then pauses for 60 seconds before tapping Start Cycle. During that 60-second window, stop/pause FastAPI. Android should show `Saved!` and `Syncing in background.` instead of a connection failure. Restart backend afterward and verify `/api/v1/sync/events` replays `entity_type=crop_cycle`.

`11-offline-stage-start-queue.yaml` validates offline crop-stage transition replay for the Rice cycle created by flow `10`. It opens the running Rice cycle while backend is online, pauses for 60 seconds before tapping the first stage `Start`, then expects Android to queue `entity_type=crop_stage` with `action=START` and show a saved-offline/syncing message. Restart backend afterward and verify the cycle becomes ACTIVE and NURSERY becomes ACTIVE.

`12-offline-activity-log-queue.yaml` validates offline crop-activity replay under the active NURSERY stage. It opens the Rice cycle and activity form while backend is online, fills a custom LABOR activity with cost `325.50`, pauses for 60 seconds before saving, then expects Android to queue `entity_type=crop_activity` and show `Saved!` plus `Syncing in background.` Restart backend afterward, tap Sync Now, and verify activities, stage-cost summary, and P&L summary include the replayed expense.

`13-activity-finance-summary-smoke.yaml` validates the post-sync UI: the NURSERY activity row is visible, and backend-derived finance totals show the replayed `₹325.50` expense.

`14-stale-context-sync-failure.yaml` validates stale-context sync failure UX for the Android dynamic test lane. It queues a fresh local `crop_cycle` replay event, pauses for 60 seconds so WSL can run `scripts/prepare_android_stale_context_sync_failure.py --apply`, then taps Sync Now and expects Android to show refresh guidance for `PARCEL_PROJECT_MISMATCH` rather than manual conflict UI. Restore the backend fixture afterward with `scripts/prepare_android_stale_context_sync_failure.py --restore --apply`.

`15-version-mismatch-conflict.yaml` validates manual-review sync conflict UX. Run WSL `scripts/prepare_android_version_mismatch_conflict.py --reset --apply` first. The flow queues the fixed backend fixture `crop_activity` event, taps Sync Now, and expects `VERSION_MISMATCH` to show manual review guidance rather than stale-context refresh guidance.

`16-workflow-invalid-conflict.yaml` validates server-authority workflow conflict UX. Run WSL `scripts/prepare_android_workflow_invalid_conflict.py --reset --apply` first. The flow queues the fixed backend fixture `crop_stage` START event for an already ACTIVE NURSERY stage, taps Sync Now, and expects `WORKFLOW_INVALID` to show workflow refresh guidance rather than stale-context or version-mismatch guidance.

`17-stale-context-recovery.yaml` validates the stale-context recovery lifecycle. Start from a restored stale-context fixture, run the flow, and during its 60-second wait run WSL `scripts/prepare_android_stale_context_sync_failure.py --apply`. After Sync Now returns `PARCEL_PROJECT_MISMATCH`, Android should show `Refresh and discard draft`; tapping it refreshes backend-owned context and deletes only that local stale draft row. Backend keeps the durable FAILED/audit trace and can be verified with `scripts/verify_android_stale_context_recovery_state.py --event-id {failed_event_id}`.

`18-version-mismatch-recovery.yaml` validates conflict recovery for `VERSION_MISMATCH`. Run WSL `scripts/prepare_android_version_mismatch_conflict.py --reset --apply` first. The flow queues the deterministic version-mismatch event, taps Sync Now, then taps `Use server version`. Android refreshes context, calls `PATCH /api/v1/sync/conflicts/{conflict_id}` with `ACCEPT_SERVER`, and discards only that local conflicted row. Verify backend with `scripts/verify_android_conflict_recovery_state.py --conflict-type VERSION_MISMATCH`.

`19-workflow-invalid-recovery.yaml` validates conflict recovery for `WORKFLOW_INVALID`. Run WSL `scripts/prepare_android_workflow_invalid_conflict.py --reset --apply` first. The flow queues the deterministic invalid stage transition, taps Sync Now, then taps `Refresh stage`. Android refreshes context, calls `PATCH /api/v1/sync/conflicts/{conflict_id}` with `ACCEPT_SERVER`, and discards only that local conflicted row. Verify backend with `scripts/verify_android_conflict_recovery_state.py --conflict-type WORKFLOW_INVALID`.

`20-cold-start-activity-persistence.yaml` validates local sync queue persistence across app cold start. Run WSL `scripts/prepare_android_cold_start_activity_persistence.py --apply` before the flow, then keep backend unavailable while the flow queues the local cold-start activity and force-stops/relaunches the app. During the 60-second wait after relaunch, restart backend. The flow asserts the pending row survived relaunch, taps Sync Now, and expects All synced. Verify backend with `scripts/verify_android_cold_start_activity_persistence.py`; set `ANDROID_COLD_START_ACTIVITY_EVENT_ID` / `ANDROID_COLD_START_ACTIVITY_ID` only if Android logs expose the random UUIDs.

`21-device-restart-activity-persistence.yaml` and `21b-device-restart-activity-replay-after-restart.yaml` validate local sync queue persistence across emulator/device restart. Run WSL `scripts/prepare_android_cold_start_activity_persistence.py --apply`, stop backend, then run phase A to queue the local device-restart activity. After phase A finishes, restart the emulator/device while preserving app data, restart backend, then run phase B. Phase B accepts either pending/waiting or All synced because WorkManager may replay immediately on app startup once backend is reachable. Verify backend with `scripts/verify_android_cold_start_activity_persistence.py`; exact UUID verification is supported from Android logs.


`22-uncertain-result-idempotency.yaml` validates uncertain-result idempotency. Run WSL `scripts/prepare_android_uncertain_result_idempotency.py --apply` before the flow with backend reachable. The flow queues a crop activity, syncs it once, resets the same synced local queue row back to PENDING without changing event_id/entity_id/payload, then syncs again. Backend should accept the duplicate same event idempotently with no duplicate activity or finance impact. Verify with `scripts/verify_android_uncertain_result_idempotency.py`; pass `ANDROID_UNCERTAIN_ACTIVITY_EVENT_ID` / `ANDROID_UNCERTAIN_ACTIVITY_ID` from Android logs for exact checks.

`23-dependency-order-replay.yaml` validates dependency-ordered replay after cold start. Run WSL `scripts/prepare_android_dependency_order_replay.py --apply`, stop backend, then run the flow. Android queues crop_cycle CREATE, crop_stage START, and crop_activity CREATE with event-ID dependency_ids. During the 60-second wait after relaunch, restart backend. The flow taps Sync Now and expects All synced. Verify with `scripts/verify_android_dependency_order_replay.py`; pass the six UUIDs from Android logs for exact checks.

`24-partial-batch-replay.yaml` validates partial-batch resilience. Run WSL `scripts/prepare_android_partial_batch_replay.py --apply` with backend reachable. The flow queues one valid crop_activity and one crop_stage that depends on a missing cycle event. The valid activity commits while the dependency-missing stage stays retryable/pending. The flow then queues the missing crop_cycle dependency, retries the same stage event, and expects All synced. Verify with `scripts/verify_android_partial_batch_replay.py`; pass the six UUIDs from Android logs for exact checks.

`25-partial-batch-conflict.yaml` validates partial-batch success plus workflow conflict handling. Run WSL `scripts/prepare_android_partial_batch_conflict.py --apply` with backend reachable. The flow queues one valid crop_activity and one WORKFLOW_INVALID crop_stage START in the same batch; Android syncs the activity and shows the existing `Workflow changed on backend` server-authority UI for the conflict, leaving the conflict pending so the backend verifier can prove pending-conflict visibility. Verify with `scripts/verify_android_partial_batch_conflict.py`; pass the four UUIDs from Android logs for exact checks. Tap `Refresh stage` afterward, then use `--ack-conflict` only if backend-side ACK proof is needed.
## Screenshots

Each flow uses `takeScreenshot`. Maestro stores screenshots in its run artifacts and prints their location in the terminal output. Please share the failed screenshot plus the terminal failure text if something breaks.

## What these tests intentionally avoid

- They do not walk real GPS boundaries. Physical GPS polygon testing still needs a real phone/field-like movement.
- They do not create a full crop cycle end-to-end yet. That flow is more stateful and should be added once backend dynamic crop forms stabilize.
- They do not assert exact dates, because backend seed/test data changes often.

## If a test gets stuck

Run:

```powershell
maestro hierarchy
```

That prints the visible UI tree. Share it with me and I can tighten the selectors.


`26-multi-conflict-pending-drawer.yaml` validates the Home/Sync Status intervention surface with both deterministic conflict types pending at once. Run WSL `scripts/prepare_android_multi_conflict_pending_drawer.py --reset --apply` with backend reachable. The flow queues VERSION_MISMATCH and WORKFLOW_INVALID in one batch and stops after verifying both distinct cards are visible, leaving both pending for `scripts/verify_android_multi_conflict_pending_drawer.py`. Use `--send-conflict-batch --resend-conflict-batch` for backend-side dedup proof and `--ack-both` only after resolving both cards or when doing backend-side ACK cleanup.
`27-queue-backpressure.yaml` validates ordinary heavy offline usage: 25 local activity rows are queued, replayed in bounded batches of 10 + 10 + 5, and cleared without showing raw queue internals to the farmer. Run WSL `scripts/prepare_android_queue_backpressure.py --reset-indexed --apply` first, then verify with `scripts/verify_android_queue_backpressure.py`; expected finance delta is 25 x INR 20.00 = INR 500.00.

`28a-interrupted-multibatch-first-batch.yaml` and `28b-interrupted-multibatch-resume.yaml` validate interrupted bounded replay. Run WSL `scripts/prepare_android_interrupted_multibatch_resume.py --reset-indexed --apply`, run 28a, verify with `scripts/verify_android_interrupted_multibatch_resume.py --phase first_batch`, then run 28b and verify with `--phase complete`. The flow queues 25 rows, commits only the first 10, then resumes the remaining 15 without duplicate materialization or finance impact.

`29-poison-row-backlog.yaml` validates that one workflow-invalid poison row in a larger backlog does not block later valid batches. Run WSL `scripts/prepare_android_poison_row_backlog.py --reset-indexed --apply`, then verify with `scripts/verify_android_poison_row_backlog.py`. Expected durable result: 24 valid activities committed once, one `WORKFLOW_INVALID` conflict card, and finance delta `24 x INR 20.00 = INR 480.00`.
