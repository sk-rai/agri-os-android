# Android Maestro sync + multilingual evidence

This file captures current Android end-to-end evidence against the latest stable backend sync/conflict contracts plus the multilingual backend-driven label contract.

Backend lane:

- Backend latest referenced by WSL: `1ac804f` plus later docs/checklist-only changes if pulled.
- CoRE/LGD and canal-layer work are deferred for this Android batch.
- Android must not change backend contracts during this evidence pass.

Android lane:

- Tenant: `android-dynamic-test`
- Project: `0f7e0a6b-8472-5d6d-8a14-a9d000000001`
- Dynamic-profile mobile: `+919900000002`
- Maestro executable: `C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat`

## Before running Android flows

Build and install:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
.\gradlew.bat :app:assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

If multiple devices are attached, use the device id printed by `adb devices`:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 install -r "app\build\outputs\apk\debug\app-debug.apk"
```

Backend preflight:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/pre_android_handoff_check.py"
```

## Multilingual overlay contract

Android resolves backend-owned labels as:

```kotlin
labels[currentLanguageCode] ?: labels["en"]
```

Evidence already collected:

| Flow | Language/state context | Result | Screenshot |
| --- | --- | --- | --- |
| `37a-multilingual-farmer-hi.yaml` | UP / Hindi (`hi`) | PASS | `37a-multilingual-farmer-hi` |
| `37b-multilingual-farmer-kn-fallback.yaml` | Karnataka / Kannada fallback (`kn`) | PASS | `37b-multilingual-farmer-kn-fallback` |
| `37c-multilingual-farmer-mr-fallback.yaml` | Maharashtra / Marathi fallback (`mr`) | PASS | `37c-multilingual-farmer-mr-fallback` |
| `37d-multilingual-farmer-pa-fallback.yaml` | Punjab / Punjabi fallback (`pa`) | PASS | `37d-multilingual-farmer-pa-fallback` |

Backend audit before each multilingual flow:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/seed_android_dynamic_profile_test_context.py --reset --apply && ../venv/bin/python scripts/audit_android_multilingual_form_labels.py"
```

Android flow commands:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\37a-multilingual-farmer-hi.yaml"
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\37b-multilingual-farmer-kn-fallback.yaml"
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\37c-multilingual-farmer-mr-fallback.yaml"
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\37d-multilingual-farmer-pa-fallback.yaml"
```

## Priority sync/conflict evidence matrix

Fill the `Result`, `Screenshot artifact path`, `Backend verifier output`, and `Gaps` columns as each flow is rerun.

| Priority | Flow | Tenant/project/language context | Backend fixture command | Maestro command | Backend verifier command | Result | Screenshot artifact path | Gaps |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `14-stale-context-sync-failure.yaml` | `android-dynamic-test`, project `...000001`, selected Android language | See stale-context section below | See stale-context section below | `verify_android_stale_context_sync_failure.py` plus recovery verifier when applicable | TODO | TODO | TODO |
| 2 | `15-version-mismatch-conflict.yaml` | `android-dynamic-test`, project `...000001`, selected Android language | `prepare_android_version_mismatch_conflict.py --reset --apply` | `maestro\15-version-mismatch-conflict.yaml` | `verify_android_version_mismatch_conflict.py` | TODO | TODO | TODO |
| 3 | `16-workflow-invalid-conflict.yaml` | `android-dynamic-test`, project `...000001`, selected Android language | `prepare_android_workflow_invalid_conflict.py --reset --apply` | `maestro\16-workflow-invalid-conflict.yaml` | `verify_android_workflow_invalid_conflict.py` | TODO | TODO | TODO |
| 4 | `26-multi-conflict-pending-drawer.yaml` | `android-dynamic-test`, project `...000001`, selected Android language | `prepare_android_multi_conflict_pending_drawer.py --reset --apply` | `maestro\26-multi-conflict-pending-drawer.yaml` | `verify_android_multi_conflict_pending_drawer.py` | TODO | TODO | TODO |

## Flow 14: stale-context sync failure

This flow intentionally pauses for 60 seconds after queuing the local draft. Run the backend mutation during that pause.

Before Maestro, restore the fixture:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply"
```

Start Maestro:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\14-stale-context-sync-failure.yaml"
```

During the `wait-60s.js` pause, run:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --apply"
```

After Maestro, capture Android event ids from logcat if needed:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 logcat -d -s HomeScreen SyncManager SyncWorker
```

Verify backend durable failure state:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_stale_context_sync_failure.py"
```

If the verifier requires the generated Android event id:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ANDROID_STALE_CONTEXT_EVENT_ID={event_id} ../venv/bin/python scripts/verify_android_stale_context_sync_failure.py"
```

