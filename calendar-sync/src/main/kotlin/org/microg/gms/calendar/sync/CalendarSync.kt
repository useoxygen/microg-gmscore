/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.json.JSONObject

data class CalendarReminder(val method: String, val minutes: Int)
data class RemoteCalendar(val id: String, val title: String, val zone: String, val color: String,
    val reminders: List<Int>, val deleted: Boolean = false, val accessRole: String = "reader",
    val defaultReminders: List<CalendarReminder> = reminders.map { CalendarReminder("popup", it) }) {
    val writable: Boolean get() = accessRole in setOf("owner", "writer")
}
data class EventTime(val millis: Long, val allDay: Boolean, val zone: String)
data class RemoteEvent(val id: String, val etag: String, val title: String, val description: String,
    val location: String, val start: EventTime?, val end: EventTime?, val recurrence: List<String>,
    val masterId: String?, val original: EventTime?, val cancelled: Boolean, val reminders: List<Int>,
    val sequence: Int = 0, val useDefaultReminders: Boolean = false, val creationId: String? = null, val raw: String? = null,
    val visibility: String = "default", val transparency: String = "opaque")
/** A provider snapshot; fingerprints are opaque to the engine and must include all local writable fields. */
data class LocalEvent(val id: Long, val event: RemoteEvent, val deleted: Boolean, val dirty: Boolean,
    val base: RemoteEvent?, val pending: JSONObject? = null, val fingerprint: String, val unsupported: Boolean = false)
data class CalendarPage(val calendars: List<RemoteCalendar>, val next: String?)
/** [unreadable]: ids of events on this page that could not be read; they are skipped, and kept if already stored. */
data class EventPage(val events: List<RemoteEvent>, val next: String?, val checkpoint: String?, val unreadable: List<String> = emptyList())
interface CalendarApi {
    fun calendars(page: String?): CalendarPage
    fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage
    fun get(calendar: RemoteCalendar, id: String): RemoteEvent? = error("Calendar writes unavailable")
    fun instance(calendar: RemoteCalendar, masterId: String, original: EventTime): RemoteEvent? = error("Calendar writes unavailable")
    fun create(calendar: RemoteCalendar, id: String, payload: JSONObject): RemoteEvent = error("Calendar writes unavailable")
    fun update(calendar: RemoteCalendar, id: String, etag: String, payload: JSONObject): RemoteEvent = error("Calendar writes unavailable")
    fun delete(calendar: RemoteCalendar, id: String, etag: String) { error("Calendar writes unavailable") }
}
enum class CalendarConflictChoice { HOLD, LOCAL, REMOTE }
interface CalendarStore {
    fun shouldSync(calendar: RemoteCalendar): Boolean = true
    fun calendar(calendar: RemoteCalendar)
    fun checkpoint(calendar: String): String?
    /** Return false when a locally changed row is retained rather than overwritten. */
    fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean
    fun reconcile(calendar: RemoteCalendar, present: Set<String>): Int
    fun checkpoint(calendar: String, value: String)
    fun reconcileCalendars(present: Set<String>) {}
    /** Only dirty rows or durable journals in this owned, selected calendar; missing rows are never deletions. */
    fun localEvents(calendar: RemoteCalendar): List<LocalEvent> = emptyList()
    fun uploadAllowed(calendar: RemoteCalendar): Boolean = false
    fun conflictChoice(calendar: RemoteCalendar, existing: LocalEvent): CalendarConflictChoice = CalendarConflictChoice.HOLD
    /** Legacy read-only rows may acquire a baseline only after direct GET proves their saved etag still current. */
    fun hydrate(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent): LocalEvent = existing.copy(base = remote)
    /** Persist the complete journal before dispatch, without clearing dirty/deleted state. */
    fun begin(calendar: RemoteCalendar, existing: LocalEvent, pending: JSONObject): LocalEvent = error("Calendar writes unavailable")
    /** Update identity/baseline, but clear dirty only if the current fingerprint still equals the sent fingerprint. */
    fun acknowledge(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent?): Boolean = error("Calendar writes unavailable")
    fun rejected(calendar: RemoteCalendar, existing: LocalEvent) { error("Calendar writes unavailable") }
    fun removeLocal(calendar: RemoteCalendar, existing: LocalEvent) { error("Calendar writes unavailable") }
}
/**
 * [skipped]: events that could not be read; [failedCalendars]: calendars whose events could not be fetched or applied, and
 * [failure] the first of their errors (the others' counts still stand).
 */
data class CalendarReport(val events: Int, val conflicts: Int, val skipped: Int = 0, val failedCalendars: Int = 0,
    val failure: Exception? = null, val uploaded: Int = 0, val uncertain: Int = 0)

/** The owner turned synchronization off, or the sync was cancelled: stops the whole run, never one calendar. */
class SyncStopped : IllegalStateException("Calendar synchronization stopped")

/**
 * All pages are admitted before applying a calendar or pruning missing owned rows.
 * Each calendar stands alone: an event that cannot be read is skipped (a stored copy is kept) and a calendar that fails
 * leaves the others to sync, its error returned in the report. Authorization, rate limits and the owner's off switch still
 * stop the whole run (thrown).
 */
