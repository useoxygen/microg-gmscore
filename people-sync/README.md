# Google Contacts synchronization

Production **Download only** uses the private Internal People v2 REST API at
`https://people-pa.googleapis.com/v2/`. Google remains the remote store; microG
imports into Android ContactsProvider. **Two-way is coming soon and disabled.**
Existing Two-way preferences are blocked before service authorization or provider
work; they are not silently routed to the unusable public v1 API. The production
sync adapter advertises supportsUploading=false while writes are gated.

This follows the owner-approved Prometheus findings in
`~/research/cyc721-contacts/private-probe-iteration.md` and `private-iteration/`.
Public v1 tokens were obtained but every tested GET returned SERVICE_DISABLED.
Private reads work with the required client context. API research is distinct
from acceptance of this implementation on a device.

## Authorization and client context

Download uses `oauth2:https://www.googleapis.com/auth/peopleapi.readonly` through
the normal microG AccountManager path. There is no write-scope fallback: readonly
sync succeeded in the device research, and discovery declares readonly for group
and encoded-photo reads. No private mutation method can dispatch a request.

`PrivatePeopleContext` is the only definition of `GMS FSA2`, client version and
`useNewRequestMaskBehavior=true`. The Android factory derives the semantic GMS
version from `Constants.GMS_VERSION_CODE`, the same advertised version already
used by microG auth requests (currently 25.24.32, controlled by the existing root
`gmsVersion`). It does not copy an unrelated research APK's version string. Sync,
batchGet, groups, photos and the non-enabling diagnostic matrix share it. No API
key, Spatula header, cookie or custom user-agent is sent.

A fixed read rejected with INVALID_ARGUMENT/400 (or HTTP426) produces **needs
update**, rather than pretending it was a connectivity failure. Automatic calls
are paused for that context binding with no I/O retry failure count. A changed
advertised version/mask or explicit Sync now/enable permits another attempt.
Known expired-cursor errors are handled before this classification and restart
with a full read. Error bodies and exception messages are never logged.

## Read protocol and normalized fields

Sync uses GET `people/sync`, pageSize 100, CONTACT_CENTRIC, CORE_ID,
PRODUCT_BESTIES and GDATA_COMPATIBILITY. Full reads use FULL_SYNC_OK; incremental
reads preserve the starting sync token with FULL_SYNC_UNDESIRABLE while paging.
All other parameters stay identical. Only a complete final page can supply the
new durable checkpoint. There is no invented private-token lifetime.

The private `personId=c<decimal>` is not a public v1 resource ID. Metadata's exact
int64 contact IDs and matching CONTACT `sourceIds` (hex) identify each source.
Internal keys are `people/fsa_<hex>`. Merged people are split only when field
metadata identifies the contact source; profile fields are not imported as owned
contact data. Ambiguous deduplicated contact fields, invalid/unrepresentable dates
and malformed entries are quarantined and their stored copies kept.

The adapter translates singular private proto-JSON fields to the shared internal
mapping: names/phonetic names, phone/email, addresses, organizations, notes,
yearless or full birthdays, nicknames, websites, relations, anniversaries/events,
IM, SIP and user-defined fields. Source etags and primary flags are preserved.
Direct reads use the verified read-only POST `people:batchGet`; each embedded
status, requested identity and source identity must agree. A malformed/error item
is never interpreted as NOT_FOUND. Mutating POST/PUT/PATCH/DELETE are unavailable.

## Ownership, checkpoints and deletion safety

Private raw contacts and groups use `SYNC4=microg-people-fsa2-read-v1`, with
account/type/dataset scoping and version/owner assertions. Private imports are
read-only and never adopt source-less/device/app rows. Legacy `microg-people-v1`
rows and unknown outcomes are preserved separately: this change does not guess
cross-backend identity equivalence, replay journals, delete or relink old imports.
If old imports exist, migration/duplicate presentation needs explicit acceptance.

Contact checkpoints bind the backend, mask/model and client context. Group
checkpoints are separate, account-scoped and bound independently. Old public
cursors cannot enter either private endpoint. A context/mask change starts full
reads while retaining existing private rows.

Every page is read before applying its snapshot. Malformed entries quarantine
all identifiable source IDs across pages, apply readable siblings, report partial
status and hold checkpoints and pull deletions. Explicit partialFailureStates
also hold deletions/checkpoints. Absence from full reads requires a successful
direct NOT_FOUND confirmation. A late malformed direct read keeps all earlier
removal candidates. Dirty, pending and unowned data are preserved. Provider writes
are version-checked batches; cursors advance only after their writes succeed.

## Photos, labels and memberships

Only non-default CONTACT photos are managed; profile fallback/monograms are not
turned into owned contact photos. The adapter creates an internal
`fsa2-photo:<hex>:<revision hash>` locator, not an image URL containing credentials.
It calls GET `people/photosEncoded` with CONTACT_PHOTO, the hex source ID,
useBytes=true and the shared context. PNG/JPEG base64 is decoded with a 4 MiB
bound. Empty/malformed/error replies preserve the old photo. API authorization
failures keep their sanitized HTTP status/reason. The backend may re-encode images;
exact byte equality is not proof of pixel identity.

