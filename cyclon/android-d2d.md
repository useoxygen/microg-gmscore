# Android → Cyclon migration (CYC-712)

This development branch is **not end-to-end Android switching parity**. Do not
ship or describe it as a completed cable/wireless Android transfer.

Implemented:

- Signature-bound, per-UID SmartDevice target76 and direct-target211 contracts;
  exact known callback shapes; no-shared-secret10590; account start10573.
  PostSetup190 tx9 completes with unavailable10590/result0 instead of hanging. The
  existing optional iPhone setup-state and retained DCT hint remain separate.
- Google's upstream Apache-2.0 UKEY2 Java library, pinned with provenance, and
  its unchanged tests/proto schemas. Protobuf-lite generated source is not checked in.
- Bounded nested SmartDevice pipe negotiation; postsetup UKEY2/HMAC/HKDF; classic
  Nearby OfflineFrame connection acceptance/encryption and ordered payload sinks.
- Local **restore-only** `com.google.android.gms/.backup.BackupTransportService`.
  It consumes finalized CYC-714 artifacts in `noBackupFilesDir/migration-staging`.
  Real token + @pm@ are required. It preserves bytes and evidence, delegates app
  policy/signatures/agents to BackupManager, and refuses normal/cloud backup writes.
  It never selects itself as the ordinary backup transport.
- Compile-only hidden Android backup API signatures. These must never appear in
  an APK. Runtime APIs, transport registration and real restores need device proof.

Outstanding:

- Initial stock Android bootstrap/session creation and user verification.
- Classic Nearby radio/discovery/Binder routing, FD streams, full cancellation and
  bandwidth upgrade. `NearbyDctService` is not a classic Nearby implementation.
- Google server account-assertion/credential exchange; explicit sign-in fallback.
- Integration with CYC-714 helper tx6 observer/results, two-phase artifact commit
  and image `config_backup_transport_whitelist` in its separate draft PRs.
- Every real source→Switch→destination data-class acceptance check.

Research and exact source evidence: private
`~/research/cyc700-switch/parity/A-SPEC.md`, current inventory `D-GAPS.md`.
Do not commit/upload decompiled Google code or private phone evidence.

Validation (hold the shared Mac build lock):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME="$HOME/Library/Android/sdk" \
  lockf -k "$HOME/research/cyc700-switch/parity/build.lock" \
  ./gradlew :migration-ukey2:test \
  :play-services-core:testMapboxDefaultDebugUnitTest \
  --tests 'org.microg.gms.migration.*' \
  :play-services-core:assembleMapboxDefaultDebug --no-configuration-cache --max-workers=2
python3 cyclon/tests/check-migration-apk.py play-services-core/build/outputs/apk/mapboxDefault/debug/com.google.android.gms-252432048.apk
```

Host tests use real localhost TCP plus independent upstream UKEY2 peers. They do
not validate stock-GMS interoperability, Bluetooth, USB host/device role switching,
Wi-Fi Direct, Google server account acceptance, or BackupManager restoring real apps.

Physical target: only the owned dev7a after board handoff and proper phone claim.
Reboot/remount/overlay changes require the parent's authorization. Verify transferred
counts/checksums/provider visibility before deleting artifacts.

A genuine Google Play Android15/API35 ARM64 source AVD was booted and successfully
updated to production GMS26.41.31/264131035. The normal source quickstart UI opens;
no peer bootstrap/export was established. Emulated LAN/TCP is a protocol stand-in;
physical USB and Bluetooth/Wi-Fi Direct acceptance need a stock Android phone.

For exact real-Android transport byte/FD proof, the debug APK includes
`org.microg.gms.migration.backup.MigrationRestoreProbe`. Push the APK to a unique
shell-owned read-only path, then run with `CLASSPATH=<apk> app_process /system/bin
org.microg.gms.migration.backup.MigrationRestoreProbe
/data/local/tmp/cyc712-restore-probe-<unique-id>`. It refuses existing evidence
folders. It touches only that fresh fixture folder and does not install microG,
change the selected backup transport or access a running package's private data.
This does not exercise BackupManager registration, real agents or app policy.

Validated on this branch:67 focused migration host tests +25 unchanged upstream
UKEY2 tests passed; debug APK assembled;6 notice-generator tests passed. APK class
inspection confirms the hidden API stubs are absent and relocated protobuf is
present. The debug probe passed on a genuine GooglePlay Android15/API35 emulator:
real hidden Binder/API shape, exact key/value and multi-chunk full-stream bytes,
caller FD ownership and source-artifact preservation. No microG target installation,
BackupManager agent restore or real source→Cyclon transfer was performed.
