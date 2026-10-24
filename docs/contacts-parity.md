# CYC-721 Google Contacts parity comparison

Evidence date: October 23, 2026 (PT). Fork baseline: `57b45c03`, cyclonfork/main.
No phone, credential extraction, live Google request, installation or release was used for this comparison.

## Backend, authorization and identity (priority 0)

**GMS FSA2 does not use our public People v1 JSON connections endpoint.**
`ContactsSyncAdapterChimeraService.onCreate/onBind` creates `ftft`;
`ftft.b` constructs `fson` and invokes `ftfs.e`.
`fson`/`fsnv` use protobuf messages (`it*`, via `khnt`) and gRPC method names
`google.internal.people.v2.InternalPeopleService/...`.
`ftpz.f` calls **SyncPeople**; `ftqa.f` calls **ListContactGroups**;
`ftkl.a/d/g` call **UpdatePerson**, **BulkInsertContacts**, **DeletePeople**, respectively;
`ftmo.b`, `ftmx.b`, `ftmm.b` call **CreateContactGroups**, **UpdateContactGroups**,
**DeleteContactGroups**. `fson.c/e` call **GetPersonPhotoEncoded** and
**UpdatePersonPhoto**, and `ftkp.d` calls **DeletePersonPhoto**.
`itih` in the supplied jadx tree identifies the InternalPeopleService authority
as `people-pa.googleapis.com`. `fson.a` also permits flag-selected host/port;
server-pushed flags and the effective host on a stock device were not captured.
This is FSA2's private People backend, not the retired GData contacts feed.

**Auth path:** `ftmv` selects `fsnp.a(context, account)` or deprecated
`fsnp.b(context, account.name, "80")` according to `kcsv...ah()`.
`fsnp.a` sets the current UID, account and `context.getPackageName()` for both
caller identities, loads the scope list from `kcsv...U()`, and adds
`social_client_app_id=80`. `fsnp.b` uses a literal scope list containing
`https://www.googleapis.com/auth/peopleapi.legacy.readwrite` and legacy
`plus.*` scopes. Separately `fstp.a` defaults to `peopleapi.legacy.readwrite`
and permits a flag-selected scope list. These are user-account OAuth scopes
through GMS's authenticated RPC client context; the wrapper does not expose a
standalone OAuth client secret or imply an anonymous/API-key-only service.
**The exact effective scope list for current server flags and token grant for
microG are not established by this APK.** Do not describe a static default as
a captured stock-phone token or granted audience.

**Ours:** `ContactSyncService.scope/contactsToken` uses AccountManager
`oauth2:https://www.googleapis.com/auth/contacts[.readonly]`; `contactsApi`
sends Bearer-authenticated JSON REST to `people.googleapis.com/v1`.
`AuthManager.getPackageSignature/requestAuth` uses the actual requesting
package and PackageManager-reported signing digest in `AuthRequest.app(...)`. On a signature-spoofing OS that digest may differ from the APK signer. The
legacy request hardcodes Google callerSig separately; it does not set isGmsApp
for this AccountManager self-call. The auth endpoint is android.googleapis.com/auth.
Public API access depends
on that presented OAuth identity and Google's API/grant policy. Sign-in is not
proof of either public API or private FSA2 permission. Neither private scope
availability nor public scope refusal can be inferred from this source.
The separate live diagnosis must identify whether the failure is token minting,
API disabled/client identity, insufficient scope, or another stage.

Google's current migration documentation explicitly says `https://www.google.com/m8/feeds`
is an alias of `https://www.googleapis.com/auth/contacts`; replacing one with the
other is not an evidenced remedy. A private backend would require a tested grant
and complete wire/schema/error/recovery compatibility; changing the URL or
scope of this JSON client cannot implement it. Preserve public read-only and
two-way consent boundaries while investigating; no silent scope escalation or
borrowed package/certificate identity.

## Fields and reconciliation

