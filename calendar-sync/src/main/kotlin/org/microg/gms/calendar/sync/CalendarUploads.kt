/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class CalendarUploads(private val api: CalendarApi, private val store: CalendarStore,
    private val enabled: () -> Boolean) {
    data class Report(val uploaded: Int, val conflicts: Int, val uncertain: Int)
    private fun allowed(calendar: RemoteCalendar) = enabled() && calendar.writable && store.shouldSync(calendar) && store.uploadAllowed(calendar)
    fun sync(calendar: RemoteCalendar, unreadable: Set<String>): Report {
        var uploaded = 0; var conflicts = 0; var uncertain = 0
        for (snapshot in store.localEvents(calendar).sortedBy { it.event.masterId != null }) {
            var local = snapshot
            if (!local.dirty && local.pending == null) continue
            // Holds, read-only mode and deselection also gate journal recovery: acknowledging a response must never
            // lead to a retry of an upload after consent was withdrawn.
            if (!allowed(calendar)) { conflicts++; continue }
            if (local.event.id in unreadable) { conflicts++; continue }
            if (local.pending?.optString("kind") == "conflict") {
                val pending = local.pending!!
                when (store.conflictChoice(calendar, local)) {
                    CalendarConflictChoice.HOLD -> { conflicts++; continue }
                    CalendarConflictChoice.REMOTE -> {
                        val remote = conflictRemote(calendar, local, pending)
                        if (remote == null || remote.cancelled && remote.masterId == null) store.removeLocal(calendar, local)
                        else if (!store.acknowledge(calendar, local, remote)) conflicts++
                        continue
                    }
                    CalendarConflictChoice.LOCAL -> {
                        val latest = conflictRemote(calendar, local, pending)
                        if (pending.optString("operation") == "create") {
                            // A conflicting client ID must never grant authority over an unrelated server event.
                            store.rejected(calendar, local)
                            local = local.copy(event = local.event.copy(id = "", etag = ""), base = null, pending = null)
                        } else if (latest == null || latest.cancelled) {
                            if (local.deleted || local.event.cancelled) {
                                val id = latest?.id ?: local.event.id.takeIf(String::isNotBlank)
                                if (local.event.masterId != null && id == null) { conflicts++; continue }
                                if (!store.acknowledge(calendar, local, id?.let { cancellation(local.event, it, latest) })) conflicts++
                                continue
                            }
                            if (local.event.masterId != null) { conflicts++; continue }
                            // An explicit keep-phone choice may recreate an event deleted in Google. It gets a new
                            // client ID; it cannot resurrect or overwrite the old server revision accidentally.
                            store.rejected(calendar, local)
                            local = local.copy(event = local.event.copy(id = "", etag = ""), base = null, pending = null)
                        } else {
                            val rebased = local.base?.let { mergeLocal(local.event, it, latest) } ?: local.event.copy(id = latest.id)
                            store.rejected(calendar, local)
                            local = local.copy(event = rebased, base = latest, pending = null)
                        }
                    }
                }
            }
            // Old download-only rows have an etag but no full baseline. Never take a newer cloud version as the
            // baseline for an already-dirty row: that would silently authorize overwriting a concurrent cloud edit.
            if (local.base == null && local.event.id.isNotBlank() && local.event.etag.isNotBlank() && local.pending == null) {
                val latest = api.get(calendar, local.event.id)
                if (latest == null || latest.cancelled || latest.etag != local.event.etag) {
                    hold(calendar, local, local.event.id); conflicts++; continue
                }
                local = store.hydrate(calendar, local, latest)
                // Hydration may discover a legacy representation whose intent requires an owner choice (for
                // example inherited reminders changed since the old adapter downloaded them). It is a hold,
                // not a dispatched mutation journal, so it must not enter mutation readback in this pass.
                if (local.pending?.optString("kind") == "conflict") { conflicts++; continue }
            }
            if (local.unsupported) { conflicts++; continue }
            if (local.pending != null) {
                val pending = local.pending!!
                val sent = local.copy(fingerprint = pending.getString("fingerprint"))
                val id = pending.getString("id")
                val kind = pending.getString("kind")
                val remote = api.get(calendar, id)
                val resolved = when (kind) {
                    "create" -> remote != null && remote.creationId == pending.getString("creationId") && !remote.cancelled
                    "update" -> remote != null && !remote.cancelled && matches(remote, pending.getJSONObject("payload"))
                    "delete" -> remote == null || remote.cancelled
                    else -> false
                }
                if (resolved) {
                    if (store.acknowledge(calendar, sent, if (kind == "delete") cancellation(sent.event, id, remote) else remote)) uploaded++ else conflicts++
                    continue
                }
                val retry = kind == "create" && remote == null || kind in setOf("update", "delete") && remote != null &&
                    !remote.cancelled && remote.etag == pending.getString("etag") && remote.sequence == pending.getInt("sequence")
                if (!retry) {
                    store.begin(calendar, local, JSONObject(pending.toString()).put("kind", "conflict").put("operation", kind))
                    conflicts++; continue
                }
                // The same client ID makes create replay idempotent. Update/delete replay still carries its original
                // etag, so a concurrent server edit can only reject the retry, never be silently overwritten.
                if (!allowed(calendar)) { conflicts++; continue }
                try {
                    val response = when (kind) {
                        "create" -> api.create(calendar, id, pending.getJSONObject("payload")).also {
                            check(it.id == id && it.creationId == pending.getString("creationId"))
                        }
                        "update" -> api.update(calendar, id, pending.getString("etag"), pending.getJSONObject("payload"))
                        else -> {
                            api.delete(calendar, id, pending.getString("etag"))
                            cancellation(sent.event, id, if (sent.event.masterId != null) api.get(calendar, id) else null)
                        }
                    }
                    if (store.acknowledge(calendar, sent, response)) uploaded++ else conflicts++
                } catch (e: CalendarRequestException) {
                    if (e.status in setOf(409, 412)) {
                        if (kind == "create" && e.status == 409) uncertain++
                        else { store.begin(calendar, local, JSONObject(pending.toString()).put("kind", "conflict").put("operation", kind)); conflicts++ }
                        continue
                    }
                    if (definitelyRejected(e)) store.rejected(calendar, local)
                    throw e
                }
                continue
            }
            val remoteId = local.event.id.takeIf(String::isNotBlank)
            val deleting = local.deleted || local.event.cancelled
            if (remoteId == null && local.event.masterId == null && deleting) {
                store.removeLocal(calendar, local); continue
            }
            val latest = when {
                remoteId != null -> api.get(calendar, remoteId)
                local.event.masterId != null -> api.instance(calendar, local.event.masterId, requireNotNull(local.event.original))
                else -> null
            }
            if ((remoteId != null || local.event.masterId != null) && (latest == null || latest.cancelled)) {
                if (deleting) {
                    val id = latest?.id ?: remoteId
                    if (local.event.masterId != null && id == null) { hold(calendar, local, ""); conflicts++ }
                    else if (!store.acknowledge(calendar, local, id?.let { cancellation(local.event, it, latest) })) conflicts++
                } else { hold(calendar, local, remoteId ?: ""); conflicts++ }
                continue
            }
            val base = local.base
            if (latest != null && (base == null || latest.etag.isBlank() || latest.sequence < base.sequence)) { hold(calendar, local, latest.id); conflicts++; continue }
            if (deleting && latest != null && ((remoteId != null && base!!.etag != latest.etag || remoteId == null && fields(base!!) != fields(latest)) || base!!.sequence != latest.sequence)) {
                hold(calendar, local, latest.id); conflicts++; continue
            }
            val fields = fields(local.event)
            val changed = if (latest == null) fields.keys else fields.keys.filter { fields[it] != fields(base!!)[it] }.toSet()
            if (latest != null && changed.any { fields(latest)[it] != fields(base!!)[it] && fields(latest)[it] != fields[it] }) {
                hold(calendar, local, latest.id); conflicts++; continue
            }
            if (latest != null && changed.isEmpty() && !deleting) {
                if (!store.acknowledge(calendar, local, latest)) conflicts++
                continue
            }
            val kind = if (deleting) "delete" else if (latest == null) "create" else "update"
            val creationId = UUID.randomUUID().toString().replace("-", "")
            val id = latest?.id ?: creationId
            val payload = try { if (deleting) JSONObject() else payload(calendar, local.event, latest, changed) }
                catch (_: UnsupportedCalendarReminder) { hold(calendar, local, id); conflicts++; continue }
            if (kind == "create") payload.put("extendedProperties", JSONObject().put("private", JSONObject().put("cyclonSyncCreate", creationId)))
            if (kind == "update") payload.put("sequence", maxOf(latest!!.sequence, local.event.sequence).also { require(it < Int.MAX_VALUE) } + 1)
            val pending = JSONObject().put("kind", kind).put("id", id).put("etag", latest?.etag ?: "")
                .put("sequence", latest?.sequence ?: 0).put("fingerprint", local.fingerprint)
                .put("creationId", creationId).put("payload", payload)
            val journaled = store.begin(calendar, local, pending)
            if (!allowed(calendar)) {
                store.rejected(calendar, journaled); conflicts++; continue
            }
            try {
                val response = when (kind) {
                    "create" -> api.create(calendar, id, payload).also { check(it.id == id && it.creationId == creationId) }
                    "update" -> api.update(calendar, id, latest!!.etag, payload).also { check(it.id == id) }
                    else -> {
                        api.delete(calendar, id, latest!!.etag)
                        cancellation(latest, id, if (latest.masterId != null) api.get(calendar, id) else null)
                    }
                }
                if (store.acknowledge(calendar, journaled, response)) uploaded++ else conflicts++
            } catch (e: CalendarRequestException) {
                if (e.status in setOf(409, 412)) {
                    // A known precondition rejection has no uncertain server effect, but must not be retried blindly.
                    if (kind == "create" && e.status == 409) uncertain++
                    else {
                        store.begin(calendar, journaled, JSONObject(pending.toString()).put("kind", "conflict").put("operation", kind))
                        conflicts++
                    }
                    continue
                }
                if (definitelyRejected(e)) store.rejected(calendar, journaled)
                throw e
            }
        }
        return Report(uploaded, conflicts, uncertain)
    }
    /** A cancelled occurrence suppresses its master; removing the local exception would resurrect that occurrence. */
    private fun cancellation(sent: RemoteEvent, id: String, remote: RemoteEvent?): RemoteEvent? =
        if (sent.masterId == null) null else remote?.takeIf { it.cancelled && it.masterId == sent.masterId && it.original != null }
            ?: sent.copy(id = id, cancelled = true)
    private fun mergeLocal(local: RemoteEvent, base: RemoteEvent, latest: RemoteEvent): RemoteEvent = latest.copy(
        title = if (local.title != base.title) local.title else latest.title,
        description = if (local.description != base.description) local.description else latest.description,
        location = if (local.location != base.location) local.location else latest.location,
        visibility = if (local.visibility != base.visibility) local.visibility else latest.visibility,
        transparency = if (local.transparency != base.transparency) local.transparency else latest.transparency,
        start = if (local.start != base.start) local.start else latest.start,
        end = if (local.end != base.end) local.end else latest.end,
        recurrence = if (local.recurrence != base.recurrence) local.recurrence else latest.recurrence,
        reminders = if (local.reminders != base.reminders) local.reminders else latest.reminders,
        useDefaultReminders = if (local.useDefaultReminders != base.useDefaultReminders || local.reminders != base.reminders)
            local.useDefaultReminders else latest.useDefaultReminders)
    private fun definitelyRejected(e: CalendarRequestException) = e.status in setOf(400, 401, 403, 404, 410, 422, 429)
    private fun conflictRemote(calendar: RemoteCalendar, local: LocalEvent, pending: JSONObject): RemoteEvent? =
        if (pending.optBoolean("missingInstance")) api.instance(calendar, requireNotNull(local.event.masterId), requireNotNull(local.event.original))
        else pending.optString("id").takeIf(String::isNotBlank)?.let { api.get(calendar, it) }
    private fun hold(calendar: RemoteCalendar, local: LocalEvent, id: String) {
        val pending = JSONObject().put("kind", "conflict").put("id", id).put("fingerprint", local.fingerprint)
        if (id.isBlank() && local.event.masterId != null) pending.put("missingInstance", true)
        store.begin(calendar, local, pending)
    }
    private fun fields(event: RemoteEvent): Map<String, String> = linkedMapOf(
        "summary" to event.title, "description" to event.description, "location" to event.location,
        "visibility" to event.visibility, "transparency" to event.transparency,
        "start" to (event.start?.let { GoogleCalendarApi.timeJson(it).toString() } ?: "null"),
        "end" to (event.end?.let { GoogleCalendarApi.timeJson(it).toString() } ?: "null"),
        "recurrence" to JSONArray(event.recurrence).toString(),
        "reminders" to "${event.useDefaultReminders}:${event.reminders.sorted().joinToString(",")}")
    private class UnsupportedCalendarReminder : IllegalArgumentException("Unrepresented calendar reminder")
    private fun payload(calendar: RemoteCalendar, event: RemoteEvent, latest: RemoteEvent?, changed: Set<String>): JSONObject = JSONObject().apply {
        require(event.start != null && event.end != null && event.end.millis > event.start.millis)
        require(event.start.allDay == event.end.allDay && event.reminders.size <= 5 && event.reminders.all { it in 0..40320 })
        require(event.visibility in setOf("default", "public", "private", "confidential"))
        require(event.transparency in setOf("opaque", "transparent"))
        for (key in changed) when (key) {
            "summary" -> put(key, event.title)
            "description" -> put(key, event.description)
            "location" -> put(key, event.location)
            "visibility" -> put(key, event.visibility)
            "transparency" -> put(key, event.transparency)
            "start" -> put(key, GoogleCalendarApi.timeJson(event.start))
            "end" -> put(key, GoogleCalendarApi.timeJson(event.end))
            "recurrence" -> put(key, JSONArray(event.recurrence.map(GoogleCalendarApi::exportRecurrence)))
            "reminders" -> {
                val reminder = JSONObject().put("useDefault", event.useDefaultReminders)
                if (!event.useDefaultReminders) {
                    // Android only represents popup reminders. Retain remote email/unknown reminder methods when
                    // changing popup reminders, rather than replacing the API's complete overrides array.
                    val inherited = latest?.useDefaultReminders == true
                    val old = latest?.raw?.let { JSONObject(it).optJSONObject("reminders")?.optJSONArray("overrides") }
                    val previous = if (inherited) calendar.defaultReminders else (0 until (old?.length() ?: 0)).map {
                        old!!.getJSONObject(it).let { value -> CalendarReminder(value.optString("method"), value.optInt("minutes", -1)) }
                    }
                    if (previous.any { it.method !in setOf("popup", "email") || it.minutes !in 0..40320 }) throw UnsupportedCalendarReminder()
                    val overrides = JSONArray()
                    for (reminder in previous.filter { it.method != "popup" })
                        overrides.put(JSONObject().put("method", reminder.method).put("minutes", reminder.minutes))
                    for (minutes in event.reminders.distinct()) overrides.put(JSONObject().put("method", "popup").put("minutes", minutes))
                    if (overrides.length() > 5) throw UnsupportedCalendarReminder()
                    reminder.put("overrides", overrides)
                }
                put(key, reminder)
            }
        }
    }
    private fun matches(remote: RemoteEvent, payload: JSONObject): Boolean {
        val values = fields(remote)
        return payload.keys().asSequence().filter { it != "sequence" }.all { key -> when (key) {
            "summary", "description", "location", "visibility", "transparency" -> values[key] == payload.getString(key)
            "start", "end" -> {
                val expected = GoogleCalendarApi.parseTime(payload.getJSONObject(key), "UTC")
                val actual = if (key == "start") remote.start else remote.end
                actual == expected
            }
            "recurrence" -> {
                val expected = payload.getJSONArray(key)
                remote.recurrence == (0 until expected.length()).map { GoogleCalendarApi.normalizeRecurrence(expected.getString(it)) }
            }
            "reminders" -> {
                val expected = payload.getJSONObject(key)
                remote.useDefaultReminders == expected.getBoolean("useDefault") &&
                    (remote.useDefaultReminders || remote.reminders.sorted() == (expected.optJSONArray("overrides")?.let { a ->
                        (0 until a.length()).mapNotNull { a.getJSONObject(it).takeIf { it.getString("method") == "popup" }?.getInt("minutes") }
                    } ?: emptyList()).sorted())
            }
            else -> false
        } }
    }
}
