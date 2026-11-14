/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import com.google.android.gms.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.microg.gms.cyclon.SyncPasswords.Accounts
import org.microg.gms.cyclon.SyncPasswords.Summary

class SyncPasswordsTest {
    @Test fun offSaysWhereTheDataStays() {
        val off = HealthReading(HealthState.OFF, HealthReason.DISABLED)
        assertEquals(Summary(R.string.cyclon_sync_contacts_off), SyncPasswords.contactsSummary(off, Accounts(0, 2)))
        assertEquals(Summary(R.string.cyclon_sync_calendar_off), SyncPasswords.calendarSummary(off, Accounts(0, 1)))
    }

    @Test fun noAccountAsksForOne() =
        assertEquals(Summary(R.string.cyclon_sync_no_account), SyncPasswords.contactsSummary(HealthReading(HealthState.OFF, HealthReason.NO_ACCOUNTS)))

    @Test fun readyDistinguishesFirstSyncFromSynced() {
        assertEquals(Summary(R.string.cyclon_sync_pending), SyncPasswords.contactsSummary(HealthReading(HealthState.READY, HealthReason.SYNC_PENDING), Accounts(1, 1)))
        assertEquals(Summary(R.string.cyclon_sync_on), SyncPasswords.contactsSummary(HealthReading(HealthState.READY, HealthReason.SYNC_SUCCESS, 1L), Accounts(1, 1)))
    }

    // With two accounts and one off, the row must not just say "On".
    @Test fun someAccountsOffAreCounted() {
        val ready = HealthReading(HealthState.READY, HealthReason.SYNC_SUCCESS, 1L)
        assertEquals(Summary(R.plurals.cyclon_sync_on_some, listOf(1, 2), count = 2), SyncPasswords.contactsSummary(ready, Accounts(1, 2)))
        assertEquals(Summary(R.plurals.cyclon_sync_pending_some, listOf(2, 3), count = 3),
            SyncPasswords.calendarSummary(HealthReading(HealthState.READY, HealthReason.SYNC_PENDING), Accounts(2, 3)))
        assertEquals(Summary(R.string.cyclon_sync_on), SyncPasswords.contactsSummary(ready, Accounts(2, 2)))
        // Unreadable counts (0 of 0) fall back to the plain words, never a made-up count.
        assertEquals(Summary(R.string.cyclon_sync_on), SyncPasswords.contactsSummary(ready, Accounts(0, 0)))
    }

    @Test fun calendarKeptInSyncByGoogleCalendarSaysSo() {
        val google = HealthReading(HealthState.READY, HealthReason.GOOGLE_CALENDAR)
        assertEquals(Summary(R.string.cyclon_sync_calendar_google), SyncPasswords.calendarSummary(google, Accounts(2, 2)))
        // An account that is off is off for the Google Calendar app's adapter too: "On" must not hide it.
        assertEquals(Summary(R.plurals.cyclon_sync_on_some, listOf(1, 2), count = 2), SyncPasswords.calendarSummary(google, Accounts(1, 2)))
    }

    @Test fun problemsAndPausesAreNamed() {
        assertEquals(Summary(R.string.cyclon_sync_attention), SyncPasswords.calendarSummary(HealthReading(HealthState.ATTENTION, HealthReason.AUTHORIZATION), Accounts(1, 2)))
        assertEquals(Summary(R.string.cyclon_sync_paused), SyncPasswords.contactsSummary(HealthReading(HealthState.PAUSED, HealthReason.SYNC_PAUSED), Accounts(1, 2)))
        assertEquals(Summary(R.string.cyclon_sync_unknown), SyncPasswords.contactsSummary(unknownHealth))
    }

    // What the home row says for real health readings: one account's Android switch off pauses the row.
    @Test fun oneUnscheduledOrFailingAccountShows() {
        val ok = ContactHealthObservation(true, true, HealthReason.SYNC_SUCCESS, 10)
        val failing = ContactHealthObservation(true, true, HealthReason.AUTHORIZATION, 0)
        val androidOff = ContactHealthObservation(true, false, HealthReason.SYNC_SUCCESS, 10)
        assertEquals(Summary(R.string.cyclon_sync_attention), SyncPasswords.contactsSummary(contactsHealth(listOf(ok, failing), masterSync = true), Accounts(2, 2)))
        assertEquals(Summary(R.string.cyclon_sync_paused), SyncPasswords.contactsSummary(contactsHealth(listOf(ok, androidOff), masterSync = true), Accounts(2, 2)))
        assertEquals(Summary(R.string.cyclon_sync_on), SyncPasswords.contactsSummary(contactsHealth(listOf(ok), masterSync = true), Accounts(1, 1)))
    }

    @Test fun autofillSaysWhetherPasswordsFill() {
        assertEquals(R.string.cyclon_autofill_on, SyncPasswords.autofillSummary(true))
        assertEquals(R.string.cyclon_autofill_off, SyncPasswords.autofillSummary(false))
    }
}
