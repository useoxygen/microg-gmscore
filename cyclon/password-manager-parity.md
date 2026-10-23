# Password Manager implementation and parity

Part of CYC-654; official-client producer interoperability is CYC-653.
This is a development implementation. It is not a full-parity or deployment verdict.
The machine-readable inventory is [password-manager-parity.json](password-manager-parity.json).
No decompiled proprietary sources, APKs, account identifiers or credentials are checked in.

## Reference and comparison method

The reference is Google Play services **26.11.36 (260400-892401869)**, version 261136035,
from MindTheGapps Android 17 arm64 package `20261022_132445`. APK SHA-256:
`35f23040de6362a77103edb4b23825cfed7310ab6efe4d59310f817d79e6c1c2`.
The Google APK signature was verified. The comparison client is Chrome 145.0.7632.218,
version 763221832. This freezes a reference; it is not a claim that either version is the
latest Google release. JADX 1.5.6 reported 262 errors across the whole Google APK.
Required synthetic/anonymous classes were extracted separately with inlining disabled.
Critical interfaces and protobuf descriptors were examined directly, rather than treating
successful decompilation as executed compatibility proof.

The password service descriptor (`iamh`) exposes **29 RPCs**, all individually inventoried.
First-party Binder `bevw` has eight transactions, 2 through 9; callbacks are `bevx`, `bepp`
and `bevy`. `beyb`/`bexa` establish the Chrome wrapper; `bexr` establishes blocked/issue
filters and creation bounds (upper bound first). `bfgu`, `beqg`, `beql`, `beqn`, `iaki` and
`iakp` establish encryption and password resource layouts. `iaiy`, `iaky`, `iait`, `iaiu`
and `iaje` establish create/update/batch/delete positions. `bfgj`/`bfef` establish device
registration and client-token provenance. These class names are reference pointers only;
the implementation is independently written, with cryptographic golden vectors and format
information also taken from Chromium's public sync protocol and Nigori tests.
`besa`, `berh`, `bfdz`, `iakx`, `iakc`, `iaiz` and `iakz` establish priority-preference
identity, plaintext specifics and RPC positions. Preference pagination uses field 4;
password pagination uses field 3. Both use separate durable mutation journals.

## Implemented behavior

- Bounded, lossless protobuf parsing and manual unary gRPC framing; fixed HTTPS Google host,
  token-bearing redirects and automatic connection retries disabled, gzip bounded, remote
  error details excluded from exceptions/logging.
- Password metadata, pagination, get, create, batch update and delete. Update uses each
  resource ID; Chrome local data is preserved in the same commit as the outgoing change.
- Priority-preference list/create/update with opaque values, unknown-field preservation,
  pagination, durable launch/reconciliation fences, offline coalescing and version conflicts.
  Settings do not require Nigori keys. Chrome transactions 5/6 use the settings callback's
  presence flag and nullable value. Background work attempts both independent sync domains.
  Native preference list/cache reopening is accepted; preference writes and Chrome's Binder
  settings callbacks still need device/server acceptance.
- Nigori PBKDF2/scrypt, default keystore wrapping, trusted-vault shared-key derivation,
  custom derived keys/passphrase unlock, and reading older entries after keybag rotation.
  Both legacy ciphertext-MAC and IV+ciphertext-MAC formats are readable. Legacy compatibility
  does not authenticate the IV; the tests deliberately distinguish their tamper envelopes.
- Google identity is bound to AccountManager's stable Google user ID; OAuth is requested
  natively, never via an exported master token. Sync is explicitly enabled by the owner.
  Hourly WorkManager refresh, network constraints, exponential retry for transient errors,
  cross-process account locking, and database-backed enable state are implemented.
- The entire cache, keys, Chrome metadata, operation journal and provider tickets are AEAD
  encrypted under account-bound Android Keystore keys. SQLite lives in no-backup storage.
  A missing key fails closed. Account removal guards subsequent reads/commits and schedules
  removal of account cache and aliases. The null-account Chrome vault is separate/local only.
- Every password mutation has a durable ID and launch fence. Unknown outcomes are reconciled
  against a complete authenticated snapshot; they are never automatically replayed. Queued
  offline edits coalesce. Incomplete or looping pagination never implies deletion. Updates
  check the version before sending and retain Google's version token. Google's delete schema
  has no version precondition; a race after the snapshot is therefore still possible.
