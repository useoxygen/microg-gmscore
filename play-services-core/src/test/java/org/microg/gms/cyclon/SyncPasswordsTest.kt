/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import com.google.android.gms.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPasswordsTest {
    @Test fun offSaysWhereTheDataStays() {
        val off = HealthReading(HealthState.OFF, HealthReason.DISABLED)
        assertEquals(R.string.cyclon_sync_contacts_off, SyncPasswords.contactsSummary(off))
        assertEquals(R.string.cyclon_sync_calendar_off, SyncPasswords.calendarSummary(off))
    }

    @Test fun noAccountAsksForOne() =
        assertEquals(R.string.cyclon_sync_no_account, SyncPasswords.contactsSummary(HealthReading(HealthState.OFF, HealthReason.NO_ACCOUNTS)))

    @Test fun readyDistinguishesFirstSyncFromSynced() {
        assertEquals(R.string.cyclon_sync_pending, SyncPasswords.contactsSummary(HealthReading(HealthState.READY, HealthReason.SYNC_PENDING)))
        assertEquals(R.string.cyclon_sync_on, SyncPasswords.contactsSummary(HealthReading(HealthState.READY, HealthReason.SYNC_SUCCESS, 1L)))
    }

    @Test fun problemsAndPausesAreNamed() {
        assertEquals(R.string.cyclon_sync_attention, SyncPasswords.calendarSummary(HealthReading(HealthState.ATTENTION, HealthReason.AUTHORIZATION)))
        assertEquals(R.string.cyclon_sync_paused, SyncPasswords.contactsSummary(HealthReading(HealthState.PAUSED, HealthReason.SYNC_PAUSED)))
        assertEquals(R.string.cyclon_sync_unknown, SyncPasswords.contactsSummary(unknownHealth))
    }

    // An end-to-end check: what the home row says for real health readings of two accounts.
    @Test fun oneFailingAccountMakesTheRowAskForAttention() {
        val ok = ContactHealthObservation(true, true, HealthReason.SYNC_SUCCESS, 10)
        val failing = ContactHealthObservation(true, true, HealthReason.AUTHORIZATION, 0)
        assertEquals(R.string.cyclon_sync_attention, SyncPasswords.contactsSummary(contactsHealth(listOf(ok, failing), masterSync = true)))
        assertEquals(R.string.cyclon_sync_on, SyncPasswords.contactsSummary(contactsHealth(listOf(ok), masterSync = true)))
    }

    @Test fun passwordsDescribeWhatTheScreenDoes() {
        assertEquals(R.string.cyclon_passwords_autofill_on, SyncPasswords.passwordsSummary(true))
        assertEquals(R.string.cyclon_passwords_manage, SyncPasswords.passwordsSummary(false))
    }
}
