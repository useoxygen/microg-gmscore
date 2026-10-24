# Android → Cyclon migration (CYC-712)

This draft is **not end-to-end Android switching parity**. Source review, host
protocol tests, production GMS Nearby interop and a full Switch copy are separate
gates. No physical phone or real eSIM transfer was used for A's validation.

Implemented on top of B#36 and C#37:

- Signature-bound, per-UID target76/direct-target211 Binder contracts. Target
  tx13/14/15/17 perform retained-secret UKEY2/HMAC/HKDF; missing secret10590,
  wrong proof10591, wrong state10593. Account transfer reports10573 and requires
  ordinary sign-in. Blockstore and PostSetup unsupported operations callback with
  errors instead of leaving a caller waiting.
- Legacy target bootstrap: classic Nearby NC_ID/PointToPoint, target-issued
  32-byte SharedSecret + sessionId in BootstrapOptions; source configuration
  feature4 acknowledges retention. Google uses this path separately from newer
  QuickStart request_wifi. Atomic, private per-UID no-backup state expires after
  Google's432000-second lifetime; clean tx23 clears it. No exported secret setter.
- Privileged setup entry
  `com.google.android.gms/org.microg.gms.migration.android.AndroidD2dBootstrapActivity`
  requires NETWORK_SETUP_WIZARD (debug DUMP). It creates a pairing, then opens
  Switch's Wi-Fi copy entry. **The setup host must launch this for a new Android
  pairing**: ACTION_LAUNCH_WIFI_D2D alone expects an existing retained session.
  The child Switch intent forwards its real result and extras to the setup host.
  Pairing does not imply Google accounts or app data were transferred.
- Classic Nearby200x routing remains separate from B's DCT300x path. Actual NSD
  LAN advertising/discovery, TCP, RFCOMM listeners/connections, regular FEF3 BLE
  advertisement/GATT slot control plane and explicit fast-UUID scan/advertising.
  Requested medium options are honored. A BLE random address is never invented
  into an RFCOMM address. Regular BLE→BT needs image-allowlisted LOCAL_MAC_ADDRESS.
- BYTES/FILE/STREAM payload callbacks, bounded FD streaming, auxiliary producer
  completion pipe, cancellation/error controls, client-death cleanup, deadlines
  and crypto sequence checks. FILE is announced before completion; its SUCCESS
  update gates consumption. BYTES uses a whole first DATA and separate empty LAST.
  Large BYTES uses field11 ParcelByteArray; FILE supplies readable content URI8;
  the optional producer status pipe uses0 for success and1 for failure.
- Peer-offered LAN, Wi-Fi Direct and hotspot bandwidth upgrade joins, preserving
  UKEY2 encryption/counters through introduction/ACK and LAST_WRITE/SAFE_TO_CLOSE.
  Failure before handoff retains the old medium; failure after handoff closes it.
  Transient networks and owned P2P groups are cleaned without tearing down a
  preexisting group. **Creating/offering target hotspot/WFD upgrades and BLE data
  sockets are still missing**; no complete physical radio parity is claimed.
- Local restore-only `com.google.android.gms/.backup.BackupTransportService`
  consumes C's finalized noBackupFilesDir/migration-staging artifacts. C helper
  registration→EOF→remote confirmation is integrated. The transport never becomes
  ordinary/cloud backup, deletes source artifacts, or bypasses framework policy,
  signing identity, version checks or app agents. C owns cable/media/Play installs,
  Google-account contacts defaults and eSIM. Its newest contacts defaults are kept.

Google account/FIDO/bootstrap credentials require authorized Google server calls
(gfui/get_bootstrap_challenges, gfuj/exchange_bootstrap_credentials,
gfuk/exchange_session_checkpoints). Offline code cannot mint them. No categorical
Google-signature/server rejection of Cyclon has been demonstrated. Local staged
BackupManager restore is a different path and need not use Google's servers.

Known deviations and unverified gates:

