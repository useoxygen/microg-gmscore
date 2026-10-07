/* SPDX-License-Identifier: Apache-2.0 */
package com.google.android.finsky

import com.google.android.finsky.model.IntegrityErrorCode
import com.google.android.finsky.model.StandardIntegrityException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class IntegrityPolicyTest {
    private val now = 1_791_288_000L
    private fun refresh(age: Long, sinceRefresh: Long = 601, hasToken: Boolean = true) =
        shouldRefreshDeviceIntegrity(now, now - age, now - sinceRefresh, 432000, 100800, 600, hasToken)

    @Test fun freshCachedTokenSurvivesWarmUp() { assertFalse(refresh(60)) }
    @Test fun softExpirationHonorsRefreshInterval() {
        assertFalse(refresh(100800, 599))
        assertTrue(refresh(100800, 600))
    }
    @Test fun hardExpirationCannotBePostponedByRecentRefresh() { assertTrue(refresh(432000, 1)) }
    @Test fun missingTokenOrTimestampRequiresRefresh() {
        assertTrue(refresh(0, hasToken = false))
        assertTrue(refresh(now))
    }
    @Test fun clockRollbackInvalidatesFutureCache() { assertTrue(refresh(-1)) }
    @Test fun specificClassicErrorsRemainActionable() {
        for (code in listOf(IntegrityErrorCode.NONCE_TOO_SHORT, IntegrityErrorCode.API_NOT_AVAILABLE, IntegrityErrorCode.TOO_MANY_REQUESTS)) {
            assertEquals(code, StandardIntegrityException(code, "test").classicIntegrityErrorCode())
        }
        assertEquals(IntegrityErrorCode.NETWORK_ERROR, IOException("offline").classicIntegrityErrorCode())
        assertEquals(IntegrityErrorCode.INTERNAL_ERROR, IllegalStateException().classicIntegrityErrorCode())
    }
}
