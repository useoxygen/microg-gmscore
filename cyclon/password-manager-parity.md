# Password Manager implementation and parity

Part of CYC-654; official-client producer interoperability is CYC-653.
This is a development implementation. It is not a full-parity or deployment verdict.
The machine-readable inventory is [password-manager-parity.json](password-manager-parity.json).
No decompiled proprietary sources, APKs, account identifiers or credentials are checked in.

The expanded source-backed batch passed **604 host tests** at source `dece944d`:
400 password-sync, 13 FIDO Core and 191 Services Core cases, with zero failures, errors
or skips. Product and instrumentation APK builds passed in the same centralized run.
The companion Core source `75613fc4a` passed the full Pixel helper suite: 37 passed,
18 skipped because vendor/image inputs were absent, zero failures.

The implementation now connects domain create/enroll/rotate/reset, genuine platform
LSKF backup and PIN/password/pattern restore, existing PIN renewal, attested enclave
PIN creation/change with private Google account reauthentication, direct SDK bulk and
generate-only recovery, coupled consent and its protected owner flow. Native blob
creation/writes, durable missing-secret PRF migration, Chrome payment delegation,
authenticated hybrid account passkeys/PRF and source-faithful private-PSL matching are
also connected and host-tested. Review and regression coverage address reset ABA/stale authority, restore scope,
SDK result mapping, authenticated Shutdown and atomic hybrid output. Android callback/parcel
fixtures compiled; their device execution remains pending.

Full parity remains open. Five Passwords inventory RPCs have no verified producer or
request/response schema in the pinned artifacts; see [the contract audit](passwords-rpc-contract-gaps.md).
Google runtime notification experiment values are not mirrored. Provider/official-client,
family and cross-device interoperability remain separate acceptance checks.

All new phone work is deferred because another chat has priority on the designated
Pixel 7a. No Services/Core/controller replacement, image publication, device test or
live account/domain/provider probe ran in this batch. The new OS recovery grant and
package-specific system-API access require a matching product image; compiling an APK
does not install them. Historical acceptance below remains tied to its recorded sources.

Current compiled product APK SHA-256:
`b868660933448e4193b6939bb8e11cbb1c12bd5e562e63c4cc871936ca2f7c08`.
Current instrumentation APK SHA-256:
`ce4bf0e7c421f7becf5ced2dff5d71c8f8379d120e02ee175c55af500fedbc19`.
These artifacts were built on the host and were not installed. The older source
`36246827` result (294 password-sync and 108 Core tests) is retained as historical evidence
in the machine-readable inventory.

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
  Trusted-vault unlock now retries once with freshly retrieved shared keys after a stale
  local keybag, following `bfgu`'s bounded retrieval behavior. This is host-tested; real
  trusted-vault recovery and rotation acceptance is still pending.
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
  Delete accepts bare PasswordSpecificsData, while save accepts the Chrome wrapper. Acknowledged
  deletion or a complete authenticated snapshot retires orphaned Chrome-local metadata;
  incomplete snapshots, uncertain outcomes and queued offline writes retain it.
  Affiliation/UI-enrichment features are not advertised as complete.
- Native management UI: local/device storage, account choice and opt-in sync, manual save,
  password/name/default-note edit, metadata search, never-save sites, delete and custom-passphrase unlock. Owner verification when a screen lock is set,
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

The implementation adds account-encrypted device registration, a durable 16-byte
creation nonce reused after a lost response, acknowledged client-token/cache-GUID storage,
heartbeat/token updates, and registered tokens on password and preference mutations.
State schema v3 reads v1/v2 while fencing older writers. An internal, non-exported push
receiver accepts the expected sender and bounded type payload, routes only by a matching
server cache GUID and schedules enabled accounts. Messaging opt-out is respected; existing
GCM account registration tokens/settings are not replaced. Follow-up sync work is appended
instead of being dropped while a prior sync is running. On source **22be48a1**, the matching-signer Pixel 7a run passed seven isolated cases,
five native cases (20.03 seconds) and one manager listing case. The native checks include
server-acknowledged CreateDeviceInfo and UpdateDeviceInfo with persisted token/cache GUID,
a freshly scheduled worker, and two follow-up workers both reaching SUCCEEDED. The identical
scheduler fixture failed on the older **5d6c8e55** product because KEEP dropped a follow-up;
this is failing-before/passing-after device proof.

