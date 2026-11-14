/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.content.ContentProvider
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.calendar.sync.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException

/** Runs the production store and engine together. SQLite executes selections and atomic batch assertions;
 * this fixture does not model CalendarProvider's framework triggers, recurrence expansion or permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CalendarStoreEngineTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val account = Account("calendar-store-fixture@example.invalid", "com.google")
    private val calendar = RemoteCalendar("chosen", "Fixture", "UTC", "#64748b", listOf(10), accessRole = "owner")
    private lateinit var provider: CalendarSqlFixtureProvider
    private lateinit var client: ContentProviderClient
    private lateinit var prefs: CalendarSyncPreferences
    private lateinit var api: Server

    @Before fun setup() {
        context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE).edit().clear().commit()
        provider = Robolectric.buildContentProvider(CalendarSqlFixtureProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        client = requireNotNull(context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY))
        prefs = CalendarSyncPreferences(context, account).also { it.configure(true, true) }
        api = Server()
    }
    @After fun cleanup() { client.close(); provider.database.close() }
    private fun store() = AndroidCalendarStore(client, account, prefs)
    private fun sync(): CalendarReport = CalendarSync(api, store(), { true }, { prefs.uploadsAllowed }).sync()
    private fun event(id: String = "event", title: String = "original", location: String = "room", sequence: Int = 1,
        etag: String = "etag1", recurrence: Boolean = false): RemoteEvent {
        val json = JSONObject().put("id", id).put("etag", etag).put("sequence", sequence)
            .put("summary", title).put("location", location).put("description", "details")
            .put("start", JSONObject().put("dateTime", "2026-10-26T16:00:00Z").put("timeZone", "UTC"))
            .put("end", JSONObject().put("dateTime", "2026-10-26T17:00:00Z").put("timeZone", "UTC"))
            .put("reminders", JSONObject().put("useDefault", false).put("overrides",
                JSONArray().put(JSONObject().put("method", "popup").put("minutes", 10))))
        if (recurrence) json.put("recurrence", JSONArray().put("RRULE:FREQ=DAILY;COUNT=3"))
        return GoogleCalendarApi.parseEvent(json, calendar)
    }
    private fun seed(base: RemoteEvent): Long {
        val store = store()
        store.calendar(calendar)
        assertTrue(store.event(calendar, base))
        store.checkpoint(calendar.id, "initial")
        api.remote[base.id] = base
        return row(Events._SYNC_ID + "=?", arrayOf(base.id)).getAsLong(Events._ID)
    }
    private fun row(selection: String, args: Array<String>): ContentValues = requireNotNull(client.query(
        Events.CONTENT_URI, null, selection, args, null)).use { cursor ->
        check(cursor.moveToFirst())
        ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }
    }
    private fun row(id: Long) = row("${Events._ID}=?", arrayOf(id.toString()))
    private fun edit(id: Long, values: ContentValues) {
        values.put(Events.DIRTY, 1)
        assertEquals(1, client.update(Events.CONTENT_URI, values, "${Events._ID}=?", arrayOf(id.toString())))
    }
    private fun title(id: Long, value: String) = edit(id, ContentValues().apply { put(Events.TITLE, value) })
    private fun assertNoFailure(report: CalendarReport) { if (report.failure != null) throw AssertionError("Sync failed", report.failure) }

    // Download only uploads nothing: Google's later version replaces a local edit instead of holding it for ever (CYC-2073).
    @Test fun downloadOnlyDirtyRowTakesGooglesLaterVersion() {
        prefs.configure(true, false)
        val id = seed(event())
        title(id, "phone edit")
        api.remote["event"] = event(title = "web edit", sequence = 2, etag = "web-etag")
        val report = sync()
        assertNoFailure(report)
        assertEquals(0, report.conflicts)
        assertEquals("web edit", row(id).getAsString(Events.TITLE))
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
        assertTrue(api.patches.isEmpty())
        assertEquals("token-10", store().checkpoint(calendar.id))
    }
    // Two-way keeps holding the same edit for upload, and counts as unsent for the hand-over to Google's adapter.
    @Test fun twoWayDirtyRowIsUnsentUntilUploaded() {
        val id = seed(event())
        assertFalse(store().hasUnsentChanges())
        title(id, "phone edit")
        assertTrue(store().hasUnsentChanges())
        assertNoFailure(sync())
        assertEquals("phone edit", api.remote["event"]!!.title)
        assertFalse(store().hasUnsentChanges())
    }
    private fun foreignCalendar(sync1: String?, syncId: String? = null, of: Account = account, visible: Int = 1, syncing: Int = 1): Long =
        ContentUris.parseId(requireNotNull(client.insert(Calendars.CONTENT_URI, ContentValues().apply {
        put(Calendars.ACCOUNT_NAME, of.name); put(Calendars.ACCOUNT_TYPE, of.type)
        put(Calendars._SYNC_ID, syncId); put(Calendars.CAL_SYNC1, sync1); put(Calendars.SYNC_EVENTS, syncing); put(Calendars.VISIBLE, visible)
    })))
    /** The hand-back cleanup of one run in which every listed calendar's download was stored. */
    private fun handBack(calendars: List<RemoteCalendar>) = store().run { calendarList(calendars); calendars.forEach(::calendarSynced) }
    private fun foreignEvent(calendar: Long, remote: String?, dirty: Boolean, parent: Long? = null, rrule: String? = null): Long =
        ContentUris.parseId(requireNotNull(client.insert(Events.CONTENT_URI, ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events._SYNC_ID, remote); put(Events.TITLE, "copy of $remote")
            put(Events.DTSTART, 0L); put(Events.DTEND, 3_600_000L); put(Events.DIRTY, if (dirty) 1 else 0)
            if (parent != null) put(Events.ORIGINAL_ID, parent)
            if (rrule != null) put(Events.RRULE, rrule)
        })))
    private fun count(table: Uri, selection: String, vararg args: String) =
        requireNotNull(client.query(table, null, selection, arrayOf(*args), null)).use { it.count }
    // Hand-back: Google's adapter left its own copy of "chosen" (CAL_SYNC1 = Google's calendar id). Inside the first sync, once
    // the calendar list is in, its clean copies go from this phone only and are downloaded again in the same run; an unsent
    // edit and its series stay and are counted; another app's calendar under the account, matching nothing, stays untouched.
    @Test fun firstSyncAfterHandBackRebuildsWithoutDoublingOrDeletingOnGoogle() {
        prefs.configure(true, false)
        api.remote["event"] = event()
        val google = foreignCalendar("chosen")
        foreignEvent(google, "event", dirty = false)
        val master = foreignEvent(google, "series", dirty = false, rrule = "FREQ=DAILY;COUNT=3")
        foreignEvent(google, null, dirty = true, parent = master)
        val other = foreignCalendar(null)
        foreignEvent(other, null, dirty = false)
        prefs.rebuild = true
        api.checkpoints.clear()
        assertNoFailure(sync())
        assertFalse(prefs.rebuild)
        assertEquals(listOf<String?>(null), api.checkpoints)
        assertEquals(2, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", google.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events._ID}=?", master.toString()))
        assertEquals(1, count(Calendars.CONTENT_URI, "${Calendars._ID}=?", other.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", other.toString()))
        assertEquals(1, store().strandedChanges())
        assertEquals(1, count(Events.CONTENT_URI, "${Events._SYNC_ID}=? AND ${Events.SYNC_DATA1}=?", "event", AndroidCalendarStore.MARKER))
        assertTrue(api.patches.isEmpty()); assertEquals(0, api.creates)
        assertEquals("event", api.remote.keys.single())
    }
    // A copy matches by CAL_SYNC1 or _SYNC_ID, and the primary calendar also by the account name; nothing else is cleaned.
    @Test fun handBackCleansOnlyCalendarsGoogleStillHas() {
        val team = foreignCalendar(null, syncId = "team"); foreignEvent(team, "a", dirty = false)
        val primary = foreignCalendar(account.name); foreignEvent(primary, "b", dirty = false)
        val unknown = foreignCalendar("gone@group.calendar.google.com"); foreignEvent(unknown, "c", dirty = false)
        val elsewhere = foreignCalendar("team", of = Account("second@example.invalid", "com.google")); foreignEvent(elsewhere, "d", dirty = false)
        prefs.rebuild = true
        handBack(listOf(RemoteCalendar("team", "Team", "UTC", "#64748b", emptyList()),
            RemoteCalendar("primary-id", "Me", "UTC", "#64748b", emptyList(), primary = true)))
        assertEquals(0, count(Calendars.CONTENT_URI, "${Calendars._ID} IN (?,?)", team.toString(), primary.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", unknown.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", elsewhere.toString()))
        assertFalse(prefs.rebuild)
        // Only the first sync after a hand-back cleans.
        val again = foreignCalendar("team"); foreignEvent(again, "e", dirty = false)
        handBack(listOf(RemoteCalendar("team", "Team", "UTC", "#64748b", emptyList())))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", again.toString()))
    }
    // Google's adapter only asks for changes after the time in its SyncState row: without the row it downloads in full again.
    @Test fun handBackClearsGooglesSyncStateOfThisAccountOnly() {
        fun state(of: Account) = provider.database.execSQL("INSERT INTO syncstate (account_name, account_type, data) VALUES (?,?,?)",
            arrayOf(of.name, of.type, "state"))
        val other = Account("second@example.invalid", "com.google")
        state(account); state(other)
        prefs.rebuild = true
        handBack(listOf(RemoteCalendar("primary-id", "Me", "UTC", "#64748b", emptyList(), primary = true)))
        assertEquals(listOf(other.name), provider.database.rawQuery("SELECT account_name FROM syncstate", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) } })
        // Not outside a hand-back: an ordinary sync of ours leaves the table alone.
        state(account)
        handBack(listOf(RemoteCalendar("primary-id", "Me", "UTC", "#64748b", emptyList(), primary = true)))
        assertEquals(2, provider.database.rawQuery("SELECT COUNT(*) FROM syncstate", null).use { it.moveToFirst(); it.getInt(0) })
    }
    // Each hand-back delete is decided when it runs: an occurrence another app changes meanwhile keeps its series.
    @Test fun handBackKeepsASeriesWhoseOccurrenceChangedMeanwhile() {
        val google = foreignCalendar("chosen")
        val master = foreignEvent(google, "series", dirty = false, rrule = "FREQ=DAILY;COUNT=3")
        var occurrence = -1L
        provider.beforeBatch = { occurrence = foreignEvent(google, null, dirty = true, parent = master) }
        prefs.rebuild = true
        handBack(listOf(calendar))
        assertEquals(1, count(Events.CONTENT_URI, "${Events._ID}=?", master.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events._ID}=?", occurrence.toString()))
        assertEquals(1, count(Calendars.CONTENT_URI, "${Calendars._ID}=?", google.toString()))
        assertEquals(1, store().strandedChanges())
    }
    // ... and an event another app adds meanwhile keeps its calendar.
    @Test fun handBackKeepsACalendarThatGainedAnEventMeanwhile() {
        val google = foreignCalendar("chosen")
        foreignEvent(google, "single", dirty = false)
        provider.beforeBatch = { foreignEvent(google, null, dirty = true) }
        prefs.rebuild = true
        handBack(listOf(calendar))
        assertEquals(1, count(Calendars.CONTENT_URI, "${Calendars._ID}=?", google.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", google.toString()))
    }
    // Hand-over: our calendars of this account leave with every event, dirty ones included; other accounts' and other apps'
    // calendars stay.
    @Test fun removingOurRowsTakesOnlyOurCalendarsOfThisAccount() {
        title(seed(event()), "phone edit")
        val other = Account("second@example.invalid", "com.google")
        AndroidCalendarStore(client, other, CalendarSyncPreferences(context, other).also { it.configure(true) }).calendar(calendar)
        val foreign = foreignCalendar("google-id"); foreignEvent(foreign, "x", dirty = true)
        assertTrue(store().removeOwnRows())
        assertEquals(0, count(Calendars.CONTENT_URI, "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.CAL_SYNC1}=?", account.name, AndroidCalendarStore.MARKER))
        assertEquals(0, count(Events.CONTENT_URI, "${Events.SYNC_DATA1}=?", AndroidCalendarStore.MARKER))
        assertEquals(1, count(Calendars.CONTENT_URI, "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.CAL_SYNC1}=?", other.name, AndroidCalendarStore.MARKER))
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", foreign.toString()))
    }
    // The owner's per-calendar choices outlive the hand-over: a calendar ours creates again takes them, not Google's flags.
    @Test fun perCalendarChoicesSurviveTheHandOver() {
        val mine = RemoteCalendar("mine", "Mine", "UTC", "#64748b", emptyList(), accessRole = "owner", selected = true)
        val reader = RemoteCalendar("reader", "Shared", "UTC", "#64748b", emptyList(), accessRole = "reader", selected = true)
        store().calendar(mine); store().calendar(reader)
        fun choice(id: String) = requireNotNull(client.query(Calendars.CONTENT_URI, arrayOf(Calendars.VISIBLE, Calendars.SYNC_EVENTS),
            "${Calendars._SYNC_ID}=?", arrayOf(id), null)).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        assertEquals(1 to 1, choice("mine")); assertEquals(0 to 0, choice("reader"))
        client.update(Calendars.CONTENT_URI, ContentValues().apply { put(Calendars.VISIBLE, 0); put(Calendars.SYNC_EVENTS, 0) }, "${Calendars._SYNC_ID}=?", arrayOf("mine"))
        client.update(Calendars.CONTENT_URI, ContentValues().apply { put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1) }, "${Calendars._SYNC_ID}=?", arrayOf("reader"))
        assertTrue(store().removeOwnRows())
        store().calendar(mine); store().calendar(reader)
        assertEquals(0 to 0, choice("mine")); assertEquals(1 to 1, choice("reader"))
    }
    // A choice the owner made while Google's adapter held a calendar is newer than the one remembered at the hand-over: at the
    // hand-back ours takes it from that adapter's copy, and the remembered one only for a calendar without a copy.
    @Test fun handBackTakesTheChoiceFromGooglesCopyOverTheRememberedOne() {
        prefs.configure(true, false)
        val mine = RemoteCalendar("mine", "Mine", "UTC", "#64748b", emptyList(), accessRole = "owner", selected = true)
        val team = RemoteCalendar("team", "Team", "UTC", "#64748b", emptyList(), accessRole = "owner", selected = true)
        store().calendar(mine); store().calendar(team)
        client.update(Calendars.CONTENT_URI, ContentValues().apply { put(Calendars.VISIBLE, 0) }, "${Calendars._SYNC_ID}=?", arrayOf("team"))
        assertTrue(store().removeOwnRows()) // remembers mine (1, 1) and team (0, 1)
        foreignCalendar("mine", visible = 0, syncing = 0) // the owner turned it off while Google's adapter held it
        fun choice(id: String) = requireNotNull(client.query(Calendars.CONTENT_URI, arrayOf(Calendars.VISIBLE, Calendars.SYNC_EVENTS),
            "${Calendars._SYNC_ID}=? AND ${Calendars.CAL_SYNC1}=?", arrayOf(id, AndroidCalendarStore.MARKER), null)).use { it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        prefs.rebuild = true
        val store = store()
        store.calendarList(listOf(mine, team))
        store.calendar(mine); store.calendar(team)
        assertEquals(0 to 0, choice("mine")); assertEquals(0 to 1, choice("team"))
    }
    // No gap: a hand-back whose download of a calendar fails keeps Google's copy of it and stays a rebuild; the next sync
    // stores the download, then removes the copy.
    @Test fun handBackKeepsGooglesCopyUntilOursHasStoredTheDownload() {
        prefs.configure(true, false)
        api.remote["event"] = event()
        val google = foreignCalendar("chosen")
        foreignEvent(google, "event", dirty = false)
        prefs.rebuild = true
        api.failEvents = true
        assertEquals(1, sync().failedCalendars)
        assertTrue(prefs.rebuild)
        assertEquals(1, count(Events.CONTENT_URI, "${Events.CALENDAR_ID}=?", google.toString()))
        api.failEvents = false
        assertNoFailure(sync())
        assertFalse(prefs.rebuild)
        assertEquals(0, count(Calendars.CONTENT_URI, "${Calendars._ID}=?", google.toString()))
        assertEquals(1, count(Events.CONTENT_URI, "${Events._SYNC_ID}=? AND ${Events.SYNC_DATA1}=?", "event", AndroidCalendarStore.MARKER))
    }
    // While ours steps aside for Google's adapter every store write refuses, so a sync already running cannot write after
    // our rows left.
    @Test fun storeWritesRefuseWhileSteppingAside() {
        prefs.configure(true, false)
        context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE).edit().putBoolean(CalendarSyncPreferences.STEPPING_ASIDE, true).commit()
        assertTrue(prefs.enabled); assertFalse(prefs.mayWrite)
        assertThrows(IllegalStateException::class.java) { store().calendar(calendar) }
        assertThrows(IllegalStateException::class.java) { store().calendarList(listOf(calendar)) }
        assertEquals(0, count(Calendars.CONTENT_URI, "${Calendars.CAL_SYNC1}=?", AndroidCalendarStore.MARKER))
        assertFalse(store().uploadAllowed(calendar))
    }
    // Right after switching from download only, every calendar is still stored read-only: until Google's list has been read
    // again, every dirty row of a syncing calendar counts as unsent, so leaving two-way cannot drop it.
    @Test fun unsentChangesRightAfterSwitchingToTwoWayIgnoreStaleAccessLevels() {
        prefs.configure(true, false)
        val id = seed(event())
        prefs.configure(true, true)
        title(id, "phone edit")
        assertTrue(store().hasUnsentChanges())
        store().calendarList(listOf(calendar.copy(accessRole = "reader")))
        assertFalse(store().hasUnsentChanges())
        store().calendarList(listOf(calendar))
        assertTrue(store().hasUnsentChanges())
    }
    // Only calendars ours uploads from hold unsent changes: read-only, unsynced and removed calendars never upload.
    @Test fun unsentChangesCountOnlyCalendarsOursUploadsFrom() {
        title(seed(event()), "phone edit")
        store().calendarList(listOf(calendar))
        assertTrue(store().hasUnsentChanges())
        val restore = ContentValues().apply { put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER); put(Calendars.SYNC_EVENTS, 1); putNull(Calendars.CAL_SYNC2) }
        for (values in listOf(ContentValues().apply { put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ) },
            ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, ContentValues().apply { put(Calendars.CAL_SYNC2, "removed") })) {
            assertEquals(1, client.update(Calendars.CONTENT_URI, values, "${Calendars.CAL_SYNC1}=?", arrayOf(AndroidCalendarStore.MARKER)))
            assertFalse(store().hasUnsentChanges())
            client.update(Calendars.CONTENT_URI, restore, "${Calendars.CAL_SYNC1}=?", arrayOf(AndroidCalendarStore.MARKER))
            assertTrue(store().hasUnsentChanges())
        }
    }
    // A calendar new to this phone takes Google's selected/hidden flags and access role; the owner's later choice stays.
    @Test fun newCalendarsSyncOnlyWhatTheOwnerShowsAndCanWrite() {
        val store = store()
        val reader = RemoteCalendar("reader", "Shared", "UTC", "#64748b", emptyList(), accessRole = "reader", selected = true)
        val hidden = RemoteCalendar("hidden", "Hidden", "UTC", "#64748b", emptyList(), accessRole = "owner", selected = true, hidden = true)
        val mine = RemoteCalendar("mine", "Mine", "UTC", "#64748b", emptyList(), accessRole = "owner", selected = true)
        for (c in listOf(reader, hidden, mine)) store.calendar(c)
        assertFalse(store.shouldSync(reader)); assertFalse(store.shouldSync(hidden)); assertTrue(store.shouldSync(mine))
        assertEquals(1, client.update(Calendars.CONTENT_URI, ContentValues().apply { put(Calendars.SYNC_EVENTS, 1) },
            "${Calendars._SYNC_ID}=?", arrayOf("reader")))
        store.calendar(reader)
        assertTrue(store.shouldSync(reader))
    }
    @Test fun concurrentPhoneTitleEditDoesNotTurnStaleProviderLocationIntoANewUpload() {
        concurrentAcknowledgement(false)
    }
    @Test fun lostReplyRecoveryMergesFromPersistedDispatchSnapshotAfterRestart() {
        concurrentAcknowledgement(true)
    }
    private fun concurrentAcknowledgement(loseReply: Boolean) {
        val id = seed(event())
        title(id, "phone first")
        api.remote["event"] = event(location = "web", sequence = 2, etag = "web-etag")
        api.afterWrite = {
            title(id, "phone later")
            if (loseReply) throw IOException("Synthetic response loss after server commit")
        }
        val first = sync()
        if (loseReply) {
            assertTrue(first.failure is IOException)
            assertNotNull(row(id).getAsString(Events.SYNC_DATA4))
            // A fresh store recovers from persisted provider state, not a retained LocalEvent object.
            assertNoFailure(sync())
        } else assertNoFailure(first)
        assertEquals("phone later", row(id).getAsString(Events.TITLE))
        assertEquals("web", row(id).getAsString(Events.EVENT_LOCATION))
        assertEquals(1, row(id).getAsInteger(Events.DIRTY).toInt())
        assertNoFailure(sync())
        assertEquals(2, api.patches.size)
        assertEquals(listOf("phone first", "phone later"), api.patches.map { it.getString("summary") })
        assertTrue(api.patches.all { !it.has("location") })
        assertEquals("web", api.remote["event"]!!.location)
        assertEquals("phone later", api.remote["event"]!!.title)
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
        assertNoFailure(sync())
        assertEquals(2, api.patches.size)
    }

    // Port of the reviewer's failing /tmp/calendar-pr54-r2-evidence/overlap-lost-reply.xml probe.
    // PR #54 round-2 overlap-lost-reply.xml: a committed title must not approve a later phone/Google location race.
    @Test fun probeSameFieldRaceDuringAcknowledgementRequiresConflict() { overlappingAcknowledgement(false) }
    @Test fun probeSameFieldRaceDuringLostReplyRecoveryRequiresConflict() { overlappingAcknowledgement(true) }
    @Test fun overlappingNormalReplySettlesOnlyAfterExplicitKeepPhoneChoice() { overlappingChoice(false, true) }
    @Test fun overlappingNormalReplySettlesOnlyAfterExplicitUseGoogleChoice() { overlappingChoice(false, false) }
    @Test fun overlappingLostReplySettlesOnlyAfterExplicitKeepPhoneChoice() { overlappingChoice(true, true) }
    @Test fun overlappingLostReplySettlesOnlyAfterExplicitUseGoogleChoice() { overlappingChoice(true, false) }

    private fun changeGoogleLocation(id: String, value: String) {
        val previous = requireNotNull(api.remote[id])
        val json = JSONObject(requireNotNull(previous.raw)).put("location", value)
            .put("etag", "cloud-${++api.revision}").put("sequence", previous.sequence + 1)
        api.remote[id] = GoogleCalendarApi.parseEvent(json, calendar)
    }
    private fun overlappingAcknowledgement(loseReply: Boolean): Long {
        val id = seed(event())
        title(id, "sent title")
        api.returnLatestAfterWrite = !loseReply
        api.afterWrite = {
            // The phone edit happens first. A later Google edit of the same field must not be accepted as
            // the baseline of that phone edit when the committed title's acknowledgement is recovered.
            edit(id, ContentValues().apply { put(Events.EVENT_LOCATION, "phone room") })
            changeGoogleLocation("event", if (loseReply) "newest cloud room" else "cloud room")
            if (loseReply) throw IOException("Synthetic lost title reply after both location edits")
        }
        val first = sync()
        if (loseReply) {
            assertTrue(first.failure is IOException)
            assertNoFailure(sync()) // Fresh store reads the journal and Google's newer revision.
        } else assertNoFailure(first)
        assertNoFailure(sync())
        assertEquals("A later phone edit must not silently overwrite Google's concurrent location",
            if (loseReply) "newest cloud room" else "cloud room", api.remote["event"]!!.location)
        assertEquals("phone room", row(id).getAsString(Events.EVENT_LOCATION))
        assertEquals("sent title", row(id).getAsString(Events.TITLE))
        assertEquals(1, row(id).getAsInteger(Events.DIRTY).toInt())
        assertEquals("conflict", store().localEvents(calendar).single().pending!!.getString("kind"))
        assertEquals("initial", store().checkpoint(calendar.id))
        assertEquals(1, api.patches.size)
        assertEquals(setOf("summary", "sequence"), api.patches.single().keys().asSequence().toSet())
        return id
    }
    private fun overlappingChoice(loseReply: Boolean, keepPhone: Boolean) {
        val id = overlappingAcknowledgement(loseReply)
        prefs.resolveConflicts(keepPhone)
        assertNoFailure(sync())
        val expected = if (keepPhone) "phone room" else if (loseReply) "newest cloud room" else "cloud room"
        assertEquals(expected, api.remote["event"]!!.location)
        assertEquals(expected, row(id).getAsString(Events.EVENT_LOCATION))
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
        assertNull(row(id).getAsString(Events.SYNC_DATA4))
        assertEquals(if (keepPhone) 2 else 1, api.patches.size)
        if (keepPhone) assertEquals(setOf("location", "sequence"), api.patches.last().keys().asSequence().toSet())
        assertNoFailure(sync())
        assertEquals(if (keepPhone) 2 else 1, api.patches.size)
    }

    @Test fun committedCreateConflictKeepPhoneUpdatesTheSameServerEvent() { committedCreateConflict(false, true) }
    @Test fun committedCreateLostReplyConflictKeepPhoneDoesNotCreateAnotherEvent() { committedCreateConflict(true, true) }
    @Test fun committedCreateConflictUseGoogleKeepsTheSameServerEvent() { committedCreateConflict(false, false) }
    private fun committedCreateConflict(loseReply: Boolean, keepPhone: Boolean) {
        val id = seed(event())
        api.remote.clear()
        edit(id, ContentValues().apply {
            listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6).forEach(::putNull)
            put(Events.ORGANIZER, account.name)
        })
        api.returnLatestAfterWrite = !loseReply
        api.afterWrite = {
            edit(id, ContentValues().apply { put(Events.EVENT_LOCATION, "phone room") })
            changeGoogleLocation(api.remote.keys.single(), "cloud room")
            if (loseReply) throw IOException("Synthetic lost create reply after overlapping edits")
        }
        val first = sync()
        if (loseReply) { assertTrue(first.failure is IOException); assertNoFailure(sync()) }
        else assertNoFailure(first)
        val remoteId = api.remote.keys.single()
        assertNoFailure(sync())
        assertEquals("conflict", store().localEvents(calendar).single().pending!!.getString("kind"))
        assertEquals("phone room", row(id).getAsString(Events.EVENT_LOCATION))
        assertEquals("cloud room", api.remote[remoteId]!!.location)
        assertEquals(1, api.creates); assertTrue(api.patches.isEmpty())
        prefs.resolveConflicts(keepPhone)
        assertNoFailure(sync())
        assertEquals(1, api.creates)
        assertEquals(setOf(remoteId), api.remote.keys)
        assertEquals(remoteId, row(id).getAsString(Events._SYNC_ID))
        assertEquals(if (keepPhone) "phone room" else "cloud room", api.remote[remoteId]!!.location)
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
        assertNull(row(id).getAsString(Events.SYNC_DATA4))
        assertEquals(if (keepPhone) 1 else 0, api.patches.size)
        if (keepPhone) assertEquals(setOf("location", "sequence"), api.patches.single().keys().asSequence().toSet())
        assertNoFailure(sync())
        assertEquals(1, api.creates)
        assertEquals(if (keepPhone) 1 else 0, api.patches.size)
    }

    @Test fun journalWithoutDispatchSnapshotBecomesResolvableConflictAndKeepsPhoneData() {
        legacyJournalConflict()
    }
    @Test fun legacyJournalConflictCanUseGoogleWithoutAnotherWrite() { legacyJournalChoice(false) }
    @Test fun legacyJournalConflictCanKeepPhoneOnlyAfterOwnerChoice() { legacyJournalChoice(true) }
    @Test fun earlierOwnerChoiceDoesNotAuthorizeLaterLegacyJournalConflict() {
        val id = createLegacyJournal()
        prefs.resolveConflicts(true)
        assertNoFailure(sync())
        assertEquals("conflict", store().localEvents(calendar).single().pending!!.getString("kind"))
        assertNoFailure(sync())
        assertEquals(1, api.patches.size)
        assertEquals("room", api.remote["event"]!!.location)
        assertEquals("phone room", row(id).getAsString(Events.EVENT_LOCATION))
        prefs.resolveConflicts(true)
        assertNoFailure(sync())
        assertEquals("phone room", api.remote["event"]!!.location)
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
    }
    private fun createLegacyJournal(): Long {
        val id = seed(event())
        title(id, "sent title")
        api.afterWrite = {
            edit(id, ContentValues().apply { put(Events.EVENT_LOCATION, "phone room") })
            throw IOException("Synthetic old-client reply loss")
        }
        assertTrue(sync().failure is IOException)
        val old = JSONObject(requireNotNull(row(id).getAsString(Events.SYNC_DATA4))).apply { remove("providerSnapshot") }
        assertEquals("update", old.getString("kind"))
        assertEquals(1, client.update(Events.CONTENT_URI, ContentValues().apply { put(Events.SYNC_DATA4, old.toString()) },
            "${Events._ID}=?", arrayOf(id.toString())))
        return id
    }
    private fun legacyJournalConflict(): Long {
        val id = createLegacyJournal()
        assertNoFailure(sync())
        assertEquals("conflict", store().localEvents(calendar).single().pending!!.getString("kind"))
        assertEquals("sent title", row(id).getAsString(Events.TITLE))
        assertEquals("phone room", row(id).getAsString(Events.EVENT_LOCATION))
        assertEquals(1, row(id).getAsInteger(Events.DIRTY).toInt())
        assertEquals("initial", store().checkpoint(calendar.id))
        assertNoFailure(sync())
        assertEquals(1, api.patches.size)
        assertEquals("room", api.remote["event"]!!.location)
        return id
    }
    private fun legacyJournalChoice(keepPhone: Boolean) {
        val id = legacyJournalConflict()
        prefs.resolveConflicts(keepPhone)
        assertNoFailure(sync())
        assertEquals(if (keepPhone) "phone room" else "room", api.remote["event"]!!.location)
        assertEquals(if (keepPhone) "phone room" else "room", row(id).getAsString(Events.EVENT_LOCATION))
        assertNull(row(id).getAsString(Events.SYNC_DATA4))
        assertEquals(0, row(id).getAsInteger(Events.DIRTY).toInt())
        assertEquals(if (keepPhone) 2 else 1, api.patches.size)
        if (keepPhone) assertEquals(setOf("location", "sequence"), api.patches.last().keys().asSequence().toSet())
        assertNoFailure(sync())
        assertEquals(if (keepPhone) 2 else 1, api.patches.size)
    }

    @Test fun concurrentReminderEditKeepsItsValueAndMergesDisjointGoogleLocation() {
        val id = seed(event())
        title(id, "phone first")
        api.remote["event"] = event(location = "web", sequence = 2, etag = "web-etag")
        api.afterWrite = {
            assertEquals(1, client.update(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.MINUTES, 5) },
                "${Reminders.EVENT_ID}=?", arrayOf(id.toString())))
        }
        assertNoFailure(sync())
        assertEquals("web", row(id).getAsString(Events.EVENT_LOCATION))
        val local = store().localEvents(calendar).single()
        assertEquals(listOf(5), local.event.reminders)
        assertNoFailure(sync())
        assertEquals(2, api.patches.size)
        assertEquals(setOf("reminders", "sequence"), api.patches.last().keys().asSequence().toSet())
        assertEquals("web", api.remote["event"]!!.location)
        assertEquals(listOf(5), api.remote["event"]!!.reminders)
    }

    @Test fun concurrentStartEditPreservesDisjointGoogleRecurrenceChange() {
        val base = event(recurrence = true)
        val id = seed(base)
        title(id, "phone first")
        val web = JSONObject(requireNotNull(base.raw)).put("etag", "web-etag").put("sequence", 2)
            .put("recurrence", JSONArray().put("RRULE:FREQ=WEEKLY;COUNT=3"))
        api.remote["event"] = GoogleCalendarApi.parseEvent(web, calendar)
        val laterStart = base.start!!.millis + 900_000
        api.afterWrite = { edit(id, ContentValues().apply { put(Events.DTSTART, laterStart) }) }
        assertNoFailure(sync())
        assertEquals("FREQ=WEEKLY;COUNT=3", row(id).getAsString(Events.RRULE))
        assertEquals(laterStart, row(id).getAsLong(Events.DTSTART).toLong())
        assertNoFailure(sync())
        assertEquals(2, api.patches.size)
        assertEquals(setOf("start", "end", "sequence"), api.patches.last().keys().asSequence().toSet())
        assertEquals(listOf("RRULE:FREQ=WEEKLY;COUNT=3"), api.remote["event"]!!.recurrence)
        assertEquals(laterStart, api.remote["event"]!!.start!!.millis)
    }

    @Test fun rebasedUploadIntentDoesNotReplaceTheActualProviderDispatchSnapshot() {
        val id = seed(event())
        title(id, "phone first")
        val observed = store().localEvents(calendar).single()
        // Keeping phone changes can rebase the engine's intent onto a disjoint Google change without writing
        // that virtual location into the provider. A later edit must compare against the actual observation.
        val rebased = observed.copy(event = observed.event.copy(location = "web"))
        title(id, "phone later")
        val acknowledged = event(title = "phone first", location = "web", sequence = 2, etag = "web-etag")
        api.remote["event"] = acknowledged
        assertFalse(store().acknowledge(calendar, rebased, acknowledged))
        assertEquals("phone later", row(id).getAsString(Events.TITLE))
        assertEquals("web", row(id).getAsString(Events.EVENT_LOCATION))
        assertNoFailure(sync())
        assertEquals(1, api.patches.size)
        assertEquals(setOf("summary", "sequence"), api.patches.single().keys().asSequence().toSet())
        assertEquals("web", api.remote["event"]!!.location)
    }

    private fun exception(master: Long, title: String, originalSyncId: String? = "master"): Pair<Long, EventTime> {
        val original = EventTime(event().start!!.millis + 86_400_000, false, "UTC")
        val values = row(master).apply {
            remove(Events._ID)
            listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4,
                Events.SYNC_DATA5, Events.SYNC_DATA6, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION).forEach(::putNull)
            put(Events.TITLE, title); put(Events.DIRTY, 1)
            put(Events.DTSTART, original.millis); put(Events.DTEND, original.millis + 3_600_000)
            put(Events.ORIGINAL_ID, master); put(Events.ORIGINAL_SYNC_ID, originalSyncId)
            put(Events.ORIGINAL_INSTANCE_TIME, original.millis); put(Events.ORIGINAL_ALL_DAY, 0)
        }
        val id = ContentUris.parseId(requireNotNull(client.insert(Events.CONTENT_URI, values)))
        client.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.METHOD, Reminders.METHOD_ALERT); put(Reminders.MINUTES, 10)
        })
        return id to original
    }
    private fun instance(master: RemoteEvent, original: EventTime): RemoteEvent {
        val json = JSONObject(requireNotNull(master.raw)).apply {
            put("id", "master_occurrence"); put("recurringEventId", master.id)
            put("originalStartTime", GoogleCalendarApi.timeJson(original))
            put("start", GoogleCalendarApi.timeJson(original))
            put("end", GoogleCalendarApi.timeJson(original.copy(millis = original.millis + 3_600_000)))
            remove("recurrence")
        }
        return GoogleCalendarApi.parseEvent(json, calendar)
    }

    @Test fun masterTitleRefreshCannotReplaceUnsyncedOccurrenceConflictBaseline() {
        val old = event(id = "master", title = "series original", recurrence = true)
        val master = seed(old)
        val (occurrence, original) = exception(master, "phone occurrence")
        val web = event(id = "master", title = "series web", sequence = 2, etag = "web-etag", recurrence = true)
        api.remote["master"] = web
        api.occurrence = instance(web, original)
        val report = sync()
        assertNoFailure(report)
        assertTrue(report.conflicts > 0)
        assertTrue(api.patches.isEmpty())
        val local = store().localEvents(calendar).single { it.id == occurrence }
        assertEquals("series original", local.base!!.title)
        assertEquals("conflict", local.pending!!.getString("kind"))
        assertEquals("phone occurrence", local.event.title)
        assertNoFailure(sync())
        assertEquals("series original", store().localEvents(calendar).single { it.id == occurrence }.base!!.title)
        assertTrue(api.patches.isEmpty())
    }

    @Test fun occurrenceTitleEditAndDisjointSeriesLocationChangeMergeWithoutRevertingGoogle() {
        val old = event(id = "master", title = "series original", recurrence = true)
        val master = seed(old)
        val (occurrence, original) = exception(master, "phone occurrence")
        val web = event(id = "master", title = old.title, location = "web", sequence = 2, etag = "web-etag", recurrence = true)
        api.remote["master"] = web
        api.occurrence = instance(web, original)
        val report = sync()
        assertNoFailure(report)
        assertEquals(1, report.uploaded)
        assertEquals(1, api.patches.size)
        assertEquals(setOf("summary", "sequence"), api.patches.single().keys().asSequence().toSet())
        assertEquals("web", row(occurrence).getAsString(Events.EVENT_LOCATION))
        assertEquals("phone occurrence", row(occurrence).getAsString(Events.TITLE))
        assertEquals("web", api.remote["master_occurrence"]!!.location)
        assertNoFailure(sync())
        assertEquals(1, api.patches.size)
    }

    @Test fun exceptionInsertedAfterMasterCheckStillPreventsMasterBaselineRefresh() {
        val old = event(id = "master", title = "series original", recurrence = true)
        val master = seed(old)
        val web = event(id = "master", title = "series web", sequence = 2, etag = "web-etag", recurrence = true)
        api.remote["master"] = web
        var occurrence: Long? = null
        provider.beforeBatch = {
            val (id, original) = exception(master, "phone occurrence")
            occurrence = id
            api.occurrence = instance(web, original)
        }
        // Insert at the exact read/check -> batch boundary. A failed assertion must roll back the master update.
        val raced = sync()
        assertTrue(raced.failure != null || raced.conflicts > 0)
        assertNotNull(occurrence)
        assertEquals("series original", row(master).getAsString(Events.TITLE))
        assertEquals("series original", store().localEvents(calendar).single { it.id == occurrence }.base!!.title)
        assertTrue(api.patches.isEmpty())
        assertNoFailure(sync())
        assertEquals("conflict", store().localEvents(calendar).single { it.id == occurrence }.pending!!.getString("kind"))
        assertTrue(api.patches.isEmpty())
    }

    @Test fun newMasterCanSettleBeforeItsSourceLessOccurrenceAcquiresAnIdentity() {
        val master = seed(event(id = "master", title = "series original", recurrence = true))
        api.remote.clear()
        edit(master, ContentValues().apply {
            listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6).forEach(::putNull)
        })
        val (occurrence, original) = exception(master, "phone occurrence", originalSyncId = null)
        assertNoFailure(sync())
        assertEquals(1, api.creates)
        assertFalse(row(master).getAsString(Events._SYNC_ID).isNullOrBlank())
        assertEquals(0, row(master).getAsInteger(Events.DIRTY).toInt())
        api.occurrence = instance(api.remote.values.single(), original)
        assertNoFailure(sync())
        assertEquals(1, api.creates)
        assertEquals(1, api.patches.size)
        assertEquals("phone occurrence", row(occurrence).getAsString(Events.TITLE))
        assertFalse(row(occurrence).getAsString(Events._SYNC_ID).isNullOrBlank())
        assertNoFailure(sync())
        assertEquals(1, api.creates)
        assertEquals(1, api.patches.size)
    }

    private inner class Server : CalendarApi {
        val remote = linkedMapOf<String, RemoteEvent>()
        val patches = mutableListOf<JSONObject>()
        var occurrence: RemoteEvent? = null
        var afterWrite: (() -> Unit)? = null
        var returnLatestAfterWrite = false
        var revision = 10
        var creates = 0
        override fun calendars(page: String?) = CalendarPage(listOf(calendar), null)
        val checkpoints = mutableListOf<String?>()
        var failEvents = false
        override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?) =
            EventPage(remote.values.toList(), null, "token-$revision").also { check(!failEvents) { "fixture" }; checkpoints += checkpoint }
        override fun get(calendar: RemoteCalendar, id: String) = remote[id] ?: occurrence?.takeIf { it.id == id }
        override fun instance(calendar: RemoteCalendar, masterId: String, original: EventTime) =
            occurrence?.takeIf { it.masterId == masterId && it.original == original }
        override fun create(calendar: RemoteCalendar, id: String, payload: JSONObject): RemoteEvent {
            creates++
            check(id !in remote)
            val body = JSONObject(payload.toString()).put("id", id).put("etag", "server-${++revision}")
            val updated = GoogleCalendarApi.parseEvent(body, calendar).also { remote[id] = it }
            return reply(updated)
        }
        override fun update(calendar: RemoteCalendar, id: String, etag: String, payload: JSONObject): RemoteEvent {
            val previous = requireNotNull(get(calendar, id))
            assertEquals(previous.etag, etag)
            patches += JSONObject(payload.toString())
            val body = JSONObject(requireNotNull(previous.raw))
            payload.keys().forEach { body.put(it, payload.get(it)) }
            body.put("etag", "server-${++revision}")
            val updated = GoogleCalendarApi.parseEvent(body, calendar)
            remote[id] = updated
            return reply(updated)
        }
        private fun reply(updated: RemoteEvent): RemoteEvent {
            val action = afterWrite.also { afterWrite = null }
            action?.invoke()
            return if (returnLatestAfterWrite) requireNotNull(remote[updated.id]) else updated
        }
    }
}

