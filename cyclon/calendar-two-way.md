# Google Calendar sync and Transfer Phone

Calendar sync is opt-in per Google account. Existing download-only opt-ins keep
their mode. Enable **Two-way event sync** in Cyclon Services → Calendar sync to
authorize `https://www.googleapis.com/auth/calendar`; download-only mode uses
`https://www.googleapis.com/auth/calendar.readonly`. The authorization screen
finishes before two-way background scheduling is enabled. Turning sync off keeps
local events and saves the off choice.

An account opt-in covers its Google calendar list, including calendars Switch
creates later. CalendarProvider's per-calendar `SYNC_EVENTS` choice is retained.
Only selected calendars for which Google grants `writer` or `owner` access can
upload. Read-only calendars stay read-only. Calendar creation, calendar deletion,
and changing calendar sharing are outside event sync; they are never inferred
from local calendar rows.

## Transfer Phone contract

Authority: `content://org.microg.gms.migration.calendar`. Use
`ContentResolver.call(uri, method, googleAccountName, null)`. The explicit
account name must match an existing `com.google` account; there is no first-account
fallback. Calls require `android.permission.NETWORK_SETUP_WIZARD`, and additionally
the caller must be the Android system UID, GmsCore itself, or the system-installed
`ai.cyclon.migration` companion. `call()` verifies Binder identity independently
of the manifest permission.

Methods:

- `beginMigration`: persist an account-scoped upload hold before starting Switch.
  Downloads continue. The hold survives process death, mode changes, and transfer
  completion. A caller cannot release it.
- `enableAfterTransfer`: refresh an already-enabled account using automatic sync
  rules. It preserves the selected mode and never overrides a saved off choice,
  missing OAuth consent, master sync, or the account's automatic sync setting.
- `status`: return current choices without changing them.

Every successful account lookup returns `result`, `enabled`, `two_way`,
`uploads_held`, and a `settings_intent`. Results:

- `enabled`: the saved account opt-in exists and Android calendar permissions are
  granted. Check `two_way` and `uploads_held` separately; this is scheduling state,
  not a claim that any event was downloaded or uploaded.
- `saved_off`: the owner explicitly turned sync off. Show an owner prompt; do not
  retry enablement or overwrite their preference.
- `consent_required`: sync has never been enabled. Offer calendar settings so the
  owner can choose download-only or two-way sync and complete authorization.
- `permission_required`: a saved opt-in exists but Android calendar permission
  is missing. Offer calendar settings.
- `no_account`: no matching Google account exists. No preferences were changed.

`settings_intent` targets the exported, `NETWORK_SETUP_WIZARD`-protected alias
`com.google.android.gms/org.microg.gms.calendar.CalendarSyncSettings`; it puts the
chosen account first. Launch it only after the owner accepts the companion's
prompt. Settings is also the route for an owner to enable two-way sync after a
download-only transfer. **Resume uploads** releases the migration hold. Existing
OAuth consent is never silently expanded by the companion.

## Event integrity

Writes use per-event etags and conditional `If-Match`; sequence numbers help
detect stale versions. Only modeled fields that changed locally are patched, so
attendees, conferencing and other remote-only fields survive. Overlapping edits
are held. Settings offers **Resolve calendar conflicts**, with an explicit
account-wide choice for currently held conflicts: keep phone changes or use
Google changes. That choice does not approve conflicts discovered later.
Unrepresented provider edits, including local attendee, organizer and guest-policy
changes, remain held rather than being silently dropped or uploaded. Keeping
phone changes does not bypass those safety checks. If a recurring occurrence no
longer exists on Google, keeping phone changes retains the conflict; use Google
changes or create a separate event rather than changing the parent series.

The provider stores the last server representation, a durable mutation journal,
and the fingerprint of the local edit before each request. Create retries use
the same client event ID. Acknowledgment preserves newer local edits. An absent
local row is never a server-delete request: only an explicit dirty tombstone
with an owned server identity can delete remotely. Losing the local calendar
also loses its sync token, so a provider wipe downloads a fresh snapshot.
Incremental tokens advance only after all pages and recoverable local work are
settled. Expired-token recovery fetches a replacement snapshot before pruning
clean owned rows.
The first two-way run refreshes the old download-only projection in full. Dirty
legacy rows acquire newly represented privacy, availability and end-zone metadata
from a verified Google revision; ambiguous inherited reminders require an owner
choice rather than being changed into fixed overrides during the upgrade.

## Required device acceptance (parent workstream)

No device or authenticated Google calls are run by the implementation agent.
On a test account and Cyclon phone:

1. Call `beginMigration`, transfer iPhone calendars through Switch over USB, and
   confirm the selected Google account really contains the exported calendars.
   Call `enableAfterTransfer`; accept the settings prompt and enable two-way
   sync. Resume uploads after inspecting the copied events.
2. Open Etar and verify calendars/events are visible. Confirm writable Google
   calendars permit editing and read-only shared calendars do not.
3. Create, edit and delete an event on the phone, including popup reminders.
   Verify the results in Google Calendar web. Edit a different event on the web
   and verify the phone receives it. Check all-day events across the DST boundary,
   a named-zone recurring series, one moved occurrence and one deleted occurrence.
   Repeat moved/deleted occurrence checks for an all-day series: live acceptance
   of the instance lookup query is required in addition to the mocked HTTP test.
4. Sync twice more and count event IDs/instances: no duplicates. Interrupt a
   create after sending it, restart sync, and confirm the same server ID is used.
5. Edit the same field on web and phone before syncing. Confirm a held conflict,
   then exercise both settings resolution choices on separate fixture events.
6. Deselect one calendar's sync setting and change a local fixture event in it;
   verify no server mutation. Begin another migration and verify uploads stay
   held until explicitly resumed. Disable account sync and repeat transfer
   completion; verify `saved_off` and no automatic re-enable.
7. On disposable fixture data, wipe CalendarProvider storage, then sync. Confirm
   server events remain intact and redownload once. Revoke calendar permission
   and OAuth access separately; confirm honest errors and no local data loss.

Host tests cover the engine, HTTP request contract, and companion preferences.
Provider instrumentation tests cover real CalendarProvider mapping and dirty
state; running those and the acceptance steps above remains separate from JVM
validation. This source change needs the usual source pin, signed microG build
and device delivery before it reaches phones.
