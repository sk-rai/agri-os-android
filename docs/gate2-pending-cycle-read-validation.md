# Gate 2 pending-cycle read navigation — verified, review pending

## Root cause and correction

StageTimelineScreen placed its Activities read-navigation action inside an
active/completed-stage condition. The callback name onLogActivity is legacy:
AppNavigation routes it to StageActivitiesScreen, not an activity mutation.
StageActivitiesScreen independently treated only completed stages/cycles as
read-only. Exposing the existing button for PENDING alone would therefore have
exposed recommendation/custom-activity mutation navigation.

Read navigation is now separate from Start/Complete/Skip. Known PENDING stages
show **View recommendations and activities**. The shared pure policy permits
logging only for an active stage in an ACTIVE/PARTIALLY_TRACKED cycle; pending,
completed, missing and unknown mutation states fail closed. Recommendation
cards have disabled click semantics, no mutation arrow, and a callback guard;
custom logging is absent. The expected date is labelled Expected start rather
than implying that a pending stage has already started.

The existing screen already contains the cycle finance summary. No new route,
ViewModel, backend endpoint, role or authorization bypass was added. Reads still
use AuthInterceptor and the existing operational-read client policy (15-second
connect/read timeouts, no automatic connection retries). The changed cycle-read
clients also do not follow redirects. No fallback scope or identity is used.

HTTP statuses are shown in debug-only fixed route labels. Finance parses exact
schema/cycle/tenant matches and required numeric totals, rather than substituting
zero for missing/malformed data. Genuine empty history is explicit. The parcel
availability read screen displays the actual supplied boolean eligibility value.

## Exact incremental file scope

- app/src/main/java/com/agrios/app/ui/cropcycle/StageTimelineScreen.kt
- app/src/main/java/com/agrios/app/ui/cropcycle/StageActivitiesScreen.kt
- app/src/main/java/com/agrios/app/ui/cropcycle/StageReadPolicy.kt (new)
- app/src/main/java/com/agrios/app/ui/reads/OperationalReadScreens.kt
- app/src/test/java/com/agrios/app/ui/cropcycle/StageReadPolicyTest.kt (new)
- maestro/67c-pending-cycle-read-only.yaml (new)
- maestro/helpers/67c-assert-six-pending-stages.yaml (new)
- maestro/helpers/67c-occupancy-and-relaunch-read-only.yaml (new)
- docs/gate2-pending-cycle-read-validation.md (new)

Prior uncommitted profile/sync/create corrections, old Maestro edits and unrelated
artifacts were preserved. This manifest is not permission to stage the entire tree.

## Verification on 2026-10-19

Android HEAD: 199890d19712f6a169ae883f3bff62b39b76ed92; initially dirty.
Backend: fee542bdbad20381aa914890ad01b387ff290f71; pre-existing documentation edits.
Health: 200. Base URL: http://10.0.2.2:8000/api/v1/.
Device: emulator-5556, Pixel_6, API 34. Maestro: 2.7.0.

APK SHA-256:
`3E632688E1F48531F47FD13F5C1075B2AF48C41EB987885D694A201A2D908D27`

Host command:
`gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PotpPrivacyIsolation=false --console=plain`

Passed: 46 tests, zero failures/errors; six new policy/finance tests. Existing
host privacy tests included. Tests cover pending read permission versus mutation
denial, active mutation policy, completed/unknown denial, genuine zero finance,
missing/malformed totals and wrong schema/tenant/cycle rejection. Build and diff
whitespace checks passed; existing deprecation/CRLF warnings remain.

No connected instrumentation suite was run. Instead, the changed affordances
were exercised on the real emulator and the native accessibility hierarchy was
audited. recommendation_card_NURSERY_0 has enabled=false, clickable=true: a
disabled semantic action, not an enabled mutation. Custom logging was absent at
the bottom of the read screen. No credential/privacy semantics were changed.

Both focused Maestro flows exited 0:
- Gate2 pending cycle recommendations activities and finance - read only
- Gate2 pending read mutation controls occupancy and relaunch

Existing session restored without OTP or clearing data. Exact farmer:
107d7261-ca96-4e95-bb1a-8eb837f3388b. Exact cycle:
54d5583e-0624-4087-ba34-d3edcdf4635e.

Authenticated HTTP proof:

| Read | Status | Verified data |
| --- | --- | --- |
| Cycle detail | 200 | PLANNED, six PENDING stages before/after relaunch |
| Recommendations | 200 | 24 cycle total, 7 NURSERY recommendations |
| Activities | 200 | Genuine empty history |
| Stage cost | 200 | crop_cycle_stage_cost_summary.v1; count 0; actual expense 0.00 |
| Profit/loss | 200 | crop_cycle_profit_loss_summary.v1; income/expenses/profit all 0.00 |
| Eligible parcels | 200 | eligible=false, HAS_ACTIVE_CYCLE, exact parcel/cycle linkage |

Stages remained NURSERY, TRANSPLANTING, TILLERING, FLOWERING, GRAIN_FILLING,
HARVEST, in that order, all PENDING. Parcel:
9d3bf97b-fa15-49f8-b4f0-36e2763e8299.

Final PostgreSQL READ ONLY transaction followed by rollback independently
confirmed PLANNED, six PENDING stages, no actual execution dates/actors/skip
reasons, one cycle/parcel/soil, zero activities/enrichments. Protected .0003 counts
remain one cycle, six stages, one activity. This count check is not a new full
historical audit comparison; no Android operation selected the protected fixture.

## External evidence and limits

Evidence root (not Git content):
`C:\Users\SANTOSH\AppData\Local\Temp\agrios-authenticated-reads\gate2-pending-reads-20261019-130020`

Includes metadata, automatic Maestro reports, six screenshots, disabled-card
hierarchy, cycle-read-sanitized.log and authenticated-read-summary.json.
Text credential-marker scanning and manual screenshot review found no auth
secrets. This is a bounded pattern/image audit, not proof against every possible
secret encoding. No auth screen was captured and no OTP was requested.

Only this preserved fixture/API34 path was run. Active-stage logging was checked
by host policy tests, not by device mutation. No stage/activity/finance write,
manual sync, provider, fixture command, backend code change, commit or push.
Application was force-stopped after verification.

Proposed commit: `fix: allow read-only inspection of pending crop stages`

Stop for review; do not begin stage transitions, activity logging or expiry tests.
