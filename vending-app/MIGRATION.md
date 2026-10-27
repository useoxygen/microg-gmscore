# Android Switch app restoration (CYC-700)

FakeStore implements `com.android.vending.setup.IPlaySetupServiceV2.BIND` and
hands app acquisition to the image's Aurora bridge. Migration stays anonymous:
the account name in Switch requests never goes to Aurora.

## Contract evidence

Evidence is Android Switch 1140078, `src/sources/defpackage/` in the retained
`~/research/cyc700-switch/android-switch-1140078` decompile:

- `chc.java:11-185` provides transaction numbers and Parcelable presence layouts;
  `eja.java:85-160` provides the method names and unbinds after each method.
- Pause (13) sends callback **0** after Aurora confirms the hold (`lqf.java:24-29`).
  Failure returns the nested synchronous error Bundle without a callback claiming a hold.
- Modern final hold (9) accepts 1 complete and 2/3 progress (`lqg.java:24-32`),
  but legacy cable/cloud `drj.java:18-22`, `chf.java:18-22` and `cyn.java:10-12`
  complete on **every** callback. Therefore tx9 sends its single code1 only after
  all selected apps have terminal receipts. A failed receipt is completion of an
  attempt, not installation success (`all_installed=false`).
- Register/unregister (15/16) and state (19) have no consumer-defined payload
  schema in this Switch build. Their `session_id`, `cyclon_app_results`,
  `complete`, `all_installed`, `setup_options` and `cyclon_hold_state` fields are
  **Cyclon extensions**. Registered receivers get code2 progress and code1 terminal
  state. Switch rendering this progress has not been demonstrated.
- Catalog keys `title`, `package_name`, `icon_url`, `developer_name`,
  `install_size`, `availability` and `is_paid` come from `ena.java:43-98`;
  only availability1 is established as available (`eew.java:275-279`).
  Unavailable documents remain in `unrestorable_document_groups` and the local
  session receipt. Recoverable catalog failure also returns an operation-level
  `error` Bundle (`reason`, `exception_type`; `lqm.java:20-24`, `lqj.java:62-64`).
- Setup options (12) are from `ejj.java:101-168`. Matched apps have document type3
  (`hmg.java:550,588-590`); absent/type0 supports legacy clients. Other document
  types return an explicit error. Restore source is provenance, not an account.
  Aurora always installs full APKs, so pre-archiving is never performed. These
  three options are persisted and forwarded; Google cloud/data restoration,
  priority, network policy and pre-setup install ordering are not implemented.

## Sessions and recovery

Pause starts a new transfer, including after a cancelled catalog-only transfer.
Catalog matching reuses that session; matching after a prior selection starts a
new session as a fallback for clients that omit pause. Repeated catalog chunks
and setupDocuments chunks share the active session (`ejj.java:32-46`). Selection
is additive only inside that session. Receipts, exclusions and retries use the
session ID. Retry only authorizes that session's selected failures.

Switch binds `PlaySetupService` again for every chunk and waits 5 s for the
connection. The install job holds the queue lock during Aurora calls, so the
main thread must never take that lock: opening the queue is lock-free, and the
Aurora bridge receives its connection on its own thread. On 10-27 a main-thread
lock wait dropped every chunk after the first (110 of 181 apps).

When Play answers 429, Aurora refuses before enqueuing. The app stays pending,
not failed, and the job waits 30 s before asking Play again.

Before enqueue IPC, FakeStore persists an uncertain-attempt marker. A later
Aurora status reconciles a queued/running/finished request without reenqueuing.
If its reply was lost and Aurora no longer has a receipt, another install requires
an explicit owner retry. Previously verified installs are never automatically
reinstalled after removal. Terminal receipts are frozen so a newer transfer's
Aurora row cannot rewrite an earlier transfer's history. Legacy requests without
session identities are retained; ambiguous pending requests need owner review.

A persisted JobScheduler job dispatches and polls Aurora's per-app states; callbacks
live in a process-wide registry because Switch unbinds after every transaction.
Service rebinding preserves callbacks. Process death loses live Binder receivers;
the job/receipts survive, and the client must re-register/query state after restart.

Aurora tx4/5 pause/resume uses a renewable 15-minute lease. FakeStore renews only
an explicitly requested hold while selected installs remain active. Resume stops
renewal; completion releases the matching session. Owner update preferences stay
unchanged. Already queued automatic updates continue: Aurora cannot safely cancel
them without risking an owner/migration download. A pre-selection hold expires if
no selection or further activity arrives. WorkManager controls when a released
update retry runs.

Catalog lookup first calls Aurora tx6 to prepare its dedicated anonymous session.
Mirror matches still work if the dispenser is unavailable. Anonymous failure is
recoverable; the owner can retry matching or open the app in App Store. No owner
account is substituted automatically.

