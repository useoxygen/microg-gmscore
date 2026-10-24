# Native password parity acceptance drivers

Part of CYC-689, CYC-690 and CYC-691. This is a runbook and debug-only native
instrumentation. Implementation, host proof, installation and acceptance are separate.

## Latest gathered result: R11, October 24, 2026

Services `3c9f9eef` / test `c2a1eb33` passed **878 host tests** (434 password-sync,
13 FIDO Core, 372 Services Core, 42 people, 17 calendar), zero failures/errors/skips,
all four Services/vending Debug/Release lint tasks with zero errors, and both APK
builds. Primary count 819 excludes people/calendar. Existing warnings remain;
minSdk19, permissions/runtime enforcement and the no-baseline policy are unchanged.
The source fixes preserve strict Base64, genuine R2 availability, full pre-30 Wi-Fi
copies and explicit forwarding of the two Java default callbacks caught by regression.

Signed product `dc642be89cdf2c04e1accffb95509d5982ef68d439cf544fb2833e1ae7b666c1`
(132,356,981 bytes) is installed and verified. Matching signed test
`154c06a97abcb9623b11762f5ddf3253064e98b08208d6a36e84d71a0a927625`
(2,113,688 bytes) ran and was removed. Expected certificates and all **3,286/85**
non-META-INF entries match tested unsigned payloads. The first streamed installation
timed out; old hash/bytes and no active sessions were reconciled before a fresh
explicit no-streaming attempt succeeded. No unknown-outcome automatic replay occurred.

The physical selection passed **26/26**: 22 ordinary methods plus four flags-authority
regressions, no failures/skips. Separate cached inventory passed with one stable
candidate and zero readable/approved/sharing/blocked/unreadable counts; this is not
peer-absence proof. Flags admission refused before provider calls/enablement; no
Google assignment was accepted. Keep those outcomes separate rather than reporting
an all-passing admission batch. The exact R10 physical/test sources below remain
historical; these R11 results apply to the new signed product/test pair.

A separate chat reported a GMS remove/reinstall at **11:44 PM PT on October 23**, with UID `10143` → `10237` and all GMS local accounts/check-in/push/settings/credential-provider data erased; a same-label account was readded at 11:54 PM PT. This preceded R11. Its account-label reuse does not establish stable cloud identity, and no GMS-data or provider-state continuity from R10 is claimed. R11 inventory zeros describe post-reset local readiness, not cloud-password absence or proof about a second account. The owner/five-setting comparisons below apply only within R11.

Core `0ba6bb459` merges main `d0be4887a`. Clean **3,175 JVM** (20 protocol,
1,988 runtime, 111 Core, 1,056 Shell), **196 Core Python in 567.827s**, 85 Shell
Python, two ShellUi Python, 21 coverage and ledger checks passed. Selected hosted
CI is green; skipped Android lanes rely on that separate manual run. Complete
Kotlin2.2.21 controller SHA-256 is
`9f63cd0d2c48e5c0f9df57df352fd02829466a2afaf31f918c50350ea0cc2701`.
Core32/r5 is signed (`8b7eaa2ddda01fba8e3d52b7e68f5547e6d604fa34b1c445406578a6680f46af`,
29,706,896 bytes), all 136 payload entries identical; guarded read-only update
preflight passed. **Core32 and its matching controller were not installed or accepted.**

Donor full-lynx `selinux_policy` compilation passed in **7m32s**, with normal
neverallows and no ignore/`-N` waiver. The exact a348 TE SHA-256
`fede8ef656e794df3f9a5440cb8e38ce968610724b33a3f53001fbe6287eba20` is unchanged
at 0ba. Product CIL `aeae3df449020a6b1170f3070995e8ef19f72ccc2f28f8317cfe52553ba37822`
contains both direct finds; combined policy is
`c4dc0a44c13d4d8cde95f49d1c6fbaba7f1f82a450207de844446a052fe30cbd`.
Earlier mismatch names were samples; exact archived-baseline review found **31**
donor differences. The 1,246-pin lynx manifest, signed target-files fingerprint and
controller were verified; 36 generation/SHA-pinned bundles cover all 30 missing
pins and base objects are available. This advances build preparation only: no
matching image was assembled/accepted and no physical policy overlay was applied.
Never flash Akita artifacts on lynx or equate APK/JAR changes with image grants.

