# Passwords RPC contracts not established by the pinned client

Part of CYC-687. These five methods are present in the Passwords service inventory, but their request/response schemas and an active producer are not established by the pinned reference:

| RPC | Admission descriptor |
| --- | --- |
| `EnableTrustedVaultPassphrase` | `ialk` |
| `RotateTrustedVaultPassphraseKey` | `iall` |
| `ClearTrustedVaultPassphrase` | `ialm` |
| `ResetWebauthnCredentials` | `ialn` |
| `MarkForEnableTrustedVaultPassphraseGroup` | `ialp` |

These are distinct from Security Domain Service enrollment, rotation and reset, and from individual passkey CRUD. No implementation or host, provider or device acceptance is claimed for these five RPCs.

## Reference and bounded evidence

The reference is Google Play services **26.11.36 (260400-892401869)**, version `261136035`. The inspected APK SHA-256 was rechecked as:

`35f23040de6362a77103edb4b23825cfed7310ab6efe4d59310f817d79e6c1c2`

The `iamh` inventory lists the 29 methods of `google.internal.chrome.sync.passwords.v1.Passwords`, the production host `chromesyncpasswords-pa.googleapis.com`, and its OAuth scope admission. For the five methods above, `ialk`, `iall`, `ialm`, `ialn` and `ialp` provide method names and admission information; they do not bind request or response message types. The generated `iakv`/`iaku` stub shells do not provide these method implementations.

The investigation used the following independent checks:

- Search all extracted GMS Java sources, supplemental extracted producer files, pinned Chrome sources and available protobuf sources for the five exact names and references to `iamh.q` through `iamh.u`. The names appeared only in `iamh` and their admission descriptor classes.
- Inspect the matching base APK's DEX string tables and native libraries directly. Each full method name appeared as one bare inventory string in `classes13.dex`; none appeared as a fully qualified RPC call path. The extra substring occurrence of `EnableTrustedVaultPassphrase` belongs to `MarkForEnableTrustedVaultPassphraseGroup`.
- Search DEX/native contents of the ten available official bundled Chimera, Chrome and Trichrome APKs. None contained the five method names.
- Compare with proven active producers, such as the separately extracted `bfdu` and `bfdi` implementations. Those bind a fully qualified method path to concrete request and response message classes. An equivalent binding was not found for these five methods.

These checks are bounded to the pinned artifacts. They establish a missing verified contract in this reference, not that the methods never exist in another client, module or server configuration. No proprietary implementation or APK is checked into this repository. The investigation performed source reads and artifact inspection; it did not execute application tests, install software, contact a provider account, or send an RPC.

## Reopened producer investigation

The follow-up investigation on October 23, 2026 distinguishes an admission inventory from actual owner operations. `iamh` implements `hjvs`: its method lookup returns an `hjvq` containing an RPC identifier, scopes and a boolean policy value. `bmif` supplies this registry to the common client/channel factory. The registry does not contain protobuf marshallers. By comparison, the active `bfdu` producer binds `BatchUpdatePasswords` to concrete request and response types in a separate method descriptor. A listed admission record therefore establishes neither an active caller nor a missing user-facing button.

