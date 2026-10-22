/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FusedMaxUpdateAgeTest {
    @Test fun aCurrentLocationRequestMayBeAnsweredFromAFixUpTo30SecondsOld() {
        // LocationManager.getCurrentLocation reaches the provider as interval 0.
        assertEquals(30_000L, FusedRequestAge.maxUpdateAgeMillis(0L))
    }

    @Test fun continuousAndPassiveSubscriptionsKeepTheDefaultAge() {
        // A navigation-style 1 s subscription must not start from a position up to 30 s behind.
        for (interval in listOf(1_000L, 5_000L, 600_000L, Long.MAX_VALUE)) assertNull(FusedRequestAge.maxUpdateAgeMillis(interval))
        assertNull(FusedRequestAge.maxUpdateAgeMillis(null))
    }
}
