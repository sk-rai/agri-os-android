# Home self-profile cache display — design for review

Home automatic restore remains disabled. This design records the explicit
action and its scoped verification; it does not authorize live requests or
deployment.

## Smallest useful Home slice

Offer an explicit, user-initiated **View server profile** action for the
authenticated self-profile context only. It is not an enrollment, restore,
repair, or sync action. Do not run it on startup, account change, recomposition,
or retry without a new tap. Render the resulting read-only projection in a
separate panel. Do not use it to replace the owned local farmer card, drive
enrollment or parcel navigation, choose a crop cycle, or enable local edits.
Assisted/mobile lookup remains out of scope.

The first slice does not discover profiles offline. The cache can contain
several farmer IDs for one owner; never pick the first or infer completeness
from a retained row. Require a successful current request identifying the
exact farmer before presenting that farmer's cached projection. A 404 or
failed refresh shows no new projection and does not delete retained data.
Label the result as cached server data; retained children may not have
appeared in the response. Do not represent it as a complete or fresh listing.

## One lease from tap to display

1. Capture one lease matching the Home session shown at the tap. Pass it to
   the refresh boundary; adapt the current internally captured, status-only
   API rather than recapturing inside it. Only an applied or unchanged result
   may return a process-local receipt carrying the validated farmer ID and
   that exact origin. No identity, credentials, or receipt in saved UI state
   or logs.
2. Read the exact farmer through the guarded cache store with the same lease.
   Check the lease and request identity at publication. After a session or
   epoch change, clear the panel and ignore late results, including failure
   feedback. Cancel an old job on context change or panel dismissal, but do
   not rely on cancellation for isolation. Keep at most one request per panel
   and use a local request generation to disqualify callbacks from older taps.
   On every new tap, immediately invalidate and hide the previously published
   projection and increment the request generation. Show no projection until
   that tap's lease-bound refresh returns an applied or unchanged receipt and
   its exact-farmer cache read passes the publication gate. A 404, failure,
   cancellation, dismissal, or disqualified callback cannot publish or restore
   a projection for that request. A callback from an older generation must
   not clear a newer generation's published result. None of these outcomes
   deletes retained cache data.
3. Gate the render itself by the observed active tenant, user, and credential
   epoch, in addition to the result's origin and request generation. Suppress
   the panel while session signals disagree or are unavailable. Implementation
   review must check both auth-first and result-first delivery, including
   identical same-owner reauthentication. This is a render-time guarantee
   once the change is observed, not a claim about pixels before session
   invalidation reaches the UI. Do not retain projection state in a global
   cache or across navigation/process recreation.

## Evidence and remaining gates

Accepted host evidence already covers the additive v10→v11 migration,
atomic owner-scoped cache storage, and lease-bound refresh using fake
transport. It does not prove Home displays or clears the right profile.

1. Independently review this design. Then implement and source-review the
   explicit action, same-lease receipt, publication gate, and isolated panel.
2. Verify with focused host tests and a separately approved isolated Compose
   test: positive display, both auth/result delivery orders, cross-tenant and
   same-tenant replacement, new epoch for the same owner, late status feedback,
   cancellation and older request completion. Also verify that no startup
   request occurs and that the existing local card and actions are unchanged.
   Use synthetic data and fake transport; do not launch the regular app.
3. Review the retained evidence and decide whether to publish the explicit
   action. Any automatic restore, live API validation, release or rollout
   requires a separate decision. None is part of this slice.

`PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.

## Accepted evidence

Independent review accepted the focused host run (four state tests and six
refresh tests) and the isolated Compose run (four cases). All passed with
zero failures, errors, or skips. The
[isolation notes](../app/src/homeCardIsolation/README.md#server-profile-panel--accepted-isolated-run)
record the sanitized result hash and limits. The Compose run used fake
requests; full Home startup, live API behavior, Room observer timing, and
deployment remain outside that evidence. Shipping the source does not
authorize automatic restore or a live rollout.
