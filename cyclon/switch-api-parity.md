# Android Switch Play services contracts (CYC-700)

This work targets Android Switch 1140078. Host Binder tests establish parcel
compatibility; a real transfer remains a separate acceptance gate. The reported
Play services version and delivery allocation are unchanged.

## Saved source account-management state

Service 76 advertises `managed_account_setup@2` and answers target transaction 16
through `ITargetDeviceCallbacks` transaction 7. The request is a callback binder
followed by API metadata; the response is nullable Status followed by nullable
ManagedAccountSetupInfo (SafeParcel fields 1 String, 2 int, 3 String).

This is saved **source** WorkProfilePayload metadata, not a query of the target's
DevicePolicyManager or its currently signed-in accounts. Real GMS `gesr.c/e`
reads/writes name, management mode, and source ID. Its fresh store returns
`(null, 0, null)`, where mode 0 means no saved source-management state. microG
does not import WorkProfilePayload, so it returns that same non-null empty state.
It does not claim that an account was transferred or that the target is unmanaged.

Evidence in the supplied decompiles:

- Switch `jwq.java:18`, `iqg.java:158-168`, `jwi.java:46-51`, `jvz.java:23-31`:
  service, transaction, callback, and fields.
- Real GMS `geqc.java:159-168`, `gelv.java:91-95`, `gelo.java:24-50`,
  `gesr.java:142-171`: dispatch and persisted-source state. `ifig.a(0)` returns
  enum value 1; the conversion in `gesr.c` produces API mode 0. The original
  jadx output has an uninitialized-local artifact; the fallback decompile and
  recovered enum confirm the default.
- Switch `ejn.java:19-29` dereferences successful results, so SUCCESS/null is
  invalid. `jwo.java:17-24` converts operation errors into a completed failed task.
  `dgy.java:285-291` offers work-profile restore only for saved source mode 3.
- Switch `jwe.java:16-21`, `ivv.java:155-205`: missing feature version 2 causes
  the update notification, five-second retries, and a two-minute expiry. With
  this query implemented and advertised, that missing-feature path is avoided.

Google account bootstrap remains unsupported; target account-pipe operations
still fail explicitly and require ordinary sign-in.

## Google Auth AANG

Service 343 now binds `com.google.android.gms.auth.aang.events.services.START`
and exposes `IGoogleAuthAangService`. Every connection validates the claimed
package against its calling UID and the existing signed-package ACCOUNT
allowlist. The returned binder belongs to that UID and rechecks authorization
on each call. Only `google_auth_api@1` is advertised; SyncAccountState, embedded
reauth and RemoveAccount remain unadvertised.

Transactions 1/2/4 answer GetAccounts/GetToken/HasCapabilities through callback
transactions 1/2/3 with a synchronous no-exception service reply and a one-way
callback. Field IDs are recorded in `AangParcelables.java`. GetAccounts returns
real Google accounts and their stored Gaia IDs, respecting explicit account
visibility and the requested AccountManager feature/capability filters. A
visible account without a stored Gaia ID fails explicitly instead of being
invented or reported as absent. Restricted/transient listings need backend state
microG does not have, so requests for them receive an immediate explicit error.

GetToken maps the four token types to the same service strings as GMS, then uses
microG's existing AuthManager with the actual caller package. Tokens retain its
package certificate and scope permission checks, caching, and account recovery.
Denied consent returns SIGN_IN_REQUIRED with the existing permission activity as
its resolution; no token is returned. Client-package impersonation is rejected.
Delegation, custom token options/network and notification-mode requests remain
explicitly unavailable. AANG does not log request or token data.

HasCapabilities reads actual stored enabled/disabled capability state and its
package visibility restrictions. Known results use the real result values
(allowed 1, denied 2, visibility-denied 4). Unknown or missing capability state,
and pending state without a prior confirmed result, returns an explicit
API_NOT_CONNECTED error, never an allowed result.
This adapter does not refresh capability state or claim that all accounts have
a capability merely because the query is supported.

The shared capability decoder now accepts only explicit STATUS_ALLOWED as a
grant. Earlier caches mixed unknown/absent statuses into the enabled set; those
caches are invalidated using a private provenance marker. A fresh server unknown
status clears a prior grant. Genuine pending responses can retain a previously
known result, as in the existing merge behavior. The interoperable GMS
`capabilities_version` remains 1; the private cache-format marker is 2.

