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

## Implemented behavior

- Bounded, lossless protobuf parsing and manual unary gRPC framing; fixed HTTPS Google host,
  token-bearing redirects and automatic connection retries disabled, gzip bounded, remote
  error details excluded from exceptions/logging.
- Password metadata, pagination, get, create, batch update and delete. Update uses each
  resource ID; Chrome local data is preserved in the same commit as the outgoing change.
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
  password edit, delete and custom-passphrase unlock. Screen-lock owner verification,
  foreground-only grants, secure window and disabled autofill for the manager itself.
- Android Credential Manager get/create and legacy Autofill get/save use the same vault.
  Exact web origin or certificate-bound Android facet only; browser-delegated origins are
  allowed only for verified Chrome packages. Password release requires owner authentication.
  Mutable PendingIntent extras cannot change the encrypted one-use selection. Autofill also
  binds the credential to the original field IDs and revalidates the requesting app.

## Remaining parity and acceptance

| Surface | Gap / required proof |
| --- | --- |
| Device registration and push | CreateDeviceInfo/UpdateDeviceInfo, stable nonce/client token, invalidation transport and reconnect handling are not connected. Current password writes use empty client-token fields, which the earlier PoC server accepted. |
| Settings | Priority-preference transport enum exists; cloud synchronization and Binder transactions 5/6 are not implemented. |
| Affiliations | Verified Digital Asset Links, branding and grouping are not implemented; no eTLD or package-name shortcut is used to authorize fills. |
| Zeroparty/legacy APIs | Service 80, Smart Lock and older credential-saving contracts still need comparison and integration. |
| Encryption recovery | Synthetic host cases cover keystore/trusted/custom derivation and keybag rotation. Real account recovery, stale-key refresh, registration, enable/rotate/clear RPCs and cross-device recovery are not accepted. |
| Conflict recovery UI | Conflicts and uncertain changes are retained. Owner selection of local/remote resolution, retry/drop, and vault-reset recovery still need UI and tests. |
| Password management | Search, note/name edits, moving between stores, import/export, blocked-site settings and checkup/breach service parity remain. |
| Sharing/passkeys/Wi-Fi | Related password-service RPCs are individually missing in the JSON inventory. Existing FIDO provider behavior is not evidence of Google-synced passkey parity. |
| Core agent fill | No secret-returning agent API is added. Core authority, private-field handling and a secret-free native fill path remain required. |
| Phone | Compile and synthetic host proof are separate from signed installation, process death/reboot, account removal/revocation and scheduled/background acceptance. |
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
