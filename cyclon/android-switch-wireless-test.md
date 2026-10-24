# First iPhone wireless attempt (CYC-701 / CYC-702)

This is a development acceptance script, not a wireless acceptance claim. Use only
Pixel 7a **3A031JEHN04180** and an owner-authorized iPhone on iOS 26.3 or later.
Agent B's artifact identity and host results are in `parity/B-RESULTS.md` in the
private research folder. The source branch is `cyc700/dct-r2`, based on dd935664.
Do not mix an APK from another branch into this run without recording its SHA256.

## 0. Ownership and exact inputs

Read `~/research/cyc700-switch/parity/BOARD.md` and run:

```sh
phone-claim status 3A031JEHN04180
```

The parent posted "7a handed over" at 7:50 PM PT on October 23, but CYC-689 then
held the whole phone until at least 8:16 PM PT. Check current claims, coordinate
on the board and wait. **Ask the parent before any reboot/remount/verity change.**
The commands below are instructions for that approved run; they were not executed
by agent B while another chat owned the phone.

Inputs:

* KMS-signed agent-B `GmsCore.apk` from `~/research/cyc700-switch/parity/B-artifacts/`.
  Verify against that directory's `SHA256SUMS` before installing.
* Android Switch **1140078** base + xxhdpi split, already in the parent's
  `~/research/cyc700-switch/overlay/root/product/priv-app/CyclonAndroidSwitch/`.
  Base SHA256 `369a2ca3811a57121a91359381ba091338654bd6f22b2ae44d9bdaf7e46d59b0`;
  density SHA256 `ceec0a144efb4c5eac059f5a1739321bea63e8a2c382024293ab6a29968527e0`.
* The parent's tested `/product` priv-app permission overlay, including
  `CONNECTIVITY_USE_RESTRICTED_NETWORKS` for microG and the dedicated Switch
  grants/denials. Keep `PERSONAL_CONTEXT_WRITE_SETTINGS` denied and do not add
  a second competing Switch policy block. Preserve microG's DUMP denial.
* Installed FakeStore with the existing standard Integrity service. This stream
  does not silently replace FakeStore or change its attestation consent. Record
  its actual version. Its per-app opt-in and device-attestation setting must be
  owner-approved if a real challenge is to succeed. Disabled/missing provider is
  an expected honest 8018 failure, not permission to send a mock token.

Build/sign staging is separate from installation. Use the parent's documented
`cable-test/prepare.sh` method for the approved overlay, then replace its microG
with the signed B APK using `adb install -r`. The same package signature and
versionCode must be compatible. Do not use `-d`, clear app data or uninstall it.
After taking the claim below, the exact update command is:

```sh
cd "$HOME/research/cyc700-switch/parity/B-artifacts"
shasum -a 256 -c SHA256SUMS
adb -s 3A031JEHN04180 install -r "$PWD/GmsCore.apk"
```
The parent may instead put B's APK and its arm64 libraries into an isolated copy
of that overlay before installing it. Preserve a checksum-verified baseline.

Take a whole-phone claim **only** for approved overlay/reboots. After the overlay
is ready, use a package-scoped claim for the test:

```sh
phone-claim take 3A031JEHN04180 --for 'CYC-701 first authorized wireless attempt' \
  --uses com.google.android.gms,com.android.vending,com.google.android.apps.restore \
  --ttl 30m
# Keep the returned claim ID; release it when the run ends.
```

Stage replacement system APKs under a temporary **non-APK** filename, wait for
the copy/push command to finish, compare the full SHA256, then rename atomically.
Never reboot while an APK copy, install or package operation is still running.
Clear only the documented GMS/Switch package-cache entries when removing overlays.
Check account presence before and after each boot, and stop on any mismatch.

After `pm uninstall-system-updates com.google.android.gms`, **reboot before testing**
when switching to a system copy with different privileged permissions. A live
reversion is the suspected trigger for stale restricted-mode UID state; a package permission grant
alone does not prove that the kernel firewall admits the UID. The precise framework race is not yet reproduced. The failed parent
run had restricted networking enabled but no `RESTRICTED_MATCH` for the GMS UID;
the exact same APK passed PAKE negatives after boot with the system copy active.
Do not disable restricted mode or change the owner's egress policy to bypass it.

