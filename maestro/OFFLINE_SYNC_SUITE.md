# Android offline sync QA suite

This is the canonical repeatable lane for Maestro flows `10-19`.

Important principle: do not reset the backend after Android has queued an offline event unless that specific flow says to. Backend reset scripts clear backend fixture rows, not Android local queue rows.

## Before starting flows 10-19

Run in WSL:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/seed_android_dynamic_profile_test_context.py --apply
../venv/bin/python scripts/seed_android_crop_cycle_test_fixture.py --reset --apply
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
```

## Android emulator state

- Full clean suite start: clearing app data before flow `10` is OK.
- Between flows `10-13`: do not clear app data; these flows depend on the offline/synced crop-cycle state.
- Between flows `14-19`: keep app data unless testing cold-start persistence of sync status.

## Guided runner

From PowerShell:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
.\maestro\run-offline-sync-suite.ps1
```

Notes:

- `seed_android_crop_cycle_test_fixture.py` uses the dedicated crop-cycle fixture farmer/parcel (`4df387e8-114f-5c44-a129-a9d000000003` / `4df387e8-114f-5c44-a129-a9d000000004`). It does not reset the dynamic profile farmer/parcel (`e1ee0941-2bad-4a18-a239-2a4119608a06` / `98c1a0fa-4f5f-4b8c-97ae-d84992db1c44`).
- Do not run `prepare_android_version_mismatch_conflict.py --reset --apply` until immediately before flow `15` or `18`.
- Do not run `prepare_android_workflow_invalid_conflict.py --reset --apply` until immediately before flow `16` or `19`; it intentionally forces NURSERY to `ACTIVE`, which contaminates a clean stage-start replay test.
- Do not run `verify_android_offline_stage_activity_replay.py` immediately after flow `10`; it expects stage START and activity replay, so use it after flows `12-13`.

The helper pauses before each group and prints the exact WSL command to run.

## Flow groups

### Flows 10-13: offline crop-cycle, stage, activity, finance

Run Android flows in order:

```powershell
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test maestro\10-offline-crop-cycle-create-queue.yaml
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test maestro\11-offline-stage-start-queue.yaml
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test maestro\12-offline-activity-log-queue.yaml
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test maestro\13-activity-finance-summary-smoke.yaml
```

Verify after flow `12` or `13`:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_offline_stage_activity_replay.py
```

If flow `10` needs a fresh eligible parcel again, reset only the crop-cycle fixture before flow `10`:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/seed_android_crop_cycle_test_fixture.py --reset --apply
```

### Flow 14: stale-context guidance

Before Android queues the event:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
```

After Android queues it, before backend restart / Sync Now:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --apply
```

Verify with Android's generated event id:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_stale_context_sync_failure.py --event-id {android_generated_event_id}
```

Restore backend state:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
```

### Flow 15: VERSION_MISMATCH guidance

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply
../venv/bin/python scripts/verify_android_version_mismatch_conflict.py
```

### Flow 16: WORKFLOW_INVALID guidance

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply
../venv/bin/python scripts/verify_android_workflow_invalid_conflict.py
```

### Flow 17: stale-context recovery

After Android refreshes context and discards only the stale local draft row:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_stale_context_recovery_state.py --event-id {failed_stale_context_event_id}
```

Expected backend state:

- `sync_processed_events.status=FAILED` remains.
- `SYNC_FAILED` audit remains.
- No `sync_conflicts` row exists.
- No accepted/committed row exists for that failed draft.

### Flow 18: VERSION_MISMATCH recovery

Android fetches pending conflicts, calls `PATCH /api/v1/sync/conflicts/{conflict_id}` with `ACCEPT_SERVER`, and discards only the local conflicted row.

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type VERSION_MISMATCH
```

### Flow 19: WORKFLOW_INVALID recovery

