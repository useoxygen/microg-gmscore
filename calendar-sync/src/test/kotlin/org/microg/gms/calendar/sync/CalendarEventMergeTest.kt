/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.calendar.sync

import org.junit.Assert.*
import org.junit.Test

class CalendarEventMergeTest {
    private val sent = RemoteEvent("event", "sent-etag", "phone first", "description", "room",
        EventTime(1_793_052_000_000, false, "America/Los_Angeles"),
        EventTime(1_793_055_600_000, false, "America/Los_Angeles"),
        listOf("RRULE:FREQ=DAILY;COUNT=3"), null, null, false, listOf(10, 30),
        sequence = 1, useDefaultReminders = false, raw = "{\"attendees\":[{\"email\":\"fixture@example.invalid\"}]}")

    @Test fun laterPhoneTitleDoesNotRevertDisjointGoogleLocation() {
        val current = sent.copy(title = "phone later")
        val remote = sent.copy(etag = "ack-etag", location = "web", sequence = 2)
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals("phone later", merged.title)
        assertEquals("web", merged.location)
        assertEquals("ack-etag", merged.etag)
        assertEquals(2, merged.sequence)
    }

    @Test fun laterStartEditDoesNotCopyOldRecurrenceOrEndOverGoogleChanges() {
        val current = sent.copy(start = sent.start!!.copy(millis = sent.start!!.millis + 60_000))
        val remote = sent.copy(recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=3"),
            end = sent.end!!.copy(millis = sent.end!!.millis + 3_600_000))
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals(current.start, merged.start)
        assertEquals(remote.end, merged.end)
        assertEquals(remote.recurrence, merged.recurrence)
    }

    @Test fun googleRemindersSurviveUnrelatedPhoneTitleAndReminderReordering() {
        val current = sent.copy(title = "phone later", reminders = listOf(30, 10))
        val remote = sent.copy(reminders = listOf(5), useDefaultReminders = true)
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals("phone later", merged.title)
        assertEquals(listOf(5), merged.reminders)
        assertTrue(merged.useDefaultReminders)
    }

    @Test fun laterPhoneReminderEditKeepsReminderValuesAndDefaultChoiceTogether() {
        val current = sent.copy(reminders = listOf(5), useDefaultReminders = true)
        val remote = sent.copy(location = "web")
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals(listOf(5), merged.reminders)
        assertTrue(merged.useDefaultReminders)
        assertEquals("web", merged.location)
    }

    @Test fun laterDefaultReminderChoiceIsAnEditEvenWhenPopupMinutesMatch() {
        val current = sent.copy(useDefaultReminders = true)
        val remote = sent.copy(location = "web")
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals(sent.reminders, merged.reminders)
        assertTrue(merged.useDefaultReminders)
    }

    @Test fun laterCancellationAndRestorationRemainLocalIntent() {
        assertTrue(CalendarEventMerge.acknowledge(sent, sent.copy(cancelled = true), sent).cancelled)
        val cancelled = sent.copy(cancelled = true)
        assertFalse(CalendarEventMerge.acknowledge(cancelled, sent, cancelled).cancelled)
        assertTrue(CalendarEventMerge.acknowledge(sent, sent, cancelled).cancelled)
    }

    @Test fun writableFieldsMergeIndependentlyAndServerOwnsIdentityAndRawMetadata() {
        val current = sent.copy(description = "phone description", visibility = "private", transparency = "transparent",
            id = "stale-local-id", etag = "stale-etag", masterId = "stale-master", original = sent.start,
            creationId = "stale-nonce", raw = "{\"stale\":true}", sequence = 99)
        val remote = sent.copy(id = "server-id", etag = "server-etag", location = "web", masterId = "server-master",
            original = sent.start!!.copy(millis = sent.start!!.millis - 86_400_000), creationId = "server-nonce",
            raw = "{\"attendees\":[{\"email\":\"server@example.invalid\"}],\"conferenceData\":{}}", sequence = 2)
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals("phone description", merged.description)
        assertEquals("private", merged.visibility)
        assertEquals("transparent", merged.transparency)
        assertEquals("web", merged.location)
        assertEquals(remote.id, merged.id)
        assertEquals(remote.etag, merged.etag)
        assertEquals(remote.masterId, merged.masterId)
        assertEquals(remote.original, merged.original)
        assertEquals(remote.creationId, merged.creationId)
        assertEquals(remote.raw, merged.raw)
        assertEquals(remote.sequence, merged.sequence)
    }