- Chrome first-party list/upsert/delete/custom-key calls use the correct callbacks and `value`
  DataHolder column, exact account, UID binding, and certificate-verified credential permission.
  Affiliation/UI-enrichment features are not advertised as complete.
- Native management UI: local/device storage, account choice and opt-in sync, manual save,
  password edit, delete and custom-passphrase unlock. Owner verification when a screen lock is set,
  foreground-only grants, secure window and disabled autofill for the manager itself.
- Android Credential Manager get/create and legacy Autofill get/save use the same vault.
  Exact web origin or certificate-bound Android facet only; browser-delegated origins are
  allowed only for verified Chrome packages. Password release requires a foreground access
  grant and, when a screen lock is configured, successful device-credential verification.
  Native web fills require agreement between the signon realm and origin URL; a realm-only
  IV edit in the legacy Nigori format cannot redirect the credential. Origin-less or inconsistent
  web entries remain manageable but are not automatically offered for native fill.
  Mutable PendingIntent extras cannot change the encrypted one-use selection. Autofill also
  binds the credential to the original field IDs and revalidates the requesting app.

## Expanded sync comparison

The 29 Passwords RPCs are one service, not the complete Password Manager surface.
The same pinned Google build also uses `google.internal.chrome.sync.entities.v1.SyncEntities`
at `chromesyncentities-pa.googleapis.com` with the `chromesync` OAuth scope. Device
registration is established by `bfgj`, `bfef`, `bfel`, `iaid`, `iaie`, `iaij`, `iaiq` and
`iair`; `jcpp` pins the distinct host/scope, and `jcqd`/`cwzd` establish the seven-day
heartbeat. `SyncIntentOperation`, `ipbb` and `ipba` establish sender, cache-GUID and type-ID
invalidation routing. These are format/behavior observations, not copied source.

The next implementation adds account-encrypted device registration, a durable 16-byte
creation nonce reused after a lost response, acknowledged client-token/cache-GUID storage,
heartbeat/token updates, and registered tokens on password and preference mutations.
State schema v3 reads v1/v2 while fencing older writers. An internal, non-exported push
receiver accepts the expected sender and bounded type payload, routes only by a matching
server cache GUID and schedules enabled accounts. Messaging opt-out is respected; existing
GCM account registration tokens/settings are not replaced. Follow-up sync work is appended
instead of being dropped while a prior sync is running. Native registration/heartbeat,
real push delivery and the new scheduler regression are pending device execution.

The broader comparison remains open: SyncEntities generic entity contracts, affiliation,
legacy credentials, leak checking, sharing and synced passkey consumers require their own
behavior and interoperability evidence. Adding a transport method alone does not complete
one of those flows.

## Remaining parity and acceptance

On October 22, 2026 (PT), source **e0336ea5** was built, signed with the phone's existing
dedicated microG identity and installed on the designated Pixel 7a without clearing app data.
Six isolated Android tests passed after fixing a real SQLite initialization failure exposed
by the first device run. Two explicitly selected native account checks also passed:
metadata/list/decryption/cache reopen (3.015 seconds), and synthetic password update/readback/
original-payload restoration (6.879 seconds). The latter persisted its restore input encrypted
before launch, cleared it only after verified restoration, and compared all unrelated records.
These checks used native account authentication; no account master token or password was
exported to the host. They accept default-keystore reads and batch updates only. A separate
repository instance is covered; process death, reboot and background scheduling are not yet
accepted. The original installed APK was preserved for restoration after private testing.

