# Gate 1 execution result — 2026-10-17

Historical run: retain this failure and its original diagnosis as evidence.
Current consolidated results and superseding decisions are in
`authenticated-read-gate1-run-2026-10-18.md`; 66a and 68 subsequently passed.

Status: **partial pass; stopped on 66a failure. Do not close Gate 1.**
Artifacts reviewed on2026-10-18. No automatic rerun, fixture repair, authorization
change, commit or push was performed.

## Results

| Check | Result |
| --- | --- |
| Selected device | emulator-5556, Pixel_6; only connected device; backend health200 |
| Authentication privacy prerequisite | Existing isolated3/3 passing result confirmed; no unexpired pending OTPs for the four fixture mobiles before execution |
| Read-only discovery | Both farmer users and both agent users active, expected tenants/roles; expected persona assignments verified |
| Dynamic/crop seed dry-runs | Both exit0; no reset/apply |
| Sequential token-safe farmer preflights | Both exit0 before Android OTP requests |
| Normal debug build/install | Succeeded; installed APK hash recorded below |
| 67a active-cycle reads | **PASS**, runner metadata exit0 |
| 66a profile/land reads | **FAIL**, step039 cannot find land_context200; real saved screen shows context NETWORK and summary403 |
| 68 persona UI isolation | **NOT RUN**, runner stopped on failure |
| Runner postflights | **NOT RUN**, failure stopped sequence |

67a passed fresh login/hydration, exact active RICE/KHARIF cycle, collection and
detail/template statuses, six stages, recommendations, existing activity, dynamic
finance and occupied-parcel assertions, and narrow contract checks. Saved finance
screen shows1 activity, actual expense325.50, P&L expenses325.50, P/L-325.50.
Saved availability screen shows HAS_ACTIVE_CYCLE and sowing2026-10-21.

66a passed normal login, completed backend hydration, exact dynamic farmer/parcel,
direct profile200, soil_profiles200 and explicit zero-profile empty state. It
stopped before later unsaved-form, relaunch and contract assertions completed.
Do not infer those later checks passed.

## Failure diagnosis

The failure screenshot is actual product error UI, not just a scrolling mismatch:

- land_context: HTTP NETWORK (transport failure; exact cause not established).
- land_summary: HTTP403, with visible Access denied copy.

Read-only database checks confirmed:

- Dynamic Farmer.user_id matches192b6593-aee6-4b12-8ac6-7d3caeffabd6.
- The farmer has ACTIVE enrollment in project...000001.
- That User has **zero ProjectRole rows** for the project.

Backend source checks found the project-scoped context and summary guards call
HumanPersonaScope.has_project_access, which checks active ProjectRole entries,
not farmer enrollment. Thus enrollment alone does not authorize the project_id
Android supplies. This explains the summary403; it does not establish the cause
of the separate context transport failure.

The farmer preflight exercises context without project_id and omits land summary,
so its passing result did not validate this exact Android request shape.

No bearer/header mismatch is established by these observations. Do not weaken
authorization, grant admin scope, automatically drop project_id after denial,
or change the assertion to accept403 as successful guidance.

## Required decision before another run

Review the intended supported farmer land-read scope with the backend owner:
is this fixture intended to have legitimate project-role access, or should the
client explicitly select an authorized tenant-level context? Keep the existing
authorization boundary intact. Do not treat this report as permission to add a
role/enrollment, modify policy, or silently retry under a different scope.

The token-safe preflight should eventually cover the same project parameters and
summary route as Android, with the agreed expected result. The context transport
failure also needs a separate bounded diagnostic. Neither change was made here.

## Traceability

- Android revision:94ce0e61eb02ab0f39b3c627f8056635ed1209c5, dirty working tree.
- Backend revision:addedc56de7a22f745befb94f3a6007a8445d3d8, dirty working tree.
- Maestro:2.7.0.
- APK SHA256:912AC4B6204A2E58D99BEADF0597153C7B7776900E53D1FD3C41AC8A31998647.
- Dynamic farmer:e1ee0941-2bad-4a18-a239-2a4119608a06.
- Dynamic parcel:98c1a0fa-4f5f-4b8c-97ae-d84992db1c44, PIN560001, soil count0.
- Preserved crop cycle:75b5d1d2-2177-4280-81dd-3938ee92fa3e.
- Existing activity:fd74a106-66b3-42fa-9d16-ccd40c746338.
- Post-run SELECT-only check: preserved cycle still has1 activity totaling325.50.

Evidence root (outside Git):
`C:\Users\SANTOSH\AppData\Local\Temp\agrios-authenticated-reads\20261017-170147`

See run-metadata.txt and each flow's timestamped commands.json/manifest.json.
67a screenshots are under:
`67a-authenticated-active-cycle-reads\2026-10-17_170228\Preserved authenticated active-cycle and occupied parcel reads\takeScreenshot\`

66a failure evidence is under:
`66a-authenticated-profile-land-reads\2026-10-17_170451\Preserved authenticated profile land and soil reads\`
with `screenshots\step-039-scrollUntilVisible-land_context_HTTP_200.png` and
the matching screen-hierarchy JSON.

## Artifact safety review

Text scans found zero matching files for Dev OTP:, access_token, device_key,
Authorization, Bearer or a JWT-shaped eyJ... prefix. All three saved images were
visually inspected: two post-login crop/availability states and the land failure.
No authentication screenshot or visible credential was found. The sole saved
failure hierarchy belongs to Land and soil, not an OTP entry screen.

This is a bounded audit of this run's artifacts, not all historical device logs.
Raw tokens/OTPs/device keys/authentication responses were never retrieved or
printed. Originals are preserved outside the repository.

Maestro logs also contain Windows heartbeat/file-lock warnings. They did not
prevent67a passing and do not explain the visible backend403. Keep this runner
reliability issue separate from the authorization-scope finding.