Evidence in the supplied Switch and real-GMS decompiles:

- Switch `ije.java:12-51`, `ijh.java:41-67`, `ijf.java:24-62`: service ID,
  descriptors, service/callback transaction codes and call flags.
- Switch `iiu.java:33-40`, `iiv.java:20-25`, `iiz.java:41-47`,
  `iix.java:58-76`, `iiy.java:19-24`, `ijb.java:20-25`, `ijc.java:20-25`:
  SafeParcelable fields. `iit.java:12-14` requests ordinary non-transient
  accounts. `ijo.java:70-74` maps fields 3/2 to Android account name/type.
- Recovered real GMS `amoa.java:70-108` confirms transactions 1/2/4;
  `amfb.java:48-84,88-108`, `amku.java:32-50`, `amla.java:29-51,97-109`,
  `amfd.java:30-43`, `amgs.java:118-174` confirm filters, token service-string
  mapping, and capability/visibility results. Additional recovered files live
  in the audit evidence directory `gms-static/aang-implementation/`.

## Credential transfer and Phenotype gaps

Device-setup credential import/export and inventory discovery are unsupported.
Their three wire features remain advertised at version 3 so Switch can reach
the immediate failures: identitycredential transactions 12/13/14 each return
`API_NOT_CONNECTED`, null response, and `ApiMetadata.SKIP` through callback
12/13/14. An empty successful inventory is not evidence that no passwords or
passkeys exist. The protected destination remains tracked by
[CYC-716](https://linear.app/cyclon/issue/CYC-716).

Switch `jed.java:24-46`, `ijq.java:160-193`, and `jdw.java:79-103` establish the
credential feature gates and wire codes. Review round 1 caught that withholding
these features prevents the error callbacks from running: `jdc.java:49-54`
marks them fully rolled out and `iww.java:12-18` enables automatic missing-feature
resolution. `ivv.java:173-196` then posts update UI and schedules five-second
retries with a two-minute expiry. With the versions present, `ivv.java:152-155`
dispatches immediately. The `jea/jeb/jec.java:15-17` callback adapters use
`imp.java:316-328,545-546` to complete failed tasks with a plain API exception,
without requesting resolution or scheduling a retry. Inventory indexing catches
that failure (`gps.java:145-153`); it is not a successful empty inventory response.
No credential URIs are opened and no credential records are imported/exported
by these unsupported operations.

Phenotype `get_storage_info_api@1` remains unadvertised and transaction 27 remains
unsupported. Real GMS `fvnq.java:491-499,593-595`, `fvpn.java:34-40`, and
`fvqy.java:38-60` return serialized shared-storage configuration backed by actual
per-package CE/DE secrets, directory roots, and snapshot formats. microG does not
publish matching shared snapshots. Empty bytes would not implement that contract.
The implementation gap is [CYC-809](https://linear.app/cyclon/issue/CYC-809).

## Device acceptance still required

The parent chat owns phones and installation. On the dev Pixel 7a with the built
microG change and image-preinstalled Android Switch 1140078:

1. Open Transfer Phone, choose iPhone, and start a new USB cable transfer. Connect
   the iPhone, approve trust/unlock requests, choose data, and copy through finish.
2. Observe the entire run and notification shade. There must be no "Update
   Google Play services to use Android Switch" notification and no
   `managed_account_setup` missing-feature message. Account-info completion must
   happen promptly, without five-second retries or the two-minute timeout. The
   expected Switch message is `Account info fetched with mode: 0`.
3. Verify real AANG calls bind the implemented service and complete through the
   correct callback. With no Google account, GetAccounts must return an empty
   real list. With an existing visible Google account, it must return that
   account; token calls must retain existing scope/signature consent and return
   real tokens or explicit errors. Unknown capabilities must not become grants.
4. Check copied contacts/messages/calendar/media and app-install results against
   source selections separately. The absence of an update notification does not
   establish that every selected data category copied, nor account/credential
   transfer or Phenotype shared-storage parity.

No physical phone, adb, provider token request, or real cable transfer was used
by this implementation workstream.