Final **12:21:36 AM PT** readback retained new Services and verified unchanged
Core31/original controller `3b32...`, owner181/SHA`6ff4...`, five raw settings,
R11 boot, adbd2000, enforcing SELinux/verity and absent test packages. There was
**no R11 Core/controller/policy change or native-fill attempt**. All three scoped
claims (`c6d133b`, `c21ca12`, `a50b6b5`) were released; claim status is empty.
Future paired-component/device work requires fresh ownership and component checks.
PR1166 merged `335c8a47` on October 23 at 11:57 PM PT; broad CYC-651 is still open.
Five RPC contracts, real cohorts, provider/official-client, family, cross-device
and physical Core-fill acceptance remain open.

Receipts under `/tmp/cyc689-parity-r11-20261023`: `services-gathered-results.json`,
`verified-artifacts.json`, `android-gathered-results.json`, `device-after.json`.
The donor compilation receipt remains
`/tmp/cyc654-parallel/cyc680-policy-result-a348f600b.json`.

## Preserved R10 gathered and physical receipt: late October 23, 2026

The following records the R10 handoff snapshot. It is not a current device
readback or acceptance of the newer R11 artifacts.

Services runtime `97f5` passed 809 host tests (434 password-sync, 13 FIDO Core,
362 Services Core) and both APK builds. Matching test source `97f5` passed the 22
physical Android fixtures. Later test-only source `0c376a85` supplies the separately
recorded flags-selection checks. Cached peer inventory passed with zero enabled/readable
candidates; this does not establish absence of eligible accounts or peers. Four
flags-selection regressions passed, but the one cached-only account admission
failed before any HTTP or vault enablement. Real server assignments remain unaccepted.

Native framework save to **On this device** and exact local cleanup passed: the
first cleanup deleted one exact synthetic row, and the recreated row's final
cleanup also deleted one, with unrelated local entries unchanged. The exact row
is absent now. Physical Core fill commands were dispatched, but native PREPARE
refused at fixed stage `service_lookup`, `precondition_failed`, effect `none`;
no picker or credential delivery followed. This does not supersede R9's owned-
emulator-only positive fill and receiving-process fence/restart scope.

Core code `de4af35bd` (documentation head `a348f600b`) adds staged diagnostics,
explicit live-controller `native_password_v1` admission, bounded capability
discovery and source-only `trust_service` /
`notification_service` lookup grants. Protocol 3 alone does not establish native
fill support. The installed CIL has direct user/power/window lookup grants and
no explicit trust/notification grants; broad missing-service logs were not scoped
to the controller PID and are supporting clues only. No physical policy overlay
changed, and there is no matching image policy build or acceptance. APK/JAR-only
updates cannot install the required image policy.

The public dependency proof was read offline from the official API 37 Google APIs
ARM64 SDK image, logical system path `/system/framework/framework.jar`, extracted
SHA-256 `cfd8c422620cf584b5f190d8e5a211b83468a5ffd2bdb5cad1e0e410a22f30d8`.
Recovered `KeyguardManager` fields eagerly call `getServiceOrThrow` for trust and
notification services (local extracted lines 81/82), before its constructor body
at line 293; `isDeviceLocked` calls TrustManager at line 333. Whole-class JADX had
one error; do not describe the full class as cleanly decompiled. No system-image
hash was measured and none is inferred from the extracted JAR hash.

