/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Round 2 of #61: binding is no "copy in progress" signal; authenticated use through the transport is. */
class RetainedSessionUseTest {
    private val switch = 10175
    @After fun reset() = RetainedSessionUse.reset()

    @Test fun overlappingTransportsKeepThePairingUntilTheLastCloses() {
        val first = RetainedSessionUse.transport(switch); val second = RetainedSessionUse.transport(switch)
        first.close(); first.close() // closing twice counts once
        assertTrue(RetainedSessionUse.inUse(switch, now = 0))
        second.close()
        assertFalse(RetainedSessionUse.inUse(switch, now = 0))
    }

    @Test fun aLiveCopyOutlastsIdleApiCallsAndItsEndReleasesThePairing() {
        RetainedSessionUse.called(switch, now = 0)
        val copy = RetainedSessionUse.transport(switch)
        // Switch's API connection went idle long ago (no calls, unbound); the copy transport is still open.
        assertTrue(RetainedSessionUse.inUse(switch, now = 10 * RetainedSessionUse.LEASE_MS))
        copy.close() // finished or failed: the old phone has left the pairing
        assertFalse(RetainedSessionUse.inUse(switch, now = 1))
    }

    @Test fun aCallWithoutATransportHoldsThePairingOnlyForTheLease() {
        RetainedSessionUse.called(switch, now = 1_000)
        assertTrue(RetainedSessionUse.inUse(switch, now = 1_000 + RetainedSessionUse.LEASE_MS - 1))
        assertFalse(RetainedSessionUse.inUse(switch, now = 1_000 + RetainedSessionUse.LEASE_MS))
    }

    @Test fun anotherUidsUseNeverHoldsSwitchsPairing() {
        RetainedSessionUse.transport(10999); RetainedSessionUse.called(10999, now = 0)
        assertFalse(RetainedSessionUse.inUse(switch, now = 0))
    }
}
