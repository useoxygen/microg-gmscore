# Google Contacts synchronization

This module implements the sync engine used by GmsCore's existing
`org.microg.gms.people.ContactSyncService`. Google remains the remote contacts
store; microG implements the Android client, account integration, reconciliation,
and contact-provider writes.

## Modes and ownership

In microG Settings, open **Google accounts → Contacts sync**. Each account has
Off, Download only, and Two-way modes. Enabling a mode requests contact-provider
permissions and the corresponding Google authorization, verifies a People API
read, and then enables Android synchronization. Background sync uses
AccountManager token renewal; missing authorization requires reconnecting.

Download-only requests `https://www.googleapis.com/auth/contacts.readonly` and
the API client rejects every mutation. Two-way requests
`https://www.googleapis.com/auth/contacts` and supports creating, updating, and
deleting Google contacts. Switching modes changes managed raw-contact editing
flags. New local-only contacts should be saved in Device storage.

The Android implementation owns rows in the selected Google account marked
`SYNC4=microg-people-v1`. It also adopts newly created, dirty, source-less rows in
that account whose IDs exceed the initial opt-in baseline. Existing unowned
rows, Device imports, and other apps' raw contacts remain outside this set.
Remote identifiers, etags, baselines, and pending uploads are stored in the
owned raw contacts' sync columns. Provider updates assert the observed row
version and use `CALLER_IS_SYNCADAPTER`. Contact and group metadata checkpoints
advance after durable provider writes. A pending photo mutation holds the contact
metadata checkpoint: after photo recovery, the next metadata pass must revisit
and apply any cloud fields skipped while that journal was pending. Photo download
hints remain durable independently of metadata checkpoints.

## Sync and recovery

- Read every page before applying a snapshot. Keep the request configuration
  fixed across pages and checkpoints; reset expired sync tokens through a full
  read. A partial read cannot justify a deletion.
- Apply incremental tombstones only to owned, clean rows. Verify absence from
  a full snapshot with a direct read, since list results lag behind mutations.
- Quarantine malformed contacts independently, including malformed field
  containers and direct readbacks. Readable siblings still sync. Keep the old
  checkpoint, preserve all pull deletion candidates, and show partial status
  until the unreadable entries recover; never treat a malformed page envelope
  as an empty account.
- Update only locally changed fields, with the latest CONTACT-source etag.
  Disjoint local/cloud edits merge. Same-field conflicts remain dirty and hold
  back the checkpoint; the status screen exposes that review is needed.
- Record a pending request before sending it. Disable HTTP redirects and
  transparent mutation retries. A lost create reply is reconciled using a
  unique `clientData` marker, without issuing another create. Lost update and
  delete replies require direct readback. Ambiguous outcomes stay pending.
- Preserve edits made during upload or recovery. Merge unchanged fields from
  the server and retain subsequent local edits with their dirty flag.
- Google's delete endpoint has no documented conditional-etag parameter. This
  implementation checks the latest etag before deleting, but cannot make that
  read and remote deletion atomic against another client.

## Photos and groups

`ContactSyncService` runs group metadata/lifecycle sync, contact fields and
memberships, then photos, serially for the authenticated account.

Photos use the documented `updateContactPhoto` / `deleteContactPhoto` endpoints,
not the ordinary person update mask. Only non-default CONTACT-source images
are managed; public profile/default avatars are not written back. Downloads
accept HTTPS Google image origins with redirects disabled and never send the
People API bearer token to an image host. Reads are bounded to 4 MiB. Downloads
request 1024 pixels; bounded decoding and JPEG encoding keep the provider photo
blob at most 256 KiB. An atomic provider batch asserts the raw-contact version,
replaces the photo and records its baseline; ContactsProvider generates the
display image/thumbnail. Uploads read the provider display-photo stream, with a
thumbnail fallback. Provider row nonce/DATA_VERSION baselines distinguish a
user edit from provider recompression. Source revisions and image hashes detect
changes even when Google reuses a URL, while text-only changes avoid image rewrites.
After a confirmed mutation, canonical-image read failure leaves a pending read,
not a pending mutation or a reason to replay the upload.