Clean compatibility composite `77ee09217` preserves installed Core 29 baseline
`70ab` / #1231. Earlier full Python validation passed 195 cases in 430.108 seconds.
The gathered JVM run had 3,168 passes and one stale `ui.collect` description-fixture
failure out of 3,169; regenerated compatibility `61bc` then passed the normal
focused case, with no floor waiver. This is not a repeated clean full-JVM run.
The two Python capability cases passed (45 native / five Java-producer scenarios).
The final focused run passed 12 runtime cases and the normal catalog fixture,
and the Core 31 release APK built. Probe cases cover hung connections, overlapping
requests, endpoint replacement, interruption and refusal of late positive results.

Core 31 `0.17.0-cyc689-password-parity-r4` was signed and installed, APK SHA-256
`6569ae37c5d9cc22e63fda39dcdbb394c5c6bc0c2dba5676711f890a2e82b0a4`.
All 136 non-META-INF entries match the tested unsigned APK. Against the restored
original controller, the default catalog omits native fill and the all-tools row
reports `missing_grant` only for `ui_controller:native_password_v1`. Owner status
and the previously failed operation receipt are unchanged. This is positive
legacy-controller refusal acceptance; physical native fill remains unaccepted.

The original image controller SHA-256
`3b32bfed839417edd052f3248b3d21284e03e65eedb2cbad5f934e60265d0457`, enforcing
verity, enforcing SELinux and adbd UID 2000 are restored and verified. Five raw
settings match; the exact synthetic row is absent. The password probe, Services
test APK, Core-status fixture and our CorePump directory were removed and absence
verified. Core 31 remains installed. All of this chat's phone claims are released;
the final scoped claim was released at 11:04 PM PT.

All broader Google-provider/official-client, family, cross-device, five-private-RPC
and actual server-assignment acceptance remains open.

## Historical receipts through the 8:15 PM PT handoff

The earlier password PR runtime was `e2246006`: 799 host
tests and six bridge tests passed, and the Services 049 product and
instrumentation APKs built. The earlier 604-test result at `dece944d` is historical.
Historical PR source `c22687a5` added only the local-probe cleanup instrumentation;
it does not change that runtime or its 799-test result.
The companion Core/Shell full JVM run passed 3,169 tests at compatibility source
`bc551b8f5`. The 194 Python passes combine discovery and six repaired modules;
they are not one clean full-suite run. The later focused 16-scenario regression
passed at composite `3d71613ce`, carrying Core PR source `8ec5d0630`; it does not
represent a repeat of all 3,169 JVM tests at that source.
The Google-account selection, family and recovery acceptance drivers compiled
but have not been executed; no Google-account or family round trip is accepted.

The separate CYC-700 compatibility runtime `5af4d461` merges password source
`73f8c33f` with exact CYC-700 source `dd935664`. It passed 843 host tests
(434 password-sync, 13 FIDO Core, 396 Services Core) and both APK builds. Its
signed Services 049 product SHA-256 is
`bb89205f4bf531ff1a9774da05b55d33918d16dbeb1e077a9f15422c61af8e93`.
The cleanup fixture at test source `068c7924`, signed SHA-256
`8b1dff4319bf8a842c24247fcec04e9099f936e4a12b6b31e258bdb90a9f7e96`,
passed the 22 ordinary Android cases and the local-probe absence method with
that product on the owned API36 image. Core native fill was not rerun against
this combined runtime. The earlier R9 fill receipt below applies to `e2246006`.

Before the CYC-700 merge, all 22 ordinary Android fixture cases passed on the
owned API36 AOSP image at the earlier baseline and again with the factory/updated
`e2246006` Services 049 setup.
The second result consists of 21 cases plus the remaining case selected with its
corrected class selector. The native callback probe passed four boolean checks;
the protected picker launched and owner cancellation passed. An earlier
operation-cancel case returned UNKNOWN with the native field empty. A previous
selected-row attempt returned REFUSED with an empty field and no journal entry;
the controller-readiness handoff was repaired before R9.

