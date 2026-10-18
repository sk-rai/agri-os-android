# Gate 1 read diagnostics

The project/PIN query, AuthInterceptor, 15-second connect/read timeouts,
and retryOnConnectionFailure(false) remain unchanged. No backend changes or correlation
headers were added. There is therefore no new ID that can be matched to backend logs.

## Approved idle-pool adjustment

Following the diagnostic rerun, only AuthenticatedReadsRepository now uses a dedicated
ConnectionPool with five maximum idle connections and two-second idle retention.
Other Android clients (including authentication and sync) are not changed. Active
requests are not cut off after two seconds; this is idle retention, not a request timeout.
There is no automatic retry, pool-wide eviction before calls, scope fallback or new header.

Evidence motivating the change: the refined 66a run observed IO -> EOF on a reused
connection, no HTTP response, after approximately 20.4 seconds idle since the soil
read. Summary then succeeded on a fresh connection. Installed Uvicorn defaults to
five-second keep-alive and running command lines showed no explicit override; that
does not prove the live runtime value or who closed the socket. OkHttp's default
idle retention is five minutes. Shorter retention is a targeted mitigation to verify,
not a claim that 66a now passes.

Host policy tests inspect the pinned OkHttp 4.12 configuration (no network) to verify
two-second retention, unchanged five-idle capacity, 15-second timeouts, retries off
and dedicated pool isolation. They do not prove real device/server eviction timing.
The last phase WRITING remains callback evidence only: OkHttp can encounter EOF
reading headers before its responseHeadersStart callback is emitted.

Debug builds emit one `AuthenticatedRead` JSON record per completed repository read.
The model accepts only allowlisted route/schema literals, enums, booleans, status codes
and monotonic elapsed milliseconds. It cannot retain exceptions, request objects,
headers, URLs, operational bodies or authentication material. Schema validity means
the expected schema_version matched, not that every business field was validated.
Existing business/UI assertions remain authoritative and unchanged.

### OTHER_IO refinement

Records now include `exception_codes` (a bounded outer-to-inner cause sequence of
fixed enum constants), `transport_phase` and nullable `connection_reused`. No runtime
class names are emitted, including names of unknown/custom exception subclasses.
Codes: EOF, SOCKET_TIMEOUT, CONNECT, SOCKET, DNS, PROTOCOL, TLS, INTERRUPTED_IO,
CANCELLED, INTERRUPTED, IO, UNKNOWN. The cause sequence is cycle-safe and limited to
12 entries. Successful HTTP records omit failure codes (empty array).

Phases: UNKNOWN, DNS, CONNECTING, TLS, CONNECTED, WRITING, READING. OkHttp event
callbacks update only scalar/enum per-call state. Addresses, domain names, connection
objects and request objects are never retained in that state or emitted.

`connection_reused=true` means connectionAcquired occurred without connectStart for
this call; false means acquisition followed a connect attempt; null means no
connection acquisition was observed. It is connection-lifecycle evidence, not proof
that a pooled connection was stale. HTTP response status remains null until execute
returns a Response; callback progress alone does not invent a status or success.

Illustrative synthetic record (not a new Android execution result):

```json
{"route":"land_context","http_status":null,"schema":null,"elapsed_ms":19,"failure_category":"OTHER_IO","response_received":false,"schema_valid":null,"exception_codes":["IO","EOF"],"transport_phase":"READING","connection_reused":true}
```

The exporter allowlists all new enum values and rejects malformed code arrays,
arbitrary phase strings and nonboolean reuse values. The offline synthetic test
exercises these rejection paths. No Maestro flow/assertion was modified.

Refinement host verification: debug build and 18 unit tests passed (zero failures
or errors), including existing host privacy checks. Synthetic exporter privacy
test passed. No device-side tests or Maestro rerun were performed for this change.

Classification uses a bounded cause chain, never messages or stack traces:

| Evidence | Category |
| --- | --- |
| SocketTimeoutException during TCP connect | CONNECT_TIMEOUT |
| SocketTimeoutException during response headers/body | READ_TIMEOUT |
| Android ErrnoException ECONNREFUSED / ECONNRESET | CONNECTION_REFUSED / CONNECTION_RESET |
| UnknownHostException / UnresolvedAddressException | DNS |
| ProtocolException / SSLException | PROTOCOL |
| CancellationException / InterruptedException / ClosedByInterruptException | CANCELLED |
| Other IOException or timeout with unknown/TLS phase | OTHER_IO |
| Otherwise | UNEXPECTED |

Generic ConnectException/SocketException messages are deliberately not interpreted.
Refused/reset can remain OTHER_IO when Android supplies no errno cause. Cancellation
continues to propagate. HTTP status is retained once a response is obtained; HTTP
records have no transport-failure category, including unreadable bodies. A missing or
incorrect expected schema produces schema_valid=false, never a substituted success.

## Host verification (2026-10-18)

- testDebugUnitTest: 15 tests, zero failures/errors (including existing privacy tests).
- assembleDebug: passed, normal non-isolated debug variant.
- Offline exporter synthetic rejection test: passed.
- Unit XML scan: zero synthetic-secret/payload/JWT-pattern matches.
- git diff --check: passed (pre-existing line-ending warnings only).
- No device instrumentation, emulator, Maestro, login, fixtures or backend calls run.

Device accessibility/hierarchy tests are unchanged; these host checks do not claim a
fresh device-side redaction result.

## Future run summary (approval required before device use)

Run only 66a, preserving its HTTP 200 assertions. Collect ONLY the diagnostic tag into
an outside-checkout evidence folder after the run (including failure). Do not collect
unfiltered authentication Logcat. Export the following safe summary independently of
the flow exit status:

```powershell
& $adb -s $device logcat -d -s 'AuthenticatedRead:I' '*:S' > (Join-Path $evidence 'read-diagnostics.log')
if ($LASTEXITCODE -ne 0) { throw 'Diagnostic collection failed' }
& .\maestro\export-authenticated-read-diagnostics.ps1 `
  -LogPath (Join-Path $evidence 'read-diagnostics.log') `
  -OutputPath (Join-Path $evidence 'sanitized-read-summary.json')
```

The exporter reconstructs an allowlisted record; it never copies arbitrary JSON
properties or raw log lines. Capture the Maestro exit code before diagnostic collection.
Use a fresh process/log boundary for the approved run so older diagnostic entries are
not mistaken for current results. Stop on a failing context; do not retry scope-free.
