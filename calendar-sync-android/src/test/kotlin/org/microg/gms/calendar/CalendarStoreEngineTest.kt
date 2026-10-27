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
        override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?) =
            EventPage(remote.values.toList(), null, "token-$revision")
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
        return true
    }
    private fun table(uri: Uri) = uri.pathSegments.first().also { require(it in setOf("calendars", "events", "reminders", "attendees")) }
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
