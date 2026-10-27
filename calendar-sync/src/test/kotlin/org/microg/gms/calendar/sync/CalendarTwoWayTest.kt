/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.TimeZone
import org.microg.gms.people.sync.HttpReply

class CalendarTwoWayTest {
    private val calendar = RemoteCalendar("chosen", "Fixture", "America/Los_Angeles", "#64748b", listOf(10), accessRole = "owner")
    private fun event(id: String = "event", title: String = "original", sequence: Int = 3): RemoteEvent = GoogleCalendarApi.parseEvent(JSONObject("""{
        "id":"$id","etag":"etag1","sequence":$sequence,"summary":"$title","description":"details","location":"room",
        "start":{"dateTime":"2026-03-08T09:00:00-07:00","timeZone":"America/Los_Angeles"},
        "end":{"dateTime":"2026-03-08T10:00:00-07:00","timeZone":"America/Los_Angeles"},
        "reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":10},{"method":"email","minutes":30}]},
        "attendees":[{"email":"fixture@example.invalid"}],"conferenceData":{"conferenceId":"preserve"}}
    """), calendar)
    private fun local(base: RemoteEvent? = event(), edited: RemoteEvent = base!!.copy(title = "phone"), deleted: Boolean = false) =
        LocalEvent(1, edited, deleted, true, base, fingerprint = "local-v1")
    private class Store : CalendarStore {
        var token: String? = "old"
        val local = linkedMapOf<Long, LocalEvent>()
        val clean = linkedMapOf<String, RemoteEvent>()
        var selected = true
        var allowed = true
        var choice = CalendarConflictChoice.HOLD
        var afterBegin: (() -> Unit)? = null
        var failAck = false
        var hydrateCount = 0
        var hydrateConflict = false
        override fun shouldSync(calendar: RemoteCalendar) = selected
        override fun uploadAllowed(calendar: RemoteCalendar) = allowed
        override fun calendar(calendar: RemoteCalendar) = Unit
        override fun checkpoint(calendar: String) = token
        override fun checkpoint(calendar: String, value: String) { token = value }
        override fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean { clean[event.id] = event; return true }
        override fun reconcile(calendar: RemoteCalendar, present: Set<String>): Int { clean.keys.retainAll(present); return 0 }
        override fun localEvents(calendar: RemoteCalendar) = local.values.toList()
        override fun begin(calendar: RemoteCalendar, existing: LocalEvent, pending: JSONObject): LocalEvent {
            val next = existing.copy(pending = JSONObject(pending.toString()))
            local[existing.id] = next
            afterBegin?.invoke()
            return next
        }
        override fun acknowledge(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent?): Boolean {
            if (failAck) throw IOException("provider commit lost")
            val current = local[existing.id] ?: return false
            if (current.fingerprint != existing.fingerprint) {
                local[existing.id] = current.copy(base = remote, pending = null,
                    event = current.event.copy(id = remote?.id ?: current.event.id, etag = remote?.etag ?: current.event.etag))
                return false
            }
            local.remove(existing.id)
            if (remote != null) clean[remote.id] = remote else clean.remove(existing.event.id)
            return true
        }
        override fun rejected(calendar: RemoteCalendar, existing: LocalEvent) {
            local[existing.id] = (local[existing.id] ?: existing).copy(pending = null)
        }
        override fun removeLocal(calendar: RemoteCalendar, existing: LocalEvent) { local.remove(existing.id) }
        override fun conflictChoice(calendar: RemoteCalendar, existing: LocalEvent) = choice
        override fun hydrate(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent): LocalEvent {
            hydrateCount++
            val pending = if (hydrateConflict) JSONObject().put("kind", "conflict").put("reason", "legacy-reminders")
                .put("id", remote.id).put("fingerprint", existing.fingerprint) else existing.pending
            return existing.copy(base = remote, unsupported = false, pending = pending).also { local[existing.id] = it }
        }
    }
    private inner class Api : CalendarApi {
        val remote = linkedMapOf<String, RemoteEvent>()
        val calls = mutableListOf<String>()
        val payloads = mutableListOf<JSONObject>()
        val etags = mutableListOf<String>()
        var beforeWrite: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        var revision = 1
        var requestedTokens = mutableListOf<String?>()
        var pages: ((String?, String?) -> EventPage)? = null
        var calendarValue = calendar
        var instance: RemoteEvent? = null
        override fun calendars(page: String?) = CalendarPage(listOf(calendarValue), null)
        override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage {
            requestedTokens += checkpoint
            return pages?.invoke(checkpoint, page) ?: EventPage(remote.values.toList(), null, "new")
        }
        override fun get(calendar: RemoteCalendar, id: String): RemoteEvent? { calls += "GET:$id"; return remote[id] }
        override fun instance(calendar: RemoteCalendar, masterId: String, original: EventTime): RemoteEvent? {
            calls += "INSTANCE:$masterId:${original.millis}"; return instance
        }
        override fun create(calendar: RemoteCalendar, id: String, payload: JSONObject): RemoteEvent {
            beforeWrite?.invoke()
            calls += "POST:$id"; payloads += JSONObject(payload.toString())
            if (remote.containsKey(id)) throw CalendarRequestException(409)
            val row = JSONObject(payload.toString()).put("id", id).put("etag", "etag${++revision}")
            val next = GoogleCalendarApi.parseEvent(row, calendar)
            remote[id] = next; afterWrite?.invoke(); return next
        }
        override fun update(calendar: RemoteCalendar, id: String, etag: String, payload: JSONObject): RemoteEvent {
            beforeWrite?.invoke()
            calls += "PATCH:$id"; payloads += JSONObject(payload.toString()); etags += etag
            val previous = remote[id] ?: instance?.takeIf { it.id == id } ?: throw CalendarRequestException(404)
            if (previous.etag != etag) throw CalendarRequestException(412)
            val row = JSONObject(requireNotNull(previous.raw))
            payload.keys().forEach { row.put(it, payload.get(it)) }
            row.put("etag", "etag${++revision}")
            val next = GoogleCalendarApi.parseEvent(row, calendar)
            remote[id] = next; afterWrite?.invoke(); return next
        }
        override fun delete(calendar: RemoteCalendar, id: String, etag: String) {
            beforeWrite?.invoke()
            calls += "DELETE:$id"; etags += etag
            val previous = remote[id] ?: instance?.takeIf { it.id == id } ?: throw CalendarRequestException(404)
            if (previous.etag != etag) throw CalendarRequestException(412)
            remote.remove(id); afterWrite?.invoke()
        }
    }
    private fun run(api: Api, store: Store, enabled: () -> Boolean = { true }) = CalendarSync(api, store, { true }, enabled).sync()
    private fun fixture(base: RemoteEvent = event(), edit: RemoteEvent = base.copy(title = "phone")): Pair<Api, Store> {
        val api = Api(); api.remote[base.id] = base
        val store = Store(); store.local[1] = local(base, edit)
        return api to store
    }
    @Test fun createJournalsClientIdBeforeNetworkAndSecondSyncDoesNotDuplicate() {
        val api = Api(); val store = Store(); store.local[1] = local(null, event("").copy(etag = ""))
        api.beforeWrite = {
            val pending = store.local[1]!!.pending!!
            assertEquals("create", pending.getString("kind")); assertTrue(pending.getString("id").matches(Regex("[0-9a-v]{32}")))
        }
        val first = run(api, store); assertEquals(1, first.uploaded); assertEquals(1, api.remote.size)
        api.beforeWrite = null
        run(api, store)
        assertEquals(1, api.calls.count { it.startsWith("POST:") }); assertEquals(1, store.clean.size); assertEquals("new", store.token)
    }
    @Test fun titleEditPatchesOnlyTitleAndSequenceKeepingAttendeesConferenceAndEmailReminders() {
        val (api, store) = fixture()
        assertEquals(1, run(api, store).uploaded)
        val patch = api.payloads.single()
        assertEquals(setOf("summary", "sequence"), patch.keySet()); assertEquals(4, patch.getInt("sequence"))
        assertEquals(listOf("etag1"), api.etags)
        val raw = JSONObject(api.remote["event"]!!.raw!!)
        assertEquals("preserve", raw.getJSONObject("conferenceData").getString("conferenceId"))
        assertEquals(1, raw.getJSONArray("attendees").length()); assertEquals(2, raw.getJSONObject("reminders").getJSONArray("overrides").length())
    }
    @Test fun remindersEditRetainsServerEmailOverrides() {
        val base = event(); val (api, store) = fixture(base, base.copy(reminders = listOf(5, 15)))
        assertEquals(1, run(api, store).uploaded)
        val overrides = api.payloads.single().getJSONObject("reminders").getJSONArray("overrides")
        assertEquals(3, overrides.length()); assertEquals("email", overrides.getJSONObject(0).getString("method"))
    }
    @Test fun inheritedRemindersStayInheritedWhenOnlyTitleChanges() {
        val base = event().copy(useDefaultReminders = true, reminders = listOf(10))
        val (api, store) = fixture(base)
        run(api, store)
        assertFalse(api.payloads.single().has("reminders"))
    }
    @Test fun localDeleteUsesEtagAndNeverInfersDeletesFromProviderWipe() {
        val (api, store) = fixture(); store.local[1] = local(deleted = true)
        assertEquals(1, run(api, store).uploaded); assertEquals(listOf("etag1"), api.etags)
        assertTrue(api.remote.isEmpty())
        api.remote["untouched"] = event("untouched"); store.local.clear(); store.clean.clear(); store.token = null
        run(api, store)
        assertNotNull(api.remote["untouched"]); assertEquals(1, api.calls.count { it.startsWith("DELETE:") })
    }
    @Test fun neverUploadedLocalDeleteDoesNotCallServer() {
        val api = Api(); val store = Store(); store.local[1] = local(null, event(""), deleted = true)
        run(api, store)
        assertTrue(api.calls.isEmpty()); assertTrue(store.local.isEmpty())
    }
    @Test fun downloadOnlyReadOnlyUnselectedAndUploadHoldNeverUpload() {
        for (kind in listOf("download", "reader", "unselected", "hold")) {
            val (api, store) = fixture()
            if (kind == "reader") api.calendarValue = calendar.copy(accessRole = "reader")
            if (kind == "unselected") store.selected = false
            if (kind == "hold") store.allowed = false
            run(api, store, { kind != "download" })
            assertTrue(kind, api.calls.none { it.startsWith("PATCH:") || it.startsWith("POST:") || it.startsWith("DELETE:") })
            assertTrue(store.local[1]!!.dirty)
        }
    }
    @Test fun withdrawalAfterJournalStopsUploadAndClearsUnsentJournal() {
        val (api, store) = fixture(); var enabled = true; store.afterBegin = { enabled = false }
        run(api, store, { enabled })
        assertTrue(api.payloads.isEmpty()); assertNull(store.local[1]!!.pending); assertEquals("old", store.token)
    }
    @Test fun disjointCloudChangeMergesWithoutOverwritingAndSequenceUsesLatest() {
        val (api, store) = fixture()
        api.remote["event"] = event().copy(location = "web", etag = "etag2", sequence = 8,
            raw = JSONObject(event().raw!!).put("location", "web").put("etag", "etag2").put("sequence", 8).toString())
        assertEquals(1, run(api, store).uploaded)
        assertEquals("web", api.remote["event"]!!.location); assertEquals(9, api.payloads.single().getInt("sequence"))
        assertEquals(listOf("etag2"), api.etags)
    }
    @Test fun overlappingCloudChangeIsHeldAcrossRetriesUntilExplicitResolution() {
        val (api, store) = fixture(); api.remote["event"] = event(title = "web").copy(etag = "etag2")
        assertEquals(1, run(api, store).conflicts); assertEquals("conflict", store.local[1]!!.pending!!.getString("kind"))
        run(api, store); assertTrue(api.payloads.isEmpty()); assertEquals("old", store.token)
        store.choice = CalendarConflictChoice.LOCAL
        assertEquals(1, run(api, store).uploaded); assertEquals("phone", api.remote["event"]!!.title)
    }
    @Test fun explicitUseGoogleResolvesHeldConflictWithoutUploading() {
        val (api, store) = fixture(); api.remote["event"] = event(title = "web").copy(etag = "etag2")
        run(api, store); store.choice = CalendarConflictChoice.REMOTE
        run(api, store)
        assertEquals("web", store.clean["event"]!!.title); assertTrue(store.local.isEmpty()); assertTrue(api.payloads.isEmpty())
    }
    @Test fun deletingChangedCloudEventHoldsEvenForDisjointRemoteEdit() {
        val (api, store) = fixture(); store.local[1] = local(deleted = true)
        api.remote["event"] = event().copy(etag = "etag2", location = "web")
        assertEquals(1, run(api, store).conflicts); assertFalse(api.calls.any { it.startsWith("DELETE:") })
    }
    @Test fun preconditionRaceBecomesDurableHoldAndDoesNotAdvanceToken() {
        val (api, store) = fixture()
        api.beforeWrite = { api.remote["event"] = event().copy(etag = "raced") }
        assertEquals(1, run(api, store).conflicts)
        assertEquals("conflict", store.local[1]!!.pending!!.getString("kind")); assertEquals("old", store.token)
        api.beforeWrite = null; run(api, store); assertEquals(1, api.payloads.size)
    }
    @Test fun lostCreateReplyRecoversByStableIdWithoutSecondPost() {
        val api = Api(); val store = Store(); store.local[1] = local(null, event("").copy(etag = ""))
        api.afterWrite = { throw IOException("reply lost") }
        assertNotNull(run(api, store).failure); assertNotNull(store.local[1]!!.pending)
        api.afterWrite = null
        assertEquals(1, run(api, store).uploaded); assertEquals(1, api.calls.count { it.startsWith("POST:") }); assertEquals(1, api.remote.size)
    }
    @Test fun createNotSentRetriesSameJournalId() {
        val api = Api(); val store = Store(); store.local[1] = local(null, event("").copy(etag = ""))
        api.beforeWrite = { throw IOException("offline before dispatch") }; run(api, store)
        val id = store.local[1]!!.pending!!.getString("id")
        api.beforeWrite = null; run(api, store)
        assertEquals(setOf(id), api.remote.keys); assertEquals("POST:$id", api.calls.last())
    }
    @Test fun updateAndDeleteNotSentReplayOriginalConditionalMutation() {
        for (delete in listOf(false, true)) {
            val (api, store) = fixture(); if (delete) store.local[1] = local(deleted = true)
            api.beforeWrite = { throw IOException("not sent") }; assertNotNull(run(api, store).failure)
            api.beforeWrite = null
            assertEquals(1, run(api, store).uploaded); assertTrue(store.local.isEmpty()); assertEquals(listOf("etag1"), api.etags)
        }
    }
    @Test fun updateAndDeleteLostReplyRecoverWithoutRepeatingWrite() {
        for (delete in listOf(false, true)) {
            val (api, store) = fixture(); if (delete) store.local[1] = local(deleted = true)
            api.afterWrite = { throw IOException("lost response") }; run(api, store); api.afterWrite = null
            assertEquals(1, run(api, store).uploaded); assertEquals(1, api.calls.count { it.startsWith(if (delete) "DELETE:" else "PATCH:") })
        }
    }
    @Test fun concurrentPhoneEditDuringRequestSurvivesAckAndLaterSync() {
        val (api, store) = fixture()
        api.afterWrite = { val now = store.local[1]!!; store.local[1] = now.copy(event = now.event.copy(title = "later"), fingerprint = "local-v2") }
        val first = run(api, store); assertEquals(1, first.conflicts); assertEquals("later", store.local[1]!!.event.title)
        assertNull(store.local[1]!!.pending); api.afterWrite = null
        assertEquals(1, run(api, store).uploaded); assertEquals("later", api.remote["event"]!!.title)
    }
    @Test fun providerAckFailureLeavesJournalForReadback() {
        val (api, store) = fixture(); store.failAck = true
        assertNotNull(run(api, store).failure); store.failAck = false
        assertEquals(1, run(api, store).uploaded); assertEquals(1, api.payloads.size)
    }
    @Test fun recurrenceAndExceptionModifyInstanceRatherThanInsert() {
        val master = event("master").copy(recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=3", "EXDATE:America/Los_Angeles;20260315T090000"))
        val (masterApi, masterStore) = fixture(master, master.copy(recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=5", "RDATE:20260329,20260405")))
        run(masterApi, masterStore)
        assertEquals("RDATE;VALUE=DATE:20260329,20260405", masterApi.payloads.single().getJSONArray("recurrence").getString(1))
        val original = event().start!!
        val inherited = event().copy(id = "", masterId = "master", original = original)
        val server = inherited.copy(id = "instance", etag = "instance-etag", raw = JSONObject(event().raw!!)
            .put("id", "instance").put("etag", "instance-etag").put("recurringEventId", "master")
            .put("originalStartTime", GoogleCalendarApi.timeJson(original)).toString())
        val api = Api(); api.instance = server
        val store = Store(); store.local[1] = local(inherited, inherited.copy(title = "one occurrence"))
        assertEquals(1, run(api, store).uploaded)
        assertTrue(api.calls.any { it.startsWith("INSTANCE:master:") }); assertTrue(api.calls.contains("PATCH:instance"))
        assertFalse(api.calls.any { it.startsWith("POST:") }); assertEquals(listOf("instance-etag"), api.etags)
    }
    @Test fun newCancelledExceptionDeletesActualInstanceConditionally() {
        val inherited = event("").copy(masterId = "master", original = event().start)
        val api = Api(); api.instance = inherited.copy(id = "instance", etag = "instance-etag")
        val store = Store(); store.local[1] = local(inherited, inherited.copy(cancelled = true), deleted = true)
        assertEquals(1, run(api, store).uploaded); assertTrue(api.calls.contains("DELETE:instance")); assertEquals(listOf("instance-etag"), api.etags)
    }
    @Test fun allDayAndDstWritesRoundTripWithoutHostZoneDependence() {
        val previous = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val before = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-03-07T09:00:00-08:00","timeZone":"America/Los_Angeles"}"""), "UTC")
            val after = GoogleCalendarApi.parseTime(JSONObject("""{"dateTime":"2026-03-08T09:00:00-07:00","timeZone":"America/Los_Angeles"}"""), "UTC")
            assertEquals(23 * 3600_000L, after.millis - before.millis)
            assertEquals(after, GoogleCalendarApi.parseTime(GoogleCalendarApi.timeJson(after), "UTC"))
            val day = GoogleCalendarApi.parseTime(JSONObject("""{"date":"2026-03-08"}"""), "America/Los_Angeles")
            val end = GoogleCalendarApi.parseTime(JSONObject("""{"date":"2026-03-09"}"""), "America/Los_Angeles")
            val api = Api(); val store = Store(); store.local[1] = local(null, event("").copy(start = day, end = end, recurrence = listOf("RRULE:FREQ=DAILY;COUNT=2")))
            run(api, store)
            val payload = api.payloads.single(); assertEquals("2026-03-08", payload.getJSONObject("start").getString("date"))
            assertFalse(payload.getJSONObject("start").has("dateTime")); assertEquals(86400_000L, api.remote.values.single().end!!.millis - day.millis)
        } finally { TimeZone.setDefault(previous) }
    }
    @Test fun syncTokenPaginationAndExpiredTokenKeepWritesIdempotent() {
        val (api, store) = fixture()
        api.pages = { token, page ->
            if (token == "old") throw CalendarRequestException(410)
            if (page == null) EventPage(listOf(event()), "page2", null) else EventPage(emptyList(), null, "fresh")
        }
        run(api, store); assertEquals(listOf("old", null, null), api.requestedTokens); assertEquals("fresh", store.token)
        assertEquals(1, api.payloads.size)
    }
    @Test fun legacyDirtyRowHydratesOnlyWhenSavedEtagStillMatches() {
        for (matching in listOf(true, false)) {
            val (api, store) = fixture()
            store.local[1] = store.local[1]!!.copy(base = null, unsupported = true)
            if (!matching) api.remote["event"] = event().copy(etag = "newer")
            val report = run(api, store)
            assertEquals(if (matching) 1 else 0, store.hydrateCount)
            assertEquals(if (matching) 1 else 0, report.uploaded)
            if (!matching) assertEquals("conflict", store.local[1]!!.pending!!.getString("kind"))
        }
    }
    @Test fun conditionalHttpTransportReceivesRealIfMatchAndEmptyDeleteResponse() {
        val captured = mutableListOf<Pair<String, Map<String,String>>>()
        val api = GoogleCalendarApi(CalendarTransport { method, url, body, headers ->
            captured += method to headers
            assertTrue(url.startsWith("https://www.googleapis.com/calendar/v3/calendars/"))
            if (method == "DELETE") { assertNull(body); HttpReply(204, "") }
            else HttpReply(200, event().raw!!)
        })
        api.update(calendar, "event", "\"etag\"", JSONObject().put("summary", "edited")); api.delete(calendar, "event", "\"etag\"")
        assertEquals(listOf("PATCH", "DELETE"), captured.map { it.first }); assertTrue(captured.all { it.second["If-Match"] == "\"etag\"" })
    }
    @Test fun codecRoundTripPreservesJournalBaselineAndRawUnknownFields() {
        val base = event().copy(masterId = "master", original = event().start, creationId = "nonce")
        assertEquals(base, CalendarEventCodec.decode(CalendarEventCodec.encode(base)))
    }
    @Test fun recurringInstanceLookupUsesOriginalStartIdentityAndAllDayDates() {
        val original = EventTime(1_772_928_000_000, true, "UTC")
        val urls = mutableListOf<String>()
        val api = GoogleCalendarApi(CalendarTransport { _, url, _, _ ->
            urls += url
            val row = JSONObject(event("instance").raw!!).put("recurringEventId", "master")
                .put("originalStartTime", GoogleCalendarApi.timeJson(original))
            HttpReply(200, JSONObject().put("items", JSONArray().put(row)).toString())
        })
        assertEquals("instance", api.instance(calendar, "master", original)!!.id)
        assertTrue(urls.single().contains("originalStart=2026-03-08")); assertTrue(urls.single().contains("showDeleted=true"))
    }
    @Test fun newPrivateFreeEventAndLaterPrivacyEditAreSentExplicitly() {
        val api = Api(); val store = Store()
        store.local[1] = local(null, event("").copy(visibility = "private", transparency = "transparent"))
        assertEquals(1, run(api, store).uploaded)
        assertEquals("private", api.payloads.single().getString("visibility"))
        assertEquals("transparent", api.payloads.single().getString("transparency"))
        val base = api.remote.values.single()
        store.local[2] = local(base, base.copy(visibility = "public")).copy(id = 2)
        assertEquals(1, run(api, store).uploaded)
        assertEquals(setOf("visibility", "sequence"), api.payloads.last().keySet())
    }
    @Test fun pendingUpdateRacedWithCloudChangeBecomesResolvableConflict() {
        val (api, store) = fixture()
        api.beforeWrite = { throw IOException("not sent") }; run(api, store)
        api.beforeWrite = null; api.remote["event"] = event(title = "web").copy(etag = "newer")
        assertEquals(1, run(api, store).conflicts)
        assertEquals("conflict", store.local[1]!!.pending!!.getString("kind"))
        store.choice = CalendarConflictChoice.REMOTE
        run(api, store); assertEquals("web", store.clean["event"]!!.title); assertTrue(api.payloads.isEmpty())
    }
    @Test fun unreadableRemoteDirtyRowIsNeverUploadedOrCheckpointed() {
        val (api, store) = fixture()
        api.pages = { _, _ -> EventPage(emptyList(), null, "new", listOf("event")) }
        assertEquals(1, run(api, store).conflicts); assertTrue(api.payloads.isEmpty()); assertEquals("old", store.token)
    }

