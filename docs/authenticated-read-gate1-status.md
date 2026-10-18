# Authenticated read Gate 1 — artifact privacy prerequisite

Status (2026-10-18): Gate 1 preserved-data smoke evidence is passing, pending
review/commit approval. 67a passed previously; 66a and 68 passed on the latest
short-idle-pool APK. No same-build full-suite rerun is claimed. Isolated OTP
privacy/accessibility tests previously passed 3/3 and were not rerun during the
transport fix. Latest host tests passed 20/20. See
`authenticated-read-gate1-run-2026-10-18.md`, `authenticated-read-gate1-coverage.md`
and `authenticated-read-gate1-commit-review.md`. The 2026-10-17 failure report is
historical, not the current result. Gate 2 remains explicitly unapproved.

## Implemented protection

- Removed the development OTP message while retaining in-memory auto-fill.
- OTP pixels use password masking and a number-password keyboard.
- OTP uses native password text-field semantics with a fixed accessible label;
  focus/edit actions are retained instead of clearing field semantics.
- Auth UI state diagnostic strings omit the code, mobile and error payload.
- Authentication errors shown by the view model use fixed messages.
- Successful verification clears the code from view-model state.
- Removed auth repository logging, including JWT claim diagnostics.
- Removed explicit language/login/OTP captures from Flow 00; its post-login
  screenshot remains.

## Threat analysis and proof limits

Automatic Maestro failure captures are not controlled by removing takeScreenshot.
Screenshots require pixel masking; hierarchy/debug captures require separate
Compose and platform accessibility checks. Password keyboard hints alone are not
proof. Masking does not remove the in-memory value needed to verify the code.

AuthUiStatePrivacyTest checks diagnostic redaction with synthetic data.
OtpArtifactPrivacyTest renders a synthetic code without network access, checks
both merged and unmerged semantics and the active Android accessibility tree,
and confirms the auto-filled Verify button still works. It does not dump nodes,
save screenshots, request an OTP, issue credentials, or contact a backend.

User approved test-only isolation and the isolated run. An opt-in build property
installs `com.agrios.app.otpprivacy`, with base Application, no Internet permission,
no startup provider, and no production activity. Tests assert these constraints.
Production variants without this property remain unchanged. Production auth UI
and ViewModel are tested with a test-only in-memory database and fake API responses;
singleton/repository injection is confined to the isolated instrumentation process.
No real auth exchange or persisted fixture user is used.

Executed on `emulator-5556`, AVD `Pixel_6`, Android 14/API 34:

```powershell
$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = "emulator-5556"
    .\gradlew.bat :app:connectedDebugAndroidTest -PotpPrivacyIsolation=true "-Pandroid.testInstrumentationRunnerArguments.class=com.agrios.app.ui.auth.OtpArtifactPrivacyTest"
} finally {
    $env:ANDROID_SERIAL = $previousSerial
}
```

Latest result after the approved accessibility fix: 3 tests, 3 passed, 0 failed,
0 skipped/errors. Build successful. Earlier iterations failed during test-node,
window and input-driver corrections; they are not claimed as successful runs.

- `privacyAndVisualMasking`: passed. Merged/unmerged semantics and Android
  accessibility tree contained no synthetic code or literal dev-message label.
  In-memory pixel comparison for two different same-length codes was identical.
  Images were not written to disk. This supports masking; it is not an OCR audit.
- `autoFillVerificationClearsProductionUiState`: passed. Fake verification received
  the expected synthetic code; real ViewModel success cleared the code.
- `accessibilityAndKeyboardRemainOperable`: passed. The password field is now
  discoverable, focusable and editable;
  accessibility focus succeeds, Compose IME edit-command entry works, and platform
  SET_TEXT succeeds and updates state. Edited values remain absent from merged,
  unmerged and platform hierarchy checks. Hardware-key injection timed out in
  earlier runs; this is not proof of physical/on-screen keyboard operability.
  After accessibility Back dismisses the IME, direct hierarchy traversal identifies
  the enabled Verify action. Platform ACTION_CLICK succeeds, invokes fake
  verification, and clears the code. The earlier provider text-search lookup did
  not find this Compose node; it was replaced with recursive hierarchy traversal.
  Compose Verify and state clearing also pass separately. No TalkBack manual pass
  or physical/on-screen keyboard end-to-end pass is claimed.

