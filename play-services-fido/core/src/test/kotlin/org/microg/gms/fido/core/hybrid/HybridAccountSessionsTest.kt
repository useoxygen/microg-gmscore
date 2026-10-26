/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.hybrid

import org.junit.Assert.*
import org.junit.Test

class HybridAccountSessionsTest {
    private val raw = byteArrayOf(1, 2, 3)
    private val hash = ByteArray(32) { it.toByte() }
    private val rp = "example.invalid"
    @Test fun actualRequestClaimIsOneUseAndEveryBoundInputIsRechecked() {
        val session = HybridAccountSessions.open(ByteArray(32) { 7 }) { true }
        val token = HybridAccountSessions.issue(session, raw, rp, hash, false, ByteArray(32) { 19 })
        try {
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, false, claimed = true) }
            HybridAccountSessions.requireLive(token, raw, rp, hash, false, claim = true)
            repeat(3) { HybridAccountSessions.requireLive(token, raw.copyOf(), rp, hash.copyOf(), false, claimed = true) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, false, claim = true) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw + 4, rp, hash, false) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, "other.invalid", hash, false) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, ByteArray(32), false) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, true) }
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, null, false) }
        } finally { session.revoke() }
    }
    @Test fun socketLossRevokesWhileNativeOwnerUiIsStillPendingAndCannotBeReopened() {
        var live = true
        val session = HybridAccountSessions.open(ByteArray(32) { 8 }) { live }
        val token = HybridAccountSessions.issue(session, raw, rp, hash, true, ByteArray(32) { 19 })
        HybridAccountSessions.requireLive(token, raw, rp, hash, true, claim = true)
        live = false
        assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, true, claimed = true) }
        live = true
        assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, true) }
        session.revoke()
        assertThrows(IllegalStateException::class.java) { HybridAccountSessions.issue(session, raw, rp, hash, true, ByteArray(32) { 19 }) }
    }
    @Test fun completedCancelledAndOtherTranscriptRequestsCannotReleaseAResult() {
        val first = HybridAccountSessions.open(ByteArray(32) { 1 }) { true }
        val second = HybridAccountSessions.open(ByteArray(32) { 2 }) { true }
        val a = HybridAccountSessions.issue(first, raw, rp, hash, false, ByteArray(32) { 19 })
        val b = HybridAccountSessions.issue(second, raw, rp, hash, false, ByteArray(32) { 19 })
        try {
            assertNotEquals(a, b)
            assertThrows(IllegalStateException::class.java) { HybridAccountSessions.complete(second, a) }
            HybridAccountSessions.complete(first, a)
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(a, raw, rp, hash, false) }
            HybridAccountSessions.requireLive(b, raw, rp, hash, false)
            second.revoke()
            assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(b, raw, rp, hash, false) }
        } finally { first.revoke(); second.revoke() }
    }
    @Test fun monotonicExpiryAndClockRollbackFailClosedWithoutSleeping() {
        var time = 500L
        val session = HybridAccountSessions.open(ByteArray(32), { time }) { true }
        var token = HybridAccountSessions.issue(session, raw, rp, hash, false, ByteArray(32) { 19 })
        time += 600_000_000_001L
        assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, false) }
        HybridAccountSessions.complete(session, token)
        token = HybridAccountSessions.issue(session, raw, rp, hash, false, ByteArray(32) { 19 })
        time--
        assertThrows(SecurityException::class.java) { HybridAccountSessions.requireLive(token, raw, rp, hash, false) }
        session.revoke()
    }
}