**On-device encryption setup has an active producer outside the static APK.** The pinned GMS promo follows `bmnn` → `bmnj`/`bmod` → `bmpd.d`/`bmpa` → `bljr.n.N(account, 4)`. Folsom operation 4 opens `GenericChimeraActivity` with `InitialPasswordEnrollment`; `anxn` selects `anxv.a(4)`, and `anxz` installs `anyl` for that operation. For `chromesync`, `anyl.onCreateView` loads the URL from `jblu.r`, whose default is `https://passwords.google.com/embedded/encryption/enroll/intro`. Its native `anxa` bridge exposes shared-key delivery, recovery-method registration and consent; it does not supply the five Passwords RPC message envelopes. The corresponding desktop enrollment URL is also declared in [Chromium 145's URL constants](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/chrome/common/url_constants.h). Thus setup must not be dismissed as an unused feature just because a native RPC producer is absent.

**Visible delete-all does not establish the `ResetWebauthnCredentials` contract.** In the pinned GMS settings path, `blbd.a` invokes the password and passkey repositories' `bfkm.c` operations. The `beyi` DeleteAll handler marks the selected account/type's sync entities dirty and locally deleted, then schedules ordinary synchronization. Independently, [Chromium 145's `SavedPasswordsPresenter::DeleteAllData`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/password_manager/core/browser/ui/saved_passwords_presenter.cc) deletes passkeys and removes password logins through their stores. Neither path establishes an invocation of the similarly named reset RPC.

The public schema checks added independent, bounded negative evidence:

- Google's [public Discovery API directory](https://www.googleapis.com/discovery/v1/apis) did not list a Passwords or Chrome Sync API. This does not describe private service discovery.
- The complete, non-truncated [googleapis repository tree at `cfc42c251e2148d608fb2fe67b2348129e640f7c`](https://github.com/googleapis/googleapis/tree/cfc42c251e2148d608fb2fe67b2348129e640f7c) contained 7,475 protobuf files, with no `google/internal` or Chrome Sync/password service schema paths. This was a tree/path check; no assertion is made about unpublished schemas.
- Anonymous reads of both official enrollment URLs returned the Google sign-in application, rather than the enrollment application's scripts. They did not establish its RPC descriptors or state transitions. No authentication material was supplied and no enrollment/reset request was sent.

Google's [on-device encryption help](https://support.google.com/accounts/answer/11350823) documents setup and a separate destructive recovery page at `passwords.google.com/encryption/reset`. It does not describe an ordinary reversible encryption toggle. It also describes clearing Chrome account data as a distinct lost-passkey recovery operation. These documented actions identify useful producer references; they do not prove which of the five RPCs they call.

| Method | What the follow-up establishes | Still required |
| --- | --- | --- |
| Enable | An active encryption setup flow delegates to authenticated web-delivered code. | The web producer's exact RPC, request/response binding, key transition and completion/readback rules. |
| Rotate | No independently bound producer established. | A pinned producer showing whether this is explicit maintenance, account recovery or another operation, plus its version and proof requirements. |
| Clear | Destructive recovery is documented; a non-destructive toggle is not. | The reset page's actual producer and destructive scope, including its uncertain-outcome reconciliation. |
| WebAuthn reset | Existing delete-all is implemented through store deletion, not a proven call to this method. | A producer distinguishing this RPC from ordinary credential deletion and Security Domain reset. |
| Group marking | An admission identifier and a separate Chromium consumer of downloaded upgrade-group metadata; no invocation of this RPC established. | Its actual caller and assignment semantics before classifying it as an owner-facing parity gap. |

The next reference was obtained by capturing external public script URLs from already authorized introductory views, filtering on the device, then downloading the assets anonymously on the host. The capture excluded cookies, credentials, inline bootstrap/account data, page HTML, request headers and RPC bodies. No enrollment or reset was submitted. The resulting current web reference is separate from the pinned GMS 26.11.36 and Chromium 145 artifacts.

## Additional offline artifacts and group-metadata consumer

A second bounded search on October 23 inspected additional locally available official artifacts, without starting an emulator, mounting or changing a system image, executing Google code, or contacting an account/provider:

- The pinned base APK's eight embedded Chimera APKs and bounded non-DEX assets added no occurrences of the five method names. The embedded module copies were inspected independently because their APK bytes differed from the previously extracted copies. The Chimera manifest places the relevant Folsom, Chrome Sync, credential-manager and credential-auth modules in the base; its declarations did not supply another private request schema.
- DEX, native and protobuf members of 20 other APKs in the same local Google-services reference archive contained none of the five method names. A broader service-name search did find fully qualified Passwords paths in `FamilyLinkParentalControls.apk` (SHA-256 `0e57780e7a16aca3421c5cd7f1089e6b231f7e42f4ff0c61b84c56abeea0bb21`). Their actual owner, `mza`, is a generated data-collection method-name registry: it associates paths with use-case identifiers, rather than protobuf marshallers or invoking code. This was not an additional RPC producer.
- The local API 37 Google APIs ARM64 image supplied a GMS 26.11.36 APK with SHA-256 `f386bff2feb0520656ae2ff5b1a75b95d6ab8d472a2dc50b60d709853cf348e5`. All 18 DEX entries were byte-identical to the pinned APK, so its different packaging does not provide an independent protocol reference. The local API 35 Google Play ARM64 image supplied GMS 24.23.35, version `242335038`, APK SHA-256 `5e093367651d0db1478e58a5cac463cbaf684edbe6ffc42743ce1390e545dee4`. Its five bare names occur in `classes9.dex`. Narrow decompilation of `eyvd` and `eyuh` establishes another admission registry and method-name/scope/policy descriptor, without request/response binding. No fully qualified invocation of these five methods was found in either APK's inspected DEX strings or bounded non-DEX members. These offline SDK artifacts were not installed or run.

There is also a concrete public consumer for related group metadata. [Chromium 145's `NigoriSpecifics`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/sync/protocol/nigori_specifics.proto) carries `TrustedVaultDebugInfo.auto_upgrade_experiment_group` at field 5 and its modification time at field 6. In the same pinned release, [`SyncServiceImpl`](https://chromium.googlesource.com/chromium/src/+/refs/tags/145.0.7632.218/components/sync/service/sync_service_impl.cc) copies the engine's group metadata into preferences and registers a synthetic field trial from the cached value; its sync-cycle consumer explicitly accommodates newly downloaded server-populated metadata. This establishes the examined download/cache/measurement path. It does not establish a `MarkForEnableTrustedVaultPassphraseGroup` caller, its private request tags, an owner-selectable assignment, or that no other client writes the metadata.

The next useful reference must add a producer, rather than another inventory or a repackaged copy of the same DEX. A bounded offline probe of a newly obtained official client/module should first locate a fully qualified method descriptor or an indirect method lookup, follow its marshaller to the concrete request/response default instances, then identify the invoking owner/worker path and its readback rules. Until that positive chain is available, neither sending guessed requests nor mapping the separately verified frontend fields to the five private gRPC envelopes is justified.

## Current public web producer reference

The separately pinned public build is `boq-identity.IdentityPasswordsUi.en.Bu5xh5HQQkk.2018.O`, observed with Chrome `155.0.8059.84`. The reproducible [metadata-only reference manifest](testing/passwords-public-web-assets.json) records exact public CDN URLs, build/module identifiers, sizes and SHA-256 hashes, including the separately captured embedded Passwords WebView and Accounts relay builds. Every retained URL has exactly HTTPS `www.gstatic.com`, a `/_/mss/` path, and no user information, explicit port, query or fragment; hashes and sizes were rechecked against the downloaded files. No JavaScript content or account/session data is included. The original local manifests are `/tmp/cyc654-web-assets/manifest.json` and `/tmp/cyc654-web-assets/public-lazy/manifest.json`. The latter modules were requested anonymously by substituting only their source-declared module identifiers into the same captured CDN build URL; no JavaScript was executed on the host. Reference modules include:

| Module | Producer role | SHA-256 |
| --- | --- | --- |
| `Pr29Vd` | Enrollment model and method descriptors | `34d3b31fb837c29e2d19309988b0eb60d63dec44e1f6ea28b30877a5c45bfa97` |
| `TZZFr` | Enrollment orchestration, key rotation, Enable and password reencryption | `e4fb69435aa7d7c6b61a59ef84f912733cf8f978262e8ccf715713cbb7f0c7d4` |
| `SnKx0e` | Enrollment setup model, Join/Remove/reauth descriptors | `954750b800a775797c739a21577c3714b2f37aa72de6063b45a1c79830f91d5f` |
| `Qia3he` | Enrollment setup eligibility, member validation and recovery-member Join | `075ffff04cc2ec3d84e7bdaa1717299e52eee7945faebfd4cefc5998274088e6` |
| `qe8AQe` | Destructive password/Wi-Fi encryption reset | `24a361a3ed9605a17980427d24c158b620f4aea6e8933c85d25a59f7c6064133` |
| `yqcswd` | Destructive passkey reset | `87f70bb75dee46d7905fcee237e37d7264992f1279d73ca179d76dc2f1946f28` |
| `MjDf5b` | Explicit all-passkey deletion confirmation | `292d29bb12dcceb387cde002535127e2501b914da13508938c41b6edcbe7db4d` |

These are **IdentityPasswordsDataService frontend contracts**, rather than the internal Passwords gRPC request schemas. The shared frontend executor sends JSPB JSON requests through `data/batchexecute`, using a short method ID, an operation index, the session's actual anti-CSRF token and selected-account web authentication. No equivalence between frontend message tags and internal gRPC message tags is inferred.

| Frontend method | Short ID | Actual producer fields and prerequisites |
| --- | --- | --- |
| `EnableTrustedVaultPassphrase` | `ZalxJ` | Request fields 1: metadata version string; 2: encrypted Nigori keybag; 3: keystore decryptor token bytes taken from frontend Nigori projection field 7; 4: returned new Security Domain epoch. The producer requires passphrase type 2, opens the existing keybag with actual keystore keys, adds a Nigori key derived from a fresh random 32-byte shared key, and preserves the existing keybag's other entries. |
| `RotateChromeSyncSharedKey` | `PdCzge` | Request fields 2: expected prior maximum epoch; 3: per-member name/wrapped shared key/membership proof (fields 1/2/3); 4: rotation proof. The enrollment producer validates every old membership proof against the source's pre-enrollment zero 16-byte key, creates SecureBox-wrapped new keys and HMAC-SHA-256 membership proofs, and uses the response's field 1 as the new epoch. A definite frontend status 9 permits one fresh-member-list retry in the web implementation. |
| `BatchUpdatePasswordSpecifics` | `v4sSqf` | Repeated field 1 carries password specifics reencrypted with the newly selected Nigori key, in batches of 200, after Enable completes. The source preserves optional encrypted notes backup. This is distinct from the internal Passwords resource wrappers. |
| `ClearTrustedVaultPassphrase` | `sdlTzd` | Requires current frontend passphrase type 5. Request field 1 is the optional `kcsid` keychain-session value. The introductory UI explicitly warns that continuing permanently deletes all saved passwords and saved Wi-Fi passwords. It is not a nondestructive encryption toggle. |
| `ResetPasskeys` | `QmX7Kb` | Request field 1 is the optional `kcsid` value. A separate introductory confirmation explicitly says all existing passkeys will be deleted and must be recreated for their online accounts. This does not establish the internal `ResetWebauthnCredentials` envelope. |

The setup producer additionally binds `EnforceReauthForOnDeviceEncryptionEnrollment` (`Y3psr`), `JoinChromeSyncSecurityDomain` (`XBvXf`) and `RemoveChromeSyncSecurityDomainMembers` (`zCViFb`). It requires keystore passphrase type 2, removes source-invalid recovery members, validates the source zero-key membership proof, and joins the actual Gaia-password public key before starting enrollment. These are actual setup prerequisites, not evidence that an arbitrary Security Domain Join can replace the password conversion.

The enrollment producer has two source-defined key-delivery orderings. With inline consent disabled, it waits for Enable and password reencryption before sending the epoch/key list. Inline consent may deliver keys earlier and invoke the native consent bridge. The current producer sends key delivery through a hidden `accounts.google.com/encryption/unlock/datarelay` frame restricted to the Passwords page's exact origin.

The relay consumer was separately pinned from public build `boq-identity.AccountsKeychainDataRelayUi.en.OpKBWWhCFJQ.2018.O`. The consumer bundle is local `asset-33.js`, SHA-256 `af0a93660543df2a3a70269b34ae4eb7d8ada2b53e0d85da91e14f2f50472871`; its complete URL is in the same public-asset manifest. `G9` creates an exact-parent-origin `encryptiondatarelay` MessageChannel. Its request union field 4 reaches `Vqa`, which supplies the actual signed-in Gaia ID and epoch/key map to `k9.Gd`. `B9` emits `{epoch, key: Uint8Array}` objects, and `k9.Gd` calls `mm.setVaultSharedKeys(accountId, JSON.stringify(domainMap))`. A subsequent `window.onKeyDataSet` callback completes native key delivery. The current relay ignores the callback's status value; a failed native consumer must therefore withhold acknowledgement rather than send a failure value that the page would treat as completion. This independently establishes the current bridge binding rather than inferring it only from the prior GMS `anxa` bridge.

The web run controllers perform their mutations on mounting the `/run` views. Investigation therefore stopped at introductory views and anonymously retrieved source-declared static modules. Neither run/setup navigation nor a mutation request was used as a discovery probe. Completion redirects and `closeView` are UI events; the inspected web producers do not perform an independent full native metadata/list readback. A native compatibility consumer must retain account/owner and uncertain-outcome fences and verify resulting state before claiming completion.

This establishes active frontend producers for enrollment and the two destructive owner recovery operations. It does **not** establish internal wire equivalence for the five original admission records, nor an active producer for `RotateTrustedVaultPassphraseKey` or `MarkForEnableTrustedVaultPassphraseGroup`. Implementation and host/provider/device acceptance remain separate claims.

## Connected owner compatibility implementation

The owner entry is implemented in `PasswordEncryptionActivity`, with policy/journal in `PasswordEncryptionFlow`, bounded current-relay parsing in `PasswordEncryptionBridgeKeys`, and native snapshot/key proof/cache integration in `PasswordEncryptionRepository`. It runs the genuine Google introductory/setup/confirmation flow instead of reconstructing private frontend requests or guessing the five internal gRPC schemas. The existing native credential and agent consumers remain native.

On Android 9 or newer, this nonexported owner activity uses a dedicated process and WebView data-directory suffix. A process-wide lease prevents a second account from clearing or replacing the first account's cookies, including during asynchronous cleanup. It authenticates the exact selected account with native webLogin, protects the window from screen capture and autofill/content capture, disables WebView debugging and console output, restricts navigation to the exact Google origins and the selected operation's finite Passwords routes, and revokes late callbacks on owner/account/cancellation changes. A document-start guard checks the frontend's actual `w2btAe` Gaia field before XHR/fetch dispatch, so an account switch cannot reach an observed frontend RPC for another account. Key delivery is admitted only from the source-proven Accounts relay frame under the active Passwords enrollment route, with exact Gaia/domain binding. Inline consent is not advertised; the source's `ilcp=false` ordering is used.

Schema 7 stores the account-bound operation baseline and durable receipt under the existing vault AEAD. Opening the web flow fences ordinary credential reads and writes across processes. Staged relay keys grant no credential authority. Page completion merely requests native verification: enrollment requires fresh same-birthday type-5 Nigori, actual Security Domain epoch/member proofs and complete decryptable snapshots before importing versioned keys and reopening consumers. Password/Wi-Fi reset requires complete empty corresponding lists and actual supported metadata with on-device encryption turned off; passkey reset checks the full raw passkey list, including shadowed entries. These checks establish current server state, not inferred Security Domain reset or a causal reset when the collection was already empty.

An interrupted flow is never automatically reopened or resent. A safe prelaunch close retires only a never-opened receipt. The explicit owner action to use current Google data requires a fresh usable keybag and complete snapshot, commits a coherent local cache, and retains acknowledged-unknown provenance in a `CLOSED` receipt. It does not claim the earlier request was cancelled or completed, and warns that the earlier request may still finish. A failed or incomplete verification leaves the durable fence in place.

Source fixtures cover the journal, unknown-outcome and account/frame boundaries, bounded typed-array parsing, exclusive cookie-profile cleanup, and execution of our own account/relay shim against synthetic browser primitives. These new fixtures and runtime code have not been executed or built by the implementing worker; the parent performs consolidated validation. No enrollment/reset provider request or device acceptance is claimed for this slice.

## Why generic Nigori support does not supply these contracts

The published Chromium 145 `nigori_specifics.proto`, `nigori_sync_bridge_impl.cc` and `pending_local_nigori_commit.cc` establish Nigori encryption, keybag handling and allowed passphrase-type transitions, including keystore/trusted-vault transitions. They do not define the five Passwords RPC request envelopes, key-version admission, client-token behavior, response outcomes or authoritative readback conditions.

In particular, the original pinned internal gRPC evidence does not establish:

- Enable/rotate: the exact keybag and version payload, source eligibility checks, required account proof and commit/readback contract.
- Clear: the internal request envelope, required proof, resulting encryption metadata and preservation rules. The separately pinned frontend now establishes its destructive password/Wi-Fi scope.
- WebAuthn reset: its relationship to password metadata, passkey generations and hardware-domain state, or how an uncertain reset is reconciled.
- Group marking: the assignment value and rollout semantics, or whether it is an owner-facing action at all.

Guessing these fields, copying a similarly named Security Domain RPC, or substituting a legacy Sync commit would produce a different, unverified operation. A transport enum or an HTTP-success stub would not close the implementation gap.

## Required evidence to implement

Direct implementations of the five original internal gRPC methods still require independently verified request/response descriptors and their producer/consumer chains. The current public frontend supplies a verified alternative producer for owner enrollment and destructive recovery, now connected through the protected owner web flow above. It does not justify filling missing internal tags, equating `RotateChromeSyncSharedKey` with `RotateTrustedVaultPassphraseKey`, or inventing group-marking semantics. Host validation, provider admission and device acceptance remain separate checks.

## Official API 37 Google APIs ARM64 revision-8 follow-up

The parent completed the bounded offline Google APIs revision-8 EROFS continuation
on October 24, 2026 at **3:37 AM PT**. The official reference is
[`arm64-v8a-37.0_r08.zip`](https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-37.0_r08.zip),
package `system-images;android-37.0;google_apis;arm64-v8a`, revision 8:

| Reference pin | Value |
| --- | --- |
| Archive bytes | `2104098168` |
| Official SDK SHA-1 | `7a8bfff584798fe87caa4d4883f6ade4658a185b` |
| Download SHA-256 | `a805271ba138841e1b960c0ffca3c54d4fd1165262db7a3c390063c08fbe61fc` |
| Signed GMS APEX SHA-256 | `c47586e1ec3875952a652ca44488d0008136db76fc715bd97f450dd02e0e1047` |
| EROFS payload SHA-256 | `d441d332387c74b2240d9d3e3ea6035f0b8b1f891526a27376c585087fccc0cb` |

The actual base is GMS `26.11.36`, version `261136035`, **216,312,877 bytes**,
whole-APK SHA-256
`f386bff2feb0520656ae2ff5b1a75b95d6ab8d472a2dc50b60d709853cf348e5`:
it is byte-identical to the retained r6 base. All **nine full APK member maps**
(the base plus eight modules), including recursively inspected nested
APK/JAR/ZIP members, match the r6 baseline; the base's 18 DEX members are unchanged.
The continuation checked the outer APEX signature, normal AVB verification and
embedded/outer public-key binding before bounded EROFS inspection. This is
container verification, not a new hardware or Google root-of-trust claim.

All four remaining lead groups comprise the five files below. Their classification
comes from parsed contents and exact pinned AOSP consumers/generators, rather than
filename exemptions:

| Exact file | Bytes / SHA-256 | Parsed classification |
| --- | --- | --- |
| Payload `apex_manifest.pb` | 32 / `24566c07acba12667e4db61fecd98b5a3c12325b5884d43d5ca198adbc8aa7d0` | Complete `ApexManifest`: field 1 name `com.google.android.gmssystem`, field 2 version 13; no unknown fields. Outer/inner copies are identical. |
| Payload `etc/permissions/privapp_allowlist_com.google.android.gmssystem.xml` | 10,069 / `2bca05d3f803a362ebf95354e299654bb25ea65c4083fa516b6dd95b26b56a7f` | `permissions` / one GMS `privapp-permissions` / 136 unique permission-name entries; no unknown elements, attributes or non-whitespace data. Comments are LINT source pointers. This is declarative privileged-grant policy, not permission to copy these grants or a protocol descriptor. |
| Payload `etc/sysconfig/preinstalled_packages_com.google.android.gmssystem.xml` | 490 / `f1fcd4d27845c367743ece1c5ba2252158c7c1e0a9657c78037e926c0d6113c3` | One GMS `install-in-user-type` policy with `SYSTEM`, `FULL` and `PROFILE`; no unknown elements, attributes or non-whitespace data. |
| Outer `resources.arsc` | 40 / `1fa3cb291285348ec1b33c85e7317d989467707f4791c4e5302f2d312d1e18c8` | Complete empty resource table: zero packages and one empty 28-byte UTF-8 string pool; no other chunks or trailing bytes. Normal `aapt2 dump resources` exits 0 with no resource entries. |
| Outer `assets/NOTICE.html.gz` | 70,102 / `21db2c492da69d355db19d47ab46a2c566466177f477306dcd0ef62a06b55840` | One gzip member, no unused/trailing compressed bytes; 554,768 decoded bytes, SHA-256 `d07648788aa243a4d0f6e1200aa912f214e0387d7655661f8fc2d17b6ccc78ff`. Exact AOSP `htmlnotice` template with 46 escaped legal/license sections; all 46 section IDs match the underlying text MD5 after removing the generator's one added newline. No active HTML tags, extra document regions or five-RPC names were found. |

The pinned consumers are `system/apex/proto/apex_manifest.proto` and
`apexd/apex_manifest.cpp` (identity/version); framework `SystemConfig.java`
and `UserSystemPackageInstaller.java` (scoped permission and installation maps);
`ResourceTypes.h` / `LoadedArsc.cpp` (resource table); and Soong
`apex/builder.go` → `android/notices.go` →
`build/make/tools/compliance/cmd/htmlnotice/htmlnotice.go` / `noticeindex.go`
(generated license assets). Source hashes and exact line anchors are retained in
the separate local assessment receipt below.

The bounded GMS/base-module packaging lead supplies **no new executable,
marshaller, request/response descriptor or invoking consumer** for the five
internal Passwords RPCs. No application code, Google JavaScript or account/provider
operation was executed, and no SDK was installed or booted for this inspection.
It does not establish equality of other Google APEXes or the whole SDK, a server
cohort, a usable account or provider/device acceptance. The separate **Google Play
revision-8 EROFS continuation is pending**; no comparison success is claimed for it.
The already obtained Play archive's pin is SHA-256
`6c671df4611aef040600cf929fee56dc6306ce44a6b94183f845d66437faf859`,
2,166,618,231 bytes, official SHA-1 `ac251e27178d78cffb0b7ee577d79643491a89a6`.

Receipts remain separate: `apis-r8-gms-erofs-comparison.json` proves the nine APK
member-map comparison; `management-scan5-erofs-metadata-assessment.json` classifies
the exact five metadata files. Both are under
`/tmp/cyc689-parity-r12-20261024/rpc-source-leads/`; the assessment SHA-256 is
`f8b7bef5792c010fb91b90e1688dc987652a105070ee2c5569b494b5aa9b0ace`.
The original missing-base/ext4 refusal receipts and the machine comparison's
pre-classification flags are unchanged. This source assessment does not rewrite
those historical outcomes or establish any of the five missing internal contracts.

## R13 static source follow-up: October 24, 2026

This appended assessment updates the pending Play statement above and preserves
all original machine/refusal/metadata receipts. The completed Google APIs r8
bounded nine-APK equality and source-aware APEX metadata assessment still
establish no new producer for the five private Passwords RPCs.

The normal offline Google Play r8 continuation completed across **all five
logical partitions and 240 APKs**. Its retained machine comparison is
`/tmp/cyc689-parity-r12-20261024/rpc-source-leads/play-r8-comparison.json`. The
separate `affiliations-play-r8-member-assessment.json` follows every recursive
difference for the nine audited GMS/base-module APKs: only
`META-INF/CERT.RSA`, `META-INF/CERT.SF` and `META-INF/MANIFEST.MF` were added.
The base's eight nested APK raw hashes change accordingly; their prior code,
resource and other recursively inventoried members remain unchanged. Different
verified certificates are real signing-authority/packaging differences. Neither
receipt claims whole-APK, runtime, all-Google-code or whole-SDK equality; the
comparison's 29 conservative leads retain their separately qualified assessment.

The extra **`com.google.android.gms.supervision`** APK is an actual new source
lead: `KidsSupervisionStub.apk`, 31,345,884 bytes, SHA-256
`3722d3ce4ea3243f16b128579141c6e7087ee174bc7d52b85a3a7706d9e086ec`,
with four DEX, three native libraries and 13 registry/metadata candidates. Names
and unmatched hashes do not prove ownership of any Passwords RPC. The independently
APPROVED `inspect-kids-supervision-producers.py` step pins the exact APK, comparison
and17-member allowlist, stages only its four DEX and 13 metadata entries, and may
run one normal bounded JADX pass. That producer-inspection step has **not run**;
its three native members remain explicit unresolved leads. There is no positive
or negative five-RPC producer result from it. The frozen plan and allowlist are
in the same R12 `rpc-source-leads` directory. No proprietary code is committed.

Separately, image31e1/build1792830218 completed unsigned at **4:11 AM PT** and
was superseded because factory Services43 lacks the two requested Wi-Fi/recovery
permissions. Genuine release51 inputs, product d8cc/build1792841434 and its new
launcher receipt passed source staging at **4:41:21 AM PT**. That is not RPC
evidence or a completed new image: remote plan transfer/rebuild is blocked after
VM termination and shared32/parallel64 `STOCKOUT`; the original shared32 is restored
stopped. R13 build/sign/offline helpers are reviewed, not executed. No new image
or device/provider acceptance is claimed. R13 preparation receipts live under
`/tmp/cyc689-parity-r13-20261024/`.

All five private RPC contracts still require a real producer plus verified
request/response and consumer authority. Provider, real cohort, family,
cross-device and physical native-fill acceptance remain pending; these static
results do not close them or establish full parity.