`ftkk` gives the GMS write field set: person.about/address/birthday/calendar/
client_data/contact_group_membership/email/event/external_id/file_as/gender/im/
interest/language/name/nickname/occupation/organization/other_keyword/phone/
relation/sip_address/user_defined/website. Read masks also include photo and
metadata, plus flag-gated newer calling-card/background-photo/relationship data.
`ftnp` enumerates ContactsProvider MIME types, including standard fields,
photo, group_membership, relation, sip_address, IM, and Google custom MIME types
for user-defined fields and file-as. `ftii` reads raw-contact starred and other
local options; `fthy` asserts starred state. `ftix`, `ftnq`, `ftjt` operate on
GroupMembership. `ftpl`/`fttu` map and compare provider rows; `ftqq` writes
high-resolution photo content. `fthu` explicitly handles individual parsing
errors, rather than treating one entry as the entire account.

`ftpz.a/f` and `fthv` carry distinct sync/page tokens; `ftja` stores sync state.
`ftpz.d` de-duplicates contact identifiers. `ftnj.a` classifies auth, parse,
network and gRPC statuses per operation; `ftnj.b` extracts invalid group IDs.
The supplied source does **not** establish that Google's conflict strategy is
identical to ours. Do not weaken our journaling, narrow field masks, direct
readback, per-row assertions, unknown-create reconciliation, or no-deletes-from-
partial-reads guarantees based on these snippets. Google's APIs also acknowledge
sync-read propagation delay; sync tokens are not read-after-write receipts.

## Android and settings

Manifest `ContactsSyncAdapterService` is exported but statically disabled;
GMS dynamically selects/enables the sync owner. `res/U0W.xml` (resource
`xml/contacts_syncadapter`, 0x7f1900a9) specifies `com.google` /
`com.android.contacts`, leaving other sync-adapter flags at platform defaults.
The private delegation service has a Google signature permission; its Chimera
implementation in this APK logs that the coordinator is disabled.
`ftfs` enables Android syncability/automatic sync after its preparation flow.
`ContactsSyncIntentOperation` handles account change/removal and charging;
`fsll.a` requests contact sync for Chime tickles (optionally changed contact IDs).
`fthj` models periodic, upload, group-download, contact-field and photo plans.
Normal ContactsProvider dirty-row notifications go through Android's upload-
capable sync adapter, rather than requiring a permanent in-process observer.

`xml/people_contacts` (`res/B9x.xml`) is **read-only dataset=plus** and belongs to
`PeopleSyncService` with ALTERNATE_CONTACTS_STRUCTURE. It is not evidence that
the primary writable contact adapter should use the same read-only XML.
Primary account metadata/editor availability must be tested with Cyclon's actual
Contacts application (including its built-in com.google account handling).
GMS checks both legacy `ContactsContract.Settings.getDefaultAccount` (`ftjz`)
and newer RawContacts.DefaultAccount APIs (`ftiu`); choosing a user's default
or moving Device contacts requires an explicit user choice, not a sync side effect.

Manifest settings actions include CONTACTS_SYNC_SETTINGS,
CONTACTS_BACKUP_SYNC_SETTINGS and their V3 forms. `ContactsSyncCore*` and
`people.consentprimitive.*` implement consent/settings infrastructure. Basic
account selection, read/write consent, refresh, actionable errors and last
success are needed. Device/SIM backup, cross-device/tethered sync, Google
consent migration, notification portal, calling cards, ads/social features and
analytics are separate Google extras; they must not silently upload local
Device/SIM contacts when enabling this account sync.

## Gap and implementation table

