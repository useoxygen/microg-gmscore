# Google Calendar sync and Transfer Phone

Calendar sync is on by default, download only, for every visible Google account
that has no saved choice (CYC-2073); existing phones get the default with the
update (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, account changes and the first sync
of an account). The default is stored apart from the owner's choice
(`defaultEnabled`, not `enabled`), so a saved on, off or two-way choice is never
overridden and "no saved choice" stays distinguishable. Android's own account
switch for the authority is set once, when the default is recorded; turning it
off later is respected. An account Android marks not syncable for the authority
(`getIsSyncable` 0, which only an explicit choice sets) gets no default, and
Cyclon's device account never gets one, whatever `debug.cyclon.show_device_account`
says. Android's automatic switch alone is not read as an owner's off: Android
starts it off for every authority of a new account, microG's sign-in does not turn
it on, and our adapter's auto-initialization sets only `isSyncable` 1, so an
off switch is what every account that never opted in looks like. Existing download-only opt-ins keep their mode. Enable
**Two-way event sync** in Cyclon Services → Calendar sync to
authorize `https://www.googleapis.com/auth/calendar`; download-only mode uses
`https://www.googleapis.com/auth/calendar.readonly`. The authorization screen
finishes before two-way background scheduling is enabled. Turning sync off keeps
local events and saves the off choice.

The default needs no screen for the OAuth token, but not for the permission:

- GmsCore's own `calendar.readonly` token comes through the same AccountManager
  path contacts sync already uses in the background: `AuthManager` lets the request
  through while **Trust Google for app permissions** is on (`auth_manager_trust_google`,
  default on), and GmsCore asks as itself (`appIsGms`). If Google answers with a
  consent request instead, the token is missing and the status says
  `authorization`; **Reconnect** in Calendar sync is the one-tap fix.
- `READ_CALENDAR`/`WRITE_CALENDAR` are runtime permissions. Cyclon's images do not
  pre-grant them to GmsCore: partner_gms's
  `default-permissions-com.google.android.gms.xml` grants contacts but not calendar,
  and Android's default grant to calendar sync adapters covers only packages
  without a launcher activity, while GmsCore has one (Cyclon Services). Without the
  permission the account is still on by default; Calendar sync and Cyclon Services
  health say the permission is missing, and **Reconnect** asks for it. Adding the
  two permissions to the partner_gms default grants would remove that tap.

