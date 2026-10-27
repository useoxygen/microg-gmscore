/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract.Attendees
import android.database.DatabaseUtils
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import org.microg.gms.calendar.sync.*

/** Only rows carrying our marker AND the intended account/calendar are eligible for updates/deletion. */
class AndroidCalendarStore(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: CalendarSyncPreferences) : CalendarStore {
    private fun sync(uri: Uri) = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
    private val calendars = mutableMapOf<String, Long>()
    private fun calendarSelection() = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=? AND ${Calendars.CAL_SYNC1}=?"
    private fun calendarArgs(calendar: String) = arrayOf(account.name, account.type, calendar, MARKER)
    override fun shouldSync(calendar: RemoteCalendar): Boolean = requireNotNull(provider.query(sync(Calendars.CONTENT_URI),
        arrayOf(Calendars.SYNC_EVENTS, Calendars.CAL_SYNC2, Calendars.CAL_SYNC6), calendarSelection(), calendarArgs(calendar.id), null)).use {
        if (!it.moveToFirst()) true
        else if (it.getString(1) == "removed") it.isNull(2) || it.getInt(2) != 0
        else it.getInt(0) != 0
    }
    // A checkpoint describes these provider rows. Losing the calendar row must also lose
    // its token, so cleared storage or a re-added account receives a complete snapshot.
    override fun checkpoint(calendar: String): String? = requireNotNull(provider.query(sync(Calendars.CONTENT_URI),
        arrayOf(Calendars.CAL_SYNC4, Calendars.CAL_SYNC5), calendarSelection(), calendarArgs(calendar), null)).use {
        if (!it.moveToFirst() || prefs.twoWay && it.getString(1) != BASELINE_VERSION) null
        else it.getString(0)?.takeIf(String::isNotBlank)
    }
    override fun checkpoint(calendar: String, value: String) {
        check(prefs.enabled)
        check(provider.update(sync(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.CAL_SYNC4, value)
            if (prefs.twoWay) put(Calendars.CAL_SYNC5, BASELINE_VERSION)
        },
            calendarSelection(), calendarArgs(calendar)) == 1)
    }
    private data class RemovedCalendar(val id: Long, val remote: String, val visible: Int, val syncing: Int)
    override fun reconcileCalendars(present: Set<String>) {
        check(prefs.enabled)
        val rows = requireNotNull(provider.query(sync(Calendars.CONTENT_URI), arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.VISIBLE, Calendars.SYNC_EVENTS),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=? AND (${Calendars.CAL_SYNC2} IS NULL OR ${Calendars.CAL_SYNC2} != ?)",
            arrayOf(account.name, account.type, MARKER, "removed"), null)).use { c ->
                buildList<RemovedCalendar> { while(c.moveToNext()) add(RemovedCalendar(c.getLong(0),c.getString(1),c.getInt(2),c.getInt(3))) }
            }
        for ((id,remote,visible,syncing) in rows.filter { it.remote !in present }) {
            check(prefs.enabled)
            // Retain all event rows, including local/unowned ones. Hide only our removed remote calendar.
            provider.update(sync(Calendars.CONTENT_URI),ContentValues().apply {
                put(Calendars.VISIBLE,0); put(Calendars.SYNC_EVENTS,0)
                put(Calendars.CAL_SYNC2,"removed"); put(Calendars.CAL_SYNC3,visible); put(Calendars.CAL_SYNC6,syncing)
            },"${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=? AND ${Calendars._ID}=?",
                arrayOf(account.name,account.type,MARKER,id.toString()))
        }
    }
    override fun calendar(calendar: RemoteCalendar) {
        check(prefs.enabled)
        val selection = calendarSelection()
        val args = calendarArgs(calendar.id)
        var restoreVisibility: Int? = null
        var restoreSyncing: Int? = null
        val id = requireNotNull(provider.query(sync(Calendars.CONTENT_URI), arrayOf(Calendars._ID,Calendars.CAL_SYNC2,Calendars.CAL_SYNC3,Calendars.CAL_SYNC6), selection, args, null)).use {
            if (it.moveToFirst()) {
                if (it.getString(1) == "removed") {
                    restoreVisibility = it.getInt(2)
                    restoreSyncing = if (it.isNull(3)) 1 else it.getInt(3)
                }
                it.getLong(0)
            } else null
        }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name); put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars._SYNC_ID, calendar.id); put(Calendars.CAL_SYNC1, MARKER)
            put(Calendars.NAME, calendar.id); put(Calendars.CALENDAR_DISPLAY_NAME, calendar.title)
            put(Calendars.CALENDAR_TIME_ZONE, calendar.zone); put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.CALENDAR_COLOR, runCatching { android.graphics.Color.parseColor(calendar.color) }.getOrDefault(0xff64748b.toInt()))
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (!prefs.twoWay) Calendars.CAL_ACCESS_READ else when (calendar.accessRole) {
                "owner" -> Calendars.CAL_ACCESS_OWNER
                "writer" -> Calendars.CAL_ACCESS_EDITOR
                else -> Calendars.CAL_ACCESS_READ
            })
            put(Calendars.MAX_REMINDERS, 5)
            put(Calendars.ALLOWED_REMINDERS, "0,1")
            put(Calendars.DIRTY, 0)
            if (id == null) { put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1) }
            restoreVisibility?.let { put(Calendars.VISIBLE,it); putNull(Calendars.CAL_SYNC2); putNull(Calendars.CAL_SYNC3) }
            restoreSyncing?.let { put(Calendars.SYNC_EVENTS,it); putNull(Calendars.CAL_SYNC6) }
        }
        calendars[calendar.id] = if (id == null) ContentUris.parseId(requireNotNull(provider.insert(sync(Calendars.CONTENT_URI), values)))
            else { check(provider.update(sync(Calendars.CONTENT_URI), values, selection, args) == 1); id }
    }
    private fun owned(calendar: Long, remote: String): Pair<Long, Boolean>? = requireNotNull(provider.query(sync(Events.CONTENT_URI),
        arrayOf(Events._ID, Events.DIRTY), "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=? AND ${Events.SYNC_DATA1}=?",
        arrayOf(calendar.toString(), remote, MARKER), null)).use { if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) != 0) else null }
    override fun event(calendar: RemoteCalendar, event: RemoteEvent): Boolean {
        check(prefs.enabled)
        val calendarId = requireNotNull(calendars[calendar.id])
        val local = owned(calendarId, event.id)
        if (local == null && event.masterId != null && event.original != null && pendingOccurrence(calendarId, event)) return false
        if (local?.second == true || local?.first?.let { pending(it) != null } == true) return false
        val current = local?.first?.let { snapshot(calendarId, it) }
        if (current != null && ((current.row.getAsInteger(Events.DIRTY) ?: 0) != 0 || current.row.getAsString(Events.SYNC_DATA4) != null)) return false
        return write(calendar, event, local?.first, current)
    }
    private data class Acknowledgement(val remote: RemoteEvent, val current: Snapshot,
        val sentRow: JSONObject?, val keepReminders: Boolean)
    private fun write(calendar: RemoteCalendar, event: RemoteEvent, localId: Long?, assertion: Snapshot? = null,
        acknowledgement: Acknowledgement? = null): Boolean {
        check(prefs.enabled)
        val calendarId = requireNotNull(calendars[calendar.id])
        // A source-less exception inherits its conflict baseline from this master. Keep that revision until the
        // exception acquires its own server identity; the same absence check below closes the insertion race.
        // A newly created master has no baseline yet and must settle first so its children can acquire an identity.
        val guardMaster = event.masterId == null && localId != null && assertion?.row?.getAsString(Events.SYNC_DATA3) != null
        if (guardMaster && hasDependentOccurrences(calendarId, event.id, localId)) return false
        if (event.cancelled && event.masterId == null && acknowledgement == null) {
            if (hasDirtyChildren(calendarId, event.id, localId)) return false
            return localId?.let { deleteDownloaded(calendarId, event.id, it) } ?: true
        }
        val original = event.original
        val parent = event.masterId?.let { owned(calendarId, it)?.first }
        // A cancelled exception can contain only id, recurringEventId and originalStartTime.
        val start = event.start ?: if (event.cancelled) original else null
        requireNotNull(start)
        val end = event.end ?: if (event.cancelled) start.copy(millis = start.millis + if (start.allDay) 86_400_000 else 1_000) else null
        requireNotNull(end)
        require(end.millis >= start.millis && start.allDay == end.allDay)
        val baseline = acknowledgement?.remote ?: event
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId); put(Events._SYNC_ID, event.id); put(Events.SYNC_DATA1, MARKER)
            put(Events.SYNC_DATA2, baseline.etag); put(Events.SYNC_DATA3, CalendarEventCodec.encode(baseline).toString())
            putNull(Events.SYNC_DATA4); put(Events.SYNC_DATA5, event.creationId)
            put(Events.TITLE, event.title); put(Events.DESCRIPTION, event.description)
            put(Events.EVENT_LOCATION, event.location); put(Events.DTSTART, start.millis)
            put(Events.EVENT_TIMEZONE, start.zone); put(Events.EVENT_END_TIMEZONE, end.zone)
            put(Events.ALL_DAY, if (start.allDay) 1 else 0)
            put(Events.STATUS, if (event.cancelled) Events.STATUS_CANCELED else Events.STATUS_CONFIRMED)
            put(Events.DIRTY, if (acknowledgement == null) 0 else 1)
            put(Events.DELETED, acknowledgement?.current?.row?.getAsInteger(Events.DELETED) ?: 0)
            put(Events.HAS_ALARM, if (event.cancelled || event.reminders.isEmpty()) 0 else 1)
            for ((prefix, column) in listOf("RRULE:" to Events.RRULE, "RDATE:" to Events.RDATE,
                "EXRULE:" to Events.EXRULE, "EXDATE:" to Events.EXDATE)) {
                val rule = event.recurrence.filter { it.startsWith(prefix) }.joinToString("\n") { it.removePrefix(prefix) }
                if (rule.isEmpty()) putNull(column) else put(column, rule)
            }
            if (event.recurrence.isEmpty()) { put(Events.DTEND, end.millis); putNull(Events.DURATION) }
            else {
                putNull(Events.DTEND)
                put(Events.DURATION, if (start.allDay) "P${(end.millis-start.millis)/86_400_000}D" else "P${(end.millis-start.millis)/1_000}S")
            }
            if (event.masterId != null && original != null) {
                put(Events.ORIGINAL_SYNC_ID, event.masterId); put(Events.ORIGINAL_INSTANCE_TIME, original.millis)
                put(Events.ORIGINAL_ALL_DAY, if (original.allDay) 1 else 0)
                if (parent != null) put(Events.ORIGINAL_ID, parent) else putNull(Events.ORIGINAL_ID)
            } else {
                putNull(Events.ORIGINAL_SYNC_ID); putNull(Events.ORIGINAL_ID)
                putNull(Events.ORIGINAL_INSTANCE_TIME); putNull(Events.ORIGINAL_ALL_DAY)
            }
        }
        val metadata = extraValues(event)
        values.putAll(metadata)
        values.put(Events.SYNC_DATA6, json(extraValues(baseline)).toString())
        if (acknowledgement != null) {
            // Unknown local edits remain visible and dirty. Only metadata unchanged since dispatch may take the
            // server's value; never bless a later organizer/guest-policy edit by moving the baseline under it.
            for (column in extraColumns - supportedExtraColumns) {
                val current = acknowledgement.current.row.getAsString(column)
                val sent = acknowledgement.sentRow
                if (sent == null || current != if (sent.isNull(column)) null else sent.get(column).toString()) {
                    values.put(column, current)
                }
            }
            if (acknowledgement.keepReminders) values.put(Events.HAS_ALARM, acknowledgement.current.row.getAsInteger(Events.HAS_ALARM) ?: 0)
        }
        val operations = arrayListOf<ContentProviderOperation>()
        assertion?.let { operations += assertions(it) }
        if (guardMaster) operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI))
            .withSelection(dependentOccurrenceSelection(), childArgs(calendarId, event.id, localId)).withExpectedCount(0).build()
        if (localId != null && !removeDuplicates(calendarId, event.id, localId, operations)) return false
        if (localId == null && event.masterId != null && event.original != null) {
            operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection(occurrenceSelection(),
                occurrenceArgs(calendarId, event)).withExpectedCount(0).build()
        }
        val eventIndex = operations.size
        if (localId == null) operations += ContentProviderOperation.newInsert(sync(Events.CONTENT_URI)).withValues(values).build()
        else operations += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI))
            .withValues(values).withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=?" +
                if (assertion == null) " AND ${Events.SYNC_DATA1}=? AND ${Events.DIRTY}=0 AND ${Events.SYNC_DATA4} IS NULL" else "",
                if (assertion == null) arrayOf(localId.toString(), calendarId.toString(), MARKER) else arrayOf(localId.toString(), calendarId.toString()))
            .withExpectedCount(1).build()
        if (localId != null && acknowledgement?.keepReminders != true) operations += ContentProviderOperation.newDelete(sync(Reminders.CONTENT_URI))
            .withSelection("${Reminders.EVENT_ID}=?", arrayOf(localId.toString())).build()
        for (minutes in if (event.cancelled || acknowledgement?.keepReminders == true) emptyList() else event.reminders) {
            val builder = ContentProviderOperation.newInsert(sync(Reminders.CONTENT_URI)).withValue(Reminders.MINUTES, minutes)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
            if (localId == null) builder.withValueBackReference(Reminders.EVENT_ID, eventIndex) else builder.withValue(Reminders.EVENT_ID, localId)
            operations += builder.build()
        }
        provider.applyBatch(operations)
        return true
    }
    override fun reconcile(calendar: RemoteCalendar, present: Set<String>): Int {
        check(prefs.enabled)
        val id = requireNotNull(calendars[calendar.id])
        val rows = provider.query(sync(Events.CONTENT_URI), arrayOf(Events._ID, Events._SYNC_ID, Events.DIRTY),
            "${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=?", arrayOf(id.toString(), MARKER), null)?.use { cursor ->
                buildList<Triple<Long, String, Boolean>> { while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) != 0)) }
            } ?: error("Calendar provider unreadable")
        var conflicts = 0
        for ((row, remote, dirty) in rows.filter { it.second !in present }) {
            check(prefs.enabled)
            if (dirty || pending(row) != null || !deleteDownloaded(id, remote, row)) conflicts++
        }
        return conflicts
    }
    private fun removeDuplicates(calendarId: Long, remoteId: String, localId: Long, operations: MutableList<ContentProviderOperation>): Boolean {
        // A newly edited occurrence may have been inserted concurrently with a clean cloud download.
        // Join its identity only after proving the other owned copies stayed clean in this transaction.
        val selection = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events._ID}!=?"
        val args = arrayOf(calendarId.toString(), remoteId, MARKER, localId.toString())
        val duplicates = requireNotNull(provider.query(sync(Events.CONTENT_URI), arrayOf(Events._ID), selection, args, null)).use { cursor ->
            buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.mapNotNull { snapshot(calendarId, it) }
        operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection(selection, args)
            .withExpectedCount(duplicates.size).build()
        for (duplicate in duplicates) {
            if ((duplicate.row.getAsInteger(Events.DIRTY) ?: 0) != 0 || duplicate.row.getAsString(Events.SYNC_DATA4) != null) return false
            operations += assertions(duplicate)
            operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection(childSelection(),
                childArgs(calendarId, remoteId, duplicate.id)).withExpectedCount(0).build()
            operations += ContentProviderOperation.newDelete(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
                arrayOf(duplicate.id.toString(), calendarId.toString())).withExpectedCount(1).build()
        }
        return true
    }
    private fun deleteDownloaded(calendar: Long, remote: String, id: Long): Boolean {
        check(prefs.enabled)
        val current = snapshot(calendar, id) ?: return true
        if ((current.row.getAsInteger(Events.DIRTY) ?: 0) != 0 || current.row.getAsString(Events.SYNC_DATA4) != null) return false
        val operations = ArrayList(assertions(current))
        // The child assertion and parent deletion share a provider transaction: a new local exception cannot be cascaded away.
        operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection(childSelection(),
            childArgs(calendar, remote, id)).withExpectedCount(0).build()
        operations += ContentProviderOperation.newDelete(sync(Events.CONTENT_URI)).withSelection(
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.DIRTY}=0 AND ${Events.SYNC_DATA4} IS NULL",
            arrayOf(id.toString(), calendar.toString(), MARKER)).withExpectedCount(1).build()
        return try { provider.applyBatch(operations); true } catch (_: android.content.OperationApplicationException) { false }
    }
    override fun uploadAllowed(calendar: RemoteCalendar) = prefs.enabled && prefs.twoWay && !prefs.uploadsHeld &&
        calendar.writable && shouldSync(calendar)

    private fun calendarId(calendar: RemoteCalendar): Long? = calendars[calendar.id] ?: requireNotNull(provider.query(sync(Calendars.CONTENT_URI),
        arrayOf(Calendars._ID), calendarSelection(), calendarArgs(calendar.id), null)).use {
        if (it.moveToFirst()) it.getLong(0).also { id -> calendars[calendar.id] = id } else null
    }
    private fun pending(id: Long): JSONObject? = requireNotNull(provider.query(sync(Events.CONTENT_URI), arrayOf(Events.SYNC_DATA4),
        "${Events._ID}=?", arrayOf(id.toString()), null)).use {
        if (it.moveToFirst()) it.getString(0)?.let(::JSONObject) else null
    }
    private fun occurrenceSelection() = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NULL AND ${Events.ORIGINAL_INSTANCE_TIME}=? AND " +
        "COALESCE(${Events.ORIGINAL_ALL_DAY},0)=? AND (${Events.ORIGINAL_SYNC_ID}=? OR ${Events.ORIGINAL_ID}=?) AND " +
        "(${Events.DIRTY}!=0 OR ${Events.SYNC_DATA4} IS NOT NULL)"
    private fun occurrenceArgs(calendar: Long, event: RemoteEvent): Array<String> {
        val master = requireNotNull(event.masterId)
        val original = requireNotNull(event.original)
        val parent = owned(calendar, master)?.first ?: -1
        return arrayOf(calendar.toString(), original.millis.toString(), (if (original.allDay) 1 else 0).toString(), master, parent.toString())
    }
    private fun pendingOccurrence(calendar: Long, event: RemoteEvent): Boolean = requireNotNull(provider.query(sync(Events.CONTENT_URI),
        arrayOf(Events._ID), occurrenceSelection(), occurrenceArgs(calendar, event), null)).use { it.moveToFirst() }
    private fun childSelection() = "${Events.CALENDAR_ID}=? AND (${Events.ORIGINAL_SYNC_ID}=? OR ${Events.ORIGINAL_ID}=?) AND " +
        "(${Events.DIRTY}!=0 OR ${Events.SYNC_DATA4} IS NOT NULL OR ${Events.SYNC_DATA1} IS NULL)"
    private fun dependentOccurrenceSelection() = "${Events.CALENDAR_ID}=? AND (${Events.ORIGINAL_SYNC_ID}=? OR ${Events.ORIGINAL_ID}=?) AND " +
        "${Events._SYNC_ID} IS NULL AND (${Events.DIRTY}!=0 OR ${Events.SYNC_DATA4} IS NOT NULL)"
    private fun hasDependentOccurrences(calendar: Long, remote: String, local: Long?) = requireNotNull(provider.query(sync(Events.CONTENT_URI),
        arrayOf(Events._ID), dependentOccurrenceSelection(), childArgs(calendar, remote, local), null)).use { it.moveToFirst() }
    private fun childArgs(calendar: Long, remote: String, local: Long?) = arrayOf(calendar.toString(), remote, (local ?: -1).toString())
    private fun hasDirtyChildren(calendar: Long, remote: String, local: Long?) = requireNotNull(provider.query(sync(Events.CONTENT_URI),
        arrayOf(Events._ID), childSelection(), childArgs(calendar, remote, local), null)).use { it.moveToFirst() }

    /** A missing row is never a tombstone. Only explicit dirty rows in a selected, owned calendar can reach the uploader. */
    override fun localEvents(calendar: RemoteCalendar): List<LocalEvent> {
        if (!prefs.enabled || !shouldSync(calendar)) return emptyList()
        val calendarId = calendarId(calendar) ?: return emptyList()
        val ids = requireNotNull(provider.query(sync(Events.CONTENT_URI), arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=? AND (${Events.DIRTY}!=0 OR ${Events.SYNC_DATA4} IS NOT NULL) AND " +
                "(${Events.SYNC_DATA1}=? OR (${Events.SYNC_DATA1} IS NULL AND ${Events._SYNC_ID} IS NULL))",
            arrayOf(calendarId.toString(), MARKER), "${Events._ID} ASC")).use { cursor ->
            buildList<Long> { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        return ids.mapNotNull { id -> snapshot(calendarId, id)?.let { local(calendar, it) } }
            .sortedBy { it.event.masterId != null }
    }
    private data class Snapshot(val row: ContentValues, val reminders: List<ContentValues>, val attendees: List<ContentValues>) {
        val id get() = row.getAsLong(Events._ID)
        val fingerprint: String get() {
            val fields = ContentValues(row).apply { syncColumns.forEach(::remove) }
            val json = JSONObject().put("event", json(fields)).put("reminders", JSONArray(reminders.map { json(it) }))
                .put("attendees", JSONArray(attendees.map { json(it) })).toString()
            return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
    private fun snapshot(calendar: Long, id: Long): Snapshot? {
        val row = requireNotNull(provider.query(sync(Events.CONTENT_URI), eventColumns,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(id.toString(), calendar.toString()), null)).use {
            if (!it.moveToFirst()) null else ContentValues().also { row -> DatabaseUtils.cursorRowToContentValues(it, row) }
        } ?: return null
        fun children(uri: Uri, owner: String, columns: Array<String>): List<ContentValues> = requireNotNull(provider.query(sync(uri), columns,
            "$owner=?", arrayOf(id.toString()), "_id ASC")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentValues().also { DatabaseUtils.cursorRowToContentValues(cursor, it) }) }
        }
        return Snapshot(row, children(Reminders.CONTENT_URI, Reminders.EVENT_ID, reminderColumns),
            children(Attendees.CONTENT_URI, Attendees.EVENT_ID, attendeeColumns))
    }
    private fun local(calendar: RemoteCalendar, snapshot: Snapshot): LocalEvent {
        val row = snapshot.row
        fun text(column: String) = row.getAsString(column)?.takeIf(String::isNotBlank)
        fun number(column: String) = row.getAsLong(column) ?: 0L
        val remote = text(Events._SYNC_ID)
        val pending = text(Events.SYNC_DATA4)?.let(::JSONObject)
        var unsupported = false
        var base = text(Events.SYNC_DATA3)?.let { CalendarEventCodec.decode(JSONObject(it)) }
        val allDay = number(Events.ALL_DAY) != 0L
        val start = EventTime(number(Events.DTSTART), allDay,
            if (allDay) "UTC" else GoogleCalendarApi.zoneOf(text(Events.EVENT_TIMEZONE) ?: calendar.zone, calendar.zone))
        val recurrence = buildList {
            for ((prefix, column) in recurrenceColumns) text(column)?.split('\n')?.filter(String::isNotBlank)?.forEach { add(prefix + it) }
        }
        val endMillis = row.getAsLong(Events.DTEND) ?: text(Events.DURATION)?.let {
            try { start.millis + durationMillis(it) } catch (_: IllegalArgumentException) { unsupported = true; null }
        }
        if (endMillis == null || endMillis <= start.millis || allDay &&
            (start.millis % 86_400_000L != 0L || endMillis % 86_400_000L != 0L)) unsupported = true
        val end = endMillis?.let { EventTime(it, allDay,
            if (allDay) "UTC" else GoogleCalendarApi.zoneOf(text(Events.EVENT_END_TIMEZONE) ?: start.zone, start.zone)) }
        var master = text(Events.ORIGINAL_SYNC_ID)
        val parentId = row.getAsLong(Events.ORIGINAL_ID)
        val parent = parentId?.let { snapshot(number(Events.CALENDAR_ID), it) }
            ?: master?.let { owned(number(Events.CALENDAR_ID), it)?.first }?.let { snapshot(number(Events.CALENDAR_ID), it) }
        if (master == null) master = parent?.row?.getAsString(Events._SYNC_ID)
        val original = row.getAsLong(Events.ORIGINAL_INSTANCE_TIME)?.let {
            val originalAllDay = number(Events.ORIGINAL_ALL_DAY) != 0L
            EventTime(it, originalAllDay, if (originalAllDay) "UTC" else
                parent?.row?.getAsString(Events.EVENT_TIMEZONE)?.let { zone -> GoogleCalendarApi.zoneOf(zone, start.zone) } ?: start.zone)
        }
        if (remote == null && original != null && parent != null) {
            // Android inserts a changed occurrence as a new row, without Google's instance id or etag.
            // The engine resolves the immutable original occurrence with events.instances before patching it.
            val parentBase = parent.row.getAsString(Events.SYNC_DATA3)?.let { CalendarEventCodec.decode(JSONObject(it)) }
            base = parentBase?.takeIf { master != null && it.start != null && it.end != null }?.let {
                it.copy(id = "", masterId = master, original = original, start = original,
                    end = original.copy(millis = original.millis + (it.end!!.millis - it.start!!.millis)),
                    recurrence = emptyList(), cancelled = false)
            }
        }
        val activeReminders = if (number(Events.HAS_ALARM) == 0L) emptyList() else snapshot.reminders
        val defaultReminders = activeReminders.any { (it.getAsInteger(Reminders.MINUTES) ?: Reminders.MINUTES_DEFAULT) == Reminders.MINUTES_DEFAULT }
        if (defaultReminders && activeReminders.any { (it.getAsInteger(Reminders.MINUTES) ?: Reminders.MINUTES_DEFAULT) != Reminders.MINUTES_DEFAULT }) unsupported = true
        val reminderMinutes = activeReminders.flatMap { reminder ->
            val method = reminder.getAsInteger(Reminders.METHOD) ?: Reminders.METHOD_DEFAULT
            val minutes = reminder.getAsInteger(Reminders.MINUTES) ?: Reminders.MINUTES_DEFAULT
            if (method !in setOf(Reminders.METHOD_DEFAULT, Reminders.METHOD_ALERT)) unsupported = true
            when {
                minutes == Reminders.MINUTES_DEFAULT -> calendar.reminders
                minutes in 0..40320 -> listOf(minutes)
                else -> { unsupported = true; emptyList() }
            }
        }.distinct().sorted()
        if (reminderMinutes.size > 5 || snapshot.attendees.isNotEmpty()) unsupported = true
        val extras = text(Events.SYNC_DATA6)?.let(::JSONObject)
        if (extras != null) for (column in extraColumns - supportedExtraColumns) {
            val expected = if (extras.isNull(column)) null else extras.get(column).toString()
            if (expected != row.getAsString(column)) unsupported = true
        }
        if (number(Events.AVAILABILITY) == Events.AVAILABILITY_TENTATIVE.toLong()) unsupported = true
        // Existing remote rows without a baseline must download first; an opaque dirty row is never guessed safe to replace.
        if (parentId != null && master == null || original != null && base == null) unsupported = true
        if (extras == null) {
            val defaults = extraValues(if (remote == null && base != null) base else
                RemoteEvent("", "", "", "", "", null, null, emptyList(), null, null, false, emptyList()))
            // Unrepresented organizer/guest changes must never be cleared as if uploaded.
            for (column in extraColumns - supportedExtraColumns - Events.ORGANIZER) if (row.getAsString(column) != defaults.getAsString(column)) unsupported = true
            val expectedOrganizer = defaults.getAsString(Events.ORGANIZER)
            if (text(Events.ORGANIZER)?.let { it != expectedOrganizer && (remote != null || it != account.name) } == true) unsupported = true
        }
        val deleting = number(Events.DELETED) != 0L || number(Events.STATUS) == Events.STATUS_CANCELED.toLong()
        if (deleting && master == null && hasDirtyChildren(number(Events.CALENDAR_ID), remote.orEmpty(), snapshot.id)) unsupported = true
        val event = RemoteEvent(id = remote.orEmpty(), etag = base?.etag ?: text(Events.SYNC_DATA2).orEmpty(),
            title = text(Events.TITLE).orEmpty(), description = text(Events.DESCRIPTION).orEmpty(), location = text(Events.EVENT_LOCATION).orEmpty(),
            start = start, end = end, recurrence = recurrence, masterId = master, original = original,
            cancelled = deleting,
            reminders = reminderMinutes, sequence = base?.sequence ?: 0,
            useDefaultReminders = defaultReminders || base?.useDefaultReminders == true && reminderMinutes == base.reminders.sorted(),
            creationId = text(Events.SYNC_DATA5), raw = base?.raw,
            visibility = when (number(Events.ACCESS_LEVEL).toInt()) {
                Events.ACCESS_CONFIDENTIAL -> "confidential"; Events.ACCESS_PRIVATE -> "private"; Events.ACCESS_PUBLIC -> "public"; else -> "default"
            }, transparency = if (number(Events.AVAILABILITY).toInt() == Events.AVAILABILITY_FREE) "transparent" else "opaque")
        return LocalEvent(id = snapshot.id, event = event, deleted = event.cancelled, dirty = number(Events.DIRTY) != 0L,
            base = base, pending = pending, fingerprint = snapshot.fingerprint, unsupported = unsupported, observed = event)
    }
    private fun assertions(snapshot: Snapshot): List<ContentProviderOperation> = buildList {
        add(ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(snapshot.id.toString(), snapshot.row.getAsString(Events.CALENDAR_ID))).withValues(snapshot.row).withExpectedCount(1).build())
        fun children(uri: Uri, owner: String, values: List<ContentValues>) {
            add(ContentProviderOperation.newAssertQuery(sync(uri)).withSelection("$owner=?", arrayOf(snapshot.id.toString()))
                .withExpectedCount(values.size).build())
            for (row in values) add(ContentProviderOperation.newAssertQuery(sync(uri)).withSelection("_id=? AND $owner=?",
                arrayOf(row.getAsString("_id"), snapshot.id.toString())).withValues(row).withExpectedCount(1).build())
        }
        children(Reminders.CONTENT_URI, Reminders.EVENT_ID, snapshot.reminders)
        children(Attendees.CONTENT_URI, Attendees.EVENT_ID, snapshot.attendees)
    }
    override fun begin(calendar: RemoteCalendar, existing: LocalEvent, pending: JSONObject): LocalEvent {
        check(uploadAllowed(calendar))
        val current = requireNotNull(snapshot(requireNotNull(calendarId(calendar)), existing.id))
        check(current.fingerprint == existing.fingerprint) { "Calendar event changed before upload" }
        val values = ContentValues().apply {
            put(Events.SYNC_DATA1, MARKER)
            put(Events.SYNC_DATA4, JSONObject(pending.toString()).apply {
                if (optString("kind") == "conflict") put("holdGeneration", prefs.conflictResolutionGeneration)
                else if (!has("providerSnapshot")) {
                    // Persist the actual provider revision, not a rebased upload intent or a copy read after a lost
                    // reply. Exclude sync columns so retries never recursively embed another mutation journal.
                    put("providerSnapshot", JSONObject().put("event", CalendarEventCodec.encode(local(calendar, current).event))
                        .put("row", json(ContentValues(current.row).apply { syncColumns.forEach(::remove) }))
                        .put("reminders", JSONArray(current.reminders.map { json(it) })))
                }
            }.toString())
            if (pending.optString("kind") == "create") {
                put(Events._SYNC_ID, pending.getString("id"))
                put(Events.SYNC_DATA5, pending.getString("creationId"))
            }
        }
        val operations = ArrayList(assertions(current))
        operations += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=?",
            arrayOf(existing.id.toString())).withValues(values).withExpectedCount(1).build()
        provider.applyBatch(operations)
        return local(calendar, requireNotNull(snapshot(requireNotNull(calendarId(calendar)), existing.id)))
    }
    override fun rejected(calendar: RemoteCalendar, existing: LocalEvent) {
        check(prefs.enabled)
        val current = snapshot(requireNotNull(calendarId(calendar)), existing.id) ?: return
        val operations = ArrayList(assertions(current))
        operations += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=?",
            arrayOf(existing.id.toString())).withValues(ContentValues().apply {
                putNull(Events.SYNC_DATA4)
                if (current.row.getAsString(Events.SYNC_DATA4)?.let(::JSONObject)?.optString("kind") == "create" &&
                    current.row.getAsString(Events.SYNC_DATA3) == null) {
                    // A definitive rejection (or cancellation before send) has no cloud effect. The corrected row is a create again.
                    putNull(Events._SYNC_ID); putNull(Events.SYNC_DATA5)
                }
            }).withExpectedCount(1).build()
        provider.applyBatch(operations)
    }
    override fun conflictChoice(calendar: RemoteCalendar, existing: LocalEvent): CalendarConflictChoice {
        val heldAt = existing.pending?.optLong("holdGeneration", prefs.conflictResolutionGeneration) ?: return CalendarConflictChoice.HOLD
        if (prefs.conflictResolutionGeneration <= heldAt) return CalendarConflictChoice.HOLD
        return if (prefs.conflictKeepLocal) CalendarConflictChoice.LOCAL else CalendarConflictChoice.REMOTE
    }
    override fun hydrate(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent): LocalEvent {
        check(prefs.enabled && existing.event.id == remote.id && existing.event.etag == remote.etag)
        val calendarId = requireNotNull(calendarId(calendar))
        val current = requireNotNull(snapshot(calendarId, existing.id))
        check(current.fingerprint == existing.fingerprint)
        val values = ContentValues().apply {
            put(Events.SYNC_DATA3, CalendarEventCodec.encode(remote).toString())
            // v1 never mapped privacy/availability or the end zone. Its provider defaults cannot mean an owner edit.
            val metadata = extraValues(remote)
            put(Events.ACCESS_LEVEL, metadata.getAsInteger(Events.ACCESS_LEVEL))
            put(Events.AVAILABILITY, metadata.getAsInteger(Events.AVAILABILITY))
            if (current.row.getAsString(Events.EVENT_END_TIMEZONE).isNullOrBlank()) put(Events.EVENT_END_TIMEZONE, remote.end?.zone)
            // v1 downloads left unrepresented fields at provider defaults; don't bless an unsupported local edit.
            put(Events.SYNC_DATA6, json(extraValues(remote.copy(raw = null))).toString())
        }
        if (remote.useDefaultReminders && existing.event.reminders.sorted() != remote.reminders.sorted()) {
            // Calendar defaults may change without changing this event's etag. The old materialized alarms are ambiguous.
            // Preserve them and require an explicit owner choice before replacing Google's inherited reminders.
            val normalized = current.copy(row = ContentValues(current.row).apply { putAll(values) })
            values.put(Events.SYNC_DATA4, JSONObject().put("kind", "conflict").put("id", remote.id)
                .put("fingerprint", normalized.fingerprint).put("holdGeneration", prefs.conflictResolutionGeneration)
                .put("reason", "legacy-reminders").toString())
        }
        val operations = ArrayList(assertions(current))
        operations += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=?",
            arrayOf(existing.id.toString())).withValues(values).withExpectedCount(1).build()
        provider.applyBatch(operations)
        return local(calendar, requireNotNull(snapshot(calendarId, existing.id)))
    }
    override fun acknowledge(calendar: RemoteCalendar, existing: LocalEvent, remote: RemoteEvent?): Boolean {
        check(prefs.enabled)
        val calendarId = requireNotNull(calendarId(calendar))
        val current = snapshot(calendarId, existing.id) ?: return false
        if (remote == null) {
            if (current.fingerprint != existing.fingerprint || !existing.deleted) return false
            removeLocal(calendar, existing)
            return true
        }
        if (current.fingerprint == existing.fingerprint) return write(calendar, remote, existing.id, current)
        val dispatched = existing.pending?.optJSONObject("providerSnapshot")
        // Old journals cannot reconstruct which fields changed after dispatch. Retain them rather than guessing
        // that stale provider values are new owner edits and overwriting disjoint Google changes next time.
        if (existing.pending != null && dispatched == null) return false
        val sent = dispatched?.getJSONObject("event")?.let(CalendarEventCodec::decode) ?: existing.observed ?: existing.event
        val currentEvent = local(calendar, current).event
        val merged = CalendarEventMerge.acknowledge(sent, currentEvent, remote)
        val keepReminders = if (dispatched == null) currentEvent.reminders != sent.reminders ||
            currentEvent.useDefaultReminders != sent.useDefaultReminders
            else JSONArray(current.reminders.map { json(it) }).toString() != dispatched.getJSONArray("reminders").toString() ||
                current.row.getAsInteger(Events.HAS_ALARM) != dispatched.getJSONObject("row").optInt(Events.HAS_ALARM)
        // Merge unchanged fields from Google, retain later local edits, and keep the actual response as the
        // next baseline. Reproject temporal fields together so DTEND/DURATION stay consistent with recurrence.
        write(calendar, merged, existing.id, current, Acknowledgement(remote, current, dispatched?.getJSONObject("row"), keepReminders))
        return false
    }
    override fun removeLocal(calendar: RemoteCalendar, existing: LocalEvent) {
        check(prefs.enabled)
        val calendarId = requireNotNull(calendarId(calendar))
        val current = snapshot(calendarId, existing.id) ?: return
        check(current.fingerprint == existing.fingerprint) { "Calendar event changed during deletion" }
        val operations = ArrayList(assertions(current))
        operations += ContentProviderOperation.newAssertQuery(sync(Events.CONTENT_URI)).withSelection(childSelection(),
            childArgs(calendarId, existing.event.id, existing.id)).withExpectedCount(0).build()
        operations += ContentProviderOperation.newDelete(sync(Events.CONTENT_URI)).withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(existing.id.toString(), calendarId.toString())).withExpectedCount(1).build()
        provider.applyBatch(operations)
    }
    private fun extraValues(event: RemoteEvent) = ContentValues().apply {
        val raw = event.raw?.let(::JSONObject)
        put(Events.ACCESS_LEVEL, when (event.visibility) {
            "confidential" -> Events.ACCESS_CONFIDENTIAL; "private" -> Events.ACCESS_PRIVATE; "public" -> Events.ACCESS_PUBLIC; else -> Events.ACCESS_DEFAULT
        })
        put(Events.AVAILABILITY, if (event.transparency == "transparent") Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        put(Events.ORGANIZER, raw?.optJSONObject("organizer")?.optString("email")?.takeIf(String::isNotBlank))
        put(Events.GUESTS_CAN_INVITE_OTHERS, if (raw?.optBoolean("guestsCanInviteOthers", true) != false) 1 else 0)
        put(Events.GUESTS_CAN_MODIFY, if (raw?.optBoolean("guestsCanModify", false) == true) 1 else 0)
        put(Events.GUESTS_CAN_SEE_GUESTS, if (raw?.optBoolean("guestsCanSeeOtherGuests", true) != false) 1 else 0)
    }
    companion object {
        const val MARKER = "cyclon-calendar-v1"
        private const val BASELINE_VERSION = "two-way-v2"
        private val recurrenceColumns = listOf("RRULE:" to Events.RRULE, "RDATE:" to Events.RDATE, "EXRULE:" to Events.EXRULE, "EXDATE:" to Events.EXDATE)
        private val extraColumns = setOf(Events.ACCESS_LEVEL, Events.AVAILABILITY, Events.ORGANIZER,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_SEE_GUESTS)
        private val supportedExtraColumns = setOf(Events.ACCESS_LEVEL, Events.AVAILABILITY)
        private val syncColumns = setOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
            Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6)
        private val eventColumns = (listOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.ALL_DAY, Events.STATUS, Events.HAS_ALARM, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
            Events.ORIGINAL_ALL_DAY) + recurrenceColumns.map { it.second } + extraColumns + syncColumns).toTypedArray()
        private val reminderColumns = arrayOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD)
        private val attendeeColumns = arrayOf(Attendees._ID, Attendees.EVENT_ID, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE)
        private fun json(values: ContentValues) = JSONObject().apply {
            for (key in values.keySet().sorted()) put(key, values.get(key) ?: JSONObject.NULL)
        }
        /** CalendarProvider uses RFC2445 durations, including the common P3600S shorthand. */
        private fun durationMillis(value: String): Long {
            val match = Regex("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T)?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(value)
                ?: throw IllegalArgumentException("Invalid calendar duration")
            val factors = listOf(604800L, 86400L, 3600L, 60L, 1L)
            val seconds = factors.indices.sumOf { index -> match.groupValues[index + 1].ifEmpty { "0" }.toLong() * factors[index] }
            require(seconds in 0..315_576_000)
            return seconds * 1000
        }
    }
}
