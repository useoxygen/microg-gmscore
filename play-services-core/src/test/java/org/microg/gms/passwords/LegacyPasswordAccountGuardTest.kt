/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test

class LegacyPasswordAccountGuardTest {
    @Test fun removalDuringCredentialReadCannotReachTheResultSink() {
        var current: String? = "fixture-original-id"
        val guard = LegacyPasswordAccountGuard("fixture-original-id") { current }
        var delivered = false
        assertThrows(SecurityException::class.java) {
            val credential = guard.checked { current = null; "fixture-secret" }
            guard.check(); delivered = credential.isNotEmpty()
        }
        assertFalse(delivered)
    }
    @Test fun sameNameReplacementDuringRepositoryConstructionCannotReachItsMutation() {
        var current: String? = "fixture-original-id"
        val guard = LegacyPasswordAccountGuard("fixture-original-id") { current }
        var mutated = false
        assertThrows(SecurityException::class.java) {
            val mutate = guard.checked { current = "fixture-replacement-id"; { mutated = true } }
            mutate()
        }
        assertFalse(mutated)
    }
    @Test fun revocationAfterReadIsRecheckedAtTheResultBoundaryAndLocalStoreStillWorks() {
        var current: String? = "fixture-original-id"
        val guard = LegacyPasswordAccountGuard("fixture-original-id") { current }
        assertEquals("fixture-result", guard.checked { "fixture-result" })
        current = "fixture-replacement-id"
        assertThrows(SecurityException::class.java) { guard.check() }
        val local = LegacyPasswordAccountGuard(null) { null }
        assertEquals("local-result", local.checked { "local-result" })
    }
}
