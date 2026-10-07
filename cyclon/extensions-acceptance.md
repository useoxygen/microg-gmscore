# Cyclon app compatibility, geofencing and Calendar sync

This round adds per-app troubleshooting, explicit failed callbacks for unsupported
location APIs, app-scoped geofencing, and opt-in Google Calendar downloads. Calendar
sync uses the native account consent flow and the `calendar.readonly` OAuth scope.
Only GET requests are issued; local edits are never uploaded. Downloaded calendars
are read-only. Existing dirty event rows are preserved and reported as conflicts.
Turning sync off cancels scheduling and retains downloaded data.

Calendar sync keeps recurring masters, moved/cancelled exceptions, timezones,
all-day dates and popup reminders in Android's Calendar Provider. Every page is
fetched before pruning or committing a new sync token. Expired tokens trigger a
complete replacement fetch before reconciliation. A removed remote calendar is
hidden while all its local events are retained. Failed requests and unreadable
provider state cannot be interpreted as an empty calendar.
Response pages are bounded to 16 MiB, calendar lists to 200 entries, and a single
calendar snapshot to 10,000 events/16 MiB of event text. Exceeding a bound fails
without pruning or committing a new token; exceptionally large calendars need a
staging implementation before those limits can be raised safely.

Geofencing supports circle registration, replacement, removal by IDs or pending
intent, ENTER/EXIT/DWELL, initial triggers, expiration and same-boot process recovery.
Registration requires the owner's fine/background location permission and mutable,
owner-created pending intent. Revoked permission removes registrations. Limits are
100 fences and five pending intents per app, plus 500 fences overall. Requests use
the existing fused manager, share one request per owner, release it after the last
fence, and sample at a bounded interval. Accuracy uncertainty does not invent a
boundary crossing. Apps must register again after reboot, as on Google Play services.

Per-app troubleshooting reads only the owner-selected package and existing recorded
push state. Permission/configuration findings remain separate from real delivery,
position acquisition and sign-in. Recovery opens native settings; opening the page
does not register, reconnect, sign in or request a position. The shared health
report adds Calendar status but still contains only enums and numeric timestamps.

## Repeatable checks

Run host regression tests with the pinned location-source APK:

```sh
./gradlew -Pcyclon.locationSourceApk=/absolute/path/upstream.apk \
  :calendar-sync:test :people-sync:test \
  :play-services-location-core:testDefaultDebugUnitTest \
  :play-services-core:testMapboxDefaultDebugUnitTest
```

Build the Android fixtures:

```sh
./gradlew -Pcyclon.locationSourceApk=/absolute/path/upstream.apk \
  :calendar-sync-android:assembleDebugAndroidTest \
  :play-services-location-core:assembleDefaultDebugAndroidTest
```

Open a dedicated AOSP emulator in T3's Device panel. Keep it offline; do not add a
Google account. Use its exact serial below. The runner refuses physical devices,
checks the reserved fixture package names, refuses to replace installed fixtures,
and retains SHA-256 receipts from both local and installed bytes. It installs and
removes only the two synthetic fixture apps. The Calendar fixture creates/cleans
its own UUID account. The location fixture uses only a synthetic GPS provider and
removes it after testing. No product app is reinstalled by this runner.

```sh
python3 cyclon/run-extension-fixtures.py --serial emulator-5558 \
  --calendar-apk calendar-sync-android/build/outputs/apk/androidTest/debug/calendar-sync-android-debug-androidTest.apk \
  --location-apk play-services-location/core/build/outputs/apk/androidTest/default/debug/play-services-location-core-default-debug-androidTest.apk \
  --aapt /absolute/path/android-sdk/build-tools/35.0.0/aapt \
  --output /absolute/path/outside-git/extension-fixtures
```

The Android tests exercise Calendar Provider recurrence expansion, cancelled
exceptions, idempotency, dirty/unowned row protections, opt-out, and calendar
removal/restoration. They also exercise the actual location service/Binder API,
pending-intent ENTER/DWELL/EXIT delivery, removal and failed unsupported callbacks.
Host tests cover pagination failures, invalid token recovery, repeated cursors,
consent withdrawal, timezone mapping and transition/health precedence.

## Dedicated signed-device acceptance before OTA

Retain an exact source pin, dedicated-signed APK hash/version, installed-byte
readback, device serial and result receipt for every scenario. Use only a designated
test account and synthetic calendars. The following remain separate from host and
offline emulator evidence:

- A real selected app receives a notification, acquires a position, and completes
  its Google sign-in flow. Repeat network changes, paused push, permission denial,
  disabled/suspended app, and account authorization loss. Troubleshooting must
  report configuration/observations without claiming actual success.
- Calendar authorization succeeds with the readonly scope. Verify pagination,
  all-day/DST events, moved/cancelled occurrences, reminders, remote changes and
  deletions, two accounts with identical remote event IDs, expired token recovery,
  permission loss, paused Android sync, offline retry and opt-out during a sync.
  Check that no local edits or unrelated calendars were overwritten/deleted.
- Geofence delivery survives client unbinding, app backgrounding, process recovery,
  Doze and network changes. Check caller isolation, permission revocation, cancelled
  pending intents, expiration, limits and replacement. Measure idle/battery use and
  transition latency outdoors with actual fixes; the synthetic GPS fixture cannot
  establish those properties.
- A no-wipe upgrade preserves accounts, location provider choice, sync opt-ins and
  app registrations. Off services remain Off. Reboot recovery follows each API's
  native semantics; clients re-register geofences after reboot.

CI builds Core/Companion, runs the host regressions, checks notices/version inputs,
and runs lint. A green PR is source validation; product pins, signing, OTA and these
live acceptance receipts are separate delivery steps.
