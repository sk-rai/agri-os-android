# Gate 1 proposed commit review

Status: proposed only. Nothing staged, committed or pushed by this review. No
new test/flow/backend execution. Unrelated user changes remain untouched.

Suggested single commit title:
`fix: protect auth artifacts and validate authenticated reads`

This is not test-only: it includes OTP privacy/accessibility protection, real
read-only product navigation, hydration correction and a scoped transport policy.
Do not use `git add .` or stage directories wholesale. Inspect the explicit files
below, particularly any later overlapping user changes, before approval/staging.

## Explicit proposed paths (35 files)

Paths relative to `C:\Users\SANTOSH\Documents\FarmInt`.

Existing tracked changes (11):

- `app/build.gradle.kts`
- `app/src/main/java/com/agrios/app/core/auth/AuthRepository.kt`
- `app/src/main/java/com/agrios/app/ui/auth/AuthScreen.kt`
- `app/src/main/java/com/agrios/app/ui/auth/AuthViewModel.kt`
- `app/src/main/java/com/agrios/app/ui/cropcycle/StageActivitiesScreen.kt`
- `app/src/main/java/com/agrios/app/ui/cropcycle/StageTimelineScreen.kt`
- `app/src/main/java/com/agrios/app/ui/dynamicform/DynamicFormScreen.kt`
- `app/src/main/java/com/agrios/app/ui/farmer/FarmerProfileScreen.kt`
- `app/src/main/java/com/agrios/app/ui/home/HomeScreen.kt`
- `app/src/main/java/com/agrios/app/ui/navigation/AppNavigation.kt`
- `maestro/00-login-hydrate-existing-profile.yaml`

New implementation / tests / flows / utility files (18):

- `app/src/main/java/com/agrios/app/data/repository/AuthenticatedReadsRepository.kt`
- `app/src/main/java/com/agrios/app/data/repository/ReadDiagnostic.kt`
- `app/src/main/java/com/agrios/app/ui/auth/AuthenticatedReadContractChecks.kt`
- `app/src/main/java/com/agrios/app/ui/reads/OperationalReadScreens.kt`
- `app/src/otpPrivacyIsolation/AndroidManifest.xml`
- `app/src/androidTest/java/com/agrios/app/ui/auth/OtpArtifactPrivacyTest.kt`
- `app/src/test/java/com/agrios/app/ui/auth/AuthenticatedReadContractChecksTest.kt`
- `app/src/test/java/com/agrios/app/ui/auth/AuthenticatedReadTransportPolicyTest.kt`
- `app/src/test/java/com/agrios/app/ui/auth/AuthUiStatePrivacyTest.kt`
- `app/src/test/java/com/agrios/app/ui/auth/OperationalReadContractTest.kt`
- `app/src/test/java/com/agrios/app/ui/auth/ReadDiagnosticTest.kt`
- `maestro/helpers/login-existing-fixture.yaml`
- `maestro/66a-authenticated-profile-land-reads.yaml`
- `maestro/67a-authenticated-active-cycle-reads.yaml`
- `maestro/68-authenticated-persona-read-isolation.yaml`
- `maestro/run-authenticated-read-suite.ps1`
- `maestro/export-authenticated-read-diagnostics.ps1`
- `maestro/test-authenticated-read-diagnostics.ps1`

Documentation (6):

- `docs/authenticated-read-diagnostics.md`
- `docs/authenticated-read-gate1-coverage.md`
- `docs/authenticated-read-gate1-status.md`
- `docs/authenticated-read-gate1-run-2026-10-17.md`
- `docs/authenticated-read-gate1-run-2026-10-18.md`
- `docs/authenticated-read-gate1-commit-review.md`

## Diff review summary

- Auth: remove credential-related diagnostic logging and visible dev-code copy;
  fixed error copy, masked accessible OTP, clear value after success. Test-only
  opt-in app isolation prevents backend auth initialization in instrumentation.
- Navigation/read surfaces: stable tags and usable back actions; land/soil,
  occupied-parcel availability and assigned/assisted views are real read-only
  screens. Backend capability decides agent navigation; backend still authorizes.
- Hydration: stop a Room insert from cancelling its own remaining refresh.
- Activity/finance: failed or malformed reads no longer masquerade as zero/empty
  success. UI assertions use preserved identity and dynamic totals.
- Transport: dedicated2s idle pool only for AuthenticatedReadsRepository;15s
  timeouts, retries disabled, normal AuthInterceptor and exact scope preserved.
- Diagnostics: enum/type/callback evidence only; no messages, stack traces,
  arbitrary class names, headers, URLs, bodies or credentials in artifact output.
- Flows: normal OTP, local-state-only identity switching; no save/create/stage,
  assignment, admin or ACK mutations. Authentication captures removed from00.
- Utilities: runner remains opt-in; evidence outside source. Individual runs used
  manual pending-OTP checks/export/audits not fully automated by the runner.

## Excluded user work / artifacts

- `maestro/17-stale-context-recovery.yaml`
- `maestro/38-agent-assisted-farmer-management.yaml`
- `docs/pitch/` and all generated pitch/screenshots
- Both oddly named untracked response-expression files
- `cleanup-before-flow26.sql`, `cleanup-flow14.sql`, `cleanup-sync-queue.sql`
- `flow14-ui.xml`, `flow39-window.xml`, `flow52-window.xml`, `flow53-window.xml`,
  `flow58-part1.patch`
- Build/test reports, APKs, emulator state, external temp evidence, credentials,
  environment files and all WSL backend changes

## Review limits / known follow-ups (not implemented here)

- Passing evidence is composite across builds:67a earlier;66a/68 final pool APK.
- The separate contract-check client uses its existing default OkHttp retry
  policy; the no-retry/pool assertion applies only to AuthenticatedReadsRepository.
- Authenticator fallback and other pre-existing clients were not broadly redesigned.
- The runner does not automate pending-OTP validation or credential-artifact audit;
  successful individual runs are not proof of runner end-to-end completion.
- Transport policy tests inspect pinned OkHttp4.12 internals, not network eviction.
- Trusted enrichment, controlled expiry and fresh mutation phases remain uncovered.

## Go/no-go before commit

- Confirm the above exact path list and scope are approved.
- Preserve all excluded changes; never reset or delete them for a clean status.
- Review the actual staged diff after a separately approved explicit staging step.
- Verify no generated evidence, auth material or backend changes are staged.
- Retain the different APK hashes and original failed evidence in documentation.
- Do not mark Gate2 complete or run it; it requires separate approval.
