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
and websites, including preferred-value flags. Photo and label synchronization, other advanced fields, contact
merge/alias handling, and guided conflict resolution remain future work.
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

## Tests

```sh
./gradlew :people-sync:test
./gradlew :play-services-core:assembleVtmDefaultDebug
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
adb -s EMULATOR_SERIAL shell am instrument -w \
  -e class org.microg.gms.people.ContactProviderTest \
  org.microg.gms.people.sync.android.test/androidx.test.runner.AndroidJUnitRunner
```

Physical testing is also available on an explicitly authorized `userdebug` or
`eng` phone (Android 10 or newer). Verify the serial first, install only the
standalone fixture APK, and pass the same serial as the instrumentation argument.
The test verifies that argument against the phone's actual serial before writing
contacts. A missing or mismatched argument refuses the run. This tests the
engine/provider integration, not GmsCore installation, Google authorization, or
real Google-side effects.

```sh
adb -s DEVICE_SERIAL shell am instrument -w \
  -e class org.microg.gms.people.ContactProviderTest \
  -e physicalDeviceSerial DEVICE_SERIAL \
  org.microg.gms.people.sync.android.test/androidx.test.runner.AndroidJUnitRunner
```

## API references

- [Connections and sync tokens](https://developers.google.com/people/api/rest/v1/people.connections/list)
- [Create contacts](https://developers.google.com/people/api/rest/v1/people/createContact)
- [Update contacts and source etags](https://developers.google.com/people/api/rest/v1/people/updateContact)
- [Delete contacts](https://developers.google.com/people/api/rest/v1/people/deleteContact)
- [Android Contacts Provider](https://developer.android.com/identity/providers/contacts-provider)
- [Android authorization](https://developer.android.com/identity/authorization)
