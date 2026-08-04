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
../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply
../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply
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
