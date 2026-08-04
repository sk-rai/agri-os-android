param(
    [string]$Maestro = "C:\Users\SANTOSH\maestro\maestro\bin\maestro.bat",
    [switch]$SkipHappyPath,
    [switch]$SkipGuidance,
    [switch]$SkipRecovery
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not (Test-Path $Maestro)) {
    throw "Maestro executable not found at '$Maestro'. Pass -Maestro with the correct path."
}

function Show-Step($title, $body) {
    Write-Host ""
    Write-Host "==> $title" -ForegroundColor Cyan
    if (-not [string]::IsNullOrWhiteSpace($body)) {
        Write-Host $body -ForegroundColor Yellow
    }
}

function Wait-ForUser($message = "Press Enter when ready to continue") {
    Write-Host ""
    Read-Host $message | Out-Null
}

function Run-Flow($flow) {
    Show-Step ('Running ' + $flow) ''
    & $Maestro test $flow
    if ($LASTEXITCODE -ne 0) {
        throw ('Maestro flow failed: ' + $flow + ' code ' + $LASTEXITCODE)
    }
}

Show-Step "Canonical WSL prep before flows 10-19" @"
Run in WSL before starting this suite:

cd ~/projects/farmint/backend
../venv/bin/python scripts/seed_android_dynamic_profile_test_context.py --apply
../venv/bin/python scripts/seed_android_crop_cycle_test_fixture.py --reset --apply
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply
../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply

Keep emulator app data after flow 10 begins.
"@
Wait-ForUser

if (-not $SkipHappyPath) {
    Show-Step "Happy path group: flows 10-13" @"
Flow 10 has a 60-second wait before submit. During that wait:
1. Stop/pause FastAPI backend.
2. Let Android tap Start Cycle and queue offline.
3. Restart backend.
4. Tap Sync Now if the flow/test instructions require it.

Do not clear app data between flows 10-13.
"@
    Wait-ForUser
    Run-Flow "maestro\10-offline-crop-cycle-create-queue.yaml"
    Show-Step "After flow 10" "Restart backend, tap Sync Now if Android still shows waiting, then continue. Flow 11 needs the flow 10 crop cycle synced and visible from backend."
    Wait-ForUser "Press Enter only after backend is running and flow 10 has synced"
    Run-Flow "maestro\11-offline-stage-start-queue.yaml"
    Show-Step "After flow 11" "Restart backend, tap Sync Now if Android still shows waiting, then continue. Flow 12 needs NURSERY stage START synced/active."
    Wait-ForUser "Press Enter only after backend is running and flow 11 has synced"
    Run-Flow "maestro\12-offline-activity-log-queue.yaml"
    Show-Step "After flow 12" "Restart backend, tap Sync Now if Android still shows waiting, then continue. Flow 13 needs the activity synced for finance totals."
    Wait-ForUser "Press Enter only after backend is running and flow 12 has synced"
    Run-Flow "maestro\13-activity-finance-summary-smoke.yaml"
    Show-Step "WSL verify happy path" @"
cd ~/projects/farmint/backend
../venv/bin/python scripts/verify_android_offline_stage_activity_replay.py
"@
    Wait-ForUser
}

if (-not $SkipGuidance) {
    Show-Step "Stale-context guidance: flow 14" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply

Flow 14 queues while backend is stopped. After Android queues the event, before backend restart / Sync Now:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --apply

After Sync Now, verify with the Android-generated event id:
../venv/bin/python scripts/verify_android_stale_context_sync_failure.py --event-id {event_id}

Restore afterward:
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
"@
    Wait-ForUser
    Run-Flow "maestro\14-stale-context-sync-failure.yaml"

    Show-Step "Version mismatch guidance: flow 15" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply

After flow:
../venv/bin/python scripts/verify_android_version_mismatch_conflict.py
"@
    Wait-ForUser
    Run-Flow "maestro\15-version-mismatch-conflict.yaml"

    Show-Step "Workflow invalid guidance: flow 16" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply

After flow:
../venv/bin/python scripts/verify_android_workflow_invalid_conflict.py
"@
    Wait-ForUser
    Run-Flow "maestro\16-workflow-invalid-conflict.yaml"
}

if (-not $SkipRecovery) {
    Show-Step "Stale-context recovery: flow 17" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply

During the 60-second wait:
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --apply

After flow, verify with the failed event id:
../venv/bin/python scripts/verify_android_stale_context_recovery_state.py --event-id {event_id}

Restore afterward:
../venv/bin/python scripts/prepare_android_stale_context_sync_failure.py --restore --apply
"@
    Wait-ForUser
    Run-Flow "maestro\17-stale-context-recovery.yaml"

    Show-Step "Version mismatch recovery: flow 18" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_version_mismatch_conflict.py --reset --apply

After flow:
../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type VERSION_MISMATCH
"@
    Wait-ForUser
    Run-Flow "maestro\18-version-mismatch-recovery.yaml"

    Show-Step "Workflow invalid recovery: flow 19" @"
Before flow:
cd ~/projects/farmint/backend
../venv/bin/python scripts/prepare_android_workflow_invalid_conflict.py --reset --apply

After flow:
../venv/bin/python scripts/verify_android_conflict_recovery_state.py --conflict-type WORKFLOW_INVALID
"@
    Wait-ForUser
    Run-Flow "maestro\19-workflow-invalid-recovery.yaml"
}

Write-Host ""
Write-Host "Offline sync suite runner completed." -ForegroundColor Green