Results show session-specific names/artwork, readable reasons and individual
retry/open actions. One summary notification per completed session opens
`ai.cyclon.migration.APP_RESULTS` with `session_id`. Notification delivery needs
POST_NOTIFICATIONS and an enabled channel; denied delivery is not recorded as
posted. A denied notification can be posted at a later observation after permission
is granted. Offline mirror records lacking artwork retain a default icon.

## Optional P2P remains unadvertised

`dvb.java:72-83`, `dur.java:34-41`, `duy.java:54-59` and `dvg.java:35-38`
establish a byte[] `P2P_DEVICE_TOKEN` and APK writable URI requests. The token is
forwarded to the source's real Play tx2 (real GMS `bdao.java:98-141`), but neither
retained decompile contains token generation/validation semantics. A fabricated
token would invent compatibility. No Play P2P endpoint or APK content provider is
registered. This evidence gap remains under CYC-712/CYC-700.

## Parent device acceptance (not run by this workstream)

1. On the claimed dev Pixel, install both PR builds together. Start a cable
   transfer with automatic updates enabled, no anonymous Aurora session, and an
   owner account as the normal App Store default. Match a mirror and a non-mirror
   free app. Verify anonymous preparation, names/icons and unchanged owner default.
2. Verify pause callback0 and no **new** automatic update enqueues until resume or
   transfer completion. Verify updates eventually resume without changing the
   owner's preference; existing queued updates may continue.
3. Select multiple apps in chunks. Observe state19 and receiver15 per-app progress,
   unregister16, and verify finalhold9 does not callback before all attempts settle.
   Verify the actual cable Switch screen's behavior (legacy vs modern consumer).
4. Exercise paid/not-found, dispenser/network outage then re-match, full disk,
   failed/cancelled installs, and a newer already installed version. Check readable
   failure receipts and that completion never claims all apps installed on failure.
5. Kill/restart FakeStore and Aurora during enqueue/download, rebind/register, and
   verify durable dispatch plus no duplicate install. For a lost enqueue reply,
   remove an installed app and clear its Aurora receipt before reconciliation:
   verify another install requires owner Retry.
6. Finish one transfer, remove a successfully installed app, then start another
   (also cancel/restart before selection). Verify distinct session results, old
   receipt stability, failure-only Retry scoped to the chosen session, and no
   reinstall of the removed successful app.
7. With notification permission/channel enabled, finish installs and verify one
   summary notification opens the correct session's APP_RESULTS. Reopen/restart
   without another alert; check per-app Retry and Open. Also deny permission and
   verify no crash or false delivered receipt.

## Review round 1

A selected target stays pending while an older Aurora row is queued/downloading/
installing. After that attempt settles, FakeStore reconciles the target again;
removal or a lost receipt never silently authorizes another install. The same
reconciliation repairs old-draft sessions that already dropped the pending target.
Actual installed version, row version and the selected minimum stay separate.
Aurora rejects an offer below `minimum_version_code` rather than reinstalling an
old offer indefinitely.

`awaiting_install` and disappeared acknowledged receipts become terminal
`owner_action_required` receipts, with `all_installed=false`. This releases a
closed final hold and allows the summary notification without claiming installation
success. An owner Retry explicitly approves another request; negotiated
`owner_approved_retry` lets Aurora resume retained install files. Legacy Aurora
requires its App Store UI for a parked installer. Old-draft acknowledgements are
inferred from persisted status and a package already absent from pending.

Private bridge tx7 (no fields) returns `bridge_version` and capability booleans
`install_options`, `automatic_update_hold`, `anonymous_prepare` and
`owner_approved_retry`. An unimplemented tx7 means original bridge v1. FakeStore
then keeps tx2 string-only and sends no unsupported tx4/5/6. A modern Switch pause
requiring an unavailable hold returns the nested synchronous error checked by
`lql.java:103-105` / `lqm.java:27-31`, with no unexpected nonzero callback. Some
async Switch callers only log that error, so catalog/setup also enforce the
persisted requirement and report `store_update_required` until Aurora supports it.

Selection has a separate persisted `selection_closed` bit. tx8/12 only add chunks;
tx9 explicitly closes them. No terminal receiver, update-hold release or summary
notification can occur between chunks. tx14 does not close selection: Switch's
SetupCompanion schedules resume on Activity destruction, including recreation
(`SetupCompanionActivity.java:299-310`; recovered `exp` worker). There is no final
chunk marker in tx12. For flows that omit tx9, the owner can explicitly choose
**Finish app selection** in the results screen. Settled chunks stop polling while
preserving their receipts and bounded hold lease; the next chunk or explicit close
schedules reconciliation. Inactivity is never presented as successful closure.

