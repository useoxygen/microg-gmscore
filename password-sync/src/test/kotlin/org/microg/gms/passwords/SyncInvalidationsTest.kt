/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class SyncInvalidationsTest {
    private val digest = ByteArray(32) { 3 }
    @Test fun freshProcessReplaysUnscheduledHintAndRetriesDoNotInventNewGenerations() {
        val state = VaultState(invalidations = SyncInvalidations().receive("work-1", setOf(SyncDeviceInfo.PASSWORDS), digest, 123))
        val reopened = VaultState.decode(state.encode())
        assertTrue(reopened.invalidations.pending)
        assertEquals(1L, reopened.invalidations.directPasswordGeneration)
        val retried = reopened.invalidations.receive("work-1", setOf(SyncDeviceInfo.PASSWORDS), digest, 456)
        assertArrayEquals(reopened.invalidations.encode(), retried.encode())
        val acknowledged = retried.acknowledgePasswords()
        assertFalse(acknowledged.pending)
        assertFalse(acknowledged.receive("work-1", setOf(SyncDeviceInfo.PASSWORDS), digest, 789).pending)
        // A new actual delivery can have an identical payload: deduping by digest would lose it.
        val next = acknowledged.receive("work-2", setOf(SyncDeviceInfo.PASSWORDS), digest, 789)
        assertTrue(next.pending); assertEquals(2L, next.generation)
    }
    @Test fun nigoriRequiresBothIndependentSnapshotsAndPreferenceHintsDoNotFakePasswordPush() {
        val both = SyncInvalidations().receive("nigori", setOf(SyncDeviceInfo.NIGORI), digest, 123)
        assertEquals(0L, both.directPasswordGeneration)
        assertTrue(both.acknowledgePreferences().pending)
        assertTrue(both.acknowledgePasswords().pending)
        val complete = both.acknowledgePasswords().acknowledgePreferences()
        assertFalse(complete.pending)
        val preference = complete.receive("preference", setOf(SyncDeviceInfo.PREFERENCES), digest, 456)
        assertEquals(1L, preference.passwords); assertEquals(2L, preference.preferences)
        assertTrue(preference.acknowledgePasswords().pending)
        assertFalse(preference.acknowledgePreferences().pending)
    }
    @Test fun malformedStateAndGenerationOverflowCannotEraseRefreshObligations() {
        assertThrows(ProtocolException::class.java) { SyncInvalidations(passwords = 1) }
        assertThrows(ProtocolException::class.java) { SyncInvalidations(generation = 1, passwordsHandled = 1) }
        assertThrows(ProtocolException::class.java) { SyncInvalidations().receive("id", setOf(1), ByteArray(31), 0) }
        assertThrows(ProtocolException::class.java) {
            SyncInvalidations(generation = Long.MAX_VALUE).receive("id", setOf(SyncDeviceInfo.PASSWORDS), digest, 0)
        }
        val empty = SyncInvalidations()
        assertArrayEquals(empty.encode(), empty.receive("irrelevant", setOf(123), digest, 0).encode())
    }
}