The automated runtime privacy/basic-accessibility prerequisite now passes. The
approved product change restores native password-field editing. Test changes
select the actual editable node and app window, check post-edit redaction, and
require and execute a real platform Verify action. These paragraphs describe the
isolated privacy prerequisite. Later approved real-fixture logins and individual
flows are recorded separately in the execution reports; the whole runner was not
successfully completed end-to-end.

Artifact audit: 22 files across connected results and HTML reports, including
three Logcat files; no saved screenshots or hierarchy dumps. Searches found no
synthetic code (forward/reversed), dev-message literal, credential field names,
Authorization/Bearer marker, or synthetic auth identity/session/device values.
No full authentication responses or JWT claims were logged by the tested path.
This audit covers the generated test artifacts, not historical device logs.

Reports:
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_6(AVD) - 14-_app-.xml`
- `app/build/outputs/androidTest-results/connected/debug/Pixel_6(AVD) - 14/`

Pre-run real-app hierarchy detection returned no readable hierarchy, so no claim
is made about pending real OTPs elsewhere. The separate package and denied network
permission prevent this test from reading or operating any such real session.

## Smoke coverage (implemented in the approved follow-up)

| Proposed flow | Required endpoints/assertions |
| --- | --- |
| 67a active cycle | crop collection/detail; six stages; populated recommendations; existing activities; successful stage-cost/P&L with dynamic baseline; occupied deterministic parcel |
| 66a profile/land | exact hydrated farmer/parcel; unsaved profile forms; successful land context; soil/enrichment empty states; same identity after relaunch |
| 68 persona | separate persona tenant; assigned work visible through real navigation; unassigned collection/target denial; no admin/PATCH/conflict probes |

Contract-only probes may cover workflow catalog/preview and enrichment reads.
They must not be presented as product UI coverage. Assigned-work and land/soil
navigation now exist as actual read-only screens, not debug evidence substitutes.
See the companion coverage document for passing assertions and remaining limits.

No backend fixtures, provider execution, authentication policy, assignments,
crop/activity/finance evidence, or unrelated user changes were modified.

## Original implementation verification snapshot (historical)

- `:app:assembleDebug`: passed.
- `:app:testDebugUnitTest`: passed (11 tests total, no failures/errors, after
  the read-only navigation follow-up).
- `:app:assembleDebugAndroidTest`: passed, including the isolated test build.
- `git diff --check` for changed tracked files: passed.
- Maestro flow execution: not attempted. New YAML was parsed statically only;
  CLI help was inspected without connecting to a device.
- Isolated test APKs installed/executed only on emulator-5556; no backend request,
  fixture command, real OTP exchange, commit or push executed.

## Latest verification and remaining boundaries

- Latest normal debug build: passed; host unit tests: 20 passed, zero failures/errors.
- Synthetic diagnostic exporter privacy tests passed; runtime sentinel/JWT scans
  of host test results found zero matches.
- 66a: exit0, exact farmer/parcel, soil200/count0, context200/schema match,
  summary200/schema match, backend guidance, unsaved forms, relaunch and narrow
  contract checks all passed. Seven text artifacts: zero credential-marker matches;
  one post-login screenshot reviewed, no visible authentication secrets.
- 68: exit0, assigned collection/detail200 and expected identity, unassigned200
  empty collection with no error; seven text artifacts: zero credential-marker
  matches; three post-login screenshots reviewed, no visible authentication secrets.
- Audits are bounded to generated artifacts and known marker checks, not a claim
  of exhaustive secret discovery or a fresh accessibility/TalkBack validation.
- No populated trusted enrichment coverage; no expired/missing-session runtime
  coverage; no unauthorized-detail or mutation probes; no Gate 2 execution.
- No commit, push, staging or new device/backend execution during handoff review.