The Core 28 APK uses compatibility source `bc551b8f5`, carrying the CYC-677
`5042e184` base plus the password-fill fixes, preserving the #1207 baseline.
The R9 controller composite was `3d71613ce`, carrying Core PR source `8ec5d0630`.
The earlier `271d62003` was a
source revision, not a PR number.

R9's local native fill succeeded: the Core operation reported succeeded,
effect applied and `filled=true`; the receiving fixture reported
`matchesSyntheticSeed=true` and `empty=false`. All four framework-only receiver
probe booleans were true. Subsequent Core observation and screenshot requests
were refused with `native_password_private`. Input using the old pre-fill
observation was refused with `stale_observation`; this is stale-observation
proof, not direct proof of the native input fence.

The exact manual root controller restarted from PID 7370 to 7863 with the same
JAR SHA-256 prefix `3314967007`. The receiving fixture stayed at PID 6323, retained its
matching/nonempty booleans, and a fresh Core UI-open request was still refused
with `native_password_private`. This proves the recorded local receiving-process
fence survived that controller restart. The exact local synthetic row was deleted
through the native manager; the temporary probe and instrumentation were uninstalled,
owned sessions/controller were stopped, and the owned emulator was shut down. Account
count remained zero and the shared SDK system-image checksum stayed unchanged.
These R9 local-image results alone do not establish physical-device, Cyclon init-domain
SELinux or Google-provider parity.

The parent owns device discovery, reservation, artifact signing, installation,
provider calls and results. The earlier physical batch finished and reservations
`c48761a` / `ac092ee` were released on October 23 at 8:15 PM PT. This chat holds
no continuing phone claim from that handoff; the other chat then had the next 30-minute priority window.
Refresh ownership and installed-component evidence before any further action.

The physical Pixel retained Core 28 and Services 049 at handoff; neither app was
downgraded. Core passed its strict installation preflight and package postflight;
its APK SHA-256 is
`a93a3aebc0bd096e8ef4ad96c5b69b3979c9534f30a4d9c25d461921c75c4ad7`.
Installed Services uses runtime `e2246006`, signed APK SHA-256
`2b3c079dd635f20fa53950ff2a23bbe181e285145ed6f73df76ae88d676245a0`.
The Core owner-status file remained exactly 181 bytes, SHA-256
`6ff41c6b9ec43e2a7cac181a1a76d67635241e11a3b2667101b0570f431b5e81`,
through installation and reboots.

All 22 ordinary Android fixtures and both unique
`PasswordLocalProbeCleanupAndroidTest` methods (absence and cleanup) passed on
the physical phone. The signed cleanup test APK at source `c22687a5` has SHA-256
`b718ac5a3bd427cacae31a2399aaf5fd25ce3891a1a717753bdfb4358e42563b`.
The first absence attempt was interrupted by an overlapping 22-case launch and
its instrumentation process crashed; a separate standalone rerun passed. That
interrupted attempt is not an additional passing case.

The framework save UI opened, but the native helper could not find the
“On this device” label. It selected nothing and cancelled. No synthetic local
credential was created, no physical Core fill was dispatched, and no physical
fill success is claimed. Cleanup reported `exactLocalProbeDeletedCount=0`,
confirmed absence and unchanged unrelated local credential bytes; this is not
positive-deletion proof. No Google-provider/account or family drivers ran.

For the controller procedure, verity was temporarily disabled and the candidate
SHA-256 `3314967007706066830610a74780a979bb1af9e977e5db411f3663387903a87b`
was installed at `/system_ext/framework/cyclon-ui-controller.jar` with root
ownership, mode 0644 and `u:object_r:system_file:s0`. It persisted across reboot
and ran through the actual init service in `u:r:cyclon_ui_controller:s0`.
Global SELinux was Enforcing; VM analysis of the kernel policy also verified
that this domain was not permissive. This is controller-domain evidence without
a physical fill or privacy-fence acceptance claim.

