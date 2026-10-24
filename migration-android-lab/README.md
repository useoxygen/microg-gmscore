# Opt-in production GMS classic Nearby interoperability lab

Not included in regular builds/images. Enable with `-PmigrationLab=true`.
The lab compiles an explicit audited subset of the same migration classes used
by GmsCore; it does not install/replace GMS, claim Google signatures, bypass its
SDK policy, or create a fake SmartDevice session.

On an **owned** Google Play Android emulator, with real Google-signed GMS and
Wi-Fi enabled, after the required device-panel open + scoped phone-claim:

```sh
lockf -k "$HOME/research/cyc700-switch/parity/build.lock" \
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew -PmigrationLab=true :migration-android-lab:assembleDebug \
  --no-configuration-cache --max-workers=2
adb -s AUTHORIZED_SERIAL install -r -t migration-android-lab/build/outputs/apk/debug/migration-android-lab-debug.apk
adb -s AUTHORIZED_SERIAL shell am start -W -n org.microg.migration.lab/.ProbeActivity
adb -s AUTHORIZED_SERIAL shell run-as org.microg.migration.lab cat files/nearby-result.txt
```

The real GMS process advertises with normal non-exempt SDK options. Our target
performs mDNS discovery/TCP, classic Binder callbacks, UKEY2 acceptance after raw
token comparison, and byte-exact bidirectional BYTES/STREAM/FILE fixtures. The
result remains RUNNING until every expected payload matches, then PASS or FAIL.
No personal data or accounts are involved. Never print the raw authentication
tokens. `app_process` cannot substitute for an installed registered SDK client.

This is a real production GMS wire peer on one device, **not** a stock
SmartDevice bootstrap, Android Switch copy, installed Cyclon target, physical
USB/BLE/WFD/hotspot, or BackupManager app restore test.