Recovery lifecycle, if run:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\17-stale-context-recovery.yaml"
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_stale_context_recovery_state.py --event-id {failed_event_id}"
```

Expected Android result:

- shows `Refresh required: local context is stale`;
- shows parcel/project refresh guidance;
- does not show manual conflict UI;
- does not route to retry queue as a normal retryable dependency failure.

## Flow 15: VERSION_MISMATCH conflict

Backend fixture:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply"
```

Maestro:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\15-version-mismatch-conflict.yaml"
```

Verifier:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_version_mismatch_conflict.py"
```

Recovery lifecycle, if run:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\18-version-mismatch-recovery.yaml"
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type VERSION_MISMATCH"
```

Expected Android result:

- shows `Manual review needed: server has a newer version`;
- shows activity changed/manual-review guidance;
- does not show stale-context copy;
- `Use server version` calls `ACCEPT_SERVER` in recovery flow.

## Flow 16: WORKFLOW_INVALID conflict

Backend fixture:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply"
```

Maestro:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\16-workflow-invalid-conflict.yaml"
```

Verifier:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_workflow_invalid_conflict.py"
```

Recovery lifecycle, if run:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\19-workflow-invalid-recovery.yaml"
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type WORKFLOW_INVALID"
```

Expected Android result:

- shows `Workflow changed on backend`;
- shows `Refresh this crop cycle/stage before retrying the action.`;
- does not show stale-context or version-mismatch copy;
- `Refresh stage` calls `ACCEPT_SERVER` in recovery flow.

## Flow 26: multi-conflict pending drawer

Backend fixture:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/prepare_android_multi_conflict_pending_drawer.py --reset --apply"
```

Maestro:

```powershell
cd C:\Users\SANTOSH\Documents\FarmInt
& "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat" test "maestro\26-multi-conflict-pending-drawer.yaml"
```

Verifier:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_multi_conflict_pending_drawer.py"
```

Optional backend dedup proof:

```powershell
wsl -e bash -lc "cd ~/projects/farmint/backend && ../venv/bin/python scripts/verify_android_multi_conflict_pending_drawer.py --send-conflict-batch --resend-conflict-batch"
```

Expected Android result:

- shows both distinct cards;
- no stale-context copy;
- no duplicate/noisy cards for same event id;
- resolving one card should not hide unrelated pending conflicts.

## Optional queue/resilience flows

Run after the priority evidence set, if time permits.

| Flow | Prep | Maestro | Verifier |
| --- | --- | --- | --- |
| Dependency-order replay | `prepare_android_dependency_order_replay.py --apply` | `23-dependency-order-replay.yaml` | `verify_android_dependency_order_replay.py` |
| Partial-batch replay | `prepare_android_partial_batch_replay.py --apply` | `24-partial-batch-replay.yaml` | `verify_android_partial_batch_replay.py` |
| Partial-batch success + conflict | `prepare_android_partial_batch_conflict.py --apply` | `25-partial-batch-conflict.yaml` | `verify_android_partial_batch_conflict.py` |
| Queue backpressure | `prepare_android_queue_backpressure.py --reset-indexed --apply` | `27-queue-backpressure.yaml` | `verify_android_queue_backpressure.py` |
| Interrupted multi-batch resume | `prepare_android_interrupted_multibatch_resume.py --reset-indexed --apply` | `28a-interrupted-multibatch-first-batch.yaml`, then `28b-interrupted-multibatch-resume.yaml` | `verify_android_interrupted_multibatch_resume.py --phase first_batch`, then `--phase complete` |
| Poison-row backlog | `prepare_android_poison_row_backlog.py --reset-indexed --apply` | `29-poison-row-backlog.yaml` | `verify_android_poison_row_backlog.py` |
| Uncertain-result idempotency | `prepare_android_uncertain_result_idempotency.py --apply` | `22-uncertain-result-idempotency.yaml` | `verify_android_uncertain_result_idempotency.py` |
| Cold-start persistence | `prepare_android_cold_start_activity_persistence.py --apply` | `20-cold-start-activity-persistence.yaml` | `verify_android_cold_start_activity_persistence.py` |
| Device/emulator restart persistence | `prepare_android_cold_start_activity_persistence.py --apply` | `21-device-restart-activity-persistence.yaml`, device restart, then `21b-device-restart-activity-replay-after-restart.yaml` | `verify_android_cold_start_activity_persistence.py` |

## Evidence entry template

Copy this block per flow when recording final evidence.

```markdown
### Flow NN: <name>

- Maestro flow:
- Tenant:
- Project:
- Language/state context:
- Backend fixture command:
- Backend fixture output:
- Maestro command:
- Maestro result:
- Screenshot artifact path:
- Backend verifier command:
- Backend verifier output:
- Pass/fail:
- Android/backend contract gaps:
```