    @Test fun missingOrCancelledNewInstanceCannotResolveAgainstItsParentSeries() {
        for (cancelled in listOf(false, true)) for (choice in listOf(CalendarConflictChoice.LOCAL, CalendarConflictChoice.REMOTE)) {
            val inherited = event("").copy(masterId = "master", original = event().start)
            val api = Api(); api.remote["master"] = event("master").copy(recurrence = listOf("RRULE:FREQ=DAILY;COUNT=3"))
            if (cancelled) api.instance = inherited.copy(id = "cancelled-instance", cancelled = true)
            val store = Store(); store.local[1] = local(inherited, inherited.copy(title = "single occurrence"))
            assertEquals(1, run(api, store).conflicts)
            val hold = store.local[1]!!.pending!!
            assertTrue(hold.getBoolean("missingInstance")); assertEquals("", hold.getString("id"))
            store.choice = choice
            run(api, store)
            assertTrue(api.calls.none { it == "GET:master" || it.startsWith("PATCH:") || it.startsWith("DELETE:") || it.startsWith("POST:") })
            assertNotNull(api.remote["master"])
            if (choice == CalendarConflictChoice.REMOTE) assertTrue(store.local.isEmpty()) else assertNotNull(store.local[1])
        }
    }
    @Test fun cloudDeletedLocalEditCanBeExplicitlyDiscardedOrRecreated() {
        for (choice in listOf(CalendarConflictChoice.LOCAL, CalendarConflictChoice.REMOTE)) {
            val (api, store) = fixture(); api.remote.clear()
            assertEquals(1, run(api, store).conflicts)
            assertEquals("conflict", store.local[1]!!.pending!!.getString("kind"))
            store.choice = choice; run(api, store)
            assertTrue(store.local.isEmpty())
            if (choice == CalendarConflictChoice.LOCAL) {
                assertEquals(1, api.remote.size); assertEquals("phone", api.remote.values.single().title)
                assertFalse(api.remote.containsKey("event"))
            } else assertTrue(api.remote.isEmpty())
        }
    }
    @Test fun keepPhoneChoiceNeverSilentlyAcknowledgesUnsupportedLocalFields() {
        val (api, store) = fixture()
        store.local[1] = store.local[1]!!.copy(base = null, unsupported = true)
        api.remote["event"] = event(title = "web").copy(etag = "newer")
        run(api, store); store.choice = CalendarConflictChoice.LOCAL
        val report = run(api, store)
        assertEquals(1, report.conflicts); assertTrue(api.payloads.isEmpty()); assertTrue(store.local[1]!!.dirty)
    }

