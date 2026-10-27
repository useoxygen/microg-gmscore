# Google Contacts synchronization

Both **Download only** and **Two-way sync** use private Internal People v2 REST
at `https://people-pa.googleapis.com/v2/`. Google remains the remote store;
microG imports into Android ContactsProvider. Two-way enables local contact,
photo, label, membership and starred changes. Off disables scheduling. The adapter
advertises uploads so provider changes can trigger sync, but every outbound
mutation rechecks the current Two-way opt-in.

The owner-approved research in
`~/research/cyc721-contacts/private-probe-iteration.md` (including Round 2) proved
the request shapes on a disposable account. Public v1 GETs return SERVICE_DISABLED
with microG's presented identity. The public client remains dormant; production
never falls back to it. **These source/host results are not device acceptance.**

## Authorization and client context

Download uses `oauth2:https://www.googleapis.com/auth/peopleapi.readonly`;
Two-way uses `oauth2:https://www.googleapis.com/auth/peopleapi.readwrite`, through
normal microG AccountManager authorization. A run is pinned to its starting mode:
changing mode stops it and cannot upgrade a readonly token to a writer.

`PrivatePeopleContext` defines GMS FSA2, `useNewRequestMaskBehavior=true` and the
semantic version derived from microG's advertised `Constants.GMS_VERSION_CODE`.
No unrelated research version, API key, Spatula header, cookie or custom user agent
is copied. Reads/photos/groups/deletes use root context. **Create and UpdatePerson
use only `getPeopleRequest.context.*`; root context makes UpdatePerson fail.**

A fixed read rejected with INVALID_ARGUMENT/400, or an explicit HTTP426 upgrade
response, reports **needs update** and pauses automatic calls for that context
without I/O retry failures. Mutation validation failures instead clear the journal,
report a conflict and keep the dirty edit available for correction. A changed
binding or explicit Sync now/enable permits one new attempt. Known expired cursors
are handled first and restart a full read. Raw error bodies are never logged.

## Download protocol

GET `people/sync` uses pageSize 100, CONTACT_CENTRIC, CORE_ID and PRODUCT_BESTIES.
The canonical group projection is used consistently, without GDATA_COMPATIBILITY:
that extension can expose My Contacts/starred under different legacy hex IDs.
The binding includes this projection, mask and client version so incompatible
cursors cannot be reused. Full reads use FULL_SYNC_OK; incremental reads preserve
the starting token and FULL_SYNC_UNDESIRABLE across every page. All other
parameters stay identical. Only the last page supplies nextSyncToken. Interrupted
pagination restarts from the old durable token, with no partial snapshot import.

Private `personId=c<decimal>`, hex CONTACT source IDs and public v1 resources are
distinct identities. Internal ownership keys are `people/fsa_<hex>`. Exact int64
contact IDs must match CONTACT source IDs. Merged people are split only when field
metadata identifies their source. Profile data is never imported as owned contact
data. Ambiguous deduplication, malformed entries and unrepresentable dates are
quarantined, preserving stored copies. POST `people:batchGet` is a read and checks
per-item status, requested identity and CONTACT source identity; an error is never
NOT_FOUND.

The mapper covers names/phonetics, phone/email, addresses, organizations, notes,
birthdays, nicknames, websites, relations, events, IM/SIP and custom fields, plus
photos, groups, memberships/My Contacts and starred. Unknown attributes in a
changed repeated field, product/delegated memberships and merged-source updates
are held for review rather than lost during whole-array replacement. Per-field
generated annotations (including phone canonicalizedForm/uri and name display
variants) do not block edits or enter write payloads; writable email displayName
and free-form address formatting remain mapped.

## Two-way mutations and recovery

Every mutation has a durable provider journal **before** transport. Requests are
sequential; automatic transport retries and redirects are disabled.

- Update: PUT `people/c<decimal>`, `container=CONTACT`, fresh CONTACT source ID and
  sourceEtag, and `person.`-prefixed snake_case fieldMask. Each masked repeated
  field sends the complete desired array. Unmasked fields stay untouched.
- Membership/starred: JSON `contactGroupMembership` (verified wire field **104**)
  travels through UpdatePerson. Include desired label IDs, `myContacts` and
  `starred`; omission removes a membership while preserving the others. The denied
  dedicated mutateMemberships/star endpoints are not used.
