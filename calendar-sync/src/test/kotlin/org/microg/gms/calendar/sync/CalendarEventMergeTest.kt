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
        val remote = sent.copy(reminders = listOf(15), useDefaultReminders = false, location = "web")
        val merged = CalendarEventMerge.acknowledge(sent, current, remote)
        assertEquals(listOf(5), merged.reminders)
        assertTrue(merged.useDefaultReminders)
        assertEquals("web", merged.location)
    }

    @Test fun laterDefaultReminderChoiceIsAnEditEvenWhenPopupMinutesMatch() {
        val current = sent.copy(useDefaultReminders = true)
        val remote = sent.copy(reminders = listOf(15), useDefaultReminders = false)
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
}