    @Test fun overlappingWritableFieldsRequireAnExplicitConflict() {
        val variants: List<Triple<String, RemoteEvent, RemoteEvent>> = listOf(
            Triple("title", sent.copy(title = "phone later"), sent.copy(title = "newer Google title")),
            Triple("description", sent.copy(description = "phone details"), sent.copy(description = "Google details")),
            Triple("location", sent.copy(location = "phone room"), sent.copy(location = "newest cloud room")),
            Triple("visibility", sent.copy(visibility = "private"), sent.copy(visibility = "public")),
            Triple("start", sent.copy(start = sent.start!!.copy(millis = sent.start!!.millis + 60_000)),
                sent.copy(start = sent.start!!.copy(millis = sent.start!!.millis + 120_000))),
            Triple("end", sent.copy(end = sent.end!!.copy(millis = sent.end!!.millis + 60_000)),
                sent.copy(end = sent.end!!.copy(millis = sent.end!!.millis + 120_000))),
            Triple("recurrence", sent.copy(recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=3")),
                sent.copy(recurrence = listOf("RRULE:FREQ=MONTHLY;COUNT=3"))),
            Triple("reminders", sent.copy(reminders = listOf(5)), sent.copy(reminders = listOf(15))))
        for ((field, current, remote) in variants) {
            val conflict = assertThrows(CalendarAcknowledgementConflict::class.java) {
                CalendarEventMerge.acknowledge(sent, current, remote)
            }
            assertEquals(field, setOf(field), conflict.fields)
        }
    }

    @Test fun samePhoneAndGoogleChangeConvergesWithoutConflict() {
        val common = sent.copy(title = "same title", description = "same details", location = "same room",
            visibility = "private", transparency = "transparent", start = sent.start!!.copy(millis = sent.start!!.millis + 60_000),
            end = sent.end!!.copy(millis = sent.end!!.millis + 60_000), recurrence = listOf("RRULE:FREQ=WEEKLY;COUNT=3"),
            cancelled = true, reminders = listOf(5, 15), useDefaultReminders = true)
        val remote = common.copy(etag = "newest-etag", sequence = 4)
        val current = common.copy(reminders = listOf(15, 5))
        assertTrue(CalendarEventMerge.conflictingFields(sent, current, remote).isEmpty())
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals(remote.copy(reminders = current.reminders), merged)
    }

    @Test fun reminderModeAndMinutesOverlapsAreOneConflict() {
        val current = sent.copy(useDefaultReminders = true)
        val remote = sent.copy(reminders = listOf(15))
        val conflict = assertThrows(CalendarAcknowledgementConflict::class.java) {
            CalendarEventMerge.acknowledge(sent, current, remote)
        }
        assertEquals(setOf("reminders"), conflict.fields)
    }

    @Test fun allConflictingFieldsAreReportedBeforeAnyMerge() {
        val current = sent.copy(title = "phone title", location = "phone room", reminders = listOf(5))
        val remote = sent.copy(title = "Google title", location = "Google room", reminders = listOf(15), etag = "newest-etag")
        val conflict = assertThrows(CalendarAcknowledgementConflict::class.java) {
            CalendarEventMerge.acknowledge(sent, current, remote)
        }
        assertEquals(setOf("title", "location", "reminders"), conflict.fields)
        assertEquals("newest-etag", remote.etag)
        assertEquals("phone title", current.title)
    }
}
