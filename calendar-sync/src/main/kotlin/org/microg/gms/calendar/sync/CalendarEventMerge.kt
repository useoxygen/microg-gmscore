/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

/** Acknowledgement must not turn concurrent changes into consent to overwrite a newer server value. */
class CalendarAcknowledgementConflict(val fields: Set<String>) :
    IllegalStateException("Concurrent calendar changes require an owner choice")

/** Merge an acknowledged server revision with only the phone edits made after dispatch. */
object CalendarEventMerge {
    fun conflictingFields(sent: RemoteEvent, current: RemoteEvent, remote: RemoteEvent): Set<String> = buildSet {
        fun compare(field: String, dispatched: Any?, phone: Any?, google: Any?) {
            if (phone != dispatched && google != dispatched && phone != google) add(field)
        }
        compare("title", sent.title, current.title, remote.title)
        compare("description", sent.description, current.description, remote.description)
        compare("location", sent.location, current.location, remote.location)
        compare("visibility", sent.visibility, current.visibility, remote.visibility)
        compare("transparency", sent.transparency, current.transparency, remote.transparency)
        compare("start", sent.start, current.start, remote.start)
        compare("end", sent.end, current.end, remote.end)
        compare("recurrence", sent.recurrence, current.recurrence, remote.recurrence)
        compare("cancelled", sent.cancelled, current.cancelled, remote.cancelled)
        // The inherited/explicit choice and its popup minutes form one writable API field.
        compare("reminders", sent.useDefaultReminders to sent.reminders.sorted(),
            current.useDefaultReminders to current.reminders.sorted(), remote.useDefaultReminders to remote.reminders.sorted())
    }

    fun acknowledge(sent: RemoteEvent, current: RemoteEvent, remote: RemoteEvent): RemoteEvent {
        val conflicts = conflictingFields(sent, current, remote)
        if (conflicts.isNotEmpty()) throw CalendarAcknowledgementConflict(conflicts)
        val remindersChanged = current.useDefaultReminders != sent.useDefaultReminders ||
            current.reminders.sorted() != sent.reminders.sorted()
        return remote.copy(
            title = if (current.title != sent.title) current.title else remote.title,
            description = if (current.description != sent.description) current.description else remote.description,
            location = if (current.location != sent.location) current.location else remote.location,
            visibility = if (current.visibility != sent.visibility) current.visibility else remote.visibility,
            transparency = if (current.transparency != sent.transparency) current.transparency else remote.transparency,
            start = if (current.start != sent.start) current.start else remote.start,
            end = if (current.end != sent.end) current.end else remote.end,
            recurrence = if (current.recurrence != sent.recurrence) current.recurrence else remote.recurrence,
            cancelled = if (current.cancelled != sent.cancelled) current.cancelled else remote.cancelled,
            reminders = if (remindersChanged) current.reminders else remote.reminders,
            useDefaultReminders = if (remindersChanged) current.useDefaultReminders else remote.useDefaultReminders)
    }
}
