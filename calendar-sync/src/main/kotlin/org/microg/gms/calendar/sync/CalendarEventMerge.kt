/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

/** Merge an acknowledged server revision with only the phone edits made after dispatch. */
object CalendarEventMerge {
    fun acknowledge(sent: RemoteEvent, current: RemoteEvent, remote: RemoteEvent): RemoteEvent {
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