    @Test fun cancelledExceptionIsRetainedAfterDeleteAndLostReplyRecovery() {
        for (loseReply in listOf(false, true)) {
            val base = event("instance").copy(masterId = "master", original = event().start)
            val (api, store) = fixture(base, base.copy(cancelled = true))
            store.local[1] = store.local[1]!!.copy(deleted = true)
            if (loseReply) api.afterWrite = { throw IOException("delete reply lost") }
            run(api, store); api.afterWrite = null
            if (loseReply) run(api, store)
            val cancelled = store.clean["instance"]!!
            assertTrue(cancelled.cancelled); assertEquals("master", cancelled.masterId); assertEquals(base.original, cancelled.original)
            assertEquals(1, api.calls.count { it == "DELETE:instance" })
        }
    }
    @Test fun useGoogleAppliesCancelledExceptionRatherThanResurrectingOccurrence() {
        val base = event("instance").copy(masterId = "master", original = event().start)
        val (api, store) = fixture(base, base.copy(title = "phone"))
        api.remote["instance"] = base.copy(cancelled = true, etag = "cancelled")
        run(api, store); store.choice = CalendarConflictChoice.REMOTE
        run(api, store)
        assertTrue(store.clean["instance"]!!.cancelled); assertEquals("master", store.clean["instance"]!!.masterId)
        assertTrue(api.payloads.isEmpty())
    }