With the owned claim, inspect `dumpsys netpolicy` and
`dumpsys connectivity trafficcontroller`. Read **effective** blocked reasons;
`blocked=RESTRICTED_MODE|APP_BACKGROUND` alone is also present in healthy baseline
state. When the restricted chain is enabled and GMS is expected to be allowed,
its `sUidOwnerMap` row must contain `RESTRICTED_MATCH`. If that rule is absent,
stop protocol tests, retain the dumps, reboot and recheck. Never interpret an
unbounded old probe's silence as a PAKE rejection. Current debug probes use a
five-second TCP connect timeout before TLS wrapping and a five-second read timeout.
The checkin receiver also retires immediate connectivity callbacks and uses a
five-minute fallback instead of spinning on a policy-blocked network.

Check the actual installed versions, granted restricted-network permission,
Switch's privileged status, Wi-Fi Aware availability and baseline counts/settings.
Enable Bluetooth and Wi-Fi only with ownership of those settings and restore their
previous values afterward. Do not change the Google account or sync consent.

## 1. Optional pre-iPhone probes

The Mac peer uses **public synthetic** session `abc234` / password `def567`.
It cannot implement Apple's Bluetooth IPv6 networking or Wi-Fi Aware bridge.
It does exercise real platform PAKE against the pinned Mac BoringSSL peer through
an owned adb reverse route, plus actual provider success/failure if challenged.
See `tools/dct-peer/README.md` for build and host self-tests.

On the Mac, create a private two-line credentials file (`chmod 600`), then run
these two bounded foreground processes in separate terminals:

```sh
python3 cyclon/tools/dct-peer/http-peer.py --port 18080 --integrity skip
~/research/cyc700-switch/harness/pake-peer --server /private/path/credentials \
  127.0.0.1 18443 127.0.0.1 18080
```

With the package claim held:

```sh
adb -s 3A031JEHN04180 reverse tcp:18443 tcp:18443
adb -s 3A031JEHN04180 shell am start -n \
  com.google.android.gms/org.microg.gms.migration.MigrationLabActivity \
  --es mode peer --ei peer_port 18443
# Probe has a 70-second deadline; run only under the claim above.
adb -s 3A031JEHN04180 reverse --remove tcp:18443
```

The expected skip result explicitly names the synthetic Mac Apple fixture.
Repeat with `--integrity challenge` to exercise the real installed provider;
a token callback alone does not prove iOS will accept it. No fixture token is
passed off as a valid Google token or real Apple attestation.

Run the on-device rejection probe separately:

```sh
adb -s 3A031JEHN04180 shell am start -n \
  com.google.android.gms/org.microg.gms.migration.MigrationLabActivity \
  --es mode pake-negative
```

Expect wrong-password, wrong-server-identity and ordinary-TLS rejection. Return
Home after probes and verify the reserved reverse route has been removed.

## 2. Start the real flow on the 7a

Start a **private, filtered** log capture without clearing the shared log buffer:

```sh
adb -s 3A031JEHN04180 logcat -v threadtime \
  GmsDct:I GmsSwitchLab:I '*:S' > /private/path/wireless-stages.log
```

Device log timestamps are in the device timezone. Convert to Pacific time before
reporting them; preserve UTC only in internal queries. These tags omit credentials,
request bodies, hashes, salts and token contents. Avoid broad provider log uploads.

On a debug microG build:

```sh
adb -s 3A031JEHN04180 shell am start -n \
  com.google.android.gms/org.microg.gms.migration.MigrationWirelessActivity \
  --ez deferredSetup true --ez firstRun false
```

For a LineageOS SUW integration, launch
`com.google.android.gms/org.microg.gms.migration.MigrationWirelessSetup` from the
wizard with `NETWORK_SETUP_WIZARD`. It returns Switch's result to the wizard.
The main entry is in release source; the DUMP override and lab probes are debug
only. The already-gated `CyclonAndroidSwitchWirelessSetupOverlay` points to this
same setup alias. No Google internal service needs to be exported.

Approve the app's Nearby devices and precise Location prompts if shown. The build
requests NEARBY_WIFI_DEVICES, Bluetooth connect/advertise and location permissions.
GmsCore also provides Wi-Fi location, so its global Wi-Fi permission does not assert
neverForLocation. Denial ends the session. The screen should show a QR only after real FC73 advertisement success. It must
encode **`https://migrate.google/1/d#s=…&p=…`**. The values are session secrets;
do not paste them into tickets/logs or save an unprotected screenshot.

