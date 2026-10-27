/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.microg.gms.calendar.sync.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarProviderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private lateinit var account: Account
    private lateinit var prefs: CalendarSyncPreferences
    private val calendar = RemoteCalendar("fixture", "Cyclon calendar fixture", "UTC", "#64748b", listOf(10))
    @Before fun setup() {
        check(Build.HARDWARE in listOf("ranchu", "goldfish") && context.packageName == "org.microg.gms.calendar.sync.android.test")
        account = Account("fixture-" + UUID.randomUUID(), "org.microg.gms.calendar.fixture")
        assertTrue(AccountManager.get(context).addAccountExplicitly(account, null, null))
        prefs = CalendarSyncPreferences(context, account); prefs.configure(true)
    }
    @After fun cleanup() {
        if (!::account.isInitialized) return
        try { prefs.configure(false) } finally {
            try {
                resolver.delete(Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").build(),
                    "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type))
            } finally { AccountManager.get(context).removeAccountExplicitly(account) }
        }
    }
    private fun <T> store(block: (AndroidCalendarStore) -> T): T = requireNotNull(resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)).use {
        block(AndroidCalendarStore(it, account, prefs).also { store -> store.calendar(calendar) })
    }
    private fun event(id: String = "master", recurrence: Boolean = false) = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"$id","summary":"Fixture","start":{"date":"2026-10-06"},"end":{"date":"2026-10-07"}}""").apply {
        if (recurrence) put("recurrence", org.json.JSONArray().put("RRULE:FREQ=DAILY;COUNT=3"))
    }, calendar)
    private fun ids(owner: Account = account): List<Long> {
        val calendarId = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?",
            arrayOf(owner.name, owner.type, AndroidCalendarStore.MARKER), null)!!.use { it.moveToFirst(); it.getLong(0) }
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.SYNC_DATA1}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(AndroidCalendarStore.MARKER, calendarId.toString()), Events._ID)!!.use { c ->
                buildList<Long> { while(c.moveToNext()) add(c.getLong(0)) }
            }
    }
    @Test fun clearedProviderRowsLoseTheirCheckpointAndRestoreACompleteSnapshot() {
        store { assertTrue(it.event(calendar,event())); it.checkpoint(calendar.id,"old"); assertEquals("old",it.checkpoint(calendar.id)) }
        resolver.delete(Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").build(),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",arrayOf(account.name,account.type))
        val tokens = mutableListOf<String?>()
        val api = object : CalendarApi {
            override fun calendars(page: String?) = CalendarPage(listOf(calendar),null)
            override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage {
                tokens += checkpoint
                return EventPage(if (checkpoint == null) listOf(event()) else emptyList(),null,"fresh")
            }
        }
        requireNotNull(resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)).use {
            val store = AndroidCalendarStore(it,account,prefs)
            assertNull(store.checkpoint(calendar.id))
            assertEquals(1,CalendarSync(api,store) { prefs.enabled }.sync().events)
            assertEquals("fresh",store.checkpoint(calendar.id))
        }
        assertEquals(listOf<String?>(null),tokens)
        assertEquals(1,ids().size)
    }
    @Test fun removedAccountForgetsItsOptInAndStatusWithoutChangingExistingAccounts() {
        prefs.status = "success"; prefs.lastSuccess = 1234
        val other = Account("fixture-" + UUID.randomUUID(),account.type)
        assertTrue(AccountManager.get(context).addAccountExplicitly(other,null,null))
        val otherPrefs = CalendarSyncPreferences(context,other)
        try {
            otherPrefs.configure(true)
            val receiver = CalendarAccountRemovedReceiver()
            receiver.onReceive(context,Intent(AccountManager.ACTION_ACCOUNT_REMOVED))
            assertTrue(prefs.enabled); assertTrue(otherPrefs.enabled)
            assertTrue(AccountManager.get(context).removeAccountExplicitly(account))
            receiver.onReceive(context,Intent(AccountManager.ACTION_ACCOUNT_REMOVED))
            assertFalse(prefs.enabled); assertEquals("",prefs.status); assertEquals(0L,prefs.lastSuccess)
            assertTrue(otherPrefs.enabled)
            assertTrue(AccountManager.get(context).addAccountExplicitly(account,null,null))
            assertFalse(CalendarSyncPreferences(context,account).enabled)
        } finally {
            otherPrefs.configure(false)
            AccountManager.get(context).removeAccountExplicitly(other)
        }
    }
    @Test fun calendarSyncChoiceSurvivesRefreshRemovalAndRestoration() {
        store { assertTrue(it.event(calendar,event())); it.checkpoint(calendar.id,"keep") }
        val selection = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?"
        val args = arrayOf(account.name,account.type,AndroidCalendarStore.MARKER)
        resolver.update(Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").build(),
            ContentValues().apply { put(Calendars.SYNC_EVENTS,0) },selection,args)
        val api = object : CalendarApi {
            override fun calendars(page: String?) = CalendarPage(listOf(calendar),null)
            override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?) = error("Calendar is disabled")
        }
        repeat(2) { iteration ->
            if (iteration == 1) store { it.reconcileCalendars(emptySet()) }
            requireNotNull(resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)).use {
                val store = AndroidCalendarStore(it,account,prefs)
                assertEquals(0,CalendarSync(api,store) { prefs.enabled }.sync().events)
                assertEquals("keep",store.checkpoint(calendar.id))
            }
            resolver.query(Calendars.CONTENT_URI,arrayOf(Calendars.VISIBLE,Calendars.SYNC_EVENTS),selection,args,null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)); assertEquals(0,it.getInt(1))
            }
        }
        assertEquals(1,ids().size)
    }
    @Test fun recurringMasterExceptionAndReminderAreIdempotent() {
        val exception = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"exception","status":"cancelled","recurringEventId":"master","originalStartTime":{"date":"2026-10-07"}}"""), calendar)
        repeat(2) { store { assertTrue(it.event(calendar, event(recurrence=true))); assertTrue(it.event(calendar, exception)) } }
        assertEquals(2, ids().size)
        resolver.query(Events.CONTENT_URI, arrayOf(Events.RRULE, Events.DURATION, Events.DTEND, Events.ALL_DAY),
            "${Events._ID}=?", arrayOf(ids().first().toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("FREQ=DAILY;COUNT=3", it.getString(0)); assertEquals("P1D", it.getString(1)); assertTrue(it.isNull(2)); assertEquals(1,it.getInt(3))
        }
        val range = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(range,event().start!!.millis)
        ContentUris.appendId(range,event().start!!.millis + 4*86_400_000L)
        resolver.query(range.build(),arrayOf(Instances.BEGIN),"${Instances.EVENT_ID} IN (?,?)",ids().map(Long::toString).toTypedArray(),Instances.BEGIN)!!.use {
            assertEquals(2,it.count) // The cancelled second occurrence must not appear in Android Calendar.
        }
    }
    @Test fun dirtyRowsSurviveUpdatesAndRemoteDeletion() {
        store { it.event(calendar, event()) }
        val id = ids().single()
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI,id), ContentValues().apply { put(Events.TITLE,"Local edit") }, null,null)
        store { assertFalse(it.event(calendar,event())); assertEquals(1,it.reconcile(calendar,emptySet())) }
        assertEquals(listOf(id),ids())
    }
    @Test fun identicalRemoteIdsInAnotherAccountAreIsolated() {
        val other = Account("fixture-" + UUID.randomUUID(),account.type)
        assertTrue(AccountManager.get(context).addAccountExplicitly(other,null,null))
        val otherPrefs = CalendarSyncPreferences(context,other)
        try {
            otherPrefs.configure(true)
            store { it.event(calendar,event()) }
            requireNotNull(resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)).use {
                val otherStore = AndroidCalendarStore(it,other,otherPrefs)
                otherStore.calendar(calendar); assertTrue(otherStore.event(calendar,event()))
            }
            val preserved = ids(other)
            assertNotEquals(ids(),preserved)
            store { assertEquals(0,it.reconcile(calendar,emptySet())) }
            assertEquals(preserved,ids(other))
        } finally {
            try { otherPrefs.configure(false) } finally {
                try {
                    resolver.delete(Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").build(),
                        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",arrayOf(other.name,other.type))
                } finally { AccountManager.get(context).removeAccountExplicitly(other) }
            }
        }
    }
    @Test fun timedRecurrenceRespectsDstAndTimezoneExclusions() {
        val timed = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"timed","start":{"dateTime":"2026-10-24T12:00:00+02:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-10-24T13:00:00+02:00","timeZone":"Europe/Berlin"},"recurrence":["RRULE:FREQ=DAILY;COUNT=3","EXDATE;TZID=Europe/Berlin:20261025T120000"]}"""),calendar)
        store { assertTrue(it.event(calendar,timed)) }
        val start = requireNotNull(timed.start).millis
        val range = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(range,start)
        ContentUris.appendId(range,start + 4*86_400_000L)
        resolver.query(range.build(),arrayOf(Instances.BEGIN),"${Instances.EVENT_ID}=?",arrayOf(ids().single().toString()),Instances.BEGIN)!!.use {
            assertEquals(2,it.count); assertTrue(it.moveToFirst()); val first = it.getLong(0)
            assertTrue(it.moveToNext()); assertEquals(49*3_600_000L,it.getLong(0)-first)
        }
    }
    @Test fun removedCalendarIsHiddenWithoutDeletingEventsAndRestoresVisibility() {
        store { it.event(calendar,event()) }
        val before = ids()
        store { it.reconcileCalendars(emptySet()) }
        val selection = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?"
        val args = arrayOf(account.name,account.type,AndroidCalendarStore.MARKER)
        resolver.query(Calendars.CONTENT_URI,arrayOf(Calendars.VISIBLE,Calendars.SYNC_EVENTS),selection,args,null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)); assertEquals(0,it.getInt(1))
        }
        assertEquals(before,ids())
        store { }
        resolver.query(Calendars.CONTENT_URI,arrayOf(Calendars.VISIBLE,Calendars.SYNC_EVENTS),selection,args,null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)); assertEquals(1,it.getInt(1))
        }
    }
    @Test fun unownedRowsAndOffConsentAreProtected() {
        store { it.event(calendar,event()) }
        val calendarId = resolver.query(Events.CONTENT_URI,arrayOf(Events.CALENDAR_ID),"${Events._ID}=?",arrayOf(ids().single().toString()),null)!!.use { it.moveToFirst(); it.getLong(0) }
        val unowned = resolver.insert(Events.CONTENT_URI,ContentValues().apply {
            put(Events.CALENDAR_ID,calendarId); put(Events.TITLE,"Unowned fixture"); put(Events.DTSTART,1_000L); put(Events.DTEND,2_000L); put(Events.EVENT_TIMEZONE,"UTC")
        })!!
        store { assertEquals(0,it.reconcile(calendar,emptySet())) }
        resolver.query(unowned,arrayOf(Events._ID),null,null,null)!!.use { assertTrue(it.moveToFirst()) }
        prefs.configure(false)
        try { store { it.event(calendar,event()) }; fail() } catch (_: IllegalStateException) { }
    }
    private fun writableCalendar() = calendar.copy(accessRole = "owner")
    private fun fixtureCalendarId() = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?",
        arrayOf(account.name, account.type, AndroidCalendarStore.MARKER), null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }
    private fun localEvent(values: ContentValues = ContentValues()): Long = ContentUris.parseId(requireNotNull(resolver.insert(Events.CONTENT_URI,
        ContentValues().apply {
            put(Events.CALENDAR_ID, fixtureCalendarId()); put(Events.TITLE, "Phone fixture")
            put(Events.DTSTART, 1792951200000L); put(Events.DTEND, 1792954800000L); put(Events.EVENT_TIMEZONE, "Europe/Berlin")
            putAll(values)
        })))
    private fun newPending(local: LocalEvent) = JSONObject().put("kind", "create").put("id", "1234567890abcdef")
        .put("creationId", "fixture-create-nonce").put("fingerprint", local.fingerprint)

    @Test fun realAccessRoleAndTwoWayConsentControlCalendarWritability() {
        val writable = writableCalendar()
        fun access() = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars._ID}=?", arrayOf(fixtureCalendarId().toString()), null)!!.use { it.moveToFirst(); it.getInt(0) }
        store { it.calendar(writable) }
        assertEquals(Calendars.CAL_ACCESS_READ, access())
        prefs.configure(true, twoWay = true)
        store { it.calendar(writable) }
        assertEquals(Calendars.CAL_ACCESS_OWNER, access())
        store { it.calendar(writable.copy(accessRole = "writer")) }
        assertEquals(Calendars.CAL_ACCESS_EDITOR, access())
        store { it.calendar(calendar) }
        assertEquals(Calendars.CAL_ACCESS_READ, access())
    }
    @Test fun createsAreSelectedScopedAndMigrationHeld() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        store { it.calendar(writable) }
        val row = localEvent()
        store { assertEquals(row, it.localEvents(writable).single().id); assertTrue(it.uploadAllowed(writable)) }
        prefs.beginMigration()
        store { assertFalse(it.uploadAllowed(writable)) }
        prefs.ownerResumeUploads()
        val uri = Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").build()
        resolver.update(uri, ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, "${Calendars._ID}=?", arrayOf(fixtureCalendarId().toString()))
        store { assertFalse(it.uploadAllowed(writable)); assertTrue(it.localEvents(writable).isEmpty()) }
    }
    @Test fun journaledCreateIdentitySurvivesRestartAndDefinitiveRejectionAllowsCorrection() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        store { it.calendar(writable) }
        val row = localEvent()
        store {
            val before = it.localEvents(writable).single()
            val journaled = it.begin(writable, before, newPending(before))
            assertEquals(row, journaled.id); assertEquals("1234567890abcdef", journaled.event.id)
            assertNotNull(journaled.pending); assertFalse(journaled.unsupported)
        }
        store {
            val recovered = it.localEvents(writable).single()
            assertEquals("1234567890abcdef", recovered.pending!!.getString("id"))
            it.rejected(writable, recovered)
            val corrected = it.localEvents(writable).single()
            assertEquals("", corrected.event.id); assertNull(corrected.pending); assertTrue(corrected.dirty)
        }
    }
    @Test fun reminderEditsDuringUploadStayDirtyAndRetainTheirValues() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val remote = event().copy(etag = "revision-a", useDefaultReminders = false, reminders = listOf(10))
        store { it.calendar(writable); assertTrue(it.event(writable, remote)) }
        val row = ids().single()
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, row), ContentValues().apply { put(Events.TITLE, "Sent title") }, null, null)
        lateinit var sent: LocalEvent
        store { sent = it.localEvents(writable).single() }
        resolver.update(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.MINUTES, 25) }, "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
        store {
            assertFalse(it.acknowledge(writable, sent, remote.copy(etag = "revision-b", title = "Sent title")))
            val current = it.localEvents(writable).single()
            assertTrue(current.dirty); assertEquals(listOf(25), current.event.reminders); assertEquals("revision-b", current.base!!.etag)
        }
    }
    @Test fun localExceptionResolvesItsMasterAndKeepsOriginalOccurrenceWhenMoved() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val master = event(recurrence = true).copy(etag = "master-revision")
        store { it.calendar(writable); it.event(writable, master) }
        val original = master.start!!.millis + 86_400_000L
        val row = localEvent(ContentValues().apply {
            put(Events.ORIGINAL_ID, ids().single()); put(Events.ORIGINAL_INSTANCE_TIME, original); put(Events.ORIGINAL_ALL_DAY, 1)
            put(Events.DTSTART, original + 86_400_000L); put(Events.DTEND, original + 2 * 86_400_000L)
            put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC")
        })
        store {
            val exception = it.localEvents(writable).single { local -> local.id == row }
            assertEquals("master", exception.event.masterId); assertEquals(original, exception.event.original!!.millis)
            assertEquals(original + 86_400_000L, exception.event.start!!.millis)
            assertEquals(original, exception.base!!.start!!.millis); assertTrue(exception.base!!.recurrence.isEmpty())
            assertFalse(exception.unsupported)
        }
    }
    @Test fun newPrivateFreeEventAndDefaultReminderModeArePreserved() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        store { it.calendar(writable) }
        val row = localEvent(ContentValues().apply {
            put(Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE); put(Events.AVAILABILITY, Events.AVAILABILITY_FREE); put(Events.HAS_ALARM, 1)
        })
        resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, row); put(Reminders.METHOD, Reminders.METHOD_ALERT); put(Reminders.MINUTES, Reminders.MINUTES_DEFAULT)
        })
        store {
            val local = it.localEvents(writable).single()
            assertEquals("private", local.event.visibility); assertEquals("transparent", local.event.transparency)
            assertTrue(local.event.useDefaultReminders); assertFalse(local.unsupported)
        }
    }
    @Test fun localProviderWipeProducesNoRemoteTombstones() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        store { it.calendar(writable); it.event(writable, event().copy(etag = "revision")) }
        resolver.delete(Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").build(),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(account.name, account.type))
        requireNotNull(resolver.acquireContentProviderClient(CalendarContract.AUTHORITY)).use {
            val fresh = AndroidCalendarStore(it, account, prefs)
            assertTrue(fresh.localEvents(writable).isEmpty()); assertNull(fresh.checkpoint(writable.id))
        }
    }
    @Test fun masterDeletionRetainsDirtyLocalException() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val master = event(recurrence = true)
        store { it.calendar(writable); it.event(writable, master) }
        val masterId = ids().single()
        localEvent(ContentValues().apply {
            put(Events.ORIGINAL_ID, masterId); put(Events.ORIGINAL_SYNC_ID, "master")
            put(Events.ORIGINAL_INSTANCE_TIME, master.start!!.millis + 86_400_000L); put(Events.ORIGINAL_ALL_DAY, 1)
            put(Events.DTSTART, master.start!!.millis + 86_400_000L); put(Events.DTEND, master.end!!.millis + 86_400_000L)
            put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC")
        })
        store { assertFalse(it.event(writable, master.copy(cancelled = true))); assertTrue(it.reconcile(writable, emptySet()) > 0) }
        resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, masterId), arrayOf(Events._ID), null, null, null)!!.use { assertTrue(it.moveToFirst()) }
    }

    @Test fun cloudExceptionMatchesDirtyOccurrenceBeforeInsertAndAcknowledgementJoinsCleanCopies() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val master = event(recurrence = true).copy(etag = "master-etag")
        store { it.calendar(writable); it.event(writable, master) }
        val masterId = ids().single()
        val original = master.start!!.copy(millis = master.start!!.millis + 86_400_000L)
        val remote = master.copy(id = "cloud-instance", etag = "instance-etag", masterId = "master", original = original,
            start = original, end = original.copy(millis = original.millis + 86_400_000L), recurrence = emptyList())
        fun newException() = localEvent(ContentValues().apply {
            put(Events.ORIGINAL_ID, masterId); put(Events.ORIGINAL_INSTANCE_TIME, original.millis); put(Events.ORIGINAL_ALL_DAY, 1)
            put(Events.DTSTART, original.millis); put(Events.DTEND, original.millis + 86_400_000L)
            put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC")
        })
        val localId = newException()
        store { assertFalse(it.event(writable, remote)) }
        assertEquals(listOf(masterId), ids()) // No second cloud row was inserted over the pending occurrence.
        store {
            val local = it.localEvents(writable).single { row -> row.id == localId }
            assertTrue(it.acknowledge(writable, local, remote))
        }
        assertEquals(2, ids().size)
        // A second native occurrence inserted after a cloud download must join the existing clean remote identity atomically.
        val secondId = newException()
        store {
            val local = it.localEvents(writable).single { row -> row.id == secondId }
            assertTrue(it.acknowledge(writable, local, remote.copy(title = "Changed locally")))
        }
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
            arrayOf(fixtureCalendarId().toString(), remote.id), null)!!.use { assertEquals(1, it.count); it.moveToFirst(); assertEquals(secondId, it.getLong(0)) }
    }

    private fun emulateLegacyMetadata(id: Long) {
        resolver.update(Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build(),
            ContentValues().apply {
                putNull(Events.SYNC_DATA3); putNull(Events.SYNC_DATA6); putNull(Events.EVENT_END_TIMEZONE)
                put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            }, "${Events._ID}=?", arrayOf(id.toString()))
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "Phone title") }, null, null)
    }
    @Test fun firstTwoWaySyncReplacesLegacyCheckpointWithOneFullSnapshot() {
        store { it.event(calendar, event()); it.checkpoint(calendar.id, "v1-checkpoint") }
        prefs.configure(true, twoWay = true)
        store {
            assertNull(it.checkpoint(calendar.id))
            it.checkpoint(calendar.id, "v2-checkpoint")
            assertEquals("v2-checkpoint", it.checkpoint(calendar.id))
        }
    }
    @Test fun legacyHydrationDoesNotInterpretOldPrivacyAndEndZoneDefaultsAsOwnerChanges() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val remote = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"legacy-zone","etag":"same-etag","visibility":"private","transparency":"transparent","start":{"dateTime":"2026-10-24T12:00:00+02:00","timeZone":"Europe/Berlin"},"end":{"dateTime":"2026-10-24T07:00:00-04:00","timeZone":"America/New_York"},"reminders":{"useDefault":false,"overrides":[]}}"""), writable)
        store { it.calendar(writable); it.event(writable, remote) }
        val id = ids().single()
        emulateLegacyMetadata(id)
        store {
            val legacy = it.localEvents(writable).single()
            assertNull(legacy.base)
            val hydrated = it.hydrate(writable, legacy, remote)
            assertEquals("Phone title", hydrated.event.title); assertEquals("private", hydrated.event.visibility)
            assertEquals("transparent", hydrated.event.transparency); assertEquals("America/New_York", hydrated.event.end!!.zone)
            assertEquals(remote.end!!.millis, hydrated.event.end!!.millis); assertNull(hydrated.pending)
            assertFalse(hydrated.unsupported)
        }
    }
    @Test fun legacyInheritedReminderMismatchHoldsForExplicitOwnerChoice() {
        prefs.configure(true, twoWay = true)
        val writable = writableCalendar()
        val remote = event().copy(etag = "unchanged-etag", useDefaultReminders = true, reminders = listOf(10))
        store { it.calendar(writable); it.event(writable, remote) }
        emulateLegacyMetadata(ids().single())
        store {
            val legacy = it.localEvents(writable).single()
            val hydrated = it.hydrate(writable, legacy, remote.copy(reminders = listOf(30)))
            assertEquals(listOf(10), hydrated.event.reminders); assertEquals(listOf(30), hydrated.base!!.reminders)
            assertEquals("conflict", hydrated.pending!!.getString("kind")); assertEquals("legacy-reminders", hydrated.pending!!.getString("reason"))
            assertEquals(CalendarConflictChoice.HOLD, it.conflictChoice(writable, hydrated))
            prefs.resolveConflicts(true)
            assertEquals(CalendarConflictChoice.LOCAL, it.conflictChoice(writable, hydrated))
        }
    }

}
