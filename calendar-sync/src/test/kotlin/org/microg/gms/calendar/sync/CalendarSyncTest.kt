/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.microg.gms.people.sync.*

class CalendarSyncTest {
    @Test fun quotaErrorsRetryWithoutRequestingNewAuthorization() {
        for ((reason,limited) in listOf("rateLimitExceeded" to true,"userRateLimitExceeded" to true,"forbidden" to false)) {
            val api = GoogleCalendarApi(HttpTransport { _,_,_ -> HttpReply(403,"""{"error":{"errors":[{"reason":"$reason"}]}}""",30) })
            try { api.calendars(null); fail() } catch (e: CalendarRequestException) {
                assertEquals(403,e.status); assertEquals(limited,e.rateLimited); assertEquals(30L,e.retryAfterSeconds)
                assertFalse(e.message!!.contains(reason))
            }
        }
    }
    private val calendar = RemoteCalendar("primary", "Fixture", "Europe/Berlin", "#64748b", listOf(10))
    private fun event(id: String = "one") = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"$id","etag":"e1","summary":"Fixture","start":{"date":"2026-10-06"},"end":{"date":"2026-10-07"}}"""), calendar)
    private class Store : CalendarStore {
        var token: String? = null
        val rows = mutableMapOf<String, RemoteEvent>()
        var conflicts = false
        var prunes = 0
        override fun calendar(calendar: RemoteCalendar) = Unit
        override fun checkpoint(calendar: String) = token
        override fun checkpoint(calendar: String, value: String) { token = value }
        override fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean { if (conflicts) return false; rows[event.id] = event; return true }
        override fun reconcile(calendar: RemoteCalendar, present: Set<String>): Int { prunes++; rows.keys.retainAll(present); return 0 }
    }
    private fun api(events: (String?, String?) -> EventPage) = object : CalendarApi {
        override fun calendars(page: String?) = CalendarPage(listOf(calendar), null)
        override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?) = events(checkpoint, page)
    }
    @Test fun disabledCalendarRefreshesMetadataWithoutDownloadingEventsOrChangingCheckpoint() {
        val backing = Store(); backing.token = "keep"
        var metadata = 0
        val store = object : CalendarStore by backing {
            override fun shouldSync(calendar: RemoteCalendar) = false
            override fun calendar(calendar: RemoteCalendar) { metadata++ }
        }
        val report = CalendarSync(api { _, _ -> error("Disabled calendar must not download events") },store) { true }.sync()
        assertEquals(1,metadata); assertEquals(0,report.events); assertEquals("keep",backing.token); assertEquals(0,backing.prunes)
    }
    @Test fun allDayAndTimedOffsetsAreIndependentOfHostTimezone() {
        val all = event()
        assertTrue(all.start!!.allDay); assertEquals("UTC", all.start.zone)
        assertEquals(86_400_000L, all.end!!.millis - all.start.millis)
        val utc = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-06T10:00:00Z"}"""), "UTC")
        val offset = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-06T12:00:00+02:00","timeZone":"Europe/Berlin"}"""), "UTC")
        assertEquals(utc.millis, offset.millis)
        val named = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-06T12:00:00","timeZone":"Europe/Berlin"}"""), "UTC")
        assertEquals(utc.millis, named.millis)
        val fractional = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-06T10:00:00.1Z"}"""), "UTC")
        assertEquals(utc.millis + 100, fractional.millis)
    }
    @Test fun recurringMasterAndCancelledExceptionKeepIdentity() {
        val master = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"master","start":{"date":"2026-10-06"},"end":{"date":"2026-10-07"},"recurrence":["RRULE:FREQ=DAILY;COUNT=3"]}"""), calendar)
        val exception = GoogleCalendarApi.parseEvent(JSONObject("""{"id":"exception","status":"cancelled","recurringEventId":"master","originalStartTime":{"date":"2026-10-07"}}"""), calendar)
        assertEquals(listOf("RRULE:FREQ=DAILY;COUNT=3"), master.recurrence)
        assertTrue(exception.cancelled); assertEquals("master", exception.masterId); assertTrue(exception.original!!.allDay)
    }
    @Test fun recurrenceDateListsUseCalendarProviderTimezoneSyntax() {
        assertEquals("EXDATE:Europe/Berlin;20261007T120000", GoogleCalendarApi.normalizeRecurrence("EXDATE;TZID=Europe/Berlin:20261007T120000"))
        assertEquals("RDATE:20261007,20261008", GoogleCalendarApi.normalizeRecurrence("RDATE;VALUE=DATE:20261007,20261008"))
        try { GoogleCalendarApi.normalizeRecurrence("RDATE;VALUE=PERIOD:20261007T120000Z/PT1H"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun unreadableCalendarListCannotReconcileCalendars() {
        var reconciled = false
        val store = object : CalendarStore by Store() {
            override fun reconcileCalendars(present: Set<String>) { reconciled = true }
        }
        val failing = object : CalendarApi {
            override fun calendars(page: String?): CalendarPage {
                if (page == null) return CalendarPage(listOf(calendar), "next")
                throw CalendarRequestException(503)
            }
            override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?) = error("Must not write after a partial calendar list")
        }
        try { CalendarSync(failing, store) { true }.sync(); fail() } catch (_: CalendarRequestException) { }
        assertFalse(reconciled)
    }
    @Test fun laterPageFailureCannotDeleteOrAdvanceCheckpoint() {
        val store = Store(); store.rows["keep"] = event("keep")
        try { CalendarSync(api { _, page -> if (page == null) EventPage(listOf(event()), "next", null) else throw CalendarRequestException(503) }, store) { true }.sync(); fail() }
        catch (_: CalendarRequestException) { }
        assertEquals(setOf("keep"), store.rows.keys); assertNull(store.token); assertEquals(0, store.prunes)
    }
    @Test fun expiredCheckpointRefetchesBeforeReconciling() {
        val store = Store(); store.token = "old"; val tokens = mutableListOf<String?>()
        CalendarSync(api { token, _ -> tokens += token; if (token != null) throw CalendarRequestException(410); EventPage(listOf(event()), null, "fresh") }, store) { true }.sync()
        assertEquals(listOf("old", null), tokens); assertEquals("fresh", store.token); assertEquals(1, store.prunes)
    }
    @Test fun conflictRetainsCheckpointForRetry() {
        val store = Store(); store.token = "old"; store.conflicts = true
        val report = CalendarSync(api { _, _ -> EventPage(listOf(event()), null, "new") }, store) { true }.sync()
        assertEquals(1, report.conflicts); assertEquals("old", store.token); assertEquals(0, store.prunes)
    }
    @Test fun withdrawnConsentStopsBeforeLocalWrites() {
        val store = Store(); var enabled = true
        try { CalendarSync(api { _, _ -> enabled = false; EventPage(listOf(event()), null, "new") }, store) { enabled }.sync(); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(store.rows.isEmpty()); assertNull(store.token)
    }
    @Test fun paginationPreservesOriginalCheckpointAndApiNeverUploads() {
        val urls = mutableListOf<String>()
        val api = GoogleCalendarApi(HttpTransport { method, url, body ->
            assertEquals("GET", method); assertNull(body); urls += url
            HttpReply(200, if (url.contains("calendarList")) """{"items":[{"id":"primary"}]}""" else if (!url.contains("pageToken=")) """{"items":[],"nextPageToken":"page"}""" else """{"items":[],"nextSyncToken":"new"}""")
        })
        val store = Store(); store.token = "original"
        CalendarSync(api, store) { true }.sync()
        assertTrue(urls.drop(1).all { "syncToken=original" in it && "singleEvents=false" in it && "showDeleted=true" in it })
        assertEquals("new", store.token)
    }
    @Test fun repeatedPageTokenFailsWithoutPruning() {
        val store = Store()
        try { CalendarSync(api { _, _ -> EventPage(emptyList(), "same", null) }, store) { true }.sync(); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, store.prunes)
    }

    @Test fun offsetZonesFromGoogleParseAndUnknownZonesFallBack() {
        // Google writes some event zones as fixed offsets, which TimeZone.getAvailableIDs does not list (Prometheus, 10-22).
        val utc = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-21T17:00:00Z"}"""), "UTC")
        val offset = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-21T10:00:00-07:00","timeZone":"GMT-07:00"}"""), "America/Los_Angeles")
        assertEquals(utc.millis, offset.millis); assertEquals("GMT-07:00", offset.zone)
        val compact = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-21T19:00:00+02:00","timeZone":"GMT+2"}"""), "UTC")
        assertEquals(utc.millis, compact.millis); assertEquals("GMT+02:00", compact.zone)
        val unknown = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-10-21T10:00:00-07:00","timeZone":"Mars/Olympus"}"""), "America/Los_Angeles")
        assertEquals(utc.millis, unknown.millis); assertEquals("America/Los_Angeles", unknown.zone)
    }
    @Test fun unreadableEventIsSkippedKeptAndRetriedWithoutBlockingTheCalendar() {
        val api = GoogleCalendarApi(HttpTransport { _, url, _ ->
            HttpReply(200, if (url.contains("calendarList")) """{"items":[{"id":"primary","timeZone":"UTC"}]}"""
                else """{"items":[{"id":"good","start":{"date":"2026-10-06"},"end":{"date":"2026-10-07"}},{"id":"bad","start":{"dateTime":"not a time"}}],"nextSyncToken":"new"}""")
        })
        val store = Store(); store.rows["bad"] = event("bad")
        val report = CalendarSync(api, store) { true }.sync()
        assertEquals(1, report.events); assertEquals(1, report.skipped); assertEquals(0, report.failedCalendars)
        // The stored copy of the unreadable event stays, and the checkpoint waits so the next sync reads it again.
        assertEquals(setOf("good", "bad"), store.rows.keys); assertNull(store.token)
    }
    private class MultiStore : CalendarStore {
        val tokens = mutableMapOf<String, String>()
        val rows = mutableMapOf<String, MutableSet<String>>()
        override fun calendar(calendar: RemoteCalendar) { rows.getOrPut(calendar.id) { mutableSetOf() } }
        override fun checkpoint(calendar: String) = tokens[calendar]
        override fun checkpoint(calendar: String, value: String) { tokens[calendar] = value }
        override fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean { rows.getOrPut(calendar.id) { mutableSetOf() } += event.id; return true }
        override fun reconcile(calendar: RemoteCalendar, present: Set<String>) = 0
    }
    private fun twoCalendars(first: (RemoteCalendar) -> EventPage) = object : CalendarApi {
        val calendars = listOf(RemoteCalendar("primary", "Main", "UTC", "#64748b", emptyList()), RemoteCalendar("holidays", "Holidays", "UTC", "#64748b", emptyList()))
        val attempted = mutableListOf<String>()
        override fun calendars(page: String?) = CalendarPage(calendars, null)
        override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage {
            attempted += calendar.id
            return if (calendar.id == "primary") first(calendar) else EventPage(listOf(event("holiday")), null, "h1")
        }
    }
    @Test fun aFailingCalendarLeavesTheOthersToSyncAndIsReported() {
        val store = MultiStore()
        val api = twoCalendars { error("Calendar data could not be applied") }
        try { CalendarSync(api, store) { true }.sync(); fail() } catch (_: IllegalStateException) { }
        assertEquals(listOf("primary", "holidays"), api.attempted)
        assertEquals(setOf("holiday"), store.rows["holidays"]); assertEquals("h1", store.tokens["holidays"])
        assertNull(store.rows["primary"]); assertNull(store.tokens["primary"])
    }
    @Test fun authorizationRateLimitsAndTheOffSwitchStillStopTheWholeRun() {
        for (failure in listOf(CalendarRequestException(401), CalendarRequestException(429), CalendarRequestException(403, rateLimited = true))) {
            val store = MultiStore()
            val api = twoCalendars { throw failure }
            try { CalendarSync(api, store) { true }.sync(); fail() } catch (e: CalendarRequestException) { assertSame(failure, e) }
            assertEquals(listOf("primary"), api.attempted); assertTrue(store.tokens.isEmpty())
        }
        var enabled = true
        val api = twoCalendars { enabled = false; EventPage(emptyList(), null, "p1") }
        try { CalendarSync(api, MultiStore()) { enabled }.sync(); fail() } catch (_: SyncStopped) { }
        assertEquals(listOf("primary"), api.attempted)
    }
}