- Bootstrap account, work-profile, Folsom/Blockstore/eSIM payloads and unexpected
  saved-network provisioning fail honestly; no successful skipped transfer is
  fabricated. The legacy producer disables account-import and packet-mode
  capabilities it does not implement. v0 display-name advertising is Google's
  supported fallback; v1/OOB and Fast Pair bootstrap extensions remain missing.
- BLE control-plane codecs/GATT and RFCOMM compile; real-radio interoperability is
  unrun. Fast BLE cannot establish a standalone BLE data socket. Bluetooth inquiry
  name/discoverability handling is not implemented. Radios must already be on;
  no shared adapter rename/toggle is hidden in this implementation.
- Only the responder side of bandwidth upgrade is implemented. Target-created
  hotspot/P2P/LAN upgrade offers, network coexistence and real bandwidth acceptance
  remain open. Old/new socket handoff has independent localhost crypto tests.
- Legacy Android200x is implemented; arbitrary classic use of v3/300x is not.
  Mixing classic and DCT in an active client is rejected. Payload ACK/safe-disconnect
  v2 and multiplexed sockets are not advertised; legacy encrypted sequencing stays.
- The no-account SmartDevice bootstrap completed against Google's ordinary source
  Discovery UI, with matching code and persisted acknowledgement. It does not
  establish Switch's subsequent tx13/14/15/17 session or copy on an installed image.
  No full SmartDevice→Switch data copy, physical USB roles, genuine Google
  account transfer, or installed app-agent restore has passed. Contacts/SMS/MMS,
  call logs, media, Wi-Fi/settings and app installs still need per-class receipts.
  Google Messages/RCS custom app API is separate from ordinary SMS/MMS import.

Validation (all Mac Gradle work holds the shared lock):

```sh
lockf -k "$HOME/research/cyc700-switch/parity/build.lock" \
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew -PmigrationLab=true :migration-ukey2:test \
  :play-services-core:testMapboxDefaultDebugUnitTest --tests 'org.microg.gms.migration.*' \
  :migration-android-lab:assembleDebug :migration-restore-fixture:assembleDebug \
  :play-services-core:assembleMapboxDefaultDebug --no-configuration-cache --max-workers=2
python3 cyclon/tests/check-migration-apk.py play-services-core/build/outputs/apk/mapboxDefault/debug/com.google.android.gms-252432048.apk
```

The opt-in [lab](../migration-android-lab/README.md) uses a real installed GMS
process and our actual target classes in another Binder endpoint on a genuine
Google Play API35 emulator. Verified so far: broker54, normal non-exempt GMS
advertising, LAN discovery/connect, matching raw UKEY2 verification and both
acceptances, exact8193-byte BYTES and100003-byte STREAM payloads in both directions.
The STREAM FD/status and FILE extension has its own receipt; don't infer a full
Switch copy from these SDK fixtures. No signature/allowlist bypass was used.

The debug `org.microg.gms.migration.backup.MigrationRestoreProbe` passed on a real
Android runtime: hidden transport Binder/API, exact key/value/full-stream bytes,
caller FD ownership, source preservation, plus C helper pipe registration before
EOF and final publication after EOF+remote success consumed by A's transport.
This is not BackupManager registration or an app agent restoring its data.

The [app-data fixture](../migration-restore-fixture/README.md) must be seeded only
on the source. The destination must first report EMPTY, then restored bytes and
counter712 without being seeded. Installation alone is not app-data proof.

Exact private protocol evidence, current receipts and stock-phone script:
`~/research/cyc700-switch/parity/A-SPEC.md`, `A2-VALIDATION.json`,
`A-FIRST-ANDROID-TEST.md`. Do not upload decompiled Google code or private device
artifacts. Only the named dev7a after board handoff + scoped claim may be a physical
Cyclon target; parent authorization is required for every reboot/remount/overlay.
Verify counts, checksums, provider ownership and gallery visibility before deletion.