Two additional native cases (16.425 seconds) passed registered-token update/readback/exact
restoration and delete/re-create/exact restoration of the single approved fixture, with
unrelated records checked. The original Services APK bytes were verified after restoration;
no app data was cleared and temporary test APKs were removed. Registration and heartbeat
are native/server-accepted. Actual end-to-end push delivery, stale-token recovery and reboot/
reconnect behavior remain unaccepted.

The push-recovery implementation persists a refresh obligation before scheduling, carries a
stable WorkManager delivery identity across retries, and acknowledges password/preference
domains independently only after a complete authenticated snapshot. Distinct deliveries
with identical payloads remain distinct; deduplication by payload hash would miss changes.
State schema v4 reads v1/v2/v3 and makes older writers refuse the new obligations.
Host tests cover interruption/reopen, retry, domain separation, incomplete pagination,
locked keys and malformed/overflow state. Native push acceptance is recorded below.
The Pixel's read-only bridge subsequently reported MCS disconnected before any remote test
edit, despite an existing FCM token. Enabling password sync now requests the normal GCM
reconnect route, which retains the existing global/per-network opt-outs and check-in policy.
It does not use a forced reconnect or change messaging settings.
On **4bf07f7e**, actual server push passed (8.878 seconds): existing messaging/network consent,
check-in and connected MCS were confirmed; an independent native update produced one expected
sender/cache-GUID/type message, direct password generation 0→1, acknowledgement 1 and changed
encrypted cache readback without manual refresh or scheduling after the remote edit. Exact
synthetic restoration completed and the private remote-client store was cleared. Original
Services was restored and the test APK removed. This accepts the native push path and normal
startup on sync enable; official Chrome producer, token-invalid, reboot and lifetime tests remain open.

The reference `bfec` maps status 9 to stale metadata. Password and preference sync now retry
once after a definite refusal, refreshing metadata and retaining the operation ID. An unknown
outcome is not replayed, a reset vault cannot receive an old queued write, and remote version
checks still run before resending. Receipts include commits made before the retry. Eight new
regressions failed before the fix; all 68 sync tests pass afterward. This recovery extension is
host-tested and compiled; forced server stale-metadata acceptance has not been performed.

OAuth recovery now invalidates only the rejected account/scope token and immediately retries
once after HTTP 401 or gRPC status 16. Device registration and password RPCs keep independent
in-memory token sessions; a registration refusal no longer invalidates the password scope.
Repeated refusal is bounded, consent denial does not trigger renewal, and uncertain writes
or redirects are never replayed. Ten host cases exercise actual HTTP responses, gRPC trailers,
unchanged mutation bytes, scope isolation, disconnects and missing native credentials. All
78 sync and 41 Core tests passed and both Android APKs compiled at that milestone. On source
**c2d5b6f0**, the matching-signer read-only native case passed (3.845 seconds): Google rejected
the synthetic invalid bearer, then native account authentication recovered the same vault
metadata. The original Services APK was restored and the temporary test package removed.
This does not accept real account revocation or interactive account recovery.

The native account path is independent of the web manager's recent-verification prompts.
On product **c2d5b6f0**, fixture **eb471e07** explicitly bypassed the token cache, requested
a fresh account token without foreground/background UI resolution, confirmed a future
lifetime and successfully retrieved the same vault metadata. No Google login UI was used.
This accepts fresh native token minting with the existing account grant; revocation,
interactive recovery, lifetime/reboot coverage and Core agent access remain separate gates.

The pinned `bexa` delete handler passes bare PasswordSpecificsData to `bfgl.g`, whereas
`beyb` unwraps the Chrome save message. The matching public Chrome 145 dispatcher also sends
bare specifics to removeLogin. Source **e088aa98** corrects this input contract and retires
Chrome-local metadata only after authenticated deletion evidence. Two wire-contract and four
metadata-lifecycle regressions failed before the fix; those six and an existing passing
safety guard pass afterward. All 99 sync and 41 Core host tests pass and both APKs compile.
These changes have not yet been installed or accepted through official Chrome.

