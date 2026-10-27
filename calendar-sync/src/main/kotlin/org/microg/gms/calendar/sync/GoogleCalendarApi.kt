/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import java.io.IOException
import org.json.JSONObject
import org.json.JSONArray
import org.microg.gms.people.sync.HttpTransport
import org.microg.gms.people.sync.HttpReply
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.TimeZone
import java.util.Date

class CalendarRequestException(val status: Int, val retryAfterSeconds: Long = 0,
    val rateLimited: Boolean = false) : IOException("Calendar API request failed ($status)")

/** Calendar mutations need conditional HTTP headers; contacts' legacy transport has no header channel. */
fun interface CalendarTransport {
    fun request(method: String, url: String, body: String?, headers: Map<String, String>): HttpReply
}
class GoogleCalendarApi(private val transport: CalendarTransport) : CalendarApi {
    constructor(transport: HttpTransport) : this(CalendarTransport { method, url, body, headers ->
        check(method == "GET" && headers.isEmpty()) { "Calendar mutation requires conditional transport" }
        transport.request(method, url, body)
    })
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun request(method: String, path: String, params: Map<String, String?> = emptyMap(),
        payload: JSONObject? = null, etag: String? = null): JSONObject? {
        val query = params.filterValues { it != null }.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value!!) }
        val headers = if (etag != null) mapOf("If-Match" to etag.also { require(it.isNotBlank()) }) else emptyMap()
        val reply = transport.request(method, "https://www.googleapis.com/calendar/v3/$path" +
            (if (query.isEmpty()) "" else "?$query"), payload?.toString(), headers)
        if (reply.status !in 200..299) {
            val reasons = runCatching { JSONObject(reply.body).getJSONObject("error").getJSONArray("errors") }.getOrNull()
            val limited = reply.status == 429 || reply.status == 403 && (0 until minOf(reasons?.length() ?: 0, 16)).any {
                reasons!!.optJSONObject(it)?.optString("reason") in setOf("rateLimitExceeded", "userRateLimitExceeded")
            }
            throw CalendarRequestException(reply.status, reply.retryAfterSeconds.coerceIn(0, 86_400), limited)
        }
        return if (reply.body.isBlank()) null else JSONObject(reply.body)
    }
    private fun get(path: String, params: Map<String, String?>): JSONObject = requireNotNull(request("GET", path, params))
    private fun eventPath(calendar: RemoteCalendar, id: String? = null) =
        "calendars/${encode(calendar.id)}/events" + (id?.let { "/${encode(it)}" } ?: "")
    override fun get(calendar: RemoteCalendar, id: String): RemoteEvent? = try {
        parseEvent(requireNotNull(request("GET", eventPath(calendar, id))), calendar)
    } catch (e: CalendarRequestException) { if (e.status in setOf(404, 410)) null else throw e }
    override fun instance(calendar: RemoteCalendar, masterId: String, original: EventTime): RemoteEvent? {
        var page: String? = null
        val pages = mutableSetOf<String>()
        var match: RemoteEvent? = null
        do {
            val body = try { get(eventPath(calendar, masterId) + "/instances", mapOf("originalStart" to timeJson(original).getString(if (original.allDay) "date" else "dateTime"),
                "showDeleted" to "true", "maxResults" to "2500", "pageToken" to page)) }
                catch (e: CalendarRequestException) { if (e.status in setOf(404, 410)) return null else throw e }
            val rows = body.optJSONArray("items")
            for (i in 0 until (rows?.length() ?: 0)) {
                val event = parseEvent(rows!!.getJSONObject(i), calendar)
                if (event.masterId == masterId && event.original?.millis == original.millis && event.original.allDay == original.allDay) {
                    check(match == null) { "Ambiguous recurring instance" }; match = event
                }
            }
            page = body.optString("nextPageToken").takeIf { it.isNotBlank() }
            check(page == null || pages.add(page) && pages.size <= 100)
        } while (page != null)
        return match
    }
    override fun create(calendar: RemoteCalendar, id: String, payload: JSONObject): RemoteEvent {
        require(id.matches(Regex("[0-9a-v]{5,1024}")))
        val body = JSONObject(payload.toString()).put("id", id)
        return parseEvent(requireNotNull(request("POST", eventPath(calendar), mapOf("sendUpdates" to "none"), body)), calendar)
    }
    override fun update(calendar: RemoteCalendar, id: String, etag: String, payload: JSONObject): RemoteEvent =
        parseEvent(requireNotNull(request("PATCH", eventPath(calendar, id), mapOf("sendUpdates" to "none"), payload, etag)), calendar)
    override fun delete(calendar: RemoteCalendar, id: String, etag: String) {
        request("DELETE", eventPath(calendar, id), mapOf("sendUpdates" to "none"), etag = etag)
    }
    override fun calendars(page: String?): CalendarPage {
        val body = get("users/me/calendarList", mapOf("maxResults" to "250", "minAccessRole" to "reader", "pageToken" to page))
        val items = body.optJSONArray("items")
        val calendars = (0 until (items?.length() ?: 0)).map { i ->
            val row = items!!.getJSONObject(i)
            RemoteCalendar(row.getString("id"), row.optString("summary"), row.optString("timeZone", "UTC"),
                row.optString("backgroundColor", "#64748b"), reminders(row.optJSONArray("defaultReminders")), row.optBoolean("deleted"), row.optString("accessRole", "reader"),
                reminderEntries(row.optJSONArray("defaultReminders")))
        }
        return CalendarPage(calendars, body.optString("nextPageToken").takeIf { it.isNotBlank() })
    }
    override fun events(calendar: RemoteCalendar, checkpoint: String?, page: String?): EventPage {
        val body = get("calendars/${encode(calendar.id)}/events", mapOf("maxResults" to "2500", "singleEvents" to "false",
            "showDeleted" to "true", "syncToken" to checkpoint, "pageToken" to page))
        val items = body.optJSONArray("items")
        val events = mutableListOf<RemoteEvent>()
        val unreadable = mutableListOf<String>()
        for (i in 0 until (items?.length() ?: 0)) {
            // An entry without an id cannot be kept out of reconciliation: the page is refused, nothing is deleted.
            val row = checkNotNull(items!!.optJSONObject(i)) { "Calendar entry is not an object" }
            try { events += parseEvent(row, calendar) }
            catch (e: Exception) { unreadable += row.optString("id").takeIf { it.isNotBlank() } ?: throw e }
        }
        return EventPage(events, body.optString("nextPageToken").takeIf { it.isNotBlank() },
            body.optString("nextSyncToken").takeIf { it.isNotBlank() }, unreadable)
    }
    companion object {
        fun timestamp(millis: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))
        fun timeJson(time: EventTime): JSONObject = JSONObject().apply {
            if (time.allDay) put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(time.millis)))
            else {
                put("dateTime", timestamp(time.millis))
                put("timeZone", zoneOf(time.zone, "UTC"))
            }
        }
        /** Reverse CalendarProvider date-list syntax without losing all-day VALUE=DATE. */
        fun exportRecurrence(line: String): String {
            val colon = line.indexOf(':'); require(colon > 0)
            val kind = line.substring(0, colon)
            require(kind in setOf("RRULE", "EXRULE", "RDATE", "EXDATE"))
            val value = line.substring(colon + 1)
            if (kind == "RRULE" || kind == "EXRULE") return "$kind:$value"
            val semicolon = value.indexOf(';')
            val zone = if (semicolon >= 0) value.substring(0, semicolon) else null
            val dates = if (semicolon >= 0) value.substring(semicolon + 1) else value
            require(dates.split(',').all { it.matches(Regex("[0-9]{8}(T[0-9]{6}Z?)?")) })
            return kind + (if (zone != null) ";TZID=$zone" else if (!dates.contains('T')) ";VALUE=DATE" else "") + ":$dates"
        }
        private val knownZones = TimeZone.getAvailableIDs().toSet()
        /** Google writes some event zones as a fixed offset ("GMT-07:00"), which getAvailableIDs does not list. */
        private val offsetZone = Regex("GMT[+-](\\d{1,2})(?::?(\\d{2}))?")
        /** A named zone as is, a GMT offset as Java names it, else the calendar's zone, else UTC. */
        fun zoneOf(zone: String, calendarZone: String): String = when {
            zone in knownZones -> zone
            offsetZone.matchEntire(zone)?.let { m -> m.groupValues[1].toInt() <= 18 && (m.groupValues[2].ifEmpty { "0" }).toInt() < 60 } == true ->
                TimeZone.getTimeZone(zone).id
            calendarZone in knownZones -> calendarZone
            else -> "UTC"
        }
        fun parseEvent(row: JSONObject, calendar: RemoteCalendar): RemoteEvent {
            val recurrence = row.optJSONArray("recurrence")
            val reminder = row.optJSONObject("reminders")
            return RemoteEvent(row.getString("id"), row.optString("etag"), row.optString("summary"), row.optString("description"),
                row.optString("location"), row.optJSONObject("start")?.let { parseTime(it, calendar.zone) },
                row.optJSONObject("end")?.let { parseTime(it, calendar.zone) },
                (0 until (recurrence?.length() ?: 0)).map { normalizeRecurrence(recurrence!!.getString(it)) },
                row.optString("recurringEventId").takeIf { it.isNotBlank() },
                row.optJSONObject("originalStartTime")?.let { parseTime(it, calendar.zone) }, row.optString("status") == "cancelled",
                if (reminder?.optBoolean("useDefault") != false) calendar.reminders else reminders(reminder.optJSONArray("overrides")),
                row.optInt("sequence"), reminder?.optBoolean("useDefault") != false,
                row.optJSONObject("extendedProperties")?.optJSONObject("private")?.optString("cyclonSyncCreate")?.takeIf(String::isNotBlank), row.toString(),
                row.optString("visibility", "default"), row.optString("transparency", "opaque"))
        }
        fun parseTime(row: JSONObject, defaultZone: String): EventTime {
            val allDay = row.has("date")
            val raw = row.getString(if (allDay) "date" else "dateTime")
            val zone = if (allDay) "UTC" else zoneOf(row.optString("timeZone", defaultZone), defaultZone)
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
        private fun reminderEntries(rows: JSONArray?): List<CalendarReminder> = (0 until (rows?.length() ?: 0)).map {
            rows!!.getJSONObject(it).let { reminder -> CalendarReminder(reminder.getString("method"), reminder.getInt("minutes")) }
        }
        private fun reminders(rows: org.json.JSONArray?): List<Int> = (0 until (rows?.length() ?: 0)).mapNotNull {
            rows!!.getJSONObject(it).takeIf { it.optString("method") == "popup" }?.optInt("minutes")?.takeIf { it in 0..40320 }
        }.distinct().take(5)
    }
}
