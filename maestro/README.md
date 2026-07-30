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
