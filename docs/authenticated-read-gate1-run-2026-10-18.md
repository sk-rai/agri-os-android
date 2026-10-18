# Gate 1 consolidated preserved-data smoke handoff — 2026-10-18

Status: passing evidence for the three approved preserved-data flows, ready for
review, not committed. This is not a same-build full-suite regression or approval
for Gate 2. No new test, backend request or device action was performed to write
this consolidation.

## Results and traceability

| Flow | Execution | Result | Evidence directory under Windows temp/agrios-authenticated-reads |
| --- | --- | --- | --- |
| 67a | 2026-10-17, original APK | exit0; active cycle, six stages, recommendations, costed activity, finance consistency, occupied parcel and narrow contracts | 20261017-170147/67a-authenticated-active-cycle-reads |
| 66a | 2026-10-18, short-idle APK | exit0; exact farmer/parcel hydration, empty soil, project-scoped context/summary, unsaved forms, relaunch and narrow contracts | 66a-short-idle-20261018-160022 |
| 68 | 2026-10-18, short-idle APK | exit0; assigned work/detail and unassigned valid empty collection | 68-persona-isolation-20261018-160725 |

Absolute evidence parent:
`C:\Users\SANTOSH\AppData\Local\Temp\agrios-authenticated-reads`

- Android revision: `94ce0e61eb02ab0f39b3c627f8056635ed1209c5`, dirty working tree.
- Backend recorded revision: `addedc56de7a22f745befb94f3a6007a8445d3d8`, with
  backend-owner corrections loaded from its dirty working tree. Revision alone
  does not identify those uncommitted backend changes.
- Device: explicitly selected emulator-5556, Pixel_6, Android 14/API34.
- Maestro: 2.7.0.
- Original 67a APK SHA256:
  `912AC4B6204A2E58D99BEADF0597153C7B7776900E53D1FD3C41AC8A31998647`.
- Successful 66a/68 APK SHA256:
  `06A00BBB534A2E41F7ED511D0ACE004947126ABAD8B6B4706EBB367A353500F8`.
- No claim that 67a was rerun on this final APK; the approved instruction was not
  to repeat it. Its read repository shares the scoped pool change only where used.

## 66a assertions and route evidence

Fixture: android-dynamic-test, project `0f7e0a6b-8472-5d6d-8a14-a9d000000001`.
Farmer `e1ee0941-2bad-4a18-a239-2a4119608a06`; parcel
`98c1a0fa-4f5f-4b8c-97ae-d84992db1c44`; PIN560001.

| Diagnostic route | HTTP | Elapsed ms | Schema / assertion |
| --- | --- | --- | --- |
| profile | 200 | 550 | Exact farmer/parcel identity checked by UI |
| soil_profiles | 200 | 62 | Count0 and explicit expected-empty copy |
| land_context | 200 | 268 | land_intelligence_context.v1; backend guidance visible |
| land_summary | 200 | 127 | land_intelligence_summary.v1; informational/nonblocking flags and card visible |

Context used a fresh connection after the interaction delay; summary reused a
connection successfully. No failure categories or exception codes were emitted
for these successful requests. Same identity hydrated after app relaunch.
Existing farmer profile, soil form/pH and parcel form rendered without saving.
Workflow catalog/preview and enrichment list/summary/latest contract checks passed;
these are contract-only, not additional screen coverage. Expected absent enrichment
means list200/count0, summary200/count0 and latest404, not an authorization failure.

Artifacts:
- `66a-short-idle-20261018-160022/sanitized-read-summary.json`
- `66a-short-idle-20261018-160022/read-diagnostics.log`
- `66a-short-idle-20261018-160022/2026-10-18_160026/Preserved authenticated profile land and soil reads/commands.json`
- same flow directory: `takeScreenshot/66a-backend-land-guidance.png`

## 68 assertions and route evidence

Separate fixture: android-persona-lifecycle-test, project
`0f7e0a6b-8472-5d6d-8a14-a9d000000201`. Assigned agent fixture ends1301;
unassigned agent fixture ends1701. Assisted farmer:
`0f7e0a6b-8472-5d6d-8a14-a9d000001402`.