| Area / severity | Fork at baseline | GMS evidence | Proposed implementation / acceptance |
|---|---|---|---|
| Backend and grants / critical | Public v1 JSON + contacts scopes, grant unproven | ftft.b → ftfs/ftmv → fsnp; fson/fsnv; ftpz.f | Capture live error stage first. Keep actual identity. Implement private FSA2 only with grant and schema proof; no speculative scope substitution. |
| Malformed entry CYC-645 / high | Person.parse aborts list page | fthu; per-operation ftnj | Quarantine individual malformed entries, preserve stored row, apply readable siblings, hold checkpoint and all absence-based deletes, expose partial status; test malformed IDs, fields and retries. |
| Photos / high | Unmapped, local photo blocks uploads | fson.c/e; ftkp; ftqq | Separate photo read/upload/delete, journal each cloud mutation, preserve image during unknown outcomes, use display-photo streams and bound images; test interruption and concurrent edits. |
| Labels and starred / high | Unmapped | ftqa/ftmo/ftmx/ftmm; ftix/ftnq/fthy | Groups table with source/etag/ownership; map My Contacts and starred system groups and membership. Journal group creates; never name-match ambiguous creates. Test rename/delete/conflict and multiple accounts. |
| Relations/events/IM/SIP/custom / medium | Unsupported locally; excluded remotely | ftkk, ftnp, ftpl/fttu | Add reversible typed mappings and preservation checks, anniversary/custom dates, Google custom MIME for userDefined. Public API does not document fileAs in updateContact's valid mask; defer write rather than send an invalid field mask. |
| Phonetic / medium | Already maps phonetic given/middle/family and org phonetic | ftkk/name mappings | Test round trip; retain unsupported phoneticFullName rather than losing it. |
| Linking / medium | Provider aggregation only; no remote merge/aliases | person identifiers and provider aggregation fields in fswc/ftii | Preserve provider aggregation; evaluate private merge/alias semantics separately. Do not merge contacts based on name/phone. |
| Metadata/settings entry / medium | No ContactsAccountType metadata; internal settings only | manifest/XML above; Cyclon Contacts AccountTypeProvider.loadTypes / GoogleAccountType | Cyclon Contacts already has editable built-in com.google handling. Do not add duplicate primary metadata as a Google extension. Add settings intent integration and verify actual editor. |
| Scheduling / medium | Hourly periodic, automatic dirty-row uploads already configured | ftfs/fthj/ContactsSyncIntentOperation/fsll | Retain OS scheduling; add supported authenticated push only with actual registration protocol, otherwise hourly is the honest latency limit. Verify reboot and token renewal. |
| Default account / medium | Not selected by sync | ftjz/ftiu | Offer Contacts editor/account selection; never change user's default without explicit choice. Test new contact saved to selected Google account. |
| Consent/status / medium | Opt-in scopes, staged activation failures, refresh and last success | ContactsSyncCore*, consentprimitive | Add partial status and actionable auth/API failure distinction. Preserve separate read-only/two-way consent. |
| Google extras / optional | Absent | backupandsync, tcs, portal, calling cards | Defer outside basic Google-account contacts sync; no automatic Device/SIM backup. |

## Evidence locations and reproducibility

Original evidence (read-only): `/Users/thomas/research/cyc700-switch/gms/264131035`.
New decompilation: `/Users/thomas/research/cyc721-contacts/classes11/sources`.
`classes11.dex` was extracted from original `base.apk`, not fetched from another
version. Decompiled with jadx 1.5.6, two workers; log `jadx-classes11.log`.
The batch ended with 64 decompiler errors. In particular, `ftfs.e` could not
be structurally reconstructed; its service entry call is visible in `ftft.b`,
and the concrete RPC/auth/field methods cited above are independently emitted.
This limits claims about the full coordinator's ordering/conflict behavior.
Use `class-index.tsv` to locate raw class definitions; unresolved/flag-selected
behavior is explicitly identified above. No decompiled Google code is copied
into the fork implementation.

Public documentation checked with Context7 on the evidence date:
- https://developers.google.com/people/contacts-api-migration
- https://developers.google.com/people/api/rest/v1/people.connections/list
- https://developers.google.com/people/api/rest/v1/people
- https://developers.google.com/people/api/rest/v1/people/updateContact
- https://developers.google.com/people/api/rest/v1/people/deleteContactPhoto

## Live diagnosis received

The separate `live-diagnosis.md` is now available. Neither the current buffers
nor the earliest/latest inspected saved bugreports retains a contacts grant/API
attempt. Android permissions are granted today. The disposable off-device
master-login HTTP 403 BadAuthentication precedes scope requests and does not
diagnose either Contacts scope. No owner retry or phone mutation was done.
Implementation adds fixed-vocabulary auth/API diagnostics and a non-enabling
check for each scope, returning before provider/baseline/scheduling changes.
The actual grant and backend remedy remain unproven until that check is run.