Each photo mutation is journaled in the raw-contact SYNC3 column before dispatch,
with the action, submitted image hash and local row token. A reply acknowledges
only the sent token, retaining subsequent local photo/text edits and contact
tombstones. Lost delete replies reconcile against fresh absence; lost upload
replies reconcile against exact image-byte equality. **If Google transforms the
bytes and equality cannot prove the result, the operation remains uncertain;
it is never automatically replayed over a possible cloud edit.** Photo endpoints
have no etag precondition, so a direct read/conflict check cannot eliminate the
race with another client between that read and the mutation. These are explicit
acceptance limits, not a claim of atomic remote photo writes.

Groups use their own paginated/incremental checkpoint and provider Groups
SYNC1–4 columns for etag, title baseline, mutation journal and ownership. New
local groups are adopted only after a separate per-account group-ID baseline;
existing unowned groups are not name-matched or adopted. Creates include a
unique clientData marker. Unknown create replies reconcile only against one
matching marker in a complete read, never by label name and never by a second
POST. Renames preserve concurrent local changes. Deletion explicitly sends
`deleteContacts=false`; local cascading removal atomically refuses edited,
pending or unowned member rows, including members added after discovery.

Owned system groups are read-only: My Contacts maps AUTO_ADD and starred maps
FAVORITES / raw-contact STARRED. Contact memberships use account-scoped group
IDs and public resource names. A missing group can be resolved by direct get
when a list lags. Independent membership additions/removals merge as sets,
including edits made during upload; a merge removing every membership is held
as a conflict. Removing the last local label falls back to My Contacts because
the public API requires at least one membership. Pre-mapping owned rows with
unrepresented native membership edits remain preserved for review rather than
being silently interpreted or uploaded.

## Current scope and release acceptance

Mapped fields are names, phone numbers, email addresses, postal addresses,
organizations, plain-text notes, complete or year-less birthdays, nicknames,
websites, relations, events/anniversaries, IM clients/protocols, SIP addresses,
and user-defined fields, including preferred-value flags. Dates remain ASCII
ISO strings regardless of the device locale; invalid or partially represented
events cannot be silently replaced by another event edit. Contact photos,
labels, My Contacts membership and starred state are also mapped. File-as,
contact merge/alias handling and guided conflict resolution remain future work.
Unmapped cloud fields are excluded from update masks. Unsupported local edits
are preserved rather than acknowledged as uploaded. Imported Device contacts
are not automatically migrated or retired.

Live Google authorization and an actual Google-side round trip are release
requirements beyond mock API/provider tests. Enable the People API for the
registered Android OAuth identity, use the existing normal authorization path,
and test renewal and revoked consent. Contacts editor behavior, the settings
consent flow, and scheduled/background execution also need end-to-end acceptance.
No OAuth client secret is embedded in
this module. A development-signed GmsCore is not an ordinary update of an
upstream-signed installation: use an upstream release or validate the product's
signing and account-retention migration before delivery.

## Authorization diagnostics and parity status

**This is not full stock-GMS parity or a confirmed fix for the real-account
activation failure.** See [the GMS comparison](../docs/contacts-parity.md).
GMS FSA2 uses private People v2 protobuf/gRPC and a flag-selected scope list
including `peopleapi.legacy.readwrite` on its legacy path. This client remains
on public People v1 with `contacts[.readonly]` until an actual grant/API result
identifies the remedy. The documented legacy `m8/feeds` scope aliases `contacts`.

Each account has separate **Check read access** and **Check two-way access**
actions. They may request Google consent for the selected scope, then issue one
minimal metadata GET (with at most one 401 token renewal). They return before
contact-provider access, adoption baselines, `configure()`, or scheduling. They
do not prove that creates/edits/deletes work; test those with disposable data.
These checks do not cancel or pause sync that was already enabled independently.