    @Test fun alreadyCancelledOccurrenceIsRetainedWhenPhoneAlsoDeletedIt() {
        for (knownId in listOf(false, true)) {
            val base = event(if (knownId) "instance" else "").copy(masterId = "master", original = event().start)
            val api = Api(); val cancelled = base.copy(id = "instance", cancelled = true, etag = "cancelled")
            if (knownId) api.remote["instance"] = cancelled else api.instance = cancelled
            val store = Store(); store.local[1] = local(base, base.copy(cancelled = true), deleted = true)
            run(api, store)
            assertTrue(store.clean["instance"]!!.cancelled); assertEquals("master", store.clean["instance"]!!.masterId)
            assertTrue(api.payloads.isEmpty())
        }
    }

    @Test fun hydrationConflictWaitsForOwnerWithoutEnteringMutationReadback() {
        val (api, store) = fixture()
        store.local[1] = store.local[1]!!.copy(base = null)
        store.hydrateConflict = true
        val report = run(api, store)
        assertNull(report.failure); assertEquals(1, report.conflicts); assertEquals(0, report.uploaded)
        assertEquals("old", store.token); assertTrue(api.payloads.isEmpty())
        assertEquals("conflict", store.local[1]!!.pending!!.getString("kind"))
        assertEquals(listOf("GET:event"), api.calls)
    }

