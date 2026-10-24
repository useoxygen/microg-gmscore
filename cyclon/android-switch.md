# Android Switch development status (CYC-700 / CYC-701 / CYC-702)

Development implementation for Android Switch 1140078 and the independently
recovered GMS 26.41.31 contracts. **This branch does not yet establish a working
end-to-end iPhone import.** Keep PR35 and image PR1221 draft. No release, feed,
product pin or OTA change belongs here.

## Implemented source

- Certificate-bound ACCOUNT discovery, optional Target21/PostSetup7 queries,
  migrate-helper v1 absent-artifact queries and empty Wi-Fi export, as before.
  SourceXos is export; it is not advertised as an import producer.
- Broker54 now routes to a per-UID Nearby Binder instead of DummyService. DCT
  3001/3002/3005, stop, accept/reject, send, disconnect/all and cancellation have
  bounded SafeParcel readers, exact raw-int result callbacks and DCT lifecycle,
  payload and bandwidth callback schemas. Outbound discovery scans FC73; a
  discovered endpoint retains its Bluetooth address/PSM for a client-role request.
- A target controller reserves Bluetooth IPv6/6LoWPAN, advertises FC73 after
  reservation, resolves only its session's `_dct._tcp` on that Network, preserves
  link-local scope (including interface scope when NSD omits it), and creates a
  platform SPAKE2+ TLS1.3 HTTP client. No certificate-TLS fallback exists.
- Reconstructed protobuf schema, `/controls`, separate `/capabilities`, independent
  `/notifications` polling and consumed-message acknowledgments. Control waits are
  bounded. QR/password/token/application bodies are never logged.
- Real checkin prerequisite and Integrity/DroidGuard path through the installed
  microG vending standard Integrity service, including warmup, project/hash,
  token and ACK. Provider opt-in remains effective. Peer-selected skip is distinct
  from provider failure: failure/timeout returns8018; no token or attestation
  success is fabricated. Remote iOS status1 and Apple variant are required.
- Normal PSK Wi-Fi Aware subscriber upgrade using the authenticated peer's
  independent passphrase, generic encoded service name, scoped IPv6/peer port and
  a successful PAKE control response before bandwidth-success notification.
  Initial-link failures do not tear down a proven upgraded connection.
- Client-role application requests preserve null-body GET versus empty-body POST,
  custom headers, URI, requestId and a separate payloadId. Bytes use bounded
  file-backed ParcelByteArray; file responses stream into unlinked owned files.
  Multipart uses exact Content-Length, metadata and boundaries, with file/count,
  total-size and disk-space limits. Calls cancel by payloadId; Binder death
  releases the terminal client's resources. Application ACK remains Switch-owned.
- Internal non-exported TargetQuickStart tx15/16/17/18/20 and PostSetup tx1
  ConnectionHint9/10 share one ephemeral session with authenticated Switch clients.
  No retained session gives10590; unsupported account transfer gives13/null.
  Quickstart callbacks are synchronous; Nearby callbacks are oneway.
- A debug-only `MigrationWirelessActivity` (DUMP-protected) displays the QR only
  after real advertisement success. `MigrationWirelessSetup` aliases it under
  NETWORK_SETUP_WIZARD for the separately gated image hook. Both are absent from
  release APKs. Back/cancel closes the session; recreation reuses it. Pending
  setup sessions expire after ten minutes; an accepted Switch owner removes that
  expiry and its last Binder death closes the cluster.

## Explicit gaps (CYC-701 / CYC-702)

- **No reverse HTTP hosting/publisher role yet.** Incoming medium-host requests
  are declined. Sending byte/file response subtypes2/3 without a pending incoming
  server request returns8009. Client-side byte/file reception is implemented.
- Full streaming/continuation semantics of ParcelableResponse field6 remain
  unresolved. There is no invented interpretation or successful placeholder.
- R4 pairing is explicitly rejected8019. Other upgrade media are unsupported.
- Google's target import onboarding task launch is still unrecovered. The debug
  flow creates a real retained connection/QR; it does not claim that opening the
  legacy Wi-Fi activity launches the new importer. The SetupWizard hook is a
  draft entry into this debug flow, not a completed setup-to-Switch launch.
- Real Binder call sequences, peer HTTP edge cases, Aware naming/roles, PAKE
  interoperability, token policy and transfer are unverified on an iPhone.
  Host green is not a wireless acceptance verdict.

## Host validation

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME="$HOME/Library/Android/sdk" \
  ./gradlew :play-services-core:testMapboxDefaultDebugUnitTest \
  --tests 'org.microg.gms.migration.*' \
  --tests org.microg.gms.auth.AndroidSwitchAccountAccessTest \
  :play-services-core:assembleMapboxDefaultDebug --no-configuration-cache
```

Tests include exact control/capability bytes; token failure/skip/wrong-attestation
and missing ACK; malformed parcel bounds and UID ownership; real localhost HTTP
control/polling/ACK; null/empty body semantics; independent request/payload IDs;
FD byte responses; multipart exact bytes/metadata; and real in-flight HTTP
cancellation. Localhost tests deliberately use a test-only HTTP adapter: the Mac
JVM lacks Android's PAKE provider, so they are **not** host PAKE/TLS acceptance.

## Device evidence and next authorized run

This continuation ran **no adb commands**, touched no phone and performed no
KMS delivery signing, remount or reboot. The parent owns dev7a `3A031JEHN04180` for its cable
test; wait for the parent to free it. Do not substitute another device.

Earlier evidence, sourced from the owner's2026-10-23 5:16 PM PT comment on
cyclon#1221: a priv-app overlay granted restricted networks and MANAGE_USB; a Mac
DctPeer opened CoC and `l2cap-tun0` appeared. It also logged accept_ra failures.
Earlier signed native PAKE loopback passed at3:35:05 PM PT. These results predate
the new controller and do not test it.

For the first authorized wireless run:

1. Claim only the dev7a packages/settings used. Coordinate any reboot with the
   parent. Use its approved overlay and exact snapshots; beware stale
   CyclonGmsCore/CyclonAndroidSwitch package_cache entries on overlay removal.
2. Sign the final debug APK through the prescribed KMS/VM path. Validate the
   single Switch permission policy, including PERSONAL_CONTEXT_WRITE_SETTINGS
   denial and absence of conflicting factory grants.
3. Inspect l2cap-tun0 link-local addresses/routes and scoped mDNS after iPhone
   connection. The accept_ra warnings alone establish neither failure nor success
   of link-local traffic. Capture credential-free GmsDct stage/status logs.
4. Use an authorized iOS26.3+ iPhone in Settings → Transfer to Android. Trigger
   `am start -n com.google.android.gms/org.microg.gms.migration.MigrationWirelessActivity`
   only after the parent frees the phone. The screen contains ephemeral pairing
   secrets; keep screenshots/logs private.
5. Establish PAKE, actual challenge/skip/token/ACK and normal Aware roles/name/port.
   If Integrity fails, retain8018 and the provider's redacted reason. Current iOS
   may reject microG's identity/project/verdict even when it returns a token.
6. Resolve the importer onboarding launch and complete reverse/continuation paths
   before calling this a full transfer. Test cancellation, Back, binder death,
   large files, wrong password/peer and physical-device cleanup separately.

Final host result for this continuation: 44 focused tests passed with no skips;
`assembleMapboxDefaultDebug` succeeded. Image branch:9 integration tests passed;
Pixel suite54 tests/18 environment skips; branding43 tests/17 environment skips.
No AOSP image, new KMS-signed delivery, installed controller or iPhone acceptance
was run. These are source/host gates only.