The reference `jcpp`/`bfdu` also puts a ten-second deadline on each password/SyncEntities
RPC. The transport now bounds the whole HTTP call, including a slowly arriving response
body, rather than relying only on individual read/write timeouts. A throttled-response
regression failed before the fix and passes afterward. A deadline after a mutation launch
retains an uncertain outcome and does not trigger token renewal or replay. All 79 sync and
41 Core host tests pass; both Android APKs compile. This deadline change is not yet installed.

The installed official Chrome 145 client opened Google's web Password Manager, which listed
the single authorized synthetic realm created by this implementation. Opening its details
requires Google's account reauthentication. The saved password remained hidden and no
official-client write ran. This accepts metadata visibility only; exact credential/notes
interoperability and the official producer round trip remain open under CYC-653.
After owner verification, the official detail screen also displayed the expected synthetic
username with the password masked. A phone-encrypted pre-edit checkpoint captured the
original fixture and unrelated-record baseline. The first automated note Save did not
produce the expected native readback; subsequent native comparison confirmed the original
payload remained byte-exact. After the owner's manual note step, diagnostic fixture
**845318ec** on product **c2d5b6f0** again found the original exact payload, unchanged
password/identity, zero notes and no changed field numbers. No note content or credential
value left the phone. This establishes the observed unchanged state, not the reason the
web step did not produce an observable write. Guarded cleanup passed without a cloud
mutation and cleared the encrypted checkpoint; unrelated records stayed unchanged. The
original Services APK bytes were verified after restoration and the test APK was removed.
The single-install session harness avoids unnecessary Services package churn during a
future complete round trip and targets the real UI process. A separate helper can compare
the masked editor's exact password value wholly on the phone, returning only booleans; it
is compiled but has not yet run. Neither producer nor exact-secret interoperability is
accepted yet. Reopening the web manager is not part of this completed diagnostic/cleanup.

The broader comparison remains open: SyncEntities generic entity contracts, affiliation,
legacy credentials, leak checking, sharing and synced passkey consumers require their own
behavior and interoperability evidence. Adding a transport method alone does not complete
one of those flows.

## Affiliation and branding comparison

CYC-673 tracks this surface. The pinned `bffq`/`bfci`, `bewm`/`bewu`, `bexk`/`bexo`,
`ioxh`/`ioxi` and `isos`/`isob`/`isoj`, plus the public Chrome145 affiliation protocol,
distinguish credential-sharing sets from display groups and PSL metadata.

The implementation now connects bounded hash-prefix lookup to an account-encrypted,
expiring cache and scheduled refresh with durable retry fences. Production lookup requires
a dedicated configured affiliation API key; an empty key performs no Google lookup. The
client uses fixed HTTPS endpoints, excludes OAuth/cookies/unrelated keys and rejects
redirects. Branding and change-password links remain metadata. A separate source-faithful
HTML matcher uses the embedded private PSL, wildcard/exception rules, exact scheme/effective
port and the accounts/myaccount Google exception; blocked, non-HTML, federated and
inconsistent-origin records remain excluded. Chrome sharing flags do not acquire PSL authority.

A separate direct-Digital-Asset-Links path requires reciprocal application declaration
and HTTPS origin proof for the current installed signer. It checks the app declaration
before web networking and bounds DNS, redirects, response size, proof lifetime and matching
time. The common matcher is connected to Credential Manager, Autofill, the protected agent
picker and legacy credentials. Chrome transactions7/8 use their separate affiliation and
branding wrappers. Unknown schemes grant no fill authority.

Host vectors and policy tests cover canonical facets, credential-sharing versus grouping,
expired/retried cache, proof boundaries and wrappers. An earlier unauthenticated lookup
returned403. Private-PSL matching, stepwise extensions and transitive display groups are
implemented and passed the gathered host batch. Configured production admission and native/official
client acceptance remain open; host checks do not establish provider acceptance.

## Password management and notes comparison

The pinned converter `berz` reads main ciphertext field 6 and, only when PasswordSpecificsData
notes field 22 is absent, falls back to encrypted notes backup field 10. Explicit empty notes
remain authoritative. Independent code now encrypts both representations together, updates
server metadata field 8 (realm, blocked flag, last use, issues and credential type), and retains
resource identity, version and unknown fields. Partial-client edits retain omitted notes and
unsupported fields; explicit note removal cannot resurrect an older backup. Password changes
clear old issue flags according to the reference converter. The matching public
[Chrome 145 password protocol](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/sync/protocol/password_specifics.proto)
also defines named notes, creation times and hide-by-default; those fields survive default-note edits.

