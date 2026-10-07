/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import java.io.IOException
import org.json.JSONObject
import org.microg.gms.people.sync.HttpTransport
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.TimeZone

data class RemoteCalendar(val id: String, val title: String, val zone: String, val color: String,
    val reminders: List<Int>, val deleted: Boolean = false)
data class EventTime(val millis: Long, val allDay: Boolean, val zone: String)
data class RemoteEvent(val id: String, val etag: String, val title: String, val description: String,
    val location: String, val start: EventTime?, val end: EventTime?, val recurrence: List<String>,
    val masterId: String?, val original: EventTime?, val cancelled: Boolean, val reminders: List<Int>)
data class CalendarPage(val calendars: List<RemoteCalendar>, val next: String?)
data class EventPage(val events: List<RemoteEvent>, val next: String?, val checkpoint: String?)
interface CalendarApi {
    fun calendars(page: String?): CalendarPage
    fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage
}
interface CalendarStore {
    fun shouldSync(calendar: RemoteCalendar): Boolean = true
    fun calendar(calendar: RemoteCalendar)
    fun checkpoint(calendar: String): String?
    /** Return false when a locally changed row is retained rather than overwritten. */
    fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean
    fun reconcile(calendar: RemoteCalendar, present: Set<String>): Int
    fun checkpoint(calendar: String, value: String)
    fun reconcileCalendars(present: Set<String>) {}
}
data class CalendarReport(val events: Int, val conflicts: Int)