| Persona / diagnostic route | HTTP | Elapsed ms | Evidence |
| --- | --- | --- | --- |
| Assigned / assigned_work | 200 | 646 | field_agent_worklist.v1; correct project and authorized farmer card |
| Assigned / assigned_farmer | 200 | 323 | Exact identity and farmer_launch_context.v1 validated by screen |
| Unassigned / assigned_work | 200 | 525 | field_agent_worklist.v1; expected empty collection; card/error absent |

The exported generic diagnostic does not currently list a schema for
assigned_farmer; its screen separately validates the expected schema and identity.
No unassigned detail, PATCH, administrative or conflict-ACK request was issued.
Farmer absence of assigned-work navigation was asserted in the passing farmer
flows, not by logging a farmer into an agent fixture.

Artifacts:
- `68-persona-isolation-20261018-160725/sanitized-read-summary.json`
- `68-persona-isolation-20261018-160725/read-diagnostics.log`
- `68-persona-isolation-20261018-160725/2026-10-18_160728/Authenticated assigned and unassigned read isolation/commands.json`
- same flow directory, `takeScreenshot/`:
  `68-assigned-farmer-worklist.png`, `68-authorized-assisted-farmer.png`,
  `68-unassigned-authorized-empty.png`.

## Failure history and narrow mitigation

Keep the original failed runs unchanged. Backend owner corrected the project-scoped
land-read check and separately verified authorized access and unrelated denial;
this Android task did not edit backend authorization or grant project roles.
Subsequent context failures remained separate transport failures, not403s.

Refined diagnostic evidence:
`66a-refined-diagnostics-20261018-150915/sanitized-read-summary.json` recorded
land_context OTHER_IO, IO -> EOF, last callback WRITING, connection_reused=true,
no response, 121ms. Approximately20.4s elapsed idle since the soil read.
Summary succeeded on a fresh connection. WRITING is a callback observation, not
proof that EOF occurred during a write; OkHttp may read before the next callback.

Installed Uvicorn defaults to5s keep-alive and process flags showed no override;
that is not proof of the live runtime value or which peer closed the socket.
OkHttp's default idle retention is5min. The approved mitigation limits idle
retention to2s in AuthenticatedReadsRepository only, retaining five maximum idle
connections. Connect/read remain15s and retryOnConnectionFailure(false) remains.
No automatic retries, project removal, tenant fallback or assertion suppression
were added. The subsequent66a pass supports this mitigation for the observed
failure, not a universal connection-reliability claim.

## Privacy and preconditions

- Each rerun checked the explicit device, health200, applicable fixture dry-runs
  and pending-unexpired-OTP counts before Android login; counts were zero.
- No simultaneous backend OTP preflight ran during Android login.
- Logins used normal in-memory development auto-fill with existing password
  masking/accessibility protections. No OTP/token/device key was supplied in YAML.
- 66a: seven text artifacts, zero credential-marker matches; one post-login
  screenshot visually reviewed, no visible authentication secret.
- 68: seven text artifacts, zero credential-marker matches; three post-login
  screenshots visually reviewed, no visible authentication secret.
- Scans covered dev-OTP literal, token/device-key fields, Authorization/Bearer
  and JWT-shaped markers. They do not prove absence of all possible unknown
  encodings or secrets in all historical device logs. No fresh runtime privacy
  instrumentation result is claimed: earlier isolated three-test pass is retained.
- Latest host verification before these successful runs: debug build passed,
  20 unit tests passed, exporter synthetic rejection passed, no runtime sentinel/
  JWT-pattern matches in test XML, diff whitespace check passed with CRLF warnings.

## Remaining gaps and stop boundary

- No trusted enrichment snapshots; no populated enrichment UI/provider execution.
- Controlled missing/expired session and recovery tests remain unexecuted.
- No full suite rerun on the final APK; 67a retains its earlier-build result.
- No claim of manual TalkBack or physical keyboard validation.
- No cycle creation, enrollment/reset, stage/action/activity/finance writes,
  assignment mutation, broad fixture reset or Gate2 work.
- Runner postflights from the original interrupted suite did not complete; do
  not relabel individual later reruns as a successful end-to-end runner execution.
- No commit/push. Review `authenticated-read-gate1-commit-review.md` before staging.