The Android mapper bounds decoding and emits at most 256 KiB per provider blob.
Photo replacement and its baseline commit atomically under the raw-row version
assertion; provider nonce/DATA_VERSION detects later local edits. Metadata stores
a durable pending photo hint, so an image failure remains retryable even after a
metadata checkpoint advances. This private backend never creates upload journals.

Groups use GET `people/contactGroups/list` with their own page/sync tokens and
INCLUDE_DELETED on incremental reads. Each embedded group result must be OK;
HTTP200 is insufficient. Direct missing-group resolution and absence confirmation
use the discovery-backed `contactGroups/get` binding with per-item checks.
Malformed results preserve old labels and hold the group cursor/deletions.

System IDs map separately from user label hex IDs. All groups are read-only.
My Contacts maps AUTO_ADD, starred maps FAVORITES/raw STARRED, and membership rows
use account/owner-scoped provider group IDs. The importer never invents My
Contacts when the private membership list is empty. It can resolve a referenced
group missing from a list through a direct read. A group with member rows is kept
until contact metadata has removed those memberships, avoiding a cascade which
would alter contact baselines behind the reader. Private group/contact/photo
writes all fail locally even if accidentally called.

## Settings, diagnostics and retained code

Choose Off or Download only in **Google accounts → Contacts sync**. Activation
obtains Android Contacts permissions and Google authorization, then verifies a
cursor-only private read before configuring the provider or scheduling. **Check
read access** and the private scope/identity matrix return before provider access,
preferences/configure or scheduling. Existing independently enabled sync is not
paused by a check. The private matrix compares readonly/readwrite/legacy grants
under actual and separately labeled appIsGms identities, using only GETs. It does
not persist probe tokens or launch shared remote challenge resolution.

Diagnostics contain only fixed labels, allowlisted auth/API status/reason/Error,
numeric HTTP status, effective package/signature fingerprints and cursor-presence
booleans. No account/contact values, tokens, cursor values, raw error text or
consent payloads are added. The auth transport/parser suppresses raw body/field
logs for contacts self-requests even with VERBOSE enabled.

Public `PeopleApi`, its journaled create/update/delete/photo/group engines and
regression tests remain inexpensive dormant code for a possible future registered
OAuth client. Production account sync does not call that factory. They are not a
fallback and do not establish private write support. No owner-level default
account choice is changed, no new-contact editor action is offered for this
read-only path, and existing hourly/OS scheduling remains. Private push,
file-as/linking and all private two-way behavior remain open under CYC-721 and its
follow-ups. No device or live Google qualification of this commit is implied.

## Tests

```sh
./gradlew :people-sync:test
./gradlew :play-services-core:assembleMapboxDefaultDebug
./gradlew :people-sync-android:assembleDebugAndroidTest
```

`ContactProviderTest` and `ContactExtrasProviderTest` run on an isolated emulator with a standalone library
test APK and synthetic `org.microg.gms.contacts.fixture` accounts. GmsCore uses
the same Android library for its provider integration. Fixture tests use a mock
remote service, write the real Android Contacts Provider, and refuse physical phones by default.
All fixture account and contact names are unique for each test; cleanup removes
only rows created by that test. They do not authenticate a real Google account.

After installing the fixture APK on that emulator, run the test runner
for the exact selected emulator:

```sh
python3 people-sync-android/tools/run-provider-tests.py --serial EMULATOR_SERIAL
```

Physical testing is also available on an explicitly authorized `userdebug` or
`eng` phone (Android 10 or newer). Verify the serial first, install only the
standalone fixture APK, and pass the same serial as the instrumentation argument.
The host runner verifies the ADB transport against `ro.serialno`, then binds the
test run to that serial, build fingerprint, and a fresh nonce in the debug APK's
private storage. A missing, stale, or mismatched proof refuses the run. This tests the
engine/provider integration, not GmsCore installation, Google authorization, or
real Google-side effects.

```sh
python3 people-sync-android/tools/run-provider-tests.py --serial DEVICE_SERIAL --physical
python3 people-sync-android/tools/run-provider-tests.py --serial DEVICE_SERIAL --physical --expect-refusal
```

The same runner accepts an emulator serial without `--physical`. It grants only
the fixture APK's contact permissions and never acquires `UiAutomation`, taps
the shared screen, stops another app, or disables accessibility services. Its
provider tests can therefore run alongside another phone automation session.
Run this helper instead of the direct instrumentation command when permissions
have not already been granted; raw instrumentation intentionally cannot approve
a physical phone by itself. The helper removes its temporary proof after the run.

The optional [public Google access lab](../people-sync-lab/README.md) uses
ordinary AccountManager authorization and the retained public v1 parser. It does
not exercise the production private backend and the GMS project currently gets
SERVICE_DISABLED from public v1. It is a separate debug app, with its own actual OAuth identity, and
does not replace the installed GmsCore or enable account sync.

## API references

- [Private v2 discovery](https://people-pa.googleapis.com/$discovery/rest?version=v2)
- [Android Contacts Provider](https://developer.android.com/identity/providers/contacts-provider)
- [AOSP photo processing](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/main/src/com/android/providers/contacts/DataRowHandlerForPhoto.java)
- Retained public v1 client only: [contacts](https://developers.google.com/people/api/rest/v1/people.connections/list), [groups](https://developers.google.com/people/api/rest/v1/contactGroups), [photos](https://developers.google.com/people/api/rest/v1/people/updateContactPhoto).