class CalendarSync(private val api: CalendarApi, private val store: CalendarStore, private val enabled: () -> Boolean,
    private val uploadEnabled: () -> Boolean) {
    constructor(api: CalendarApi, store: CalendarStore, enabled: () -> Boolean) : this(api, store, enabled, { false })
    fun sync(): CalendarReport {
        fun consent() { if (!enabled()) throw SyncStopped() }
        val calendars = mutableListOf<RemoteCalendar>()
        var page: String? = null
        val seen = mutableSetOf<String>()
        do {
            consent()
            val response = api.calendars(page)
            calendars += response.calendars
            check(calendars.size <= 200)
            page = response.next
            check(page == null || seen.add(page!!))
        } while (page != null)
        var count = 0
        var conflicts = 0
        var skipped = 0
        var uploaded = 0
        var uncertain = 0
        var failedCalendars = 0
        var failure: Exception? = null
        for (calendar in calendars.distinctBy { it.id }.filterNot { it.deleted }) {
            consent()
            try {
                val outcome = syncCalendar(calendar, ::consent)
                count += outcome.events; conflicts += outcome.conflicts; skipped += outcome.skipped
                uploaded += outcome.uploaded; uncertain += outcome.uncertain
            } catch (e: SyncStopped) { throw e
            } catch (e: InterruptedException) { throw e
            } catch (e: CalendarRequestException) {
                if (e.status == 401 || e.status == 429 || e.rateLimited) throw e
                failedCalendars++; failure = failure ?: e
            } catch (e: Exception) { failedCalendars++; failure = failure ?: e }
        }
        consent()
        store.reconcileCalendars(calendars.filterNot { it.deleted }.map { it.id }.toSet())
        return CalendarReport(count, conflicts, skipped, failedCalendars, failure, uploaded, uncertain)
    }

    private fun syncCalendar(calendar: RemoteCalendar, consent: () -> Unit): CalendarReport {
        if (!store.shouldSync(calendar)) {
            store.calendar(calendar)
            return CalendarReport(0, 0)
        }
        var checkpoint = store.checkpoint(calendar.id)
        var reset = false
        var events: List<RemoteEvent>
        var unreadable: Set<String>
        var nextCheckpoint: String
        while (true) {
            try {
                val gathered = mutableListOf<RemoteEvent>()
                val skippedIds = mutableSetOf<String>()
                val pages = mutableSetOf<String>()
                var page: String? = null
                var finalCheckpoint: String? = null
                do {
                    consent()
                    val response = api.events(calendar, checkpoint, page)
                    gathered += response.events
                    skippedIds += response.unreadable
                    check(gathered.size <= 10_000)
                    check(gathered.sumOf { it.title.length.toLong() + it.description.length + it.location.length + it.recurrence.sumOf(String::length) + (it.raw?.length ?: 0) } <= 16 * 1024 * 1024)
                    page = response.next
                    check(page == null || pages.add(page))
                    if (page == null) finalCheckpoint = response.checkpoint
                } while (page != null)
                nextCheckpoint = requireNotNull(finalCheckpoint?.takeIf { it.isNotBlank() })
                events = gathered.distinctBy { it.id }.sortedBy { it.masterId != null }
                unreadable = skippedIds - events.map { it.id }.toSet()
                break
            } catch (e: CalendarRequestException) {
                if (e.status != 410 || checkpoint == null || reset) throw e
                // Fetch a replacement snapshot first; do not wipe local data on invalidation.
                checkpoint = null
                reset = true
            }
        }
        consent()
        store.calendar(calendar)
        val local = store.localEvents(calendar)
        check(local.map { it.id }.distinct().size == local.size) { "Duplicate local calendar row" }
        val dirtyIds = local.mapNotNull { it.event.id.takeIf(String::isNotBlank) ?: it.pending?.optString("id")?.takeIf(String::isNotBlank) }.toSet()
        var stored = 0
        var conflicts = 0
        for (event in events) {
            consent()
            // Preserve local changes and ambiguous mutations until their journal is settled. A later checkpoint
            // revisits any unhandled remote change; an acknowledged upload already includes the latest baseline.
            if (event.id in dirtyIds) continue
            if (!store.event(calendar, event)) conflicts++ else stored++
        }
        if (checkpoint == null) {
            consent()
            // An unreadable event is not deleted: a copy stored before stays until it can be read again.
            conflicts += store.reconcile(calendar, events.map { it.id }.toSet() + unreadable + dirtyIds)
        }
        consent()
        val uploads = CalendarUploads(api, store, { enabled() && uploadEnabled() }).sync(calendar, unreadable)
        conflicts += uploads.conflicts
        // A conflict must remain retrievable after the owner resolves a local change. A skipped event does not hold the
        // checkpoint (that would refetch the whole calendar on every sync): its stored copy stays, and Google returns it
        // again the next time it changes.
        if (conflicts == 0 && uploads.uncertain == 0) store.checkpoint(calendar.id, nextCheckpoint)
        return CalendarReport(stored, conflicts, unreadable.size, uploaded = uploads.uploaded, uncertain = uploads.uncertain)
    }
}