**Check private service access** is a separate diagnostic-only entry point.
It tests `peopleapi.readonly`, `peopleapi.readwrite`, and
`peopleapi.legacy.readwrite` individually, first with the actual PM-presented
identity, then with the existing `isGmsApp` / `appIsGms` override as a labeled
`GMS_APP` comparison. All token requests use the same microG AccountManager path,
force a fresh request and do not persist probe responses/tokens. Local consent
may be requested, but probe failures do not launch the shared remote challenge
resolver. Private probe consent also skips owner-avatar fetching and raw payload
logging. The legacy `fsnp` bundle's ten unrelated plus scopes are not requested.

Each successful grant permits exactly one GET of
`https://people-pa.googleapis.com/v2/people/sync` with `pageSize=1`,
`syncParameters.fullSyncOption=FULL_SYNC_OK`,
`mergedPersonSourceOptions.personModelParams.personModel=CONTACT_CENTRIC`,
`requestMask.includeField=person.metadata`, and the response selector
`fields=nextPageToken,nextSyncToken`. No cursor is followed or saved. HTTPS
redirects/retries are disabled and response reads are capped at 64 KiB. The
receipt contains only candidate/identity labels, sanitized auth status/error and
HTTP status, effective app/caller SHA-1 fingerprints, allowlisted API status/reason,
and token-presence booleans. Tokens and response bodies live only in RAM; logs,
UI results and preferences never receive their values or contact content.

The GET intentionally sends no borrowed API key or Spatula header. A successful
REST read is evidence for that exact scope/identity/transport only; a denial may
still reflect private-service/header policy, not just a refused scope. The normal
public sync scopes, identity, stores and scheduling are unchanged. No private
sync or write implementation is enabled by this check.

Diagnostics under `GmsContactSync` include fixed stages, requested scope, numeric
HTTP status, allowlisted auth/API reason/status codes and the public presented
client/caller SHA-1 digests. A PackageManager-reported signature can differ from
the APK signer on a signature-spoofing OS. Never enable HTTP body VERBOSE logging.
No account identifiers, tokens, challenge URLs, consent blobs, contact contents,
or arbitrary exception text are added to these diagnostics. Unknown reasons are
reported as UNKNOWN. Production authorization behavior and identities are unchanged; the separately
labeled probe override is diagnostic only. Retaining an HTTP status does not
itself repair a grant.

Cyclon Contacts already uses an editable built-in GoogleAccountType, including
phonetic fields and custom-field support. Adding duplicate primary metadata in
GmsCore would also be loaded as a Google extension, so this change does not add
a competing dataset. Settings support CONTACTS_SYNC_SETTINGS and its V3 action;
**New contact in this account** opens the Contacts editor with the chosen Google
account in two-way mode. The editor remains responsible for the user's default
account choice. Existing hourly periodic scheduling and OS dirty-row uploads
remain; private Chime/GCM registration is not implemented.

Required remaining work is remote linking/file-as, a supported push trigger,
actual Google grants/round trips,
and device/provider/editor acceptance. Device/SIM backup and Google-only portal,
consent migrations, social fields and calling-card services are outside the
basic account sync implementation. No Device contacts are imported automatically.

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

An optional [Google access probe](../people-sync-lab/README.md) tests the selected
account through ordinary AccountManager authorization and the production People
API parser. It is a separate debug app, with its own actual OAuth identity, and
does not replace the installed GmsCore or enable account sync.

## API references

- [Connections and sync tokens](https://developers.google.com/people/api/rest/v1/people.connections/list)
- [Create contacts](https://developers.google.com/people/api/rest/v1/people/createContact)
- [Update contacts and source etags](https://developers.google.com/people/api/rest/v1/people/updateContact)
- [Delete contacts](https://developers.google.com/people/api/rest/v1/people/deleteContact)
- [Android Contacts Provider](https://developer.android.com/identity/providers/contacts-provider)
- [Android authorization](https://developer.android.com/identity/authorization)

- [Contact photos](https://developers.google.com/people/api/rest/v1/people/updateContactPhoto)
- [Contact groups](https://developers.google.com/people/api/rest/v1/contactGroups)
- [Membership semantics](https://developers.google.com/people/api/rest/v1/people#ContactGroupMembership)
- [AOSP photo version and provider processing](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/main/src/com/android/providers/contacts/ContactsDatabaseHelper.java)