Additional parent device checks: start a v13 selection while Aurora downloads v12;
verify the v13 target survives and advances after v12 settles. Restart during an
install to reach AWAITING_INSTALL, and separately clear an acknowledged download
row: both must finish final hold with an owner-action receipt and require an
explicit Retry/Open App Store action. Temporarily use an older Aurora: legacy
string-only installs remain valid, but modern Switch's unsupported hold is shown
as an update-required error before apps are selected. Delay the final setup chunk:
no hold release/terminal callback/summary notification until tx9 or explicit owner
closure. Exercise a cable flow with final hold disabled and the owner closure UI.

## Review round 2: wizard return and crash recovery

An owner Retry is now consumed in the **same durable commit** that sets the
uncertain-attempt marker, before enqueue IPC. Its captured value is sent with that
one request's options. A lost/throwing reply followed by a missing receipt returns
to owner review after restart; it cannot reuse the prior approval to reinstall an
app the owner removed. A Retry waiting behind an older active download remains
unconsumed until an enqueue is actually attempted.

### Protected Transfer Phone completion contract

Switch commonly skips finalhold9 in cable and apps-only flows. Transfer Phone must
call this endpoint after its launched Switch wizard returns, for success **or**
cancel, including a crash reported as a cancelled Activity result. This is an
explicit lifecycle signal, not a timeout. This PR provides the FakeStore endpoint;
the companion call site is wired by the parent workstream.

- URI/authority: `content://com.android.vending.migration`.
- Transport: `ContentResolver.call(uri, method, null, extras)` on an IO thread.
- Method `getCurrentSession`: no extras (or an empty Bundle); reply `session_id`
  String or null. This does not create a session. Query it **when the wizard
  returns**, since Switch creates its session while the wizard is running.
- Method `transferEnded`: extras `outcome` String, required and equal to `success`
  or `cancelled`; `session_id` String, optional but recommended from the query.
  Omitting the ID closes the current session. A supplied stale ID is rejected and
  must not be retried by silently dropping the ID.
- Accepted reply: `accepted=true`, `session_id`, `selection_closed=true`,
  `already_closed` Boolean, and `transfer_outcome`. Duplicate returns are safe;
  the first recorded outcome wins. It is a closure acknowledgement and contains
  no `all_installed` or installation-success claim.
- No session: `accepted=false`, `error=no_active_session`; no session is created.
  A stale ID returns `accepted=false`, `error=stale_session`, current `session_id`.
  Invalid methods/arguments/outcomes throw `IllegalArgumentException`.
- Every method verifies the original Binder caller before clearing identity:
  `ai.cyclon.migration` must be installed as a system/updated-system app, its UID
  must equal the caller UID, and it must match Android's platform signature or
  use `Process.SYSTEM_UID`. Other callers get `SecurityException`. A package name
  in extras or a granted permission cannot authorize the call. No URI grants,
  query, insert, update or delete operations are supported.

The companion should declare this query if its package-visibility configuration
does not already cover the provider:

```xml
<queries>
    <provider android:authorities="com.android.vending.migration" />
</queries>
```

The return handler can use the following Kotlin on an IO dispatcher (imports
`android.net.Uri` and `android.os.Bundle`). If `session_id` is null, there is no
app session to close; do not fabricate one or infer installation success.

```kotlin
val uri = Uri.parse("content://com.android.vending.migration")
val resolver = context.contentResolver
val sessionId = resolver.call(uri, "getCurrentSession", null, null)
    ?.getString("session_id")
if (sessionId != null) {
    val reply = resolver.call(uri, "transferEnded", null, Bundle().apply {
        putString("session_id", sessionId)
        putString("outcome", if (wizardSucceeded) "success" else "cancelled")
    })
    // Check reply.accepted/error; installed-app results remain in APP_RESULTS.
}
```

Closing selection persists the outcome, schedules reconciliation and lets existing
per-app receipts decide completion. It does not cancel already selected installs
or mark them installed. Neither inactivity nor resume14 closes a selection.

While installs have settled but selection remains open, the job posts a
**Finish app selection** notification. It opens that session's APP_RESULTS and
lets the owner explicitly review/finish; clicking the notification alone does
not close anything. It has its own durable receipt and cannot consume the final
summary notification. A new chunk clears the prompt while installs run; terminal
completion replaces it in the same notification slot. Permission/channel delivery
requirements still apply.

Parent device checks: leave cable finalhold disabled, return from the wizard and
call the companion contract for success and cancel; verify closure and exactly one
final summary after actual install attempts settle. Withhold the companion call,
restart FakeStore after installs settle, and verify the non-terminal review prompt
opens the right session without a terminal callback until explicit closure. Call
from a non-companion/non-platform app and verify denial/no mutation. Finally lose
an accepted owner-Retry reply, remove the app and its Aurora receipt, restart, and
verify another enqueue requires a **new** Retry.
