/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.gcm.registeration

import google.internal.notifications.v1.RegistrationStatus
import org.junit.Assert.*
import org.junit.Test

class ChimeRegistrationTest {
    private val now = 10 * CHIME_RETRY_AFTER_FAILURE_MILLIS

    private fun account(status: RegistrationStatus, updatedAt: Long) =
        ChimeGmsAccount("1", "owner@example.com", null, status, null, updatedAt)

    @Test
    fun newAccountsRegisterOnAnyTrigger() {
        assertTrue(needsChimeRegistration(null, now, explicit = false))
        assertTrue(needsChimeRegistration(null, now, explicit = true))
    }

    @Test
    fun registeredAccountsAreLeftAlone() {
        val registered = account(RegistrationStatus.STATUS_REGISTERED, now - 1)
        assertFalse(needsChimeRegistration(registered, now, explicit = false))
        assertFalse(needsChimeRegistration(registered, now, explicit = true))
    }

    @Test
    fun reconnectsWaitAfterAFailureButTheOwnerCanRetryAtOnce() {
        val failed = account(RegistrationStatus.STATUS_UNREGISTERED, now)
        repeat(60) { minute ->
            assertFalse(needsChimeRegistration(failed, now + minute * 60_000L, explicit = false))
        }
        assertTrue(needsChimeRegistration(failed, now + CHIME_RETRY_AFTER_FAILURE_MILLIS, explicit = false))
        assertTrue(needsChimeRegistration(failed, now + 1, explicit = true))
    }

    @Test
    fun legacyRecordsAndClockRollbackDoNotBlockRegistration() {
        assertTrue(needsChimeRegistration(account(RegistrationStatus.STATUS_UNREGISTERED, 0), now, explicit = false))
        assertTrue(needsChimeRegistration(account(RegistrationStatus.STATUS_UNREGISTERED, now + 5_000), now, explicit = false))
    }

    @Test
    fun attemptTimeSurvivesStorageAndOldRecordsReadAsZero() {
        val stored = account(RegistrationStatus.STATUS_UNREGISTERED, 1234L)
        assertEquals(stored.copy(representativeTargetId = "", obfuscatedGaiaId = ""), ChimeGmsAccount.parseJson(stored.toJson()))
        val legacy = """{"id":"1","accountName":"owner@example.com","registrationStatus":1}"""
        assertEquals(0L, ChimeGmsAccount.parseJson(legacy).updatedAt)
    }
}
