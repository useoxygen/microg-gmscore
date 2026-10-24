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
version and use `CALLER_IS_SYNCADAPTER`; checkpoints advance only after writes.

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

## Current scope and release acceptance

Mapped fields are names, phone numbers, email addresses, postal addresses,
organizations, plain-text notes, complete or year-less birthdays, nicknames,
websites, relations, events/anniversaries, IM clients/protocols, SIP addresses,
and user-defined fields, including preferred-value flags. Dates remain ASCII
ISO strings regardless of the device locale; invalid or partially represented
events cannot be silently replaced by another event edit. Photo and label
synchronization, file-as, contact merge/alias handling, and guided conflict
resolution remain future work.
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

Diagnostics under `GmsContactSync` include fixed stages, requested scope, numeric
HTTP status, allowlisted auth/API reason/status codes and the public presented
client/caller SHA-1 digests. A PackageManager-reported signature can differ from
the APK signer on a signature-spoofing OS. Never enable HTTP body VERBOSE logging.
No account identifiers, tokens, challenge URLs, consent blobs, contact contents,
or arbitrary exception text are added to these diagnostics. Unknown reasons are
reported as UNKNOWN. The underlying authorization behavior and identities are
unchanged; retaining an HTTP status does not itself repair a grant.

Cyclon Contacts already uses an editable built-in GoogleAccountType, including
phonetic fields and custom-field support. Adding duplicate primary metadata in
GmsCore would also be loaded as a Google extension, so this change does not add
a competing dataset. Settings support CONTACTS_SYNC_SETTINGS and its V3 action;
**New contact in this account** opens the Contacts editor with the chosen Google
account in two-way mode. The editor remains responsible for the user's default
account choice. Existing hourly periodic scheduling and OS dirty-row uploads
remain; private Chime/GCM registration is not implemented.

Required remaining work is photos, label/group lifecycle and starred mapping,
remote linking/file-as, a supported push trigger, actual Google grants/round trips,
and device/provider/editor acceptance. Device/SIM backup and Google-only portal,
consent migrations, social fields and calling-card services are outside the
basic account sync implementation. No Device contacts are imported automatically.

## Tests

```sh
./gradlew :people-sync:test
./gradlew :play-services-core:assembleMapboxDefaultDebug
./gradlew :people-sync-android:assembleDebugAndroidTest
```

`ContactProviderTest` runs on an isolated emulator with a standalone library
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
