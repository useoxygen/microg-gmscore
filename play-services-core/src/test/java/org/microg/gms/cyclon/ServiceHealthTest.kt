/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import org.junit.Assert.*
import org.junit.Test

class ServiceHealthTest {
    @Test fun disabledPushDominatesUnreadableConnection() {
        assertEquals(HealthState.OFF, pushHealth(false, null).state)
        assertEquals(unknownHealth, pushHealth(null, true))
    }
    @Test fun enabledPushNeedsAnObservedConnection() {
        assertEquals(HealthState.ATTENTION, pushHealth(true, false).state)
        assertEquals(HealthState.CONNECTED, pushHealth(true, true).state)
        assertEquals(unknownHealth, pushHealth(true, null))
    }
    @Test fun optionalContactsAreOffAndFailuresAreUnknown() {
        assertEquals(HealthReason.NO_ACCOUNTS, contactsHealth(emptyList(), true).reason)
        assertEquals(HealthState.OFF, contactsHealth(listOf(account(false, "authorization", 123)), true).state)
        assertEquals(unknownHealth, contactsHealth(null, true))
    }
    @Test fun oneSuccessfulAccountDoesNotHideAnotherFirstSync() {
        val reading = contactsHealth(listOf(account(true, "success", 123), account(true, "", 0)), true)
        assertEquals(HealthReason.SYNC_PENDING, reading.reason)
        assertNull(reading.lastSuccessAt)
    }
    @Test fun oldestSuccessAndAnyActiveFailureWin() {
        val accounts = listOf(account(true, "success", 456), account(true, "success", 123))
        assertEquals(123L, contactsHealth(accounts, true).lastSuccessAt)
        assertEquals(HealthReason.AUTHORIZATION, contactsHealth(accounts + account(true, "authorization", 999), true).reason)
        assertEquals(123L, contactsHealth(accounts + account(true, "authorization", 999), true).lastSuccessAt)
    }
    @Test fun pausedSchedulingIsNotAnAuthFailure() {
        assertEquals(HealthReason.SYNC_PAUSED, contactsHealth(listOf(account(true, "authorization", 123)), false).reason)
        assertEquals(123L, contactsHealth(listOf(account(true, "authorization", 123)), false).lastSuccessAt)
        assertEquals(HealthState.PAUSED, contactsHealth(listOf(account(true, "success", 123).copy(scheduled = false)), true).state)
        assertEquals(unknownHealth, contactsHealth(listOf(account(true, "success", 123).copy(scheduled = null)), true))
    }
    @Test fun enabledContactsNeedPermissionButDisabledContactsDoNot() {
        assertEquals(HealthReason.CONTACT_PERMISSION, contactsHealth(listOf(account(true, "success", 123)), true, false).reason)
        assertEquals(HealthState.OFF, contactsHealth(listOf(account(false, "", 0)), true, false).state)
    }
    @Test fun locationReadyDoesNotClaimAMeasuredPosition() {
        assertEquals(HealthState.OFF, locationHealth(false, true, true).state)
        assertEquals(HealthState.OFF, locationHealth(true, false, null).state)
        assertEquals(HealthReason.LOCATION_PERMISSION, locationHealth(true, true, false).reason)
        assertEquals(HealthReason.LOCATION_READY, locationHealth(true, true, true).reason)
        assertEquals(unknownHealth, locationHealth(true, null, true))
    }
    @Test fun registrationIsARecordedCheckinNotCertification() {
        assertEquals(HealthState.OFF, registrationHealth(false, 123).state)
        assertEquals(HealthReason.REGISTRATION_PENDING, registrationHealth(true, 0).reason)
        assertEquals(123L, registrationHealth(true, 123).lastSuccessAt)
        assertEquals(unknownHealth, registrationHealth(true, null))
    }
    @Test fun serverTextCannotEnterDiagnosticReport() {
        val privateError = "user@example.com token=secret latitude=37.4 https://private.endpoint/?key=secret"
        val reading = contactsHealth(listOf(account(true, privateError, 123)), true)
        val report = ServiceHealthSnapshot(contacts = reading, observedAt = 456,
            servicesVersionCode = 252432038, androidApi = 36).diagnosticReport()
        assertEquals(unknownHealth, reading)
        assertFalse(report.contains(privateError))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("@"))
        assertFalse(report.contains("https://"))
        assertTrue(report.contains("contacts=unknown; code=unreadable"))
        assertTrue(report.length < 2048)
    }
    private fun account(enabled: Boolean, status: String, success: Long) =
        ContactHealthObservation(enabled, true, contactHealthReason(status), success)
}
