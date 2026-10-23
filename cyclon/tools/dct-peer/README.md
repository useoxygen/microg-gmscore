# Bounded macOS DCT test peer

Build outside the checkout:

```sh
cyclon/tools/dct-peer/build.sh "$HOME/research/cyc700-switch/evidence/DctPeer.app"
```

This uses macOS CoreBluetooth directly. Public Nearby's Apple layer has working
L2CAP wrappers, but its C++ advertisement format does not match current GMS
migration and its Wi-Fi Aware implementation is iOS-only. This harness validates
only the migration advertisement and optionally CoC open/close. It cannot act as
an iPhone IPv6/PAKE/HTTP/Aware peer. No unrelated discovered devices are printed.

On the owned 7a with the signed **debug** microG APK, run the bounded radio probe:

```sh
adb -s 3A031JEHN04180 shell am start -n \
  com.google.android.gms/org.microg.gms.migration.MigrationLabActivity \
  --es mode ble --es test_session abc234
```

Run the Mac peer concurrently; it exits after 25 seconds. The phone probe closes
its advertisement/network reservation after 15 seconds or when its Activity is
destroyed. This does not toggle Bluetooth or change the default network. If
Bluetooth is off or privacy permission is unavailable, retain BLOCKED status.

```sh
"$HOME/research/cyc700-switch/evidence/DctPeer.app/Contents/MacOS/DctPeer" \
  --session abc234 --connect
```

macOS may require the owner to allow Bluetooth access. A successful FC73 match
proves byte-level discovery; CoC open/close proves only that channel. It does not
prove IPv6, mDNS, PAKE, Play Integrity, Wi-Fi Aware or Android Switch transfer.
Return Home after each probe. Do not run this against other phones.

The current 7a image blocks the real `ble` reservation because microG lacks
`CONNECTIVITY_USE_RESTRICTED_NETWORKS`. The image-side experiment adds that grant;
do not attempt a remount or a runtime privilege bypass. A separate
`--es mode raw-ble --es test_session abc234` probe advertises the same FC73 bytes
over a raw insecure CoC solely to isolate advertisement/CoC mechanics. It is
bounded by Activity destruction after 15 seconds and is not a migration channel.

Other debug-only probe modes: omit `mode` for bounded local PAKE TLS1.3 loopback;
`--es mode session` displays the current-format QR and explicitly labels it as a
session-only prototype with no active radio/data flow. The activity requires
DUMP at its exported entry, refuses `user` OS builds, and is absent from release
APKs. Credentials are not written to logs or persisted.
