# Account-synced passkeys and acceptance

The current account consumer connects Android Credential Manager, legacy GMS FIDO
and authenticated caBLE hybrid create/get to the same account-isolated encrypted
vault. Device-only AndroidKeyStore passkeys keep their separate provider path.
The last gathered host run recorded in [the parity inventory](password-manager-parity.md)
passed 400 password-sync, 13 FIDO and 191 Core tests (604 total), and compiled the
product and instrumentation APKs. Those results belong to source `dece944d`.
They do not establish provider, installed-image or cross-device acceptance.

The account must have password sync enabled and authenticated metadata. Begin-get
reads a complete fresh snapshot, including when an unrelated RP is already cached.
The native picker binds stable Gaia ID, vault birthday, RP, credential sync ID,
request digest and installed caller identity in a one-use encrypted ticket. It
requires a secure screen lock, current native owner confirmation and a foreground
window. Caller, account, reset, current credential and pending deletion authority
are rechecked while the account/repository/passkey locks remain held through the
actual signing or extension operation. Account keys never enter `fido.db`.

The vault reads real versioned `hw_protected` keys through Folsom. Missing keys open
exact-account/domain recovery with reset disabled. Device authorization uses real
DroidGuard bytes and the pinned `chromesync_get_device_authorization_key` flow;
field19 device/domain epochs must be server-proven. Google reauthentication uses
the actual packaged lookup token and an exact-origin, main-frame xreauth bridge.
The owner confirms again before a bounded retry. A local owner grant is never
substituted for a Google RAPT. Account removal erases the independent encrypted
store and its AndroidKeyStore key.

Creation retains prior same-RP/user records and writes the source shadow list.
A durable launch record precedes mutation; unknown outcomes are reconciled by a
complete snapshot without automatic replay. Owner rename preserves identity and
opaque fields. Deleting an active entry includes the reviewed same-RP/user group;
selecting an explicitly shadowed entry deletes only that entry. Partial/unknown
receipts survive reopening. Explicit retry uses a new attempt and archives prior
provenance; local closure does not claim remote rollback.

Implemented extension consumers are:

- PRF in Credential Manager, legacy GMS FIDO and hybrid. JSON inputs receive the
  WebAuthn domain hash once; typed/CTAP 32/64-byte inputs are already hashed.
  Credential-specific inputs select the actual credential ID. Existing encrypted
  field2 supplies the HMAC secret. A field9 or missing-secret credential follows
  the pinned PKCS8/HKDF migration and commits its encrypted update before output.
  Public credential IDs never substitute for that secret. JSON uses nested
  `prf.results`; typed SafeParcel field2 concatenates the optional two results.
  Hybrid uses the source CBOR unsigned outputs at make key6/get key8.
- Credential Manager credBlob creation/read and bounded largeBlob creation,
  read/write use the real encrypted fields3/4/5. DEFLATE is strictly bounded to
  2 KiB. Writes preserve device/domain envelopes and opaque metadata and commit
  through the durable secret-update journal before success. An uncertain write
  never becomes automatically replayable. The pinned legacy parcel API has no
  proven typed blob ABI and does not advertise one.
- Payment enrollment preserves the source field11 opt-in. Cross-origin signing
  requires the actual permitted Chrome signer, browser transaction client-data
  hash, finite credential allowlist and the selected credential's stored opt-in.
  A hybrid BrowserOptions container grants no browser/SPC authority.

Hybrid authority comes from the actual authenticated Noise transcript and original
CTAP digest, full immutable options, RP and client-data hash. A private one-use
request lease binds the internal Hybrid Activity, account picker and native vault
result. Socket loss and authenticated Shutdown immediately revoke the lease and
cancel pending owner work. Final output admission, Noise nonce reservation and
socket enqueue share one connection-generation guard. Process restart cannot
reconstruct a lease. Unsupported options are refused or left unadvertised; an
account selection does not silently fall back to local storage.

Trusted-vault support also includes explicit physical membership, source-backed
create/enroll/rotate/reset journals, actual OS LSKF vault upload and restoration,
existing PIN renewal, attested enclave new/change PIN, private Google reauth,
native GPM PIN restoration, SDK bulk/generate/list consumers and coupled consent.
The separate five descriptor-only Passwords lifecycle RPCs still lack a verified
producer contract; they are recorded in [the bounded contract audit](passwords-rpc-contract-gaps.md).
Those missing contracts are distinct from acceptance of the connected consumers.

The remaining acceptance scopes are tracked explicitly:

| Issue | Required real evidence |
| --- | --- |
| CYC-688 / CYC-689 | Installed Credential Manager, legacy FIDO and hybrid owner/caller/cancellation behavior, genuine server/device authorization and extension responses. |
| CYC-687 / CYC-691 | A designated second device recovers the same account's actual keys and signs a passkey created on the first device; wrong account/domain/epoch and cancellation remain refused. |
| CYC-690 | Two actual synthetic accounts in the same Google family send/receive an authenticated invitation, preserve duplicates/conflicts and complete exact synthetic cleanup. |
| CYC-693 | Native rename/delete group confirmation, uncertainty/reopen and signing exclusion on installed components. |
| CYC-653 | Official-client exact-secret/producer interoperability; self-written/self-read is insufficient. |

Host vectors and built APKs do not satisfy these rows. The owner has now made the
Pixel 7a available to this chat, while the parent coordinates actual device and
account selection. No new device/provider result is claimed by this document.
The companion controller is an image component; its deployment follows Core's
`android/Core/dev/updates.md` and the native password probe guide, independently
of the Services APK. Dedicated affiliation/Vault API configuration and installed
image grants must be verified before their corresponding acceptance cases.