Source **d42828d2** subsequently passed all seven isolated Android tests and all three
opt-in account cases (16.752 seconds for the latter), including delete, confirmed remote
absence, re-create and exact fixture restoration. It also corrected apostrophe client-tag
identity to match Google's Android URI encoding and [Chromium's EscapePath character map](https://chromium.googlesource.com/chromium/src/+/refs/tags/143.0.7499.44/base/strings/escape.cc).
Both this identity bug and an oversized-state replacement bug have failing-before/passing-after
host regressions. State serialization is now bounded and streams rather than copying every
prefix. The preference extension writes state schema v2, reads existing v1 state, and makes
old v1 writers refuse the new journal instead of silently dropping unknown pending work.
The human UI check reached the manager's screen-lock-required state; opening the activity
does not establish owner authentication or listing/fill acceptance. Chrome's Settings entry
opens the manager, but that is not an official Chrome producer round trip.

Source **fe124f61** passed 33 password-sync host tests, 34 Core host tests, all seven
isolated Android tests and all four opt-in native account cases (17.674 seconds for the
latter). The added live case accepts read-only priority-preference retrieval and encrypted
cache reopening; it does not establish a nonempty preference result or preference writes.
Password CRUD again restored the exact approved fixture payload and checked unrelated
records. Native fill now also rejects disagreement between the stored signon realm and
origin URL, with a failing-before/passing-after legacy-IV modification regression.

After testing, the original Services APK **0.3.17.252432-114 (cf93b15)** was restored and
its active version read back without clearing app data. The temporary instrumentation APK
was removed, this thread released the Pixel session, and the signing VM was stopped.
The private development build is no longer active on the phone. That tested build required
a PIN, pattern or password in the owner UI; the native sync checks passed without one.
Owner UI acceptance remains open.

The subsequent optional-screen-lock change allows full UI/provider use without configuring
a screen lock. The manager still requires an explicit Open passwords tap and explains the
access consequence. Provider access follows an OS picker selection; no auto-selection is
advertised. When a secure lock is configured, a successful credential challenge is required;
cancellation, a missing prompt, launch failure or a changed lock policy never grant no-lock
access. Access is checked against the live lock state and resumed activity, cleared on stop,
and never persisted. Touches obscured by overlays are rejected. These are product policies,
not claims of Google parity or authentication-bound encryption. Android's
[KeyguardManager contract](https://developer.android.com/reference/android/app/KeyguardManager)
distinguishes configured credentials, current user lock and visible keyguard; the implementation
checks all three. Overlay filtering follows Android's [tapjacking guidance](https://developer.android.com/privacy-and-security/risks/tapjacking).

Seven new policy regressions passed within **41 Core unit tests**, and the debug and
instrumentation APKs compiled. At that stage, the follow-up had not been installed or
device-accepted. Later acceptance is recorded below; configured-lock/provider and work-profile
behavior remain unaccepted.

The subsequent secondary-process run on source **8ba21975** exposed a real scheduling
failure: `PasswordRepository.setEnabled` called an uninitialized WorkManager in `:ui`.
Earlier main-process probes did not cover the manager's process. Source **06e357cd** adds
on-demand [WorkManager configuration](https://developer.android.com/topic/libraries/architecture/workmanager/advanced/custom-configuration)
in the Application, selects the main process for scheduling and uses the remote enqueue/
cancel API with bounded acknowledgements. The existing WorkManager version remains 2.7.0;
the exact multiprocess artifact's license metadata and packaged notice were verified.
Seven isolated Android cases and three opt-in read-only account cases passed in `:ui`,
including completion of a freshly scheduled `PasswordSyncWorker`. This accepts one scheduled
worker execution, not hourly refresh, reboot, push or long-term background behavior.

The first manager harness used an Espresso input API removed on this Android 17 build.
The test-only stack is now pinned to Espresso 3.7.0 and AndroidX Test 1.7.0/1.3.0, while
production Compose and runtime versions stay unchanged. These instrumentation fixtures are
for API 28+ devices. Manager actions inject motion events through Android's input dispatcher;
they do not invoke Compose click callbacks or disable the secure window.

On source **63697035**, all seven isolated checks and three read-only account/worker checks
passed again. The manager's account case also passed through injected taps: open without
a screen lock, enable account sync, display the approved invalid-domain fixture's realm and
username, no secret input fields, and `FLAG_SECURE` retained. Its background/reopen case
timed out. After owner Google Cloud reauthentication, matching-signer product **5d6c8e55**
passed the seven isolated cases, three read-only account/worker cases and manager listing
again. A real Settings/task-switch reopen test also timed out on that product.

Focused fixture **d62e23d3** captured only input/lock-state metadata: the manager was
resumed, focused and unlocked, but no touch reached its window callback in the failing step.
Fixture **db090afa** waits for stable input focus before injecting motion events. Against the
**same 5d6c8e55 product APK**, its lifecycle case passed (one test, **6.801 seconds**): explicit
Open passwords, stop behind Settings, return to a cleared grant, open again, recreate to a
cleared grant and open again. No product access or overlay check changed for that pass.
No-lock manager listing and this lifecycle path are device-accepted; provider/configured-lock
and wider background/recovery acceptance remain open.

The tested product APK SHA-256 is
`8c32b8a69b35abdb0d7f1d4a9e62eb73e98e354b4fd6f1b879e8769ecd3372c9`;
the passing lifecycle fixture APK SHA-256 is
`94ba45d816c54f9c15981d939d279c22422d74a0ac2eb71e355d2fffc035f669`.
Each completed run restored the original Services build without clearing data and removed
its temporary test APK. Final live readback also matched the original APK bytes, SHA-256
`f516ddc5d66e2a8b48cfe754cdd2511f93e5594b56ba17ec35c7777f302b7f17`.
No password or preference cloud writes ran in this review round.

| Surface | Gap / required proof |
| --- | --- |
| Device registration and push | CreateDeviceInfo/UpdateDeviceInfo, encrypted stable nonce/client token, heartbeat and cache-GUID invalidation routing are implemented. Host tests cover loss/reopen/token propagation and isolation. Native/server, real push delivery, token invalidation and reconnect acceptance remain open. |
| Settings | Durable priority-preference synchronization and Binder transactions 5/6 are implemented and host-tested. Native list/cache reopening passed; preference writes, official-client callbacks, management controls and provider behavior still need verification. |
| Affiliations | Verified Digital Asset Links, branding and grouping are not implemented; no eTLD or package-name shortcut is used to authorize fills. |
| Zeroparty/legacy APIs | Service 80, Smart Lock and older credential-saving contracts still need comparison and integration. |
| Encryption recovery | Synthetic host cases cover keystore/trusted/custom derivation and keybag rotation. Real account recovery, stale-key refresh, registration, enable/rotate/clear RPCs and cross-device recovery are not accepted. |
| Conflict recovery UI | Conflicts and uncertain changes are retained. Owner selection of local/remote resolution, retry/drop, and vault-reset recovery still need UI and tests. |
| Password management | Search, note/name edits, moving between stores, import/export, blocked-site settings and checkup/breach service parity remain. |
| Sharing/passkeys/Wi-Fi | Related password-service RPCs are individually missing in the JSON inventory. Existing FIDO provider behavior is not evidence of Google-synced passkey parity. |
| Core agent fill | No secret-returning agent API is added. Core authority, private-field handling and a secret-free native fill path remain required. |
| Phone | Signed private installation, isolated cases and historical native synthetic CRUD/restoration passed. Secondary-process reads, a scheduled worker, no-lock account listing and manager background/reopen/recreation through injected taps passed. Provider input, configured-lock verification, process death/reboot, real account removal/revocation and hourly/background-lifetime acceptance remain. |
| Official interoperability | Read a credential produced by official Chrome, then verify native edits/deletion and a locally produced credential with official Chrome. Self-written/self-read is insufficient. |

## Reproduce the checks

With Android SDK configured:

```sh
./gradlew :password-sync:test :play-services-core:testMapboxDefaultDebugUnitTest
./gradlew :play-services-core:assembleMapboxDefaultDebug :play-services-core:assembleMapboxDefaultDebugAndroidTest
python3 cyclon/notices/test_generate.py
```

`PasswordVaultAndroidTest` is an inert synthetic fixture: unique local identities only, no
Google account selection and no RPCs. It checks Android Keystore/SQLite behavior, account
AAD, missing-key denial, revocation guards, settings visibility and browser-origin denial.
Compile is not execution. Run only on the designated development phone, after checking the
installed signer and preserving its current APK/data. Never uninstall/clear GmsCore data or
replace a phone reserved by another active task. Sign test artifacts with the existing
dedicated microG role; do not substitute a platform/releasekey or publish a development APK.

`PasswordNativeSyncAndroidTest` is separately opt-in through instrumentation arguments. It is
inert without an explicit account selector; its mutation case additionally requires the exact
synthetic-only acknowledgement. It refuses an existing mutation journal and targets only the
reserved invalid-domain fixture. Do not run it on an owner/customer account or treat its
self-produced update as official Chrome interoperability proof.