## 3. Thomas's iPhone steps

1. Keep the iPhone unlocked and nearby, with Bluetooth/Wi-Fi available. Disconnect
   the data cable; this is the wireless attempt.
2. Open **Settings → General → Transfer or Reset iPhone → Transfer to Android**.
3. Follow its prompt and scan the **7a's QR with the iPhone**. Do not use the old
   App Store "Switch to Android" QR/app flow.
4. Approve the local connection prompts. Wait for pairing, Integrity and Aware.
5. After Aware is authenticated, the 7a should automatically open Android Switch's
   new importer. It joins the retained cluster using PostSetup ConnectionHint9/10;
   no QR/password Intent extras are used. The intent is the recovered
   `ResumeWifiD2dRestore`/`MultiD2dMigrateFlowActivity` contract with quickstart,
   after-account, iOS-Wi-Fi and OS-migration flags. This bypasses the Google
   onboarding-task host; it does not claim full task-manager parity.
6. Choose only owner-authorized test data. For the first attempt prefer a small
   known album; do not choose contacts tied to a Google account until agent C's
   local-only/upload-consent behavior is integrated and verified.

## 4. Stage evidence and failure interpretation

Read these in order; never infer later success from an earlier stage:

| Stage log | What it establishes |
|---|---|
| `Bluetooth IPv6 reservation ready` | Restricted network/PSM reservation succeeded |
| `Bluetooth IPv6 network available` / link diagnostics | A network and reported link-local address/routes exist |
| `Scoped DCT service resolved; linkLocal=true scoped=true` | Session-filtered `_dct._tcp` resolved on that Bluetooth Network |
| `Integrity challenge sent` | PAKE/control + info/capabilities reached the integrity exchange |
| `Integrity peer requested token` or `peer selected skip` | Actual peer policy choice; skip is not a provider-failure fallback |
| `Integrity provider … stage=warmup/token status=N` | Installed provider's numeric response; zero is provider success only |
| `Integrity token sent; awaiting peer attestation and ACK` | Google token was sent, with no content logged |
| `Integrity Apple attestation received` / `exchange complete` | Required Apple variant/status and peer ACK were received |
| `Authenticated Wi-Fi Aware [publisher] channel ready` | Aware data path plus new PAKE HTTP traffic proved the upgrade |
| `Switch ResumeWifiD2dRestore importer launched` | The explicit importer launch was dispatched; confirm its actual UI |
| `Session closed status=…` | 8016 auth, 8017 capability, 8018 integrity, 8019 upgrade, 8012 endpoint I/O, 8005 cancel/disconnect |

If there are earlier `accept_ra` warnings, inspect the **actual live interface**
while this claim and connection are held:

```sh
adb -s 3A031JEHN04180 shell ip -6 addr show dev l2cap-tun0
adb -s 3A031JEHN04180 shell ip -6 route show table all dev l2cap-tun0
```

Require a usable link-local address and route, scoped mDNS resolution, and PAKE
HTTP traffic. Router-advertisement configuration warnings alone prove neither
failure nor success. A raw Mac CoC open alone does not verify IPv6 or mDNS.

If provider stage fails or times out, retain **8018** and the numeric provider
error. If the token was sent but no peer ACK follows, record that boundary;
Google project/package/device verdict acceptance may be the blocker. Never retry
using fake tokens or silently choose skip. R4 pairing is not implemented; this
build explicitly offers normal PSK with R4=false. A peer that requires pairing,
unsupported media or a publisher-role application request fails honestly.

## 5. Completion and cleanup

Verify counts, total bytes, selected checksum samples and gallery/provider
visibility **before deleting transferred data**. An importer launch or progress
screen is not evidence that any data class completed. Record cancellation/Back,
Switch process death, disappearance of DCT/Aware networks, and no retained
credentials on a fresh launch. Test reverse responses with the host tests before
repeating large files on a phone.

Close the UI, return Home, remove only this run's adb reverse route, stop only the
captured foreground logs/peers, and release the exact claim ID. Coordinate the
parent's approved baseline restore; do not remove an overlay without the known
scoped package-cache cleanup (`CyclonGmsCore-*` / `CyclonAndroidSwitch-*` entries).
Any cleanup reboot still needs the parent's OK. Restore the exact original APKs,
permissions and changed settings, and post the actual final device state on the
board. Do not mark CYC-701/702 complete while device/iPhone acceptance remains.