The management screen adds metadata-only search, account holder name/default-note editing,
and never-save site controls. Passwords and notes are masked in the editor. Never-save rules
are rechecked under the vault lock before automatic save; manual management remains available.
Separate Compose dialog windows now have their own secure-window and obscured-touch guard.
Host validation has passed 59 password-sync and 41 Core tests; product and instrumentation
APKs compile. The matching-signer 0e9c8653 build passed all nine isolated Android cases.
Its initial metadata-editor run left an uncertain fixture restoration; the encrypted restore
checkpoint survived. On product 2756e591, fixture 6dcec757 recovered the exact synthetic
payload and cleared the journal (6.232 seconds), then all three manager cases passed
(23.47 seconds), including native-input name/note save, actual secure-dialog checks,
metadata-only search and lifecycle grant clearing. Unrelated data was checked.

Phone-only readback confirmed that Google retained backup field 10 when a restore update
omitted it, even though the main ciphertext contained the original payload. The synthetic
fixture therefore uses its explicitly authorized delete/re-create path for exact restoration.
Normal note deletion sends an authoritative empty field 22 and matching empty encrypted
backup. On product 6ea691ca the native note write, explicit clear, empty encrypted backup and
repeat-download no-resurrection assertions passed, with exact synthetic restoration afterward.
The combined case failed at its subsequent GetPassword call (status 5). That unused-in-product
adapter remains unaccepted and is being investigated separately through bounded read-only probes.
The standalone note-deletion case subsequently passed as recorded below. The initial server-push
test timed out (97.123 seconds); an FCM token alone is not push-delivery acceptance.
Original Services was restored without clearing data and temporary test packages removed.
On product **6ea691ca** with fixture **6fd24225**, all nine isolated cases, standalone note
deletion/no-resurrection (11.8 seconds), seven native cases in `:ui` (32.249 seconds) and
all three manager cases (22.031 seconds) passed. The exact password-update fixture was also
corrected: its legacy restore input omitted field 20, so partial-client merging correctly
retained the newer modification time while restoring the password value. Guarded recovery
restored the original bytes (6.948 seconds); future fixture cleanup bypasses partial-client
merge through authorized synthetic recreation, preserving Chrome-local metadata.
The push precondition remained disconnected (33.593 seconds), and five bounded read-only
GetPassword request shapes returned status 5 or 3; those two cells remain unaccepted.
The current expanded host batch implements username/site identity edits, durable
copy/verified-commit/delete moves, bounded Google CSV and authenticated encrypted archives.
SAF operations reauthenticate the owner and verify the exact stable account after document
selection. Conflict/reset controls retain uncertain provenance and require an explicit owner
choice. Checkup reads actual saved leaked/phished/reused/weak metadata and changes only the
reference leaked-issue mute field. Those saved flags remain distinct from the connected
owner-requested fresh assessment below. New owner-flow device acceptance is CYC-689.

## Fresh breach assessment

CYC-692 connects the native secure manager action to the pinned GMS two-RPC consumer:
`LookupLeakMatches` and `ReencryptLookupHash` at `passwordsleakcheck-pa.googleapis.com`,
using the legitimate account `identity.passwords.leak.check` OAuth scope and synced-checkup
use case 2. No unrelated API key or offline substitute is used. Requests contain a 26-bit
username-hash bucket, a fresh blinded P-256 point and the first response's opaque crypto
context; full usernames, passwords and site URLs are absent from the wire.

`haps`/`hzut` establish email-aware canonicalization and public salts; `ipts`/`iptt` establish
P-256 hash-to-field, even-y mapping, scalar blinding and unblinding. `hzuh`, `hzuv`/`hzuw`,
`hzvf`/`hzvg`, `hzvi`/`hzvh` and `hzvo`/`hzve` establish the two messages, bucket/context and
fixed service endpoint. Independent literal Chromium145 username-hash, bucket, scrypt and
fixed-scalar point vectors accompany strict point and malformed-response controls.

