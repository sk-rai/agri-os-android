# Gate 1 authenticated-read implementation handoff

Status (2026-10-18): preserved-data smoke coverage has passing evidence for 67a,
66a and 68. See `authenticated-read-gate1-run-2026-10-18.md` for the consolidated
results and `authenticated-read-gate1-commit-review.md` for proposed commit scope.
67a passed on the earlier APK; 66a and 68 passed on the short-idle-pool APK. This is
not a single-build full-suite result or a full Android/backend compatibility pass.
The initial failure report remains preserved as historical evidence; its pending
scope decision is superseded by the backend owner's correction and successful
project-scoped 66a rerun. No Android scope fallback or backend authorization
weakening was used. No fixture reset/apply, domain-data repair, commit or push
was performed in the reruns.

## Coverage matrix

All routes below are GETs under `/api/v1/` except normal OTP login. New operational
reads use the normal AuthInterceptor. No public/header-only retry is available.

| Flow/screen | Routes | Assertions |
| --- | --- | --- |
| Safe login helper | normal auth OTP request/verify | Only nonsecret TEST_MOBILE; safe in-memory auto-fill; Home reached and Auth departed; no authentication screenshots |
| 67a Home/timeline | farmers/me/profile; crop-cycles PLANNED/ACTIVE/COMPLETED; crop-cycles/{id}; crop-cycles/templates/RICE | Exact farmer/cycle identity; actual hydration result; collection200x3; detail/template200; ACTIVE RICE/KHARIF; six individually visited stage positions |
| 67a activity screen | crop-cycles/{id}/recommended-activities; activities; stage-cost-summary; profit-loss-summary | Actual NURSERY recommendation and supplied existing activity ID; non-null finance totals captured dynamically; positive expense and P&L consistency; no saved activity or stage transition |
| 67a parcel availability | crop-cycles/eligible-parcels?farmer_id=...&season=KHARIF&project_id=... | HTTP200; exact deterministic parcel; HAS_ACTIVE_CYCLE; exact existing cycle; backend sowing date in2026; no creation |
| 66a profile/land/soil | farmers/me/profile and existing by-mobile refresh; soil-profiles?parcel_id=...; profile/land-intelligence-context; profile/land-intelligence-summary | Exact farmer/parcel; direct soil200 and explicitly supplied expected count; empty-state assertion only when count0; backend guidance/cards; informational/nonblocking flags; same successful hydration after relaunch |
| 66a unsaved forms | forms/profile-contract/bootstrap and soil/parcel form schemas | Existing soil/parcel forms opened without Save/Submit; existing farmer profile read surface used rather than re-enrollment |
| 68 assigned work | auth/mode-bootstrap; field-agent/worklist?assigned_only=true&project_id=...; farmers/{id}/launch-context | Backend capability controls navigation; assigned fixture farmer card and authorized detail; separate persona project visible; unassigned valid200 empty list; no admin/PATCH/ack probes |
| Narrow contract checks | workflow-catalog/enabled-crop-workflows; published workflow-preview; soil-profiles/enrichments list/summary/latest | Schema/count/identity checks; zero snapshots means list200 + valid summary200/count0 + latest404, never401/403 as empty. Contract-only, not UI coverage |

The prior intentional coverage-failure placeholders have been removed only after
implementing real read-only screens. No 66b/67b/69 or Gate2 behavior was added.

## New product navigation

- Farmer profile parcel card → **Land and soil guidance**: own-profile identity,
  soil profiles and backend land guidance. PIN is hydrated where present and may
  be entered solely for a read. Back remains usable even if guidance fails.
- Home → **Parcel availability**: explicit season query and all authorized parcel
  statuses, including occupied parcels previously filtered out of create forms.
- Home → **Assigned farmers**, only when backend mode-bootstrap says agent is
  available → authorized card → **Assisted farmer** read-only context.
- Existing debug persona/land callbacks that mutate data remain unused.

New screens have no Save, Create, Start/Complete, assignment or provider action.
Errors401/403/404 have distinct fixed copy. Each new read runs once; no automatic
unauthorized retry loop or fallback to public access exists. AuthenticatedReadsRepository
has retries disabled, 15-second connect/read timeouts and a dedicated two-second
idle pool. Its debug diagnostics contain only allowlisted route/schema/exception
codes, status, timing, transport phase and connection-reuse evidence. Bodies,
headers, messages and auth secrets are not logged as diagnostics. See
`authenticated-read-diagnostics.md` for scope and interpretation limits.
The separate narrow contract-check client retains its existing OkHttp retry policy;
do not claim that every Android client has retries disabled.

## Authentication artifact protection

Prior isolated OTP verification passed3/3 on emulator-5556 (Pixel6/API34): merged
and unmerged semantics, Android hierarchy, pixel masking, auto-fill, clearing,
accessible focus/edit/Verify. Artifact audit found no secret markers. No real
authentication was involved. Keyboard proof used Compose IME edit commands, not
a manual TalkBack/on-screen keyboard session. These tests were not rerun on a
device during this implementation; OTP field implementation is unchanged.

