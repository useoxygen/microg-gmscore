# Synced passkey consumer

This slice connects Google-account passkey records to Android Credential Manager create/get flows. Device-only AndroidKeyStore passkeys retain their existing provider path and are never silently assigned to a Google account.

The account must already have password sync enabled and authenticated Chrome Sync metadata. Begin-get reads a complete fresh account snapshot before constructing entries, so an unrelated populated cache cannot hide a passkey created on another device. The native chooser binds one account's stable GoogleUserId, vault birthday, RP, credential sync ID, request hash and verified caller identity in a one-use encrypted ticket. The mutable PendingIntent contains only its opaque URI. The installed caller signer, account identity, foreground state, screen lock and owner confirmation are checked again before returning a WebAuthn response. The native activity is secure and excludes Autofill.

`SyncedPasskeyVault` reads `hw_protected` keys through the stable-account Folsom store. Missing keys open the existing owner-gated Google recovery flow for that exact account/domain with reset disabled. Keys are not exported to a model API, Intent, log or telemetry. Account removal erases the independent no-backup AEAD state and its AndroidKeyStore key through `purgeRemovedAccounts`.

Google device authorization uses the existing native DroidGuard implementation and the exact `chromesync_get_device_authorization_key` flow. The provider decodes the real response bytes, rejects the implementation's error sentinel, and requests the exact device-key versions needed by field19. New registrations use the nested device/domain envelope and require positive server-returned device and domain epochs. No locally generated value substitutes for a server device key or RAPT token.

Creation authenticates a full remote snapshot before launch, adds the existing same-RP/user IDs to the new credential's shadow list, and retains old entities for recovery. It stores a launch record before sending the mutation. A definite refusal or pre-launch cancellation clears the attempt. A transport-uncertain result is reconciled through a complete snapshot and is never blindly replayed. The owner can explicitly close an unresolved attempt from the native create flow; the old birthday, encrypted resource and launch provenance remain archived locally, and a possible remote credential is retained.

Sources pinned for independent interoperability work:

- GMS 26.11.36 `bese`, `beri`, `ialc`: resource id1, lowercase-hex sync_id client tag2, encrypted EntitySpecifics4 or plaintext specifics7. Encrypted EntitySpecifics carries WebauthnCredentialSpecifics at895275.
- `iake`/`iakf`: ListWebauthnCredentials request parent1/page3, response resources1/next2. `iaja`/`iala`/`iajg`: create/update/delete request layouts, including client token4.
- `ajgx`/`ajgu`/`ajhb`: HKDF-SHA256 domain/device labels, AES-GCM12-byte nonce prefix/128-bit tag, exact envelope AAD and nested PKCS8 layouts. Legacy9 and12 remain readable; new registrations use19.
- `beua`/`beyo`/`iajn`/`iajt`: salted DroidGuard inputs, `hw_protected` availability, genuine device authorization keys and explicit account reauth branch.
- Chromium145 `webauthn_credential_specifics.proto`: identity, epoch, hidden and shadow fields; newest-creation tie breaking after explicit shadowing.

The deterministic host tests cover independent HKDF and AES-GCM vectors, actual native signatures from decrypted synthetic PKCS8, wrong keys/epochs/AAD, outer and nested envelopes, unknown-field preservation, exact RPC request bytes, bounded pagination, first-use candidate fetching, reset/uncertain mutation retention and shadow selection. Those tests were added for the parent's consolidated run; this worker ran no builds or suites.

Remaining acceptance and scope limits:

- Google's AANG account reauthentication API is absent locally. A server reauth response yields `PasskeyReauthRequiredException`; owner screen-lock confirmation is never treated as a Google RAPT token.
- New security-domain membership, LSKF/GPM-PIN enrollment and reset contracts remain separate work. Existing exact-account recovery keys can be consumed; the feature does not pretend an unjoined device is enrolled.
- PRF, largeBlob/credBlob, payment passkeys and non-ES256 creation are not yet exposed through this consumer. Unknown encrypted fields are preserved. `credProps` reports the discoverable key created by this path.
- Credential Manager is the connected native consumer in this slice. Legacy GMS FIDO APIs continue using their existing local/hybrid paths.
- Host checks do not establish Google server acceptance, installed image permission behavior, second-account acceptance, cross-device registration/signing or on-device owner/cancellation behavior. Device work is deferred by the user's current priority.