Handoff restored the original image controller SHA-256
`3b32bfed839417edd052f3248b3d21284e03e65eedb2cbad5f934e60265d0457`,
its mode/context, enforcing verity and a reboot; adbd returned to UID 2000.
The password probe, test APK, Core status probe and this chat's temporary remote
JAR directory were removed. Preferred service was returned to None;
`credential_service` was restored empty and `credential_service_primary` to
`null`, matching their original observations. `autofill_service` ended at
`null`; its original raw value was not captured, so only the original preferred
None UI observation is available for that setting. The authoritative local
handoff receipt is `/tmp/cyc654-device-batch/physical-transition.json`.

Historical physical evidence remains separate: the earlier October 23 baseline
was Services `cf93b15`, Core 26 `cyc677-ident` / CYC-677 source `5042e184` and the
image controller. An earlier acceptance build combined that source with
password-fill head `5d5551a90`. The 7:22 PM PT wireless read found CYC-700
Services 048 labeled `dd93566`; the other chat subsequently restored Services
044 before this physical batch. The combined CYC-700 049 build was therefore
not installed on that restored phone. `c07be00` was the other chat's earlier
CYC-700 claim ID, not a Core source revision. The earlier manual-controller
baseline is historical. The connected Pixel 8a is a release/user Cyclon Services
phone with four accounts that do not match the approved primary test account,
and has no installed Chrome. It is not an approved second test account/device
or an official GPM peer merely because it is connected.

## Native selection, with labels only on the host

The debug APK contains a private `PasswordAcceptanceAccountActivity`. Release
products do not contain it. A host chooses a random run UUID and role label;
actual account names and stable Google IDs stay in an encrypted no-backup store
on the phone. The receipt requires the same installed account, stable Gaia ID,
boot, run/role and a 30-minute monotonic lifetime. It cannot adopt a removed and
re-added same-name account. Debug journals use a separate encrypted root so
normal passkey cleanup cannot erase interrupted synthetic cleanup evidence.

Primary selection automatically recognizes exactly one already-enabled local
vault containing the existing approved synthetic realm
`https://cyc651.example.invalid/` and username `cyc651-synthetic-user` with a
nonempty password. It does not make a network request, enable sync, initialize a
vault or choose a different account. Ambiguous selection uses the secure native
picker. A family role automatically recognizes only another approved fixture;
otherwise the owner explicitly selects a synthetic test account in that picker.
An eligible family member is not automatically assumed to be a synthetic account.

Use the target and instrumentation component verified for the matching signed
artifacts. These commands are templates to run only in the reserved window:

```sh
ACCEPTANCE_RUN="$(python3 -c 'import uuid; print(uuid.uuid4())')"
ACCEPTANCE_TEST_COMPONENT=com.google.android.gms.test/androidx.test.runner.AndroidJUnitRunner
ACCEPTANCE_TEST_CLASS=org.microg.gms.passwords.PasswordParityAcceptanceAndroidTest

adb -s "$ACCEPTANCE_SERIAL" shell am instrument -w -r \
  -e targetProcess com.google.android.gms:ui \
  -e class "$ACCEPTANCE_TEST_CLASS#selectApprovedSyntheticAccountEntirelyOnPhone" \
  -e passwordAcceptance cyc689-native-selection \
  -e passwordAcceptanceRun "$ACCEPTANCE_RUN" \
  -e passwordAcceptanceRole primary "$ACCEPTANCE_TEST_COMPONENT"
```

Keep the same run UUID for subsequent commands. Re-run selection if its receipt
expires. A fallback picker accepts only a private, one-use in-process request;
blocking vault/file reads and saves run off the UI thread, with fresh owner and
generation checks before publication/result. There are no mutable account extras or exported selection component. All status
fields below are booleans or counts. The tests catch native failures without
printing account names, tokens, credential bytes or remote response bodies.

## Discover actual prerequisites before mutation

Run each named readiness method with the same target process and run UUID:

