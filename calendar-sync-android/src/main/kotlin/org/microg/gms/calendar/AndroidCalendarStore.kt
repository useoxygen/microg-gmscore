/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
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
        arrayOf(Calendars.CAL_SYNC4), calendarSelection(), calendarArgs(calendar), null)).use {
        if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
    }
    override fun checkpoint(calendar: String, value: String) {
        check(prefs.enabled)
        check(provider.update(sync(Calendars.CONTENT_URI), ContentValues().apply { put(Calendars.CAL_SYNC4, value) },
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
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
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
        if (local?.second == true) return false
        if (event.cancelled && event.masterId == null) {
            if (local != null && provider.delete(sync(Events.CONTENT_URI),
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.DIRTY}=0",
                arrayOf(local.first.toString(), calendarId.toString(), MARKER)) != 1) return false
            return true
        }
        val original = event.original
        val parent = event.masterId?.let { owned(calendarId, it)?.first }
        // A cancelled exception can contain only id, recurringEventId and originalStartTime.
        val start = event.start ?: if (event.cancelled) original else null
        requireNotNull(start)
        val end = event.end ?: if (event.cancelled) start.copy(millis = start.millis + if (start.allDay) 86_400_000 else 1_000) else null
        requireNotNull(end)
        require(end.millis >= start.millis && start.allDay == end.allDay)
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId); put(Events._SYNC_ID, event.id); put(Events.SYNC_DATA1, MARKER)
            put(Events.SYNC_DATA2, event.etag); put(Events.TITLE, event.title); put(Events.DESCRIPTION, event.description)
            put(Events.EVENT_LOCATION, event.location); put(Events.DTSTART, start.millis)
            put(Events.EVENT_TIMEZONE, start.zone); put(Events.ALL_DAY, if (start.allDay) 1 else 0)
            put(Events.STATUS, if (event.cancelled) Events.STATUS_CANCELED else Events.STATUS_CONFIRMED)
            put(Events.DIRTY, 0); put(Events.DELETED, 0)
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
        val operations = arrayListOf<ContentProviderOperation>()
        if (local == null) operations += ContentProviderOperation.newInsert(sync(Events.CONTENT_URI)).withValues(values).build()
        else operations += ContentProviderOperation.newUpdate(sync(Events.CONTENT_URI))
            .withValues(values).withSelection("${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.DIRTY}=0",
                arrayOf(local.first.toString(), calendarId.toString(), MARKER)).withExpectedCount(1).build()
        if (local != null) operations += ContentProviderOperation.newDelete(sync(Reminders.CONTENT_URI))
            .withSelection("${Reminders.EVENT_ID}=?", arrayOf(local.first.toString())).build()
        for (minutes in if (event.cancelled) emptyList() else event.reminders) {
            val builder = ContentProviderOperation.newInsert(sync(Reminders.CONTENT_URI)).withValue(Reminders.MINUTES, minutes)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
            if (local == null) builder.withValueBackReference(Reminders.EVENT_ID, 0) else builder.withValue(Reminders.EVENT_ID, local.first)
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
            if (dirty) conflicts++ else if (provider.delete(sync(Events.CONTENT_URI),
                "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA1}=? AND ${Events.DIRTY}=0",
                arrayOf(row.toString(), id.toString(), MARKER)) != 1) conflicts++
        }
        return conflicts
    }
    companion object { const val MARKER = "cyclon-calendar-v1" }
}