- Create: POST `people` with nested post-get options and a clientData operation
  marker. Contact IDs are server allocated; none is invented. This implementation
  sends one contact per request, so no bulk limit is crossed.
- Delete: DELETE `people?contactId=<hex>`, shared context and
  `deletionContext.mutationContext.source=ANDROID_FSA2` plus actual manufacturer/model
  in androidDeviceInfo.id. A checked NOT_FOUND readback is required to acknowledge.
- Photos: PUT `people/photos` with CONTACT_PHOTO, hex containerId, PNG/JPEG format,
  bounded base64 bytes and root context; remove with DELETE `people/photos/delete`
  and hex contactId. Check embedded PersonResponse when present, otherwise read
  the contact back. These endpoints have no proven atomic etag precondition.
- Labels: create with CONTACT_GROUP; rename with both outer id and inner group.id,
  current fingerprint and `updateMask=name`; delete with `deleteContacts=false`.
  Check every embedded result and confirm absence. System groups cannot be deleted
  or renamed by this client.

FingerprintMismatch/409/412 is durably held as a conflict across engine/client
restarts, without another mutation. Unknown create replies are reconciled by a
full read and unique operation marker; absent/ambiguous markers stay uncertain and
never authorize a second POST. Contact/group clientData retention is schema-backed
and unit-tested, **not yet live-qualified**. Unknown updates/deletes use direct
readback. Local edits made after dispatch remain dirty when a reply is acknowledged.

Photo journals compare readback hashes or absence. Google may re-encode images;
if an upload reply is lost and the downloaded bytes differ, the operation stays
uncertain and is never replayed over a possible cloud edit. A confirmed mutation
receipt is acknowledged even if optional image caching fails. Unit tests exercise
these outcomes through the actual private client; no new live calls were made.

## Provider ownership, checkpoints and deletion safety

Private rows/groups retain `SYNC4=microg-people-fsa2-read-v1` as a stable ownership
marker (the name is historical). Queries and mutations are scoped by account,
type, dataset, owner and row version. Imports are read-only in Download only and
editable in Two-way; system group metadata remains read-only. Separate durable
private adoption floors can exclude pre-existing unowned rows in developer fixture
mode; customer defaults adopt the account's own imports. Before a new import can
upload or a download can insert another raw row, a complete account snapshot
reconciles it. An imported source ID must exactly match the verified remote
CONTACT source ID or resource name. Numeric aliases are never guessed, and an
unknown source ID never falls back to content matching. Source-less contacts can
match one complete mapped-field projection, or become a new contact. Device/app,
other-account, dataset and other-adapter rows remain separate.

Import adoption and duplicate deletion first require every populated native
column to survive the mapper's reconstruction (including type/label/protocol
semantics). Names use ContactsProvider's read-only `complete_name` query with only
the reconstructed mapped inputs, so redundant display names and inferred
full/phonetic styles are accepted using the provider's own rules. Generated phone
numbers must match the same E.164 formatter and detected-country/fallback path;
generated photo file IDs remain subject to the existing photo-content checks.
Organization office location, differing name/style/preferences, nickname types
and other lossy columns stay unowned for review, even if the MIME type is
recognized. Missing normalization proof never authorizes discarding a value.
Versioned import checks reconsider earlier name holds; a normalization service
becoming available can trigger another full reconciliation without a native edit.

Adoption records ownership and the remote etag without replacing native data.
Switch transfers no original etag or baseline: divergent populated fields and
pre-adoption deletions are retained as conflicts rather than overwriting cloud
changes. Omitted cloud fields are excluded from upload masks. Repeated complete
imports are removed with version-checked batches only when their data is already
accounted for; hashed import history preserves newer edits, photos, favorites and
label choices across subsequent transfers. Ambiguous copies stay unowned for
review. Unverified rows have a separate status and row/version cache; they do not
prevent other contacts' checkpoints, deletions or photo sync. Turning sync off
keeps unverified copies. Migration holds and Download only still gate every cloud
mutation. See [Switch contact acceptance](../cyclon/switch-contacts-acceptance.md).

On the first private sync, clean legacy public-owned contacts and
groups are removed atomically as the sync adapter, and cursors reset for a full
private download. Any dirty/pending legacy row pauses import with **needs review**;
no old edits or journals are discarded. Concurrent edits or group members outside
the cleanup also prevent deletion. No remote identity matching/relinking is guessed.
Public activation never succeeded in the live tests, so such imports are hypothetical.

