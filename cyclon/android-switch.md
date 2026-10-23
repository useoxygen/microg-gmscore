# Android Switch development status (CYC-700)

This is an incomplete implementation for Android Switch 1140078. It does not yet
provide an iPhone transfer or a functioning Nearby v3 service. No release, feed
or product pin change is part of this branch.

## Implemented

- Account discovery for the exact Switch package and its two verified Google
  signer generations. This grants ACCOUNT discovery only; password/token/OWNER
  operations remain denied.
- Optional Target21/PostSetup7 setup-state queries, with an empty Bundle through
  callback6. The nullable ApiMetadata envelope includes its separate magic word.
- Version1 migrate-helper broker and absent-staging queries. Operations needing
  real restore data fail explicitly. Wi-Fi export returns an empty network list.
  SourceXos is declared but its unimplemented export features are not advertised.
- Separate FC73 advertisement codec/owner, Android Bluetooth IPv6 reservation,
  scoped NSD resolution, platform PAKE TLS1.3 contexts, scoped HTTP requests,
  streaming multipart file bodies, bounded FD-backed byte payload reads and a
  normal PSK Wi-Fi Aware data-path primitive. These are not wired to a controller.
- A debug-only DUMP-protected probe activity for native PAKE loopback, bounded
  radio mechanics, and a session-only QR. Release APKs omit this entry point.
- A bounded macOS CoreBluetooth advertisement/CoC harness in `tools/dct-peer`.
  It cannot substitute for the iPhone IPv6/PAKE/control/Aware implementation.

The actual DCT link is Bluetooth IPv6/6LoWPAN, not a raw CoC carrying TLS. Android
then acts as TLS client/prover using platform `SPAKE2PLUS_PRERELEASE`, context
`os-migration`, empty client identity and the session as server identity. There
is no certificate-TLS fallback. Public Nearby's C++ DCT advertisement differs
from current GMS migration and is not used as a compatible implementation.

## Verified on the owned 7a, 2026-10-23 PT

The signed debug build based on 0b0ce2c was sideloaded over a backed-up microG
252432044/cf93b15. Fork main already includes the Google-prompt changes.

- 3:34–3:35 PM: Switch account query returned count1, migrate-helper connected
  version1, and the corrected non-null ApiMetadata query returned empty target
  setup state. Password/OWNER access remained denied.
- 3:35:05.891 PM: `PASS platform PAKE TLS1.3 loopback`.
- 3:36:09.415 PM: Bluetooth IPv6 reservation failed with SecurityException.
  The installed image lacks `CONNECTIVITY_USE_RESTRICTED_NETWORKS`; normal
  `CHANGE_NETWORK_STATE` is insufficient because this request is restricted.
  The image experiment in cyclon PR1221 adds the grant through the existing
  single GmsCore allowlist, with DUMP still denied. No permission bypass,
  remount, reboot or flash was used.
- USB still reaches UI_AWAITING_USB and requires privileged MANAGE_USB. User-app
  installation cannot validate that path. The QR screen displays a prototype
  session and explicitly starts no radio or data flow.

Private evidence is under `~/research/cyc700-switch/evidence/`, especially
`batched-usb-logcat.txt`, `batched-probes-logcat.txt`, `session-qr.png`, and the
host test logs. No Google APKs or decompiled code belong in this repository.

## Remaining work

- CYC-701: actual Nearby v3 Binder/session/controller, control/capability and
  notification negotiation, payload callbacks/cancellation/death cleanup,
  negative PAKE cases, real scoped link and Aware peer tests, and R4 handling.
- CYC-702: target quickstart and authenticated session handoff to Switch.
  SourceXos is the export API. The current QR is not a Switch launch path.
  OEM gRPC requires the configured package plus its signing history; Cyclon
  SetupWizard cannot use the shipped Samsung allowlist entry.
- CYC-703: AOSP image/privapp/signer inventory and SetupWizard Back/Skip checks,
  then real iPhone cable and wireless acceptance. Current iOS Play Integrity
  challenge/token/ACK enforcement remains an explicit peer-test gate.

## Host checks

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :play-services-core:testMapboxDefaultDebugUnitTest \
  --tests 'org.microg.gms.migration.*' \
  --tests org.microg.gms.auth.AndroidSwitchAccountAccessTest \
  --no-configuration-cache
```

The 24 focused tests cover account identity boundaries, real Binder envelope
shapes, advertisement bytes, session encoding, Aware bitmap conversion, scoped
HTTP request rules, exact multipart bytes/stream lengths and FD byte envelopes.
Native PAKE is a device probe because the host JVM lacks Android's PAKE provider.