Only the four fixture mobiles are allowed by the helper. Never supply an OTP,
token, device key, claims, JWT secret or authentication response through YAML,
environment variables, screenshots or command arguments. Automatic Maestro
failure screenshots/hierarchy dumps still rely on the runtime-tested password
masking/redaction, not merely omission of explicit screenshots.

## Readiness and remaining limits

- Supply current preserved dynamic farmer/parcel IDs, preserved crop-cycle ID,
  existing costed activity ID and expected manual soil-profile count. Do not
  confuse absence of enrichment snapshots with absence of manual soil records.
- Dynamic/crop tenant/project stay android-dynamic-test/...000001. Assigned and
  unassigned personas stay android-persona-lifecycle-test/...000201.
- The unassigned **collection** is expected to return200 with no farmers. A401/403
  is a failure, not empty success. No arbitrary unassigned detail/admin probe is
  introduced; farmer flows assert absence of assigned-work navigation.
- Backend fixture preflight supports dynamic/crop, not the persona fixtures.
  Persona preparation must already be complete and reviewed. No repair/reset
  is performed by this runner.
- Finance assertions use dynamic UI captures, not stale hardcoded totals. The
  earlier 67a pass covered the six stages, recommendations, existing activity,
  finance consistency and occupied parcel. Its historical SELECT-only check
  found the preserved activity count/cost unchanged. It was not rerun after the
  read-client pool change; no same-build full regression is claimed.
- Trusted enrichment snapshots are absent: contract-only list/summary empty
  checks and latest404 passed; populated provider/enrichment UI is not covered.
- Session expiry, missing-bearer recovery, fresh enrollment and cycle creation
  remain outside this gate. 68 proves assigned detail and unassigned collection
  isolation, not arbitrary unassigned-detail or mutation denial.

## Future full-suite execution reference — not authorized by handoff review

Use actual nonsecret preserved IDs below; placeholders are deliberately invalid.
Preview omits `-Execute` and performs no commands:

```powershell
.\maestro\run-authenticated-read-suite.ps1 -DeviceId emulator-5556 `
  -DynamicFarmerId '<existing-dynamic-farmer-uuid>' `
  -DynamicParcelId '<existing-dynamic-parcel-uuid>' `
  -CropCycleId '<preserved-crop-cycle-uuid>' `
  -CostedActivityId '<preserved-costed-activity-uuid>' `
  -ExpectedSoilProfileCount <verified-count> -LandPinCode '560001'
```

After review and separate execution approval, the same command with `-Execute`
checks explicit device/localhost health; records Android/backend revisions and
dirty flags, Maestro version and APK SHA256; verifies addedc5 ancestry; executes
only seed dry-runs; runs token-safe dynamic then crop preflights sequentially
before Android OTP; builds/unit-tests/installs; runs67a→66a→68; and performs
status-only postflights. It stops on every native nonzero exit and restores
ANDROID_SERIAL. Evidence defaults to the Windows temp directory outside source.
No raw Logcat dump, reset/apply, fixture repair or cleanup deletion is performed
by the runner itself. Maestro can produce automatic device-logcat artifacts;
audit those independently. The approved individual reruns also used manual
pending-OTP checks and allowlisted diagnostic export; the runner does not yet
automate those steps. Only local app state is cleared when changing identities.

## Verification and changed files

Latest normal debug build passed; 20 host unit tests passed with zero failures or
errors, including privacy, diagnostic and transport-policy checks. Earlier,
four YAML files passed static PyYAML parsing and the runner passed
PowerShell AST parsing. `git diff --check` passed, with line-ending warnings only.
The unit checks cover denial versus empty results, tenant/project isolation,
informational summary contracts, backend label fallback, availability schema,
guidance identity, and diagnostic payload redaction. They do not prove UI layout.
Home hydration no longer keys its coroutine on the Room state it is populating,
preventing the first insert from cancelling the remaining hydration.

YAML parsing alone is not Maestro selector/command validation. Individual approved
executions now provide runtime evidence for 67a, 66a and 68. No additional execution
was performed while consolidating this handoff.

New files this follow-up:
- `app/src/main/java/com/agrios/app/data/repository/AuthenticatedReadsRepository.kt`
- `app/src/main/java/com/agrios/app/ui/reads/OperationalReadScreens.kt`
- `app/src/test/java/com/agrios/app/ui/auth/OperationalReadContractTest.kt`

Updated in this follow-up:
- `app/src/main/java/com/agrios/app/ui/navigation/AppNavigation.kt`
- `app/src/main/java/com/agrios/app/ui/home/HomeScreen.kt`
- `app/src/main/java/com/agrios/app/ui/farmer/FarmerProfileScreen.kt`
- `app/src/main/java/com/agrios/app/ui/cropcycle/StageTimelineScreen.kt`
- `maestro/66a-authenticated-profile-land-reads.yaml`
- `maestro/67a-authenticated-active-cycle-reads.yaml`
- `maestro/68-authenticated-persona-read-isolation.yaml`
- `maestro/run-authenticated-read-suite.ps1`
- this document and `docs/authenticated-read-gate1-status.md`

Earlier OTP/helper/contract-probe changes and unrelated user work are preserved.
