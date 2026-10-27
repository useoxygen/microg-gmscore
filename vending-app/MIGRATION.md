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
  Failure sends a nonzero code with the nested error Bundle; it never claims a hold.
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
