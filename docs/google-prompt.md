# Google sign-in prompts ("Is it you?") on Cyclon

Tracking: [useoxygen/cyclon#842](https://github.com/useoxygen/cyclon/issues/842). Research basis: the 2026-10-16 interoperability report for #842 (kept outside this repository). Its evidence labels are used here:

- **PUBLIC**: stated by Google's or Chromium's open source code; may apply to Android prompts only partly.
- **HISTORICAL**: static reading of a public decompilation of Play services 20.15.15 (2020). It is not a current specification.
- **UNKNOWN**: needs an authorized test on a device.
- **DESIGN**: a Cyclon choice, not a claim about Google.

Status: implemented and host-tested. Nothing in this document has been built into an image, installed, or accepted on a device. The native route was never run against Google.

## Two routes

| Route | What it is | State in this fork |
|---|---|---|
| GNOTS web route (upstream microG #2866/#2990) | A GNOTS push from sender `745476177629` with `gcmb=notifications` names a notification. GmsCore fetches it and shows a notification. Tapping it opens Google's own confirmation page, and the owner decides there. | **On by default** (stage A). Settings → Accounts → "Receive two-step verification prompts". |
| Native AuthZen route | A SecureGcm message in app-data key `P` (PUBLIC: sender `745476177629`, protocol `gcmV1`, version 1). It is verified and decrypted with the account's symmetric `authzen` key, answered on a native screen, and the reply goes to CryptAuth. | **Foundation only, off.** It cannot handle a real prompt. See "Gated" below. |

These are two separate routes. If the web route works, that does not show the native route works, and the reverse is also true. Accept and label each one separately.

## Implemented (stage B, package `org.microg.gms.auth.authzen`)

| File | What it does |
|---|---|
| `AuthZenCrypto.kt` | A Kotlin port of Google's open source SecureMessage and SecureGcm code (`github.com/google/ukey2` @10fc737a, Apache-2.0). It covers HKDF-SHA256 subkeys (`SIG:1`, `ENC:2`), HMAC-SHA256 and ECDSA-P256 signatures, AES-256-CBC, and the 20-byte header binding tag. Verification runs on the exact `header_and_body` bytes as received, which are never reserialized, and happens before decryption. Every failure gives the same exception. RSA is refused, and NONE is refused where encryption is required. P-256 points are checked to lie on the curve. Google's four test vectors pass, and the builder reproduces both HMAC vectors byte for byte. |
| `play-services-core-proto/.../cryptauth/securemessage.proto` | Adds Google's SecureMessage envelope messages. The existing public key messages are unchanged. |
| `play-services-core-proto/.../authzen/securegcm.proto` | Google's `Type` enum and `GcmMetadata`, nothing else. It contains **no transaction schema**. |
| `AuthZenEnvelope.kt` | Sorts each message as GNOTS, SecureGcm or other, using transport fields only. GNOTS always wins, so the web route is unaffected. A SecureGcm message is refused for a wrong sender, more than 64 KiB, an empty payload or bad Base64. |
| `AuthZenKeyStore.kt` | A versioned registry for the symmetric `authzen` bundle: generation counter, enrollment journal (staged, sent, committed), active and decrypt-only keys, server deactivate and delete, and per-account clear. It is stored in `noBackupFilesDir/authzen` under the shared cross-process CryptAuth file locks. The registry never reads or writes the `PublicKey` file of the Workspace proof. |
| `AuthZenProtocol.kt` | The decoder framework. `AuthZenProfiles.verified` is **empty**, so every request and cancellation is reported as `no_verified_profile`. Unknown types, versions other than 1, and any content a profile does not fully support are rejected. Cyclon's own bounds are always enforced (DESIGN): transaction id of 1 to 64 bytes, lifetime up to 15 minutes, 2 to 9 distinct numbers with the correct number among them, display text bounded and free of control characters, and the original bytes kept unchanged. |
| `AuthZenReducer.kt`, `AuthZenRepository.kt` | A pure state machine (`VERIFIED_PENDING`, `PRESENTED`, `AUTHENTICATING`, `DECISION_AUTHORIZED`, `SENDING`, `APPROVED_CONFIRMED`, `DENIED_CONFIRMED`, `CANCELED`, `EXPIRED`, `UNSUPPORTED`, `OUTCOME_UNKNOWN`) and a durable store with one atomic read-reduce-write per action. It handles: deduplication by account, key generation and transaction id; conflicts, which fail closed; cancellation that arrives before its request; expiry from the server lifetime anchored to the monotonic clock, with a local cap and a wall-clock check for late delivery; a reboot ends pending requests, and a send in progress becomes unknown; at most 3 pending requests per account and 10 per device; tombstones kept for 24 hours. |
| `AuthZenService.kt` | Holds the main components. `AuthZenIngress` runs envelope, key lookup, verification, decoding, storage and the notification, with an alert limit of 3 per account and 10 per device per minute. `AuthZenConsent` is the owner's side. `AuthZenSender` makes the final checks at the send boundary, stores `SENDING` before the network call, signs and encrypts the reply with `PublicKey` plus the `authzen` key, and sends it once. |
| `AuthZenApi.kt` | Fixed endpoints and scope; push data never chooses either. It refreshes the token once after a 401. 403, other refusals and redirects (never followed) end as rejected. 429 respects Retry-After. 5xx, a timeout after sending and an unparsable 200 all count as unknown. HTTP is behind a `AuthZenTransport` interface; tests use a fake. The production transport is never constructed while no profile is verified. |
| `AuthZenNotifier` (in `AuthZenAndroid.kt`) | Its own high-importance channel `cyclon_authzen` with generic text ("Google sign-in request / Open to review", "Unlock to review" on the lock screen). It has no action buttons. Its PendingIntent is immutable and explicit and carries only an opaque random handle. Blocked notifications are reported, never worked around. |
| `ui/AuthZenActivity.kt` | Not exported. `FLAG_SECURE` is set before any content is drawn, and recents screenshots are off. It shows only the stored record for a validated handle. While the phone is locked it shows only "Unlock to review". Decision buttons filter touches through overlays and cannot be pressed twice. The screen closes when it goes to the background. |
| `ui/OwnerAuthentication.kt` | `OwnerAuthGate`, using the system BiometricPrompt with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` and confirmation required (Android 11+). Each Yes needs a new prompt. With no secure lock, Yes is unavailable. |
| `AuthZenDiagnostics.kt` | Logcat tag `GmsAuthZen`. Each line has a stable event name, and details can only be booleans, enum values, counts or size buckets. |
| `AuthZenFeature.kt` | The feature flag, lab capture, and readiness reasons. |

What consent guarantees (host-tested, `AuthZenFlowTest`):

- Receiving, opening, rendering, resuming or reopening a request never decides anything.
- Yes needs the right number when the request has numbers, then a new owner authentication tied to one attempt id, the request digest and the key generation. A cancelled, failed, old or repeated callback authorizes nothing.
- A wrong number ends the attempt as `MISMATCH`, with no retry.
- No (`DENY`) needs no authentication. Dismissing is no answer at all.
- Before sending, the code checks again that the account exists, the owner has opted in, the key generation is current and the request has not expired or been cancelled.
- An uncertain send is final (`OUTCOME_UNKNOWN`) and is never repeated.
- A 200 response that the profile does not recognize is not shown as approved.

## Gated: what is off and why

1. **Feature flag, off.** The native route runs only in a build made with `-Pcyclon.authzenNative=true` (`BuildConfig.CYCLON_AUTHZEN_NATIVE`). Nothing can switch it at run time: no setting, Intent or broadcast reads or writes it. In the default build a `P` message produces only `event=ingress` and `event=readiness reason=feature_off`.
2. **Owner opt-in.** The flagged build also needs the owner's two-step setting to be on (`owner_opted_out` otherwise).
3. **Protocol not verified.** No profile is registered, so the route reports `protocol_not_verified` and the screen says "Native prompt protocol not yet verified on device. Nothing was sent to Google." The HISTORICAL 20.15.15 field map (transaction request tags, number list with the correct number first, outcome numbers such as `APPROVE_MISMATCHED=200`) is **not** implemented as a profile. It is only a starting point for comparing against captured traffic.
4. **No `authzen` key.** Enrollment of the symmetric bundle is **not implemented**. Its current key type, derivation, handle convention and AUTHZEN feature metadata are UNKNOWN. Nothing calls `AuthZenKeyRegistry.stage()`.
5. **Not changed:** the Workspace screen-lock proof. `CryptAuthRequests.kt`, `CryptAuthKeys.kt`, `CryptAuthFlow.kt` and `CryptAuthAttempts.kt` are untouched by stage B. The request still enrolls only `PublicKey`, with an empty `clientEphemeralDh` and no feature metadata. No AUTHZEN capability is advertised, and nothing is sent to CryptAuth from the new code.
6. **Lab capture.** Shape-only logging needs `-Pcyclon.authzenLabCaptureUntil=<epoch millis>`. The build fails if that time is more than three days ahead, and capture stops by itself at that time. No Intent can reach it. It logs field numbers, wire types and size buckets of the envelope and, after verification, of the plaintext. It also logs the unverified header's scheme numbers, the size bucket of its key id, and its `GcmMetadata` type and version. It never logs values, ids, keys, numbers or text.

Build commands:

```sh
# Default (shipping): native route off, no capture.
./gradlew :play-services-core:assembleMapboxDefaultRelease

# Lab build for the device stage only, for example capture for 48 hours:
./gradlew :play-services-core:assembleMapboxDefaultDebug \
  -Pcyclon.authzenNative=true \
  -Pcyclon.authzenLabCaptureUntil=$(( ($(date +%s) + 48*3600) * 1000 ))
```

Even a lab build cannot answer a prompt until a verified profile and `authzen` enrollment exist (stages C and D).

## Before enabling: device test and protocol capture

These steps are for a later authorized stage. **None of them has been done.**

### Preflight and safe capture

1. Get explicit authorization for one dev phone and its serial. Settle the device naming used in #842 (founders-02 or Prometheus) first. Record model, OS build, GmsCore source SHA, versionCode, signer and package path. Do not use an owner's daily phone, and do not substitute another device.
2. Use only the throwaway Google test account supplied for #842. The operator gets its password privately and never puts it in a command, log or report. Make sure a recovery method exists so the account cannot be locked out.
3. Install a lab build with lab capture on (above). Analytics or crash upload must not carry lab logs. Record the restore procedure and use the authorized signer and update method. Do not casually downgrade or replace the system app.
4. Capture shapes only: `Gms2FA` for the web route, `GmsAuthZen` for the native route. If field values are ever needed for schema work, inspect them only in a short-lived, encrypted local capture in a restricted lab session; never log or share them. Turn findings into synthetic fixtures signed with synthetic keys.
5. Do not use TLS interception or disable certificate checks. Instrument this client at its encode and decode boundaries, with the test account only.
6. Check readiness and record booleans only: secure lock, fresh unlock, check-in and push enabled, microG self-registration allowed, two-step setting on, notification permission and channel allowed, DND state. Test the **existing GNOTS route first**. Logcat `Gms2FA event=readiness` should reach `gms_registered=true` and `chime_registered` of at least 1.

### Establish current wire facts

7. In a private browser on another computer, sign in to the test account through Google's normal sign-in page, with Google prompts or 2-step verification set up and a backup method. Do not repeat requests to force eligibility.
8. Start a real sign-in until Google offers "Check your phone". Record the recipient device shown, the approximate start time in PT, and whether number matching is required. `/signin/challenge/dp` is a browser route that the server controls. It is not an API and must not be called directly.
9. Follow one attempt end to end in logcat:
   - `Gms2FA event=received` means the web route got it.
   - `GmsAuthZen event=ingress` means a `P` message arrived. Its `shape` lines give type and version.
   - Neither means nothing reached GmsCore: device selection, token or transport.
   One resend after expiry is enough to compare ids and collapse behaviour. Do not loop.
10. Native enrollment: record the ordered bundle names in Google's SyncKeys response and their type, action, storage and verification requirements. Find out the AUTHZEN feature metadata shape. Check symmetric key agreement and key proof against a current reference. Compare `PublicKey` fingerprints only as a local before/after equality check. Find out whether the server asks for DroidGuard, key attestation or other signals.
11. Native request: establish the current profile. That means the transaction id; the expiry units and epoch; the type and version; how the number choices are encoded and how the correct one is marked; the browser, app and location fields; any required ACK or seen-state sync; and the cancellation format. Every field the code uses must be backed by a current synthetic fixture made from that observation.
12. Reply: confirm the endpoint (HISTORICAL `https://www.googleapis.com/cryptauth/v1/authzen/replytx` and `/synctx`), the scope and token mode, the JSON or proto framing, the signing and encryption key ids, the required timing, UI and security fields, the accepted negative and mismatch values, and what counts as success. A network success is not enough: the browser must advance only after the owner's correct decision.

### What enabling means in code (stages C and D)

- Add `authzen/transactions_<profile>.proto` and an `AuthZenProfile` for the **verified** profile only. Record which names are original and which are research labels. Add the profile to `AuthZenProfiles.verified`.
- Implement `authzen` enrollment as its own feature-gated flow: Chromium-style v2 enrollment with ephemeral DH, the verified type and KDF, and the journal in `AuthZenKeyRegistry`. Advertise AUTHZEN metadata only after its bytes are confirmed. Do not change the Workspace single-bundle request.
- Hook account removal and opt-out to `AuthZenKeyRegistry.clear()` and `AuthZenAction.AccountRemoved`.

### Acceptance cases (device)

| Case | Expected |
|---|---|
| Correct Yes with correct number | New OS authentication; Google accepts; browser moves on. |
| Yes cancelled or biometric failure | No positive reply is sent. |
| Wrong number | Attempt ends; the browser does not authenticate; no later Yes. |
| No | The browser refuses or ends the attempt the way the server defines it. |
| Dismiss or back | Nothing is sent; the request stays pending until it expires or is cancelled. |
| Locked phone | Generic notification; details only after unlock; Yes still needs a new authentication. |
| Expiry during the screen or authentication | Controls stop; no late approval. |
| Duplicate, resend or cancel | One decision at most; the old prompt closes. |
| Offline, timeout or reboot | No queued automatic Yes; the result shown is accurate (unknown if unknown). |
| Two accounts | Only the addressed account can respond. |
| Notifications denied | Readiness says so honestly; nothing launches in the background. |
| Key rotation | Pending requests fail safely; `PublicKey` is unchanged. |
| Workspace regression | The screen-lock proof and token retry still work, without loops or a 429 storm. Test with an authorized Workspace test account, separately from the personal account. |

### Cleanup

Remove the test account from the phone. Sign out test browser sessions. Revoke only the registrations the lab created. Restore the recorded system package state. Delete local captures. Keep only sanitized shape notes, synthetic fixtures, version receipts and results. Record each status separately: implemented, host-tested, built and signed, installed, device-accepted.

## Still UNKNOWN

- Whether Google uses the GNOTS route, the native `P` route or neither for this account's number-match challenge, and why a phone listed as a prompt device shows nothing.
- The current sender of `P` messages and its `from` value (this code accepts only `745476177629`), collapse keys, and the envelope.
- Current AUTHZEN feature metadata, the `authzen` key type, KDF and handle convention, and whether enrollment is required for selection.
- Current request, reply, cancel and sync schemas; number semantics; expiry units; the replay window; the endpoint; the token profile; what counts as success.
- Whether DroidGuard, hardware attestation or Play Integrity is required. If it is, report the route as unsupported; never fake a verdict.
- Android 17 behaviour of the keyguard, BiometricPrompt and notifications on the Cyclon image.
