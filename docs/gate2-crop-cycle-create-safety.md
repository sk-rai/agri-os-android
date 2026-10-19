# Gate 2 crop-cycle create safety — verified

The implementation checkpoint below was host-only. Later separately approved
device and backend checkpoints are summarized at the end. Do not rerun Create
for the existing cycle.

## Defect and correction

The backend-driven crop-cycle form used an OkHttp client with implicit retries.
An IOException generated a new offline cycle ID and scheduled another CREATE.
A lost response could therefore turn an already committed direct request into a
second operation. This is a proven code path, not an observed duplicate cycle.

The direct create path now uses CropCycleCreateOnce, a Mutex-protected per-form
attempt latch. Success, HTTP rejection and uncertainty are all retained; another
activation of that intent does not call the API. A request/response attempt is
NonCancellable so cancellation cannot release its boundary while its request is
active. Cancellation after dispatch still requires read reconciliation.

The create client retains the normal AuthInterceptor and 15-second connect/read
timeouts. Automatic connection retries and redirects are explicitly disabled;
redirects are not followed as additional mutation requests. HTTP rejection is
not success. A transport/parser/unexpected exception or absent success body is
uncertain. No replacement UUID, offline event or worker is generated.

The UI disables the create action once dispatch is attempted. rememberSaveable
retains that guard through supported saved-state restoration. Fixed copy directs
the operator to read existing cycles before any further attempt. No request,
response/error body, exception text or credential enters diagnostic output:

`CycleCreate(outcome=UNCERTAIN, httpStatus=null)`

`CycleCreate(outcome=HTTP_REJECTED, httpStatus=403)`

`CycleCreate(outcome=ACCEPTED, httpStatus=200)`

This is not a durable idempotency protocol. A newly opened form or a process
restart without saved state gets a new intent. Never reopen/resubmit following
uncertainty without a separately reviewed backend read checkpoint. Known-offline
cycle creation no longer silently queues from this direct form. A future durable
offline-create design needs stable operation identity and explicit reconciliation;
it is not implemented here. Existing profile/parcel/soil queue behavior is unchanged.

## Exact incremental file scope

- app/src/main/java/com/agrios/app/core/sync/CropCycleCreateOnce.kt (new)
- app/src/main/java/com/agrios/app/ui/dynamicform/DynamicFormScreen.kt (crop path,
  attempt guard and fixed reconciliation copy; earlier profile edits preserved)
- app/src/test/java/com/agrios/app/core/sync/CropCycleCreateOnceTest.kt (new)
- docs/gate2-crop-cycle-create-safety.md (new)

## Verification

`gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PotpPrivacyIsolation=false --console=plain`

Passed: 40 host tests, zero failures/errors; seven new tests. Coverage includes
concurrent/repeated activation, transport uncertainty, HTTP rejection, missing
body, cancellation race, diagnostic redaction, and client retry/redirect/timeout
configuration. Existing host privacy and queue tests were included. Fake request
callbacks only; no backend or OTP requests. No device/instrumentation test run.

Test XML scan: zero synthetic-private-sentinel, OTP-banner, bearer or JWT-pattern
matches. Diff whitespace check passed (existing CRLF warnings remain).

Debug APK SHA-256:
`D502B7F1E3130E7DD37928E6DFF9945244FDBAC46DD8CFD38A0A7F95641C2EBC`

## Original approval boundary — superseded by checkpoints below

Review this correction before installation or Maestro. The earlier Flow 08 is
not safe to reuse unchanged: it clears app state, assumes old identity labels,
has stale fixture-reset instructions, and does not prove live workflow selection.
The separately approved one-shot Gate 2 cycle phase still needs a narrow flow
using the existing session, exact parcel, live published workflow selection,
runtime sowing date and read-only post-create checks. Do not run Flow 08, reset
fixtures, create a cycle, transition a stage or log an activity under this fix.

## Subsequent approved verification and publication

On 2026-10-18 the corrected APK was installed preserving the session. Android
verified the exact Gate 2 parcel ELIGIBLE and current published RICE/KHARIF catalog
and preview reads, then tapped Start Cycle once. The diagnostic was ACCEPTED,
HTTP 201. Cycle 54d5583e-0624-4087-ba34-d3edcdf4635e hydrated after relaunch with
status PLANNED and six PENDING stages. No offline replacement event was created.

The backend owner confirmed exactly one cycle, one CROP_CYCLE_CREATED audit,
published version 7fd53887-ea29-448f-b150-921940eec890 pinned, no sync event,
zero activities/expenses/income, and no stage transitions. No general server
exactly-once claim follows from this single submission.

On 2026-10-19 the pending-stage navigation correction passed focused authenticated
read tests and was published as 66a7dc5. See gate2-pending-cycle-read-validation.md.
Latest combined host verification: 46 tests passed, zero failures/errors; debug
APK built. The shared DynamicFormScreen profile and crop intent guards are
packaged together with the reviewed queue corrections in one buildable safety
commit. No new device run, fixture command or mutation was performed to publish
these already-tested changes.
