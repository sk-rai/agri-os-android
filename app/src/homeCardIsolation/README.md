# Home panels: isolated instrumentation target

This document records isolated test evidence; it does not authorize a repeat build, install, launch, or test execution. `PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.

`-PhomeCardIsolation=true` is opt-in and cannot be combined with the other isolation or ownership-host flags. The debug target ID is `com.agrios.app.homecard`; its test APK is `com.agrios.app.homecard.test`. Only the dedicated instrumentation source directory participates. The farmer-card, crop-cycle, queue-panel, and server-profile cases are excluded from ordinary instrumentation to prevent accidental execution against the normal application. Existing dependency declarations are unchanged.

The target overlay replaces `AgriOsApp` with plain `android.app.Application`, removes `MainActivity`, and requests removal of all providers, receivers, services, activity aliases and permissions. The Compose test-manifest library supplies `androidx.activity.ComponentActivity`. The test overlay declares a plain Application and requests removal of providers, receivers, services, activity aliases and requested permissions. It retains the AndroidX Test ActivityScenario helper activities. The first filtered run stopped before both card assertions because the test overlay had removed EmptyActivity. The runner requires the isolated target package and the plain Application class in `newApplication`, then explicitly instantiates that class. The runner also requires one exact class filter from the four reviewed classes. These source guards are not a substitute for inspecting merged manifests; providers can run before test methods.

After separate build approval, inspect the merged **and packaged** manifests before any install. Require the exact isolated package IDs, the expected runner and instrumentation target, plain Application on both APKs, no permissions or startup components, no `MainActivity`, and only `androidx.activity.ComponentActivity` on the target. The test APK must contain exactly the three `androidx.test.core.app.InstrumentationActivityInvoker` nested activities `BootstrapActivity`, `EmptyActivity`, and `EmptyFloatingActivity`. Verify exactly ten packaged JUnit cases: two farmer-card, two crop-cycle, two queue-panel, and four server-profile cases (2 + 2 + 2 + 4). Stop if anything differs; do not install a mismatched APK. Build only the isolated debug/test APKs, offline with toolchain auto-download disabled; do not provision dependencies as part of that approval.

After separate install and execution approval, use only an explicitly approved dedicated device serial and those inspected APKs. Choose the exact filter for the separately approved class:

```text
adb -s <approved-dedicated-serial> shell am instrument -w -r -e class com.agrios.app.ui.home.HomeOwnedFarmerCardTest com.agrios.app.homecard.test/com.agrios.app.ui.home.HomeCardIsolationRunner
adb -s <approved-dedicated-serial> shell am instrument -w -r -e class com.agrios.app.ui.home.HomeCropCyclePanelTest com.agrios.app.homecard.test/com.agrios.app.ui.home.HomeCardIsolationRunner
adb -s <approved-dedicated-serial> shell am instrument -w -r -e class com.agrios.app.ui.home.HomeQueuePanelTest com.agrios.app.homecard.test/com.agrios.app.ui.home.HomeCardIsolationRunner
adb -s <approved-dedicated-serial> shell am instrument -w -r -e class com.agrios.app.ui.home.HomeServerProfilePanelTest com.agrios.app.homecard.test/com.agrios.app.ui.home.HomeCardIsolationRunner
```

Expect two cases for each of the farmer-card, crop-cycle, and queue-panel filters, and four for the server-profile filter. Report sanitized counts and distinguish any skip or infrastructure failure from a pass.

## Recorded execution checkpoint

Two separately approved filtered runs on `emulator-5554` reported four passes: two `HomeOwnedFarmerCardTest` cases and two `HomeCropCyclePanelTest` cases, with zero failures and zero skips. The farmer-card run used the inspected APK pair after verifying local hashes; the crop-cycle run independently checked the installed pair against those hashes and did not rebuild or reinstall. The earlier `EmptyActivity` infrastructure failure was not counted as a pass.

These runs exercised the production card and panel with synthetic state and callbacks: same-tenant and cross-tenant replacement, stale results, and replacement navigation. The card cases also cover query-first inactivity and the shared enrollment predicate; the panel cases include replacement load failure. They do not establish generated Room mapping, full Home startup, hydration, live requests, or synchronization behavior. Counts are from the recorded instrumentation runs, not a new rerun for this checkpoint.

## Queue panel execution checkpoint

The reported filtered `HomeQueuePanelTest` run on `emulator-5554` recorded 2 passed, 0 failed, and 0 skipped. Across same-tenant and cross-tenant switches, cached and late A emissions left the panel in its checking state without exposing A's counts, rows, or actions. Once B's summary arrived with mixed-owner attention rows, only B's eligible discard action appeared and it passed B's event ID. The held fixture exposed no discard or recovery action.

The historical recorded total is six isolated Compose passes across the three classes that ran. The queue cases cover production rendering and callback selection with synthetic state, not Room emission timing, queue mutation, sync admission, or live sync. This checkpoint records the reported run; it does not independently reproduce it.

The earlier inspected four-case APK pair predates the queue-panel addition. The recorded six passes are historical evidence, not the current packaged-test requirement.

## Server profile panel — accepted isolated run

The exact additional runner filter is
`com.agrios.app.ui.home.HomeServerProfilePanelTest`. A future approved
rebuild must verify ten packaged cases: the six previously defined cases
and four server-profile cases, with the same manifest allowlist. The
previously inspected and installed APKs are not evidence for these sources.

The new tests mount the production tap/render boundary beside the production
local farmer card. The fake counts requests and retains callbacks for
controlled late delivery; it opens no database and performs no HTTP.
The cases cover zero requests on mount, one per tap, new-tap clearing,
a not-found result, tenant/user/epoch replacement, and stale success and
failure suppression. They exercise the composed panel, not full Home startup,
Room auth emission timing, or the production network/lease adapter.

Independent review accepted the sanitized result: four started and passed,
zero failures, errors or skips, on `emulator-5554` (`Pixel_6`, Android user 0).
Both installed APK hashes matched the reviewed artifacts. The result-file
SHA-256 is `30E906E5EF1454312836E2E969AA01A39E20223794E9CA9CE783AC38D731C506`.
The APKs and result remain local. This does not establish full Home startup,
live API behavior, Room emission timing, or deployment. No rerun is authorized.