Contact and group cursors are independent and bound to the backend/projection.
Production stages both cursors until the entire group/contact/photo pass finishes
without skips, conflicts or uncertainty. A write, provider or photo failure leaves
the previous durable cursors intact. A crash between the two final commits can
repeat reads, but cannot replay journaled mutations.

Every page is read before applying a snapshot. Partial/malformed results quarantine
source IDs, permit readable sibling progress, and hold checkpoints and pull
removals. Full-read absence requires direct NOT_FOUND. Dirty/pending/unowned rows
are preserved. Group removal waits for contact metadata to remove member rows;
it cannot cascade through contact baselines. Photos do not mutate after a partial
metadata/group read.

Only non-default CONTACT photos are owned; profile fallback/monograms are excluded.
GET `people/photosEncoded` uses CONTACT_PHOTO, hex containerId and useBytes=true.
PNG/JPEG base64 is bounded to 4 MiB; provider image processing emits at most
256 KiB per blob. Version-checked batches atomically replace photos and record
revision/hash baselines. Empty/malformed/error replies preserve stored images.

## Settings and acceptance boundary

Google accounts → Google Contacts sync shows one card per Google account,
modelled on Google's own settings: a sync switch (turning it on enables two-way
sync), a status line with the last sync and "Sync now", the actions a status needs
(permission settings, Reconnect, Try again) and an "Upload changes from this phone"
switch; turning it off selects Download only. Activation obtains Contacts
permissions and the requested OAuth grant, then verifies a cursor-only read before
saving the mode or scheduling. Turning sync off asks for confirmation, warns about
unsynced edits, and then removes the contacts and labels that sync imported or
adopted for that account (`SyncedContacts`), as Google's sync does; rows that
existed before sync was enabled, other adapters' rows and system groups are kept,
and the cursors are reset so a later enable downloads everything again. Raw auth
transport/parser logging is suppressed for Contacts self-requests even with
VERBOSE enabled. Diagnostics use allowlisted enums/statuses and no
contact/account/token/error-body content.

CYC-721/725/726 remain open pending review/merge and CYC-729 device/provider/editor
qualification. Unit-only areas include journal/restart/unknown-outcome behavior,
client marker retention and private extended-field writes. The separate live API
probes established CRUD, phone/email/note/name edits, photo set/replace/remove,
label create/rename/delete, membership/star cycles and 251-contact paging. They do
not prove this engine/provider implementation, readonly photo/group grants,
version-derived context, token renewal/reboot, multiple accounts or installed OS
behavior. Merged-source edits and unrepresented field edits deliberately hold;
transformed lost photo replies remain uncertain. No atomic precondition was
established for contact/photo deletion or photo replacement after the fresh read.

File-as/linking (CYC-727), authenticated push (CYC-728) and full
stock-GMS parity are outside this continuation. The owner-level default contact
account is unchanged. No phones, installs, releases or merges were used here.

## Tests

Review round 2 adds failing-before/passing-after coverage for generated private
readback fields and contact/group validation rejections: **178 engine tests and
11 focused Android host tests pass**, zero failures/errors/skips. Four host provider
tests execute the actual cleanup assertions/deletes on synthetic rows, covering
clean replacement, dirty/pending service refusal, a concurrent edit and protected
group members. The combined MapboxDefaultDebug app/provider fixture assemblies and
both Debug lint targets passed (`/tmp/cyc721-r2-final.log`). These tests do not
execute the native ContactsProvider on a device.

October 24, 2026 (PT), implementation head `8d4185b49`: **175 engine tests and
7 focused Android host tests passed**, zero failures,
errors or skips. The CI MapboxDefaultDebug app, standalone fixture APK and both
`people-sync-android:lintDebug` / `play-services-core:lintMapboxDefaultDebug` passed
in the final combined run. **25 provider fixtures compiled only; none executed.**
Recovery tests construct new engine/client instances with retained synthetic store
state; they are not Android process-restart or installed-device evidence.

```sh
ANDROID_HOME=$HOME/Library/Android/sdk timeout 1500 ./gradlew :people-sync:test :play-services-core:assembleMapboxDefaultDebug --max-workers=2
ANDROID_HOME=$HOME/Library/Android/sdk timeout 1500 ./gradlew :people-sync-android:assembleDebugAndroidTest --max-workers=2
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