The operation pins the stable account, vault birthday and complete password baseline.
Progress reports completed credential checks, and cancellation cancels the active leak
HTTP call. Existing leaked groups are counted separately and skipped, following `bnjn`.
`bnkr`/`bnkn` add only a new positive first-detection date; existing dates, mutes, other issues
and unknown fields remain intact. Fresh negatives do not clear saved warnings. Credentials
without a usable canonical username are explicitly unassessed. No metadata is queued until
all required checks finish; the full baseline is compared before one atomic enqueue. A
fresh result requires verified mutation responses/cache state, without an extra independent
post-update LIST. Grant, network, partial-query, cancellation and uncertain-save failures
cannot report a completed fresh assessment; ordinary durable outbox rules retain any
already-launched write.

All 22 focused breach tests passed in the consolidated `36246827` host run.
Legitimate provider grant/admission, real response interoperability and
native/device acceptance remain pending. No account/network/device probe ran for this slice.

## Recovered key epochs and enrollment boundary

State schema 6 reads schemas 1 through 5 and makes older writers refuse the new authority.
Field 20 retains bounded explicit positive recovered epochs and material, bound to the vault
birthday under the account AEAD. The owner recovery flow captures the birthday before
opening the exact-account WebView and verifies the actual Nigori keybag before retaining
epochs. Duplicate or conflicting epochs, changed birthdays and revoked owner/account state
are refused. Raw-only caches remain usable for unlock and never acquire inferred epochs;
the enrollment getter requires versioned recovery and revalidates the current keybag.
Both explicit reset adoption and an observed automatic empty-outbox reset clear versioned
authority, including when the replacement snapshot fails. Eleven focused regression tests
and the updated migration expectations passed in the consolidated `36246827` host run.

The CYC-687 native owner flow now uses those verified epochs to enroll a physical type1
member into an existing `chromesync` domain. It pins stable Gaia identity, vault birthday,
the real password keybag and the domain's current recovered epoch. The account's stable
protected pair and exact canonical Join request are durable before a LAUNCHED receipt and
actual dispatch. A Join acknowledgement alone does not install the pair: complete member
pagination, direct exact-member readback, matching public identity/domain/epoch, decrypted
shared keys and membership proofs must succeed, with the domain and owner checked again.
Unknown outcomes retain the same pair/request and never replay automatically; explicit
retry creates a new attempt ID and archives the previous receipt. Post-Join read failures
remain uncertain. Closure retains provenance and does not undo a possible remote member.
The journal lives inside the existing encrypted Folsom account store and is removed by its
existing account cleanup. The connected UI compiles and all 18 enrollment regressions
passed in the consolidated `36246827` host run; provider, device and cross-device acceptance remain pending.

The expanded CYC-687 implementation now adds new-domain creation and explicit existing
member enrollment, rotation and reset in both supported domains. Complete member snapshots,
exact previous/current epoch checks and real SecureBox membership proofs precede confirmation.
A reset fence survives uncertain dispatch and cache clearing; confirmed reset clears recovered
keys and affected enrollment/recovery journals before the fence can be acknowledged.
Owner Binder resolutions bind the actual caller UID/current signer, stable account and domain,
expire after two minutes and cannot turn a launched unknown operation into an automatic replay.
No recovery or enrollment is triggered by a key getter.

Platform LSKF backup uses the actual privileged Android RecoveryController, its signed
certificate trust and real OS snapshot. It wraps the full 97-byte member pair and uploads the
exact account vault before joining and verifying each separate recovery member. Native restore
lists real backups, accepts the old device's PIN/password/pattern privately, derives the pinned
hash and starts an actual OS ephemeral recovery session. One owner-confirmed guess produces one
OpenVault request. The recovered AES key must authenticate the selected member pair; current
member and rotation proofs then validate recovered domain keys. Password recovery additionally
verifies current Nigori and the pinned birthday. Account key/journal confirmation uses an atomic
compare-and-set. Unknown guesses never replay automatically; a restart can finish locally only
when the validated private pair was durably saved before interruption.