/** All pages are admitted before applying a calendar or pruning missing owned rows. Never uploads. */
class CalendarSync(private val api: CalendarApi, private val store: CalendarStore, private val enabled: () -> Boolean) {
    fun sync(): CalendarReport {
        fun consent() { check(enabled()) { "Calendar synchronization stopped" } }
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
        for (calendar in calendars.distinctBy { it.id }.filterNot { it.deleted }) {
            consent()
            if (!store.shouldSync(calendar)) {
                store.calendar(calendar)
                continue
            }
            var checkpoint = store.checkpoint(calendar.id)
            var reset = false
            var events: List<RemoteEvent>
            var nextCheckpoint: String
            while (true) {
                try {
                    val gathered = mutableListOf<RemoteEvent>()
                    val pages = mutableSetOf<String>()
                    page = null
                    var finalCheckpoint: String? = null
                    do {
                        consent()
                        val response = api.events(calendar, checkpoint, page)
                        gathered += response.events
                        check(gathered.size <= 10_000)
                        check(gathered.sumOf { it.title.length.toLong() + it.description.length + it.location.length + it.recurrence.sumOf(String::length) } <= 16 * 1024 * 1024)
                        page = response.next
                        check(page == null || pages.add(page!!))
                        if (page == null) finalCheckpoint = response.checkpoint
                    } while (page != null)
                    nextCheckpoint = requireNotNull(finalCheckpoint?.takeIf { it.isNotBlank() })
                    events = gathered.distinctBy { it.id }.sortedBy { it.masterId != null }
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
            var calendarConflicts = 0
            for (event in events) {
                consent()
                if (!store.event(calendar, event)) calendarConflicts++ else count++
            }
            if (checkpoint == null) {
                consent()
                calendarConflicts += store.reconcile(calendar, events.map { it.id }.toSet())
            }
            consent()
            // A conflict must remain retrievable after the owner resolves a local change.
            if (calendarConflicts == 0) store.checkpoint(calendar.id, nextCheckpoint)
            conflicts += calendarConflicts
        }
        consent()
        store.reconcileCalendars(calendars.filterNot { it.deleted }.map { it.id }.toSet())
        return CalendarReport(count, conflicts)
    }
}

class CalendarRequestException(val status: Int, val retryAfterSeconds: Long = 0,
    val rateLimited: Boolean = false) : IOException("Calendar API request failed ($status)")

class GoogleCalendarApi(private val transport: HttpTransport) : CalendarApi {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun get(path: String, params: Map<String, String?>): JSONObject {
        val query = params.filterValues { it != null }.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value!!) }
        val reply = transport.request("GET", "https://www.googleapis.com/calendar/v3/$path?$query", null)
        if (reply.status !in 200..299) {
            val reasons = runCatching { JSONObject(reply.body).getJSONObject("error").getJSONArray("errors") }.getOrNull()
            val limited = reply.status == 429 || reply.status == 403 && (0 until minOf(reasons?.length() ?: 0, 16)).any {
                reasons!!.optJSONObject(it)?.optString("reason") in setOf("rateLimitExceeded", "userRateLimitExceeded")
            }
            throw CalendarRequestException(reply.status, reply.retryAfterSeconds.coerceIn(0, 86_400), limited)
        }
        return JSONObject(reply.body)
    }
    override fun calendars(page: String?): CalendarPage {
        val body = get("users/me/calendarList", mapOf("maxResults" to "250", "minAccessRole" to "reader", "pageToken" to page))
        val items = body.optJSONArray("items")
        val calendars = (0 until (items?.length() ?: 0)).map { i ->
            val row = items!!.getJSONObject(i)
            RemoteCalendar(row.getString("id"), row.optString("summary"), row.optString("timeZone", "UTC"),
                row.optString("backgroundColor", "#64748b"), reminders(row.optJSONArray("defaultReminders")), row.optBoolean("deleted"))
        }
        return CalendarPage(calendars, body.optString("nextPageToken").takeIf { it.isNotBlank() })
    }
    override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage {
        val body = get("calendars/${encode(calendar.id)}/events", mapOf("maxResults" to "2500", "singleEvents" to "false",
            "showDeleted" to "true", "syncToken" to checkpoint, "pageToken" to page))
        val items = body.optJSONArray("items")
        return EventPage((0 until (items?.length() ?: 0)).map { parseEvent(items!!.getJSONObject(it), calendar) },
            body.optString("nextPageToken").takeIf { it.isNotBlank() }, body.optString("nextSyncToken").takeIf { it.isNotBlank() })
    }
    companion object {
        private val knownZones = TimeZone.getAvailableIDs().toSet()
        fun parseEvent(row: JSONObject, calendar: RemoteCalendar): RemoteEvent {
            val recurrence = row.optJSONArray("recurrence")
            val reminder = row.optJSONObject("reminders")
            return RemoteEvent(row.getString("id"), row.optString("etag"), row.optString("summary"), row.optString("description"),
                row.optString("location"), row.optJSONObject("start")?.let { parseTime(it, calendar.zone) },
                row.optJSONObject("end")?.let { parseTime(it, calendar.zone) },
                (0 until (recurrence?.length() ?: 0)).map { normalizeRecurrence(recurrence!!.getString(it)) },
                row.optString("recurringEventId").takeIf { it.isNotBlank() },
                row.optJSONObject("originalStartTime")?.let { parseTime(it, calendar.zone) }, row.optString("status") == "cancelled",
                if (reminder?.optBoolean("useDefault") != false) calendar.reminders else reminders(reminder.optJSONArray("overrides")))
        }
        fun parseTime(row: JSONObject, defaultZone: String): EventTime {
            val allDay = row.has("date")
            val raw = row.getString(if (allDay) "date" else "dateTime")
            val zone = if (allDay) "UTC" else row.optString("timeZone", defaultZone)
            require(zone in knownZones)
            val hasOffset = !allDay && Regex("(Z|[+-]\\d{2}:?\\d{2})$").containsMatchIn(raw)
            // An explicit IANA zone also permits local dateTime values without a numeric offset.
            require(allDay || hasOffset || row.has("timeZone"))
            val normalized = if (allDay) raw else raw.replace(Regex("Z$"), "+0000")
                .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
                .replace(Regex("\\.(\\d+)")) { "." + it.groupValues[1].padEnd(3, '0').take(3) }
            val pattern = if (allDay) "yyyy-MM-dd" else "yyyy-MM-dd'T'HH:mm:ss" +
                (if (raw.contains('.')) ".SSS" else "") + (if (hasOffset) "Z" else "")
            val format = SimpleDateFormat(pattern, Locale.US)
            format.isLenient = false
            format.timeZone = TimeZone.getTimeZone(zone)
            val position = ParsePosition(0)
            val parsed = requireNotNull(format.parse(normalized, position))
            require(position.index == normalized.length)
            return EventTime(parsed.time, allDay, zone)
        }
        /** CalendarProvider date lists use "Zone;dates", whereas RFC5545 uses a TZID parameter. */
        fun normalizeRecurrence(line: String): String {
            val colon = line.indexOf(':'); require(colon > 0)
            val property = line.substring(0, colon).split(';')
            val kind = property.first()
            require(kind in setOf("RRULE", "EXRULE", "RDATE", "EXDATE"))
            val dates = line.substring(colon + 1)
            if (kind == "RRULE" || kind == "EXRULE") { require(property.size == 1); return "$kind:$dates" }
            require(property.drop(1).all { it.startsWith("TZID=") || it == "VALUE=DATE" || it == "VALUE=DATE-TIME" })
            val zone = property.firstOrNull { it.startsWith("TZID=") }?.removePrefix("TZID=")
            return "$kind:" + (zone?.let { "$it;" } ?: "") + dates
        }
        private fun reminders(rows: org.json.JSONArray?): List<Int> = (0 until (rows?.length() ?: 0)).mapNotNull {
            rows!!.getJSONObject(it).takeIf { it.optString("method") == "popup" }?.optInt("minutes")?.takeIf { it in 0..40320 }
        }.distinct().take(5)
    }
}