/** Only the columns used by the production adapter; app edits explicitly set DIRTY in these tests. */
class CalendarSqlFixtureProvider : ContentProvider() {
    lateinit var database: SQLiteDatabase
    var beforeBatch: (() -> Unit)? = null
    override fun onCreate(): Boolean {
        database = SQLiteDatabase.create(null)
        fun table(name: String, columns: Collection<String>, defaults: Map<String, String> = emptyMap()) {
            val definitions = columns.distinct().joinToString(",") { column ->
                if (column == "_id") "_id INTEGER PRIMARY KEY AUTOINCREMENT"
                else "\"$column\" " + (defaults[column] ?: "TEXT")
            }
            database.execSQL("CREATE TABLE $name ($definitions)")
        }
        table("calendars", listOf(Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars._SYNC_ID,
            Calendars.CAL_SYNC1, Calendars.CAL_SYNC2, Calendars.CAL_SYNC3, Calendars.CAL_SYNC4, Calendars.CAL_SYNC5,
            Calendars.CAL_SYNC6, Calendars.CAL_SYNC7, Calendars.CAL_SYNC8, Calendars.CAL_SYNC9, Calendars.CAL_SYNC10,
            Calendars.NAME, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_TIME_ZONE, Calendars.OWNER_ACCOUNT,
            Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS,
            Calendars.DIRTY, Calendars.VISIBLE, Calendars.SYNC_EVENTS))
        val zero = "INTEGER NOT NULL DEFAULT 0"
        val one = "INTEGER NOT NULL DEFAULT 1"
        table("events", listOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
            Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
            Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE,
            Events.EVENT_END_TIMEZONE, Events.ALL_DAY, Events.STATUS, Events.DIRTY, Events.DELETED, Events.HAS_ALARM,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.ACCESS_LEVEL, Events.AVAILABILITY,
            Events.ORGANIZER, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_SEE_GUESTS),
            mapOf(Events.CALENDAR_ID to "INTEGER", Events.DTSTART to "INTEGER", Events.DTEND to "INTEGER",
                Events.ORIGINAL_ID to "INTEGER", Events.ORIGINAL_INSTANCE_TIME to "INTEGER", Events.ORIGINAL_ALL_DAY to "INTEGER",
                Events.ALL_DAY to zero, Events.DIRTY to zero, Events.DELETED to zero, Events.HAS_ALARM to zero,
                Events.ACCESS_LEVEL to zero, Events.AVAILABILITY to zero, Events.GUESTS_CAN_MODIFY to zero,
                Events.GUESTS_CAN_INVITE_OTHERS to one, Events.GUESTS_CAN_SEE_GUESTS to one, Events.STATUS to one))
        table("reminders", listOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
            mapOf(Reminders.EVENT_ID to "INTEGER", Reminders.MINUTES to "INTEGER", Reminders.METHOD to "INTEGER"))
        table("attendees", listOf(Attendees._ID, Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE))
        table("syncstate", listOf("_id", "account_name", "account_type", "data"))
        return true
    }
    private fun table(uri: Uri) = uri.pathSegments.first().also { require(it in setOf("calendars", "events", "reminders", "attendees", "syncstate")) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
        database.query(table(uri), projection, selection, args, null, null, order)
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        ContentUris.withAppendedId(uri, database.insertOrThrow(table(uri), null, values))
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) =
        database.update(table(uri), values, selection, args)
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = database.delete(table(uri), selection, args)
    override fun getType(uri: Uri): String? = null
    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        beforeBatch.also { beforeBatch = null }?.invoke()
        database.beginTransaction()
        try { return super.applyBatch(operations).also { database.setTransactionSuccessful() } }
        finally { database.endTransaction() }
    }
}