Existing GPM PIN renewal consumes the real type5 PIN metadata, authenticates trusted recovery
certificates using the OS trust source, and performs exact vault upload and SDS replacement
with fresh readback. New/change PIN uses an actual Android Keystore identity, honest hardware/software key
classification, pinned Noise NK and the complete AMD/DICE/leaf/handshake attestation chain.
A private owner Google PIN-reset reauthentication flow validates a real main-frame200/204
response; the RAPT remains local to the fresh operation. Actual enclave wrapping, vault
upload and current member readback precede confirmation. Native GPM PIN recovery uses
the source UTF8/six-digit forms. Direct SDK generate-only source4 never opens the vault;
source3 restoration imports all returned supported domains through one OS session and
atomically commits their keys and receipt. SDK recovery uses the trusted source caller and
does not require enabling the separate Services password vault. Coupled account/domain
consent uses genuine lower LSKF setup, exact source leave/update effects and selected-domain
persistence; getSyncStatus returns the source seven integer states. Protected owner resolution
keeps finite mutation receipts and requires explicit review before another attempt. The source default
one-day wall-clock cooldown is persisted in protected per-domain field16 only after an
actual notification post. Google runtime experiment/skip flag values are not mirrored;
that source qualification remains under CYC-687. The five descriptor-only Passwords lifecycle methods have no verified producer or
request/response schema in this pinned APK and its examined official modules. Their contracts
remain open; see [the contract audit](passwords-rpc-contract-gaps.md). Generic Chrome Nigori
transition cryptography does not establish those RPC envelopes. Creating an SDS domain alone
does not convert an existing Nigori password vault. This runtime and its fixtures passed the gathered host run; genuine provider/device
acceptance remains pending.

The family-sharing source comparison found no separate client sharing-key enrollment RPC:
Google reads committed cross-user keys from Nigori and creates absent password metadata with
CreateMetadata(users/me). The implementation follows that path and refuses a missing committed
keybag; the earlier standalone sharing-key enrollment gap was unsupported by the reference.

## Bounded passkey extensions

Native Credential Manager and legacy GMS FIDO create/get connect PRF evaluation to the
selected authenticated credential. Inputs use the WebAuthn domain-separated hash and exact
credential-ID selection. Typed SafeParcel results use field1 enabled and field2 concatenated
first/optional-second32-byte results; native JSON uses nested prf.results. Missing-secret
migration derives the source HKDF secret from actual private-key PKCS8 material and durably
commits authenticated ciphertext before returning output. Authenticated hybrid account PRF is connected to the same real account vault. It consumes
the source already-hashed inputs and returns unsigned CTAP make/get extension results. A
one-use process-local lease binds the authenticated Noise transcript, original CTAP digest,
complete translated options, RP and client-data hash through the private owner flow. Socket
loss and authenticated caBLE Shutdown revoke authority while owner UI is pending; no selected
account request falls back to the local FIDO database.

Native Credential Manager reads signed credBlob and raw-DEFLATE largeBlob data inside the
owner/caller/RP/account/birthday-authorized secret scope. Creation supports the bounded32-byte
credBlob and actual Boolean CBOR result. Large blob support and writes accept at most2048
uncompressed bytes and preserve immutable identity/device/payment/opaque record fields. The
original request digest and SECRET_UPDATE journal fence signing until authenticated server
readback confirms the exact mutation. PRF migration and blob writes share one update when both
are requested. Unknown writes retain provenance and cannot use generic retry without the
original request. These paths passed their host fixtures and both APK builds; native acceptance remains pending.

Chrome-delegated payment discovery and assertions require its exact installed release signer,
Binder UID/package, real synced thirdPartyPaymentsSupport flag and a nonempty allow list for
cross-RP selection. The privileged browser supplies the payment client-data hash and owns the
transaction UI. Native CM SPC remains unsupported consistently with the reference routing.
The pinned legacy AuthenticationExtensions ABI ends at fields2..14 and supplies no proven
blob fields; arbitrary legacy fields are not invented. The pinned GMS producer is ES256/P256,
so non-ES256 is not recorded as a source-proven missing reference capability.

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
No password or preference cloud writes ran in that historical review round.

