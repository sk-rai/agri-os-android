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
maestro test maestro\00-login-hydrate-existing-profile.yaml
maestro test maestro\01-home-history-smoke.yaml
maestro test maestro\02-farmer-profile-parcel-geometry.yaml
maestro test maestro\03-start-crop-eligible-parcel.yaml
```

Optional / more fragile while backend forms continue changing:

```powershell
maestro test maestro\04-dynamic-land-intelligence-guidance.yaml
```

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

