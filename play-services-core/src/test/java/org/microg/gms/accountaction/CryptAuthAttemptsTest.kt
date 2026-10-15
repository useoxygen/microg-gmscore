/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.accountaction

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.accountaction.CryptAuthAttempts.Decision
import org.microg.gms.accountaction.CryptAuthAttempts.Outcome
import org.microg.gms.accountaction.CryptAuthAttempts.WaitReason

class CryptAuthAttemptsTest {
    private val hour = CryptAuthAttempts.HOUR_MILLIS
    private val start = 1_000_000L
    private var now = start
    private val attempts = CryptAuthAttempts(clock = { now })

    private fun failOnce(account: String = "work") {
        assertEquals(Decision.Run, attempts.decide(account))
        attempts.record(account, Outcome.FAILED)
    }

    @Test
    fun allowsThreeAttemptsPerAccountPerHour() {
        repeat(3) {
            failOnce()
            now += 5 * 60_000L
        }
        assertEquals(Decision.Wait(start + hour, WaitReason.ATTEMPTS_EXHAUSTED), attempts.decide("work"))

        now = start + hour
        assertEquals(Decision.Run, attempts.decide("work"))
    }

    @Test
    fun oneProofAtATimeAndOthersDoNotWait() {
        assertEquals(Decision.Run, attempts.decide("work"))
        repeat(5) { assertEquals(Decision.InProgress, attempts.decide("work")) }
        assertEquals(Decision.Run, attempts.decide("personal"))
        attempts.record("work", Outcome.SUCCEEDED)
        assertEquals(Decision.RetryTokenOnly, attempts.decide("work"))
    }

    @Test
    fun failurePausesSoQueuedRequestsCannotSpendTheBudget() {
        failOnce()
        repeat(10) {
            now += 10_000L
            assertEquals(WaitReason.RECENT_FAILURE, (attempts.decide("work") as Decision.Wait).reason)
        }
        now = start + 5 * 60_000L
        assertEquals(Decision.Run, attempts.decide("work"))
    }

    @Test
    fun requestsRightAfterASuccessOnlyRetryTheToken() {
        assertEquals(Decision.Run, attempts.decide("work"))
        attempts.record("work", Outcome.SUCCEEDED)
        repeat(10) {
            now += 5_000L
            assertEquals(Decision.RetryTokenOnly, attempts.decide("work"))
        }
        now += 60_000L
        assertEquals(Decision.Run, attempts.decide("work"))
    }

    @Test
    fun rateLimitPausesForAnHourEvenWithAttemptsLeft() {
        assertEquals(Decision.Run, attempts.decide("work"))
        attempts.record("work", Outcome.RATE_LIMITED)
        now += hour - 1
        assertEquals(Decision.Wait(start + hour, WaitReason.RATE_LIMITED), attempts.decide("work"))
        now += 1
        assertEquals(Decision.Run, attempts.decide("work"))
    }

    @Test
    fun accountsAreIndependent() {
        repeat(3) {
            failOnce()
            now += 5 * 60_000L
        }
        assertTrue(attempts.decide("work") is Decision.Wait)
        assertEquals(Decision.Run, attempts.decide("personal"))
    }

    @Test
    fun savedBoundsCarryOverToAnotherProcess() {
        failOnce()
        now += 5 * 60_000L
        assertEquals(Decision.Run, attempts.decide("work"))
        attempts.record("work", Outcome.RATE_LIMITED)

        val other = CryptAuthAttempts(clock = { now })
        other.restore("work", attempts.save("work"))
        assertEquals(Decision.Wait(now + hour, WaitReason.RATE_LIMITED), other.decide("work"))
    }

    @Test
    fun unreadableBoundsCountAsNoHistory() {
        attempts.restore("work", "garbage")
        assertEquals(Decision.Run, attempts.decide("work"))
    }

    @Test
    fun boundsFromBeforeAClockRollbackStillHold() {
        val later = now + 10 * hour
        val other = CryptAuthAttempts(clock = { now })
        other.restore("work", "$later,$later,$later;;;${later + hour}")
        assertEquals(Decision.Wait(now + hour, WaitReason.RATE_LIMITED), other.decide("work"))

        // The caller saves the normalized bounds, so the wait ends one hour after the rollback.
        val resaved = other.save("work")
        now += hour
        val next = CryptAuthAttempts(clock = { now })
        next.restore("work", resaved)
        assertEquals(Decision.Run, next.decide("work"))

        val third = CryptAuthAttempts(clock = { now })
        third.restore("work", "$later,$later,$later;;;")
        assertEquals(Decision.Wait(now + hour, WaitReason.ATTEMPTS_EXHAUSTED), third.decide("work"))
    }
}