| Surface | Gap / required proof |
| --- | --- |
| Device registration and push | Native/server registration, heartbeat, token-backed CRUD and follow-up workers passed. Actual native Google push, durable hint acknowledgement and background readback passed on 4bf07f7e after normal messaging startup on enable. Token invalidation, network-reconnect, reboot/lifetime and official-producer acceptance remain open. |
| Settings | Durable priority-preference synchronization and Binder transactions 5/6 are implemented and host-tested. Native list/cache reopening passed; preference writes, official-client callbacks, management controls and provider behavior still need verification. |
| Affiliations | Dedicated-key lookup, encrypted cache/refresh, direct reciprocal DAL, common fill matching and branding wrappers are connected. Private-PSL matching and source-correct Chrome grouped-only flags are implemented and host-tested. Production admission and official/native acceptance remain CYC-673. |
| Legacy credentials | Get/save/delete use caller-bound encrypted one-use owner flows. Legacy GMS FIDO create/get use the real account-synced vault with exact caller/signer/origin, Gaia/birthday and owner gates, including existing-secret PRF and its typed result ABI. Authenticated hybrid requests now reach the real account vault with live transcript/request leases and PRF; the local database remains separate. Legacy blobs have no proven typed ABI. Other unsupported advanced options and provider/device acceptance remain CYC-688/CYC-689. |
| Encryption recovery | Exact-account/domain recovery, encrypted Folsom migration, caller admission, stale obligations, source-shaped member-key decoding and schema6 verified key-epoch retention are implemented. Explicit-owner existing-`chromesync` physical type1 enrollment is connected, with protected stable pair/request, durable launch and full proof readback; host validation passed. Domain create/enroll/rotate/reset, real platform LSKF backup/restore and existing GPM PIN renewal are implemented and host-tested. New/change PIN enclave, native GPM PIN recovery and direct SDK bulk/generate/list consumers are implemented and host-tested; coupled consent/getSyncStatus and protected owner resolution are implemented under CYC-687; provider/device/cross-device acceptance remains open, including CYC-691. |
| Conflict recovery UI | Explicit local/remote/drop/new-ID retry and local adoption of a reset vault are connected. Unknown writes keep durable provenance and never automatically replay. Current device acceptance is CYC-689. |
| Password management | Identity edits, durable copy/verify/delete moves, bounded Google CSV, encrypted archive import/export and SAF owner reauthentication are connected. Checkup preserves saved flags/mutes. The owner-requested two-RPC fresh breach consumer is implemented; provider grant/admission, server interoperability and device acceptance remain CYC-692/CYC-689. |
| Family sharing | Exact family eligibility, enrolled authenticated HPKE keys, fresh-recipient checks and launch receipts are connected. Incoming background policy matches the pinned default: import new, preserve conflicts, skip invalid siblings, consume processed invitations; same-sender updates default off. Get/CreateMetadata and committed Nigori keybag behavior match the reference; no separate sharing-key registry RPC was found. Second-family-account acceptance is CYC-690. |
| Synced passkeys | Account-bound Credential Manager, legacy GMS FIDO and authenticated caBLE hybrid create/get use the real vault with current owner/domain authority. Native rename/delete, missing-secret PRF migration, bounded blob creation/read/write and Chrome-delegated payment are connected. Hybrid leases bind transcript, original request and immutable options with immediate disconnect/shutdown revocation. The gathered host batch passed; CYC-687/CYC-688/CYC-689/CYC-691 interoperability acceptance remains pending. |
| Wi-Fi | Explicit account opt-in, encrypted cloud adapter, owned-network native consumer and durable uncertain-outcome fences are connected. READ_WIFI_CREDENTIAL requires the companion privileged image grant; old images refuse. The current target-29 caller also requires owner-granted precise location, enabled Location and allowed precise-location/Wi-Fi-scan app-ops. NETWORK_SETTINGS is signature-only, not a privapp image grant. Native/image acceptance remains CYC-689. |
| Core agent fill | `security.password.fill` takes empty inputs and returns only `filled`; private native delivery targets the retained field. A durable receiving-app observation/input/capture fence lasts until authoritative UID process death or reboot. Owner-assisted native fields only; browser/WebView origins are refused. Device/controller acceptance remains CYC-689. |
| Phone | Earlier signed installations and synthetic CRUD/restoration are historical evidence. All new device work is deferred by the owner because another chat has priority; no components were replaced in this batch. CYC-689 tracks a gathered coordinated acceptance run. |
| Official interoperability | Metadata reading passed historically, but official exact-secret reading and producer note/write round trips remain unaccepted under CYC-653. Self-written/self-read is insufficient. |

## Reproduce the checks

With Android SDK configured:

```sh
./gradlew --console=plain --continue :password-sync:test :play-services-fido-core:testDebugUnitTest :play-services-core:testMapboxDefaultDebugUnitTest :play-services-core:assembleMapboxDefaultDebug :play-services-core:assembleMapboxDefaultDebugAndroidTest
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