Android fetches pending conflicts, calls `PATCH /api/v1/sync/conflicts/{conflict_id}` with `ACCEPT_SERVER`, and discards only the local conflicted row.

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type WORKFLOW_INVALID
```
### Flow 20: cold-start offline activity persistence

Before flow:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_cold_start_activity_persistence.py --apply
```

Keep backend unavailable while Android queues the local cold-start activity and force-stops/relaunches the app. During the 60-second wait after relaunch, restart backend. The flow proves the local pending sync row survives app cold start, taps Sync Now, and expects All synced.

Verify afterward:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_cold_start_activity_persistence.py
```

If Android logs expose exact UUIDs, optional exact verification is also supported:

```bash
ANDROID_COLD_START_ACTIVITY_EVENT_ID={event_id} \
ANDROID_COLD_START_ACTIVITY_ID={activity_id} \
../venv/bin/python scripts/verify_android_cold_start_activity_persistence.py
```
### Flow 21: device restart offline activity persistence

Before flow:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_cold_start_activity_persistence.py --apply
```

Keep backend unavailable while Android queues the local device-restart activity in phase A. After phase A finishes, restart the emulator/device while preserving app data, then restart backend. Run phase B to relaunch Android and observe either the pending sync row or All synced. WorkManager may replay immediately on app startup once backend is reachable, so the backend verifier is the durable pass/fail check. Do not rerun WSL prep between phase A and phase B.

Verify afterward:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_cold_start_activity_persistence.py
```

Exact UUID verification is also supported using `ANDROID_COLD_START_ACTIVITY_EVENT_ID` and `ANDROID_COLD_START_ACTIVITY_ID` from Android logs.


### Flow 22: uncertain-result idempotency retry

Before flow:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_uncertain_result_idempotency.py --apply
```

Keep backend reachable. Android queues a crop_activity CREATE, Sync Now commits it, then the test hook simulates losing the local acknowledgement by resetting the same local queue row back to PENDING. The retry must reuse the exact same event_id/entity_id/payload. Backend should return `accepted` containing the same event id with empty conflicts/failed, and durable state should still contain one activity row and one 325.50 finance impact.

Verify after first replay and again after retry:

```bash
cd ~/projects/farmint/backend
ANDROID_UNCERTAIN_ACTIVITY_EVENT_ID={event_id} \
ANDROID_UNCERTAIN_ACTIVITY_ID={activity_id} \
../venv/bin/python scripts/verify_android_uncertain_result_idempotency.py
```

Optional backend duplicate resend proof:

```bash
cd ~/projects/farmint/backend
ANDROID_UNCERTAIN_ACTIVITY_EVENT_ID={event_id} \
ANDROID_UNCERTAIN_ACTIVITY_ID={activity_id} \
../venv/bin/python scripts/verify_android_uncertain_result_idempotency.py --resend
```
### Flow 23: dependency-ordered replay after cold start