| Method | `passwordAcceptance` | What it actually reads |
| --- | --- | --- |
| `readActualFamilyEligibilityAndNativeRecipientMatchingWithoutSending` | `cyc690-readiness` | Real family recipients/current sharing keys; eligible, installed and approved-fixture recipient counts; pending invitation count. Sends nothing. |
| `readActualSyncedPasskeyAndRecoveryReadinessWithoutEnrollmentOrSigning` | `cyc691-readiness` | Fresh real passkey snapshot, current `hw_protected` availability, secure-lock state, dedicated Vault API configuration presence, RECOVER_KEYSTORE grant and reflection API visibility. Enrolls/signs nothing. |

For example, substitute the desired method and acceptance flag in:

```sh
adb -s "$ACCEPTANCE_SERIAL" shell am instrument -w -r \
  -e targetProcess com.google.android.gms:ui \
  -e class "$ACCEPTANCE_TEST_CLASS#readActualFamilyEligibilityAndNativeRecipientMatchingWithoutSending" \
  -e passwordAcceptance cyc690-readiness \
  -e passwordAcceptanceRun "$ACCEPTANCE_RUN" "$ACCEPTANCE_TEST_COMPONENT"
```

Configuration presence, reflection visibility, account counts and a successful
GET are readiness evidence. They do not prove backend admission for a mutation,
OS recovery execution, passkey signing or restoration. Do not copy another
service's API key. Affiliation and Vault require their dedicated configuration;
only its presence boolean may be reported. The candidate/image must retain the
actual required grants, including the privileged READ_WIFI_CREDENTIAL image grant
for Wi-Fi and RECOVER_KEYSTORE plus package-specific hidden-API access for OS
recovery. The current target-29 Wi-Fi caller also needs owner-granted precise
location, enabled Location, and allowed precise-location/Wi-Fi-scan app-ops.
NETWORK_SETTINGS is signature-only and cannot be supplied by a privapp allowlist;
it is not a required substitute for this location path. An APK update cannot
provide the privileged image grants or the controller JAR.

## Real two-account family round trip

This requires two actual synthetic Google accounts in the same family, both
already opted into password sync, with authenticated metadata and sharing keys.
They may be on the same reserved phone. The native family list must identify the
second selected account's exact Gaia ID and current eligible recipient key.
No account or family is created by the harness. If no suitable account exists,
Google's genuine account/family setup is a remaining resource prerequisite.
Never select one of the Pixel 8a's unknown accounts as a substitute.

Select role `family` using the selection command above. The picker stays native
when the second account has no existing approved fixture. Then invoke:

```sh
adb -s "$ACCEPTANCE_SERIAL" shell am instrument -w -r \
  -e targetProcess com.google.android.gms:ui \
  -e class "$ACCEPTANCE_TEST_CLASS#realFamilySendAutomaticReceiveAndExactSyntheticCleanup" \
  -e passwordAcceptance cyc690-family-roundtrip \
  -e passwordAcceptanceRun "$ACCEPTANCE_RUN" "$ACCEPTANCE_TEST_COMPONENT"
```

The driver refuses preexisting pending mutation/sharing journals or initial
receiver invitations. Use dedicated synthetic accounts and an exclusive window
with no concurrent invitations or credential edits: the real automatic receiver
processes its account batch. The test:

1. Binds both current vault birthdays and checkpoints unrelated record digests
   natively before effects. It clones only the approved synthetic credential into
   a unique per-run synthetic identity, leaving the approved original untouched.
2. Commits that source copy through the real repository, refreshes family
   eligibility/key and invokes the actual native sharing send. The production
   launch journal must acknowledge it.
3. Uses the actual automatic receiver; requires its COMPLETE receipt for that
   invitation GUID, exact five-field identity/password readback and invitation
   acknowledgement. A server ACK alone does not satisfy native import.
4. Compare-and-deletes only those exact two introduced rows, requires complete
   absence/no pending changes, and verifies every preexisting credential unchanged.

