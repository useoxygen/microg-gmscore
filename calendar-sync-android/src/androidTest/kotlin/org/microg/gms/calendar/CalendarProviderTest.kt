/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
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
}