    @Test fun editingInheritedPopupReminderPreservesCalendarEmailDefault() {
        val listApi = GoogleCalendarApi(CalendarTransport { _, _, _, _ -> HttpReply(200, """{
            "items":[{"id":"chosen","accessRole":"owner","timeZone":"America/Los_Angeles",
            "defaultReminders":[{"method":"popup","minutes":10},{"method":"email","minutes":30}]}]}
        """) })
        val selected = listApi.calendars(null).calendars.single()
        assertEquals(listOf(10), selected.reminders)
        assertEquals(listOf(CalendarReminder("popup", 10), CalendarReminder("email", 30)), selected.defaultReminders)
        val base = event().copy(useDefaultReminders = true,
            raw = JSONObject(event().raw!!).put("reminders", JSONObject().put("useDefault", true)).toString())
        val (api, store) = fixture(base, base.copy(reminders = listOf(5), useDefaultReminders = false))
        api.calendarValue = selected
        assertEquals(1, run(api, store).uploaded)
        val reminders = api.payloads.single().getJSONObject("reminders")
        assertFalse(reminders.getBoolean("useDefault"))
        val overrides = reminders.getJSONArray("overrides")
        assertEquals(2, overrides.length())
        assertEquals("email", overrides.getJSONObject(0).getString("method")); assertEquals(30, overrides.getJSONObject(0).getInt("minutes"))
        assertEquals("popup", overrides.getJSONObject(1).getString("method")); assertEquals(5, overrides.getJSONObject(1).getInt("minutes"))
    }
    @Test fun unsupportedInheritedReminderOrCombinedLimitHoldsPopupEditWithoutDroppingData() {
        val defaults = listOf(
            listOf(CalendarReminder("sms", 30)),
            listOf(CalendarReminder("email", -1)),
            (1..5).map { CalendarReminder("email", it) })
        for (previous in defaults) {
            val base = event().copy(useDefaultReminders = true,
                raw = JSONObject(event().raw!!).put("reminders", JSONObject().put("useDefault", true)).toString())
            val (api, store) = fixture(base, base.copy(reminders = listOf(5), useDefaultReminders = false))
            api.calendarValue = calendar.copy(defaultReminders = previous)
            val report = run(api, store)
            assertNull(report.failure); assertEquals(1, report.conflicts); assertTrue(api.payloads.isEmpty())
            assertEquals("conflict", store.local[1]!!.pending!!.getString("kind")); assertEquals("old", store.token)
        }
    }

}
