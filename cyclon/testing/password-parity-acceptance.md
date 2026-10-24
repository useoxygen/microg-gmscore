# Native password parity acceptance drivers

Part of CYC-689, CYC-690 and CYC-691. This is a runbook and debug-only native
instrumentation. The latest gathered Services runtime is `e2246006`: 799 host
tests and six bridge tests passed, and the Services 049 product and
instrumentation APKs built. The earlier 604-test result at `dece944d` is historical.
The companion Core/Shell full JVM run passed 3,169 tests at compatibility source
`bc551b8f5`. The 194 Python passes combine discovery and six repaired modules;
they are not one clean full-suite run. The later focused 16-scenario regression
passed at composite `3d71613ce`, carrying Core PR source `8ec5d0630`; it does not
represent a repeat of all 3,169 JVM tests at that source.
The Google-account selection, family and recovery acceptance drivers compiled
but have not been executed; no Google-account or family round trip is accepted.

On the owned API36 AOSP image, all 22 ordinary Android fixture cases passed at
the earlier baseline and again with the factory/updated Services 049 setup.
The second result consists of 21 cases plus the remaining case selected with its
corrected class selector. The native callback probe passed four boolean checks;
the protected picker launched and owner cancellation passed. An earlier
operation-cancel case returned UNKNOWN with the native field empty. A previous
selected-row attempt returned REFUSED with an empty field and no journal entry;
the controller-readiness handoff was repaired before R9.

The Core 28 APK uses compatibility source `bc551b8f5`, carrying the CYC-677
`5042e184` base plus the password-fill fixes. The current controller composite is
`3d71613ce`, carrying Core PR source `8ec5d0630`. The earlier `271d62003` was a
source revision, not a PR number.

R9's local native fill succeeded: the Core operation reported succeeded,
effect applied and `filled=true`; the receiving fixture reported
`matchesSyntheticSeed=true` and `empty=false`. All four framework-only receiver
probe booleans were true. Subsequent Core observation and screenshot requests
were refused with `native_password_private`. Input using the old pre-fill
observation was refused with `stale_observation`; this is stale-observation
proof, not direct proof of the native input fence.

The exact manual root controller restarted from PID 7370 to 7863 with the same
JAR checksum `3314967007`. The receiving fixture stayed at PID 6323, retained its
matching/nonempty booleans, and a fresh Core UI-open request was still refused
with `native_password_private`. This proves the recorded local receiving-process
fence survived that controller restart. The exact local synthetic row was deleted
through the native manager; the temporary probe and instrumentation were uninstalled,
owned sessions/controller were stopped, and the owned emulator was shut down. Account
count remained zero and the shared SDK system-image checksum stayed unchanged.
These local-image results do not establish physical-device, Cyclon init-domain
SELinux or Google-provider parity.

The parent owns device discovery, reservation, artifact signing, installation,
provider calls and results. The owner previously released the Pixel 7a for this
chat. The earlier October 23 baseline was Services `cf93b15`, Core code 26
`cyc677-ident`, and an active image init controller. That installed Core APK
payload matched the clean CYC-677 build at `5042e184`. A separate acceptance build
combined that source with password-fill head `5d5551a90`; preserve the active image
components before any transition. `c07be00` is the other chat's CYC-700 whole-device
claim ID, not a Core source revision. The last claim read, October 23 at 7:24 PM
PT, reserved the physical Pixel 7a through 7:43 PM PT. Refresh the claim and actual
installed-component evidence before any physical-device action; expiry alone
does not establish a fresh reservation. No candidate installation from this
gathered batch has run on that physical phone. Read-only wireless preflight at 7:22 PM PT matched the known hardware serial and
found Core26 unchanged but Services048 at source label `dd93566`, a different APK
from this chat's prior048 fixture. Preserve that active source before an update.
The earlier manual-controller baseline is stale. The connected Pixel 8a is a release/user Cyclon Services
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

Core's existing `android/Core/dev/passwordprobe/README.md` at companion source
`75613fc4a` supplies the executable API34+ local-credential native fill fixture,
boolean-only status provider and privacy/Stop/controller-restart matrix. Its
`android/Core/dev/updates.md` protects the selected target, signer, version,
privileged image origin and update authority. Preserve its full controller/image
component requirements; do not replace a live controller or treat an APK-only
update as acceptance.

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