A failure retains the encrypted cleanup checkpoint. Re-running the start method
refuses it; it never repeats an uncertain send. Invoke `finishApprovedIncomingAndCleanupAfterInterruptionWithoutResending` with
`passwordAcceptance=cyc690-finish-receive-cleanup` for bounded recovery. Before
send, it can cancel only a durably QUEUED clone creation or delete a positively
identified committed clone; launched/uncertain creates remain fenced. If a process
died after send acknowledgement but before saving the caller receipt, recovery
requires exactly one COMPLETE sender journal matching the saved parent, tag,
recipient and full source digest. Unknown/ambiguous sends remain pending and are
never replayed. Known incoming/cleanup can then finish. Absence requires both no
pending mutation and no authenticated committed record, even when an optimistic
DELETE already hides a row. Do not clear journals, force a retry, or delete
possible future delivery evidence.

This is a real new-import/ack/cleanup case. It does not by itself accept duplicate,
conflict, invalid-sibling, notification or same-sender-update matrices. Those
retain their separate source/host proof until genuine native cases are run.

## Existing executable native checks

`PasswordNativeSyncAndroidTest` and `PasswordOfficialClientAndroidTest` now accept
`passwordAcceptanceRun` instead of an account-name argument. Keep their existing
explicit case/mutation opt-ins and select individual methods. Their legacy
account-name arguments remain compatible; new runs should use the native handle.
The ordinary suite does not perform these live calls without its opt-ins.
The official-client phone-local comparison remains documented in
[official-password-reader.md](official-password-reader.md). A local self-read is
not the independent official-client comparison.

Core's `android/Core/dev/passwordprobe/README.md` at historical companion source
`75613fc4a` supplies the executable API34+ local-credential native fill fixture,
boolean-only status provider and privacy/Stop/controller-restart matrix. Its
`android/Core/dev/updates.md` protects the selected target, signer, version,
privileged image origin and update authority. Preserve its full controller/image
component requirements; do not replace a live controller or treat an APK-only
update as acceptance. The latest explicit capability and matching-image policy
requirements above also apply; the old source reference is not current acceptance.

## Cross-device passkeys and restoration

CYC-691 needs an explicitly approved second device and the same actual synthetic
account, plus a genuine RP transaction. This source adds a native readiness GET;
it does not create an unverified RP or call raw `vault.create`/`withSecret` and
label that Credential Manager interoperability. Required connected paths are:

- First device: actual Credential Manager or permitted Chrome/legacy/hybrid
  registration at a real HTTPS RP, selecting the native synthetic account.
- Second device: genuine same-account key restoration with private OS/PIN/Google
  owner verification, followed by actual RP authentication using the recovered
  synced credential. Compare actual server-verified login and public registration
  identity natively; never copy keys, cookies, passwords, RAPT or app data.
- Exercise wrong-account/domain/epoch, cancellation, rotation and replay refusal
  separately. Do not reset an account-wide domain or mutate personal credentials.

A stock official-client peer could provide stronger independent interoperability,
but no such peer is currently designated. A second Cyclon device must have a
safe signer/image setup and the actual approved test account; connected hardware
alone does not meet those requirements. An owned native RP also requires actual
caller certificate/Digital Asset Links proof. A caller-supplied website string
or a fabricated Chrome identity never supplies that authority.

## Gathered host validation

The parent should gather the changed sources and run the full suites and both
APKs once:

```sh
./gradlew :password-sync:test :play-services-fido-core:testDebugUnitTest \
  :play-services-core:testMapboxDefaultDebugUnitTest \
  :play-services-core:assembleMapboxDefaultDebug \
  :play-services-core:assembleMapboxDefaultDebugAndroidTest --continue
```

Record source/fixture hashes, actual target labels, signing/image gates, which
native methods ran and their boolean/count receipts. Keep implemented,
host-tested, built, installed and device/provider accepted as separate states.
