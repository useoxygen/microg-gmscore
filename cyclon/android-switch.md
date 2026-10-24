# Android Switch development status (CYC-700 / CYC-701 / CYC-702)

Round 2 extends dd935664 for Android Switch1140078 and the independently recovered
GMS26.41.31 contracts. **No end-to-end iPhone wireless import is established.**
PR35/image PR1221 and the agent-B continuation remain draft; no release, image
pin, feed or OTA is part of this change.

## Implemented source

The existing certificate-bound account access, optional SmartDevice queries,
helper queries, SourceXos export, Nearby v3 Binder, FC73/Bluetooth IPv6 reservation,
scoped mDNS and platform SPAKE2+ TLS1.3 client remain the starting point. Setup
and authenticated Switch clients share one ephemeral cluster; PostSetup tx1
returns ConnectionHint9/10, or10590 when no real session exists.

Round 2 adds:

* A PAKE-only HTTP/1.1 server and normal-PSK Wi-Fi Aware publisher role, including
  peer/local-Network address checks, bounded workers/headers/bodies, chunked
  framing and notification retention until consumed-message ACK. An upgrade
  callback requires initial integrity completion **and actual new-link PAKE HTTP**.
* Incoming HTTP requests delivered through Nearby with separate requestId and
  payloadId; outgoing byte/file responses correlated to a pending request.
  File descriptors/URIs stream; metadata and per-part lengths are preserved.
  `ParcelableResponse` field6 is multipart batching, field1 is `isLast`, recovered
  from both GMS suppliers/stream writer and Switch's response producer. Batches
  are bounded and retained until sent/cancelled. No pending request gives8009.
* Incremental client-side multipart reception, distinguishing bytes from files,
  with last-batch flags and owned descriptors. Application `/communications` and
  `/file-content-ack` paths remain Switch-owned; `/_callback` is one-way. An HTTP
  transport receipt is separate from the application's file-content ACK.
* Bounded notification buffering across setup→Switch handoff; early server
  application requests wait for the accepted Switch payload listener. Back,
  timeout, cancellation, Binder death and the BACKUP-protected
  `OS_MIGRATION_CANCELLED` broadcast close the retained resources.
* A main-source NETWORK_SETUP_WIZARD-protected `MigrationWirelessSetup` alias and
  Activity, with a debug-only DUMP override for adb. QR appears after actual
  advertisement success and is `https://migrate.google/1/d#s=…&p=…`. After upgrade,
  the Activity uses the independently confirmed `ResumeWifiD2dRestore` contract:
  MultiD2dMigrateFlowActivity, quickstart/after-account/iOS-Wi-Fi/OS-migration flags.
  Switch reads the retained credentials through ConnectionHint; they are not
  copied into Intent extras. Results return to the Lineage wizard.
* Credential-free Integrity stage/provider-status logging, including actual
  peer-selected skip, token challenge, warmup/token result, Apple attestation and
  ACK. It still uses installed FakeStore's real standard Integrity/DroidGuard
  machinery and consent policy. Failure/timeouts remain8018; no token is invented.
* Native Mac BoringSSL PAKE TLS1.3 peer, public synthetic HTTP fixture and debug
  Android adb-reverse/negative-PAKE probes. Credentials never appear on the native
  peer's command line or logs. See `tools/dct-peer/README.md`.

## Explicit limits

* No real iPhone handshake, Integrity acceptance, Aware naming/role interop,
  link-local IPv6/mDNS runtime or setup/importer UI acceptance was performed here.
* R4 pairing is not implemented. Publisher explicitly reports R4=false. A valid
  ordinary passphrase remains eligible when a peer advertises R4 capability;
  this is not evidence that the actual iPhone accepts non-R4 negotiation. No
  six-character PAKE-password PMK is substituted for the independent Aware PSK.
* Other upgrade media, arbitrary outbound application requests while operating
  only as HTTP publisher, optional account/passkey/eSIM transfer and full Google
  onboarding-task-manager parity remain unsupported. The direct recovered import
  handoff is a deliberate Cyclon-owned route, pending actual runtime acceptance.
* Newer generic Phenotype operations and optional Checkin broker130 remain gaps
  in the broader inventory. The DCT controller's existing real checkin
  prerequisite is separate. Do not infer live Google flag or account parity.
* Host fixtures deliberately use synthetic Apple attestation and, in the host
  shape test only, a clearly synthetic token. They establish framing/crypto
  mechanics, never Apple/Google trust or real-peer acceptance.

## Validation and next run

Final verified host/artifact results are recorded in the private
`~/research/cyc700-switch/parity/B-RESULTS.md` and the continuation PR. The focused
suite includes real loopback HTTP server responses, byte/file multipart
continuation, metadata, cancellation, notification ACK/replay, handoff buffering,
null-GET/empty-POST behavior and importer Intent arguments. Host JVM HTTP tests
remain deliberately distinct from Android PAKE/radio acceptance.

The first actual iPhone test is specified step by step in
[android-switch-wireless-test.md](android-switch-wireless-test.md), including exact
APK/overlay identity, claim/reboot boundaries, iPhone Settings steps, filtered
Integrity logs, and live `l2cap-tun0` address/route/mDNS checks despite accept_ra
warnings. Device ownership must be checked at run time; no other phone may be
substituted. Verify imported data before cleanup and restore the parent's exact
baseline afterward.