A calendar new to the phone syncs events when Google marks it shown in Google
Calendar (`selected`, not `hidden`) and the owner owns or writes it, when it is a
holiday calendar, and always for the primary calendar. Other calendars are
created with sync and visibility off; the owner's per-calendar choice on the
phone then wins, as before. In download-only mode Google's version replaces a
locally changed event (and Google's deletion removes it) instead of holding it as
a conflict for ever; a journal from an earlier two-way run still waits.

## Handing over to the Google Calendar app

Android gives the `com.android.calendar` slot for `com.google` accounts to one
sync adapter; ours wins because it is in the system image. The Google Calendar
app (`com.google.android.calendar`) ships Google's own adapter for the same slot
(`com.google.android.syncadapters.calendar.CalendarSyncAdapterService`), with
guests, push and Google's conflict rules. Where that app can sync, ours steps
aside and Google's fills the store; ours remains the fallback.

- **Condition** (`CalendarHandover.googleCanSync`): the app is installed and
  enabled, holds both `READ_CALENDAR` and `WRITE_CALENDAR` (it asks on first
  open; Google's adapter does not run without them), and declares an enabled
  `android.content.SyncAdapter` service whose meta-data names account type
  `com.google` and authority `com.android.calendar`.
- **Not while any account syncs two-way** (rule 1): the slot is per account type
  and authority, so it is all accounts or none. If any Google account has
  two-way on, ours keeps the slot for every account and Calendar sync says why
  ("Two-way sync is on for …, so the Google Calendar app's sync is not used").
  A transfer upload hold keeps ours the same way, because Google's adapter has no
  hold and would upload what Switch copies at once. There is no check of unsent
  rows at hand-over time: only download-only accounts are ever handed over, and
  in download only a local edit is not an upload (Google's copy wins).
- **Order of a hand-over** (rules 2 and 7):
  1. The hourly check is scheduled (WorkManager, unique, kept), so a process
     death later in the hand-over still leaves something that decides again.
  2. Ours stops writing: it sets its persisted "stepping aside" flag
     (`steppingAside` in GmsCore's preferences), cancels its running and pending
     syncs for the authority, removes its periodic sync and disables
     `CalendarSyncService` (`COMPONENT_ENABLED_STATE_DISABLED`, `DONT_KILL_APP`).
     Disabling the component does not stop a sync thread that already passed its
     checks, so the flag does: while it is set, the sync's "active" check fails at
     its next step and every store write of ours refuses. The flag stays set while
     ours is aside and is cleared when ours takes the slot back or undoes.
  3. Ours removes **all** of its calendars (`CAL_SYNC1=cyclon-calendar-v1`) and
     their events for every Google account, as the sync adapter, on the phone
     only, including accounts whose sync ours has off. Google's adapter then
     starts from nothing of ours instead of finding rows whose `_SYNC_ID`s are
     Google's ids. A local edit or delete in them is dropped by definition.
  4. Ours checks that Android gave the slot to the Google Calendar app (rule 5a,
     below). It then queries once more for calendars with our marker and removes
     any that appeared (a write that was already under way when the flag was
     set); if that fails, ours takes the slot back. Only then does it record that
     the removal is complete (`asideRowsRemoved`) and request a forced (manual,
     expedited) sync for every account whose sync is on.

  The hand-over is complete only with that record, not with the component
  state. An evaluation that finds `CalendarSyncService` disabled without it (a
  process death between disabling and finishing the removal) finishes the
  removal first; if that fails, or ours may hold rows it cannot reach without
  calendar permission, it takes the slot back like a hand-back (rule 2's undo,
  with a rebuild, since Google's adapter may have held the slot meanwhile).

  If the removal leaves a row with our marker, ours undoes: it enables its
  component again, registers its periodic syncs again, keeps the slot, and
  Calendar sync says so. Ours never steps aside while rows with our marker
  remain. Without calendar permission ours cannot remove them: it then does not
  try at all while it may hold rows (it created a calendar since its rows last
  left; a phone from before this change counts if any account ever synced), and
  says so; a phone where ours never wrote, or whose rows already left, steps
  aside without the permission. Android reassigns the slot when the component
  state changes (`RegisteredServicesCache` on `PACKAGE_CHANGED`); the owner's
  test on a Pixel 7a with `pm disable` showed it take the slot at once.
- **Per-calendar choices survive** (rule 3): before removing its calendars, ours
  remembers per account each calendar's visibility and sync choice
  (`VISIBLE`, `SYNC_EVENTS`) by Google calendar id. At a hand-back, a choice the
  owner made while Google's adapter held the calendar is newer: when the
  hand-back cleanup finds that adapter's copy of the calendar, ours takes
  `VISIBLE` and `SYNC_EVENTS` from it, and uses the remembered choice only for a
  calendar without a copy. When ours creates the calendar again it applies that
  choice once instead of the default from Google's flags.
- **Liveness** (rule 5), measured on the adapter, not the clock:
  - (a) Right after disabling ours, the hand-over waits up to 10 seconds for
    `ContentResolver.getSyncAdapterTypes()` to list the `com.google` /
    `com.android.calendar` type for the Google Calendar app's package (read from
    `SyncAdapterType.toString()`, which names the package since Android 10;
    the package name is not public API). If it still does not, ours takes the
    slot back at once.
  - (b) Each hourly check while ours is aside counts when the phone has a
    validated network and at least one account's sync is on (master sync and
    Android's switch for the account). After 6 such checks, an account whose
    sync is on and that has no calendar row created after the hand-over (ours
    records the store's highest calendar `_id` once its rows are gone) is not
    being synced: ours takes back the slot and Calendar sync names the account.
    A calendar with our marker never counts as a row Google's adapter created.
    This check does not run while GmsCore lacks the calendar permission: it
    cannot read the store then, and ours could not sync without it either.
    Opening Calendar sync, boot or a sync request does not count. The counter and
    the recorded `_id` are kept in GmsCore's preferences, so they survive a reboot
    and a GmsCore update.
  - After (a) or (b), ours does not step aside again until the Google Calendar
    app's version changes; there is no time-based retry.
- **Handing back**: when the condition stops holding (app removed, disabled,
  permission revoked), a two-way choice or transfer hold appears, liveness
  applies or the kill switch is set, ours returns to its manifest default and
  every account is marked for a rebuild.
- **Hand-back cleanup** (rule 4) runs inside the account's first sync. Once the
  calendar list has been fetched from Google and before any calendar syncs, ours
  drops its checkpoints and finds the calendars of the account without our
  marker that match a calendar in that list: its `CAL_SYNC1` (where Google's
  adapter puts the Google calendar id; seen on a Pixel 7a) or `_SYNC_ID` equals a
  listed id, or, when the list has a primary calendar, equals the account name.
  A row of this Google account carrying Google's own calendar id is that
  adapter's copy by definition, so no further test is applied. A calendar that
  matches nothing (another app's under the same account) is left untouched. A
  matched copy is cleaned only after ours has stored that calendar's download in
  the same run (`CalendarStore.calendarSynced`), so no event is missing in
  between; for that short time the calendar shows twice. If a calendar's
  download fails, its copy stays and the account stays marked for a rebuild, so
  the next sync downloads again and retries the cleanup; the rebuild ends once
  every matched calendar is done. In a copy the clean events are removed as the
  sync adapter (nothing reaches Google), then the calendar if it is empty. Each delete repeats its
  condition when it runs: an event only while `DIRTY=0`; a recurring master (or
  any row an exception points at) only in the batch that asserts no row with
  `ORIGINAL_ID` / `ORIGINAL_SYNC_ID` pointing at it is dirty; a calendar only in
  the batch that asserts no event is left in it. An absent local row is still
  never a server delete.
- **Edits Google's adapter had not uploaded** stay on the phone after a hand-back,
  in Google's old calendar next to our fresh copy. Ours does not upload them.
  Calendar sync counts them ("N changes made while the Google Calendar app was
  syncing were not uploaded") and says what the owner can do: turn the Google
  Calendar app back on, whose adapter then finds and uploads them (our rows leave
  again first), or make the change again in Google Calendar on the web.
- **When it is decided**: on boot, package replaced and account changes; when the
  app is fully removed or its data is cleared (both revoke its permissions, and
  both are broadcasts a manifest receiver of an app targeting Android 8+ still
  gets); at the start of every sync ours is asked to run (a permission grant sends
  no broadcast, but the Google Calendar app requests a sync when it starts, and
  the request reaches ours while ours holds the slot); when Calendar sync is
  opened; and hourly (WorkManager) while Google's adapter holds the slot, because
  `PACKAGE_ADDED`/`CHANGED`/`REPLACED` and permission revocation reach no manifest
  receiver of a target-29 app. A disabled app or revoked permission can therefore
  take up to about an hour to hand back. Only the hourly check counts for
  liveness.
- **Our schedule** while ours keeps the slot with the app installed: the app
  removes a periodic sync with empty extras for the authority when it is opened, so
  ours registers its hourly sync with the extra `cyclon_calendar_periodic` and
  registers it again at the start of every sync.
- **Owner choices** (`enabled`, `twoWay`, holds) are not changed by either
  direction. While aside, Android's per-account switches for the authority belong
  to Google's adapter too, so ours does not change them. Android's switch is per
  account and authority, not per adapter: an account the owner turned off (ours
  sets `isSyncable` 0 then) or switched off in Android's account settings stays
  off for Google's adapter, and the forced hand-over sync skips it.
- **Leaving two-way** (rule 6): download only lets Google's copy replace a local
  edit, so the settings switch refuses to leave two-way while unsent or
  conflicting rows exist in calendars ours uploads from, and says to tap
  **Sync now** or resolve the conflicts first. Download only stores every
  calendar read-only, so right after turning two-way on the local access levels
  say nothing; until a sync has written them from Google's calendar list, every
  dirty row in a syncing calendar counts as unsent.
- **Kill switch**: `adb shell setprop debug.cyclon.calendar_own_sync 1` keeps ours
  (until reboot). While ours is aside it runs no sync, so the property is read at
  the hourly check, when Calendar sync is opened, and on boot, package replaced and
  account changes, not at the next sync request. `persist.cyclon.calendar.own_sync=1`
  is read the same way and survives a reboot, but Cyclon's sepolicy must first
  let the shell set it and GmsCore read it (not part of this change). Our
  hand-back cleanup removes Google's clean rows; when the switch is cleared, the
  new hand-over's forced sync asks Google's adapter to refill them. Whether its
  adapter downloads rows it had synced before from such a request is not known;
  if events are missing afterwards, clear the Google Calendar app's storage and
  open it again (that hands back once, then over again with Google's adapter
  starting fresh).
- **Downgrade**: the disabled component state survives package updates. A GmsCore
  build without this code leaves `CalendarSyncService` disabled, and nothing syncs
  through ours. Recover with
  `adb shell pm enable com.google.android.gms/org.microg.gms.calendar.CalendarSyncService`
  (or `pm default-state` for the manifest default), then request a sync.
- **Status**: Calendar sync and Cyclon Services health say the Google Calendar
  app keeps the phone's calendar in sync (health `calendar=ready; code=google_calendar`)
  instead of showing ours as off or failing, but per account: an account whose
  Android switch is off shows as off (Calendar sync: "Off: sync for this account
  is off in Android's account settings, so the Google Calendar app does not sync
  it either"; health off when every account is, Sync & passwords "On for N of M
  accounts"), and master sync off shows as paused. The transfer provider's
  `google_calendar_sync` is per account in the same way.

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
`uploads_held`, `default_on`, `google_calendar_sync` and a `settings_intent`.
`default_on` is true while sync is on only by default (no saved owner choice;
download only). `google_calendar_sync` is true while the Google Calendar app's
own adapter keeps this account in sync (ours steps aside and Android's switch for
the account is on); the other fields still
describe the owner's choices for ours, which apply again if ours takes back the
slot. Results:

- `enabled`: the saved account opt-in exists and Android calendar permissions are
  granted. Check `two_way` and `uploads_held` separately; this is scheduling state,
  not a claim that any event was downloaded or uploaded.
- `saved_off`: the owner explicitly turned sync off. Show an owner prompt; do not
  retry enablement or overwrite their preference.
- `consent_required`: sync has never been enabled and no default is recorded yet.
  Offer calendar settings so the owner can choose download-only or two-way sync and
  complete authorization.
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
the same client event ID. The journal also retains the actual dispatched provider
projection: acknowledgment merges unchanged fields from Google while preserving
later local edits, including after a lost reply. If the dispatch value, current
phone value and acknowledged Google value of a field all differ, the phone data
and baseline stay unchanged and a durable conflict requires an owner choice.
Older journals without a dispatch snapshot also require that explicit choice
after concurrent edits. A known committed create retains its event ID through
either conflict choice. A master refresh waits while an
unsynced occurrence still depends on its inherited baseline; once that occurrence
settles or its conflict is resolved, the master and sync token can advance. An absent
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

### What a phone showed (Pixel 7a, 2026-11-14)

The PR's GmsCore was mounted over the installed APK on a rooted test phone (no signing, no image), so the boot and
account triggers were not exercised. Stepping aside, the status screen, taking the slot back with the kill switch, the
download-only default, and "a phone-side edit and delete in download-only mode do not reach Google" all behaved as
described here. One defect was found and fixed: Google's adapter keeps "synced up to" per calendar in the account's
`SyncState` row of CalendarProvider and afterwards only asks Google for later changes. After Google → ours → Google it
recreated the calendars and left three of four empty. Ours now deletes that account's `SyncState` row when it cleans up
after a hand-back; with that, the second hand-over refilled all four calendars (and the attendees) in about a minute.

## Required device acceptance: default and hand-over (CYC-2073)

No device was used by the implementation agent; none of this has run on a phone.

1. Phone without the Google Calendar app, account added before the update, no
   saved choice: after the update, Calendar sync shows the account on by default,
   download only; the store fills without opening any screen. If GmsCore lacks the
   calendar permission, Calendar sync and health say so and **Reconnect** fixes it
   in one tap. A saved off choice from before stays off.
2. Install the Google Calendar app, do not open it: ours keeps the slot. Open it and
   grant calendar access: within one sync request, `dumpsys package com.google.android.gms`
   shows `CalendarSyncService` disabled, `dumpsys content` shows Google's adapter
   for `com.android.calendar`, and the store refills with Google's rows, including
   attendees. Calendar sync and health say the Google Calendar app keeps the
   calendar in sync. Insert and delete an event through the store; confirm both
   reach Google.
3. Two accounts, one in two-way mode: open the Google Calendar app and grant
   access. Ours keeps the slot for both accounts, nothing of ours is removed, and
   Calendar sync says "Two-way sync is on for <account>". Switch that account to
   download only (after **Sync now** if it has unsent edits): the next sync
   request hands over. A transfer hold (`beginMigration`) keeps ours the same way.
4. Revoke the Google Calendar app's calendar permission, then disable the app, then
   uninstall it (separately): within about an hour (or on opening Calendar sync),
   ours is enabled again, rebuilds with one full download, the store holds each
   event once, and nothing was deleted in Google Calendar web.
5. With the app installed and permitted, `setprop debug.cyclon.calendar_own_sync 1`:
   ours takes back the slot at the next evaluation (open Calendar sync); clearing
   it hands over again, and the forced sync refills the store with each event once.
6. With ours holding the slot and the app installed without permission, open the
   app: `dumpsys content` still lists ours' hourly periodic sync (with
   `cyclon_calendar_periodic`).
7. Our rows absent: after step 2, the store holds no `cyclon-calendar-v1` calendar
   and no `deleted=1` event of ours (`content query --uri content://com.android.calendar/events`),
   Google's adapter writes its own calendars, and each event appears once in the
   calendar app. Before step 2, hide one calendar and turn sync off for another in
   the calendar app; after a later hand-back (step 4) both keep that choice. While
   Google's adapter holds the slot, show the hidden calendar again: after the
   hand-back it stays shown.
8. Download-only hand-over with phone-side changes: before granting the Google
   Calendar app access, edit one event and delete another on the phone (download
   only). Grant access: both changes are gone from the phone, Google's versions
   come back through Google's adapter, and nothing changed in Google Calendar web.
9. Liveness: restrict the Google Calendar app's background data
   (`cmd netpolicy add restrict-background-blacklist <its uid>`), so the phone stays
   online but Google's adapter cannot download, then grant it access. Ours steps
   aside; after 6 hourly checks online (about 6 hours; airplane mode does not
   count), ours takes back the slot, Calendar sync names the account and the
   reason, and ours does not hand over again, however often it is asked, until
   the Google Calendar app is updated. Reboot once in between: the count
   continues. Right after each hand-over, `dumpsys content` shows the slot with
   the Google Calendar app (rule 5a; if Android kept it with ours, ours took it
   back at once and Calendar sync says so).
10. Account switched off: with two accounts, turn calendar sync off for one in
    Android's account settings, then hand over. Google's adapter does not sync it;
    Calendar sync shows it as off, health and Sync & passwords say on for 1 of 2.
11. Two-way to download only: in two-way mode, edit an event with uploads held or
    offline, then switch to download only: the switch refuses and says to sync or
    resolve first; after **Sync now** uploads the edit, the switch works. Also
    switch a download-only account to two-way while offline, edit an event in a
    writable calendar, and switch back at once: refused (access levels not yet
    refreshed).
12. Hand-back keeps another app's calendar: create a local calendar under the
    Google account with another app (or `content insert` as a sync adapter with a
    `cal_sync1` Google does not know), then hand back (step 4): that calendar and
    its events stay; Google's adapter's calendars are replaced by ours.
13. Without calendar permission for GmsCore on a phone where ours synced before:
    granting the Google Calendar app access does not hand over, and Calendar sync
    says ours could not remove its own calendars first. Grant GmsCore the
    permission (**Reconnect**): the next evaluation hands over.

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