Before flow:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_dependency_order_replay.py --apply
```

Keep backend unavailable while Android queues the three related rows and cold-starts. Android uses event IDs as `dependency_ids`:

- crop_cycle CREATE: `[]`
- crop_stage START: `[cycle_event_id]`
- crop_activity CREATE: `[cycle_event_id, stage_event_id]`

During the 60-second wait after relaunch, restart backend. Android taps Sync Now and should replay crop_cycle -> crop_stage -> crop_activity in dependency order.

Verify afterward:

```bash
cd ~/projects/farmint/backend
ANDROID_DEP_ORDER_CYCLE_EVENT_ID={cycle_event_id} \
ANDROID_DEP_ORDER_CYCLE_ID={cycle_entity_id} \
ANDROID_DEP_ORDER_STAGE_EVENT_ID={stage_event_id} \
ANDROID_DEP_ORDER_STAGE_ENTITY_ID={stage_entity_id} \
ANDROID_DEP_ORDER_ACTIVITY_EVENT_ID={activity_event_id} \
ANDROID_DEP_ORDER_ACTIVITY_ID={activity_entity_id} \
../venv/bin/python scripts/verify_android_dependency_order_replay.py
```

Optional duplicate retry proof:

```bash
cd ~/projects/farmint/backend
ANDROID_DEP_ORDER_CYCLE_EVENT_ID={cycle_event_id} \
ANDROID_DEP_ORDER_CYCLE_ID={cycle_entity_id} \
ANDROID_DEP_ORDER_STAGE_EVENT_ID={stage_event_id} \
ANDROID_DEP_ORDER_STAGE_ENTITY_ID={stage_entity_id} \
ANDROID_DEP_ORDER_ACTIVITY_EVENT_ID={activity_event_id} \
ANDROID_DEP_ORDER_ACTIVITY_ID={activity_entity_id} \
../venv/bin/python scripts/verify_android_dependency_order_replay.py --resend
```
### Flow 24: partial-batch replay resilience

Before flow:

```bash
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_partial_batch_replay.py --apply
```

Keep backend reachable. Android queues a mixed batch:

- valid crop_activity CREATE with `dependency_ids=[]`
- missing crop_stage START with `dependency_ids=[missing_cycle_event_id]`

Expected first response accepts only the valid activity and returns `DEPENDENCY_MISSING` for the missing stage. Android treats `DEPENDENCY_MISSING` as retryable, keeps the stage row pending/retryable, then queues the missing crop_cycle dependency and retries the same stage event.

Verify after mixed batch and after dependency retry:

```bash
cd ~/projects/farmint/backend
ANDROID_PARTIAL_VALID_ACTIVITY_EVENT_ID={valid_activity_event_id} \
ANDROID_PARTIAL_VALID_ACTIVITY_ID={valid_activity_id} \
ANDROID_PARTIAL_MISSING_CYCLE_EVENT_ID={missing_cycle_event_id} \
ANDROID_PARTIAL_MISSING_CYCLE_ID={missing_cycle_entity_id} \
ANDROID_PARTIAL_MISSING_STAGE_EVENT_ID={missing_stage_event_id} \
ANDROID_PARTIAL_MISSING_STAGE_ENTITY_ID={missing_stage_entity_id} \
../venv/bin/python scripts/verify_android_partial_batch_replay.py
```

Backend-side dependency commit + retry proof:

```bash
cd ~/projects/farmint/backend
ANDROID_PARTIAL_VALID_ACTIVITY_EVENT_ID={valid_activity_event_id} \
ANDROID_PARTIAL_VALID_ACTIVITY_ID={valid_activity_id} \
ANDROID_PARTIAL_MISSING_CYCLE_EVENT_ID={missing_cycle_event_id} \
ANDROID_PARTIAL_MISSING_CYCLE_ID={missing_cycle_entity_id} \
ANDROID_PARTIAL_MISSING_STAGE_EVENT_ID={missing_stage_event_id} \
ANDROID_PARTIAL_MISSING_STAGE_ENTITY_ID={missing_stage_entity_id} \
../venv/bin/python scripts/verify_android_partial_batch_replay.py --commit-dependency-and-retry
```

Optional mixed-batch resend proof:

```bash
cd ~/projects/farmint/backend
ANDROID_PARTIAL_VALID_ACTIVITY_EVENT_ID={valid_activity_event_id} \
ANDROID_PARTIAL_VALID_ACTIVITY_ID={valid_activity_id} \
ANDROID_PARTIAL_MISSING_CYCLE_EVENT_ID={missing_cycle_event_id} \
ANDROID_PARTIAL_MISSING_CYCLE_ID={missing_cycle_entity_id} \
ANDROID_PARTIAL_MISSING_STAGE_EVENT_ID={missing_stage_event_id} \
ANDROID_PARTIAL_MISSING_STAGE_ENTITY_ID={missing_stage_entity_id} \
../venv/bin/python scripts/verify_android_partial_batch_replay.py --resend-mixed-batch
```