## Implementation status of this draft

Implemented: per-entry quarantine and partial reports; held metadata checkpoints
and pull-deletion fences; unreadable direct-read isolation; extended field and
date mappings; safe auth/API diagnostics; non-enabling scope checks; settings
and selected-account editor entry points. The same draft now implements contact
photo download/upload/delete, groups/labels, My Contacts and starred, including
provider code and durable mutation recovery. See `people-sync/README.md` for the
state layout and recovery rules.

Photo download hints have independent per-row durable work. Pending photo
mutation journals hold the contact metadata checkpoint so the next metadata pass
revisits cloud fields skipped before photo recovery cleared the journal. Photo
mutations use dedicated endpoints, bounded images,
provider display-photo reads and atomic photo/baseline writes. Unknown upload
replies require exact readback equality; transformed or different readback stays
uncertain without automatic replay. The endpoint has no etag precondition, so
the direct preflight check does not eliminate concurrent-cloud-write races.

Group creates carry unique clientData markers; loss recovery never name-matches
or repeats a create. Group metadata has a separate checkpoint and journal.
Deleting a label never requests contact deletion. Provider group deletion guards
its cascading member changes atomically and preserves edited/unowned rows.
Memberships and starred merge as sets, including concurrent local/cloud changes;
pre-mapping native edits and invalid empty membership merges remain preserved.
All mutation paths keep mode checks, actual client identity, row assertions,
no transparent retries and separate read-only/two-way consent. Expanded read
masks invalidate old metadata checkpoints through the existing mask check.

Not implemented: private FSA2 wire client or a grant/root-cause fix; remote
merge/alias and file-as writes (CYC-727); authenticated Chime/GCM registration
(CYC-728). Actual grants and device/provider/editor acceptance remain CYC-729.
CYC-725/726 now have implementation on PR #39 and remain subject to review,
merge and acceptance. CYC-721 must not be described as full stock parity or a
proven real-account activation fix.

## Validation receipt

Review follow-up: the combined metadata/photo recovery regression fails at
`766cdf4c` because a pending photo journal allows the metadata checkpoint to
advance. Holding that checkpoint fixes the loss: the next pass applies the cloud
name after photo recovery without replaying the upload. All 118 engine tests
passed (`photo-checkpoint-before.log` / `photo-checkpoint-after.log` in the
research folder). The Android build/lint receipts below precede this engine-only
follow-up; those checks were not rerun.

Final photo/group validation is recorded in
`/Users/thomas/research/cyc721-contacts/photos-groups-final-verification.log`.
All 117 engine tests passed (zero failures/errors/skips); the CI Mapbox Default
Debug app and provider instrumentation APK built successfully. All 21 provider
fixtures were compiled only. The prior two-check original-source harness fails at 57b45c03 and passes after
the quarantine fix (`baseline-proof/`). New fixtures cover provider photos,
version baselines, loss/restart/concurrent edits, group IDs and lifecycle,
My Contacts/starred, protected members and pre-mapping preservation. Provider
instrumentation is compiled, not executed. No phone/emulator or live Google
API was accessed by this implementation task; no grant/root-cause, release
signing, installation or device acceptance is established.

The API extension was checked against current official People v1 discovery
schemas (`people-v1-discovery.json` in the research folder) and documentation:
- https://developers.google.com/people/api/rest/v1/people/updateContactPhoto
- https://developers.google.com/people/api/rest/v1/people/deleteContactPhoto
- https://developers.google.com/people/api/rest/v1/contactGroups
- https://developers.google.com/people/api/rest/v1/people#ContactGroupMembership

AOSP provider evidence is saved under `provider-source/`: data rows initialize
DATA_VERSION to 0 and increment on updates; Photo handling preprocesses images
before insertion and creates the display file/thumbnail. Group insertion and raw
insertion suppress app auto-membership behavior for sync-adapter calls. The
implementation still replaces memberships explicitly within its guarded batch.
