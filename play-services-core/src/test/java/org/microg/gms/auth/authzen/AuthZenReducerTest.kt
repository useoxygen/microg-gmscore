/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.authzen

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AuthZenReducerTest {
    private val clock = TestClock()
    private val storage = InMemoryStorage()
    private val repository = AuthZenRepository(storage, TestLock)
    private val profile = SyntheticProfile()
    private var handles = 0

    private fun request(id: String = "tx-1", created: Long = clock.wall, lifetime: Long = 5 * 60_000L, choices: List<Int> = listOf(32, 57, 81), correct: Int? = 32) =
        profile.decodeRequest(syntheticRequestBody(id, created, created + lifetime, choices, correct))

    private fun receive(request: AuthZenRequest = request(), account: String = "a", generation: Long = 1, digest: String = SecureMessageCrypto.sha256(request.originalBody).hex()) =
        repository.apply(AuthZenAction.Receive(account, generation, profile.name, request, digest, handle(), request.numberChoices.reversed()), clock.now())

    private fun handle() = String.format("%032x", ++handles)

    private fun inserted(result: AuthZenResult? = receive()) = (result as AuthZenResult.Inserted).record

    private fun state(handle: String) = repository.find(handle, clock.now())?.state

    private fun authorize(record: AuthZenRecord, number: Int? = 32, attempt: String = "attempt-1"): AuthZenRecord {
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, number, attempt), clock.now())
        return (repository.apply(AuthZenAction.AuthResult(record.handle, attempt, record.digest, record.generation, true), clock.now()) as AuthZenResult.Updated).record
    }

    @Test
    fun duplicatesAreRecognizedAndConflictsFailClosed() {
        val record = inserted()
        assertTrue(receive() is AuthZenResult.Duplicate)
        val forged = request()
        assertEquals(AuthZenResult.Conflict, receive(forged, digest = "different"))
        assertEquals(AuthZenState.UNSUPPORTED, state(record.handle))
        assertNull(repository.find(record.handle, clock.now())!!.request)
    }

    @Test
    fun cancellationBeforeRequestKeepsTheRequestHidden() {
        val tombstone = (repository.apply(AuthZenAction.Cancel("a", 1, "tx-1".toByteArray().hex(), handle()), clock.now()) as AuthZenResult.Inserted).record
        assertEquals(AuthZenState.CANCELED, tombstone.state)
        val late = receive()
        assertTrue(late is AuthZenResult.Duplicate)
        assertEquals(AuthZenState.CANCELED, (late as AuthZenResult.Duplicate).record.state)
    }

    @Test
    fun cancellationEndsAPendingRequestAndRacesAnAuthentication() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, 32, "attempt"), clock.now())
        repository.apply(AuthZenAction.Cancel("a", 1, record.transactionId, handle()), clock.now())
        val late = repository.apply(AuthZenAction.AuthResult(record.handle, "attempt", record.digest, 1, true), clock.now())
        assertTrue(late is AuthZenResult.Ignored)
        assertEquals(AuthZenState.CANCELED, state(record.handle))
    }

    @Test
    fun keyGenerationIsPartOfTheIdentity() {
        inserted()
        assertTrue(receive(generation = 2) is AuthZenResult.Inserted)
        assertTrue(receive(account = "b") is AuthZenResult.Inserted)
    }

    @Test
    fun expiryFollowsTheMonotonicClockDespiteWallClockRollback() {
        val record = inserted()
        clock.wall -= 3600_000L
        clock.elapsed += 5 * 60_000L
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
    }

    @Test
    fun wallClockForwardOrLateDeliveryExpires() {
        val record = inserted()
        clock.wall += 10 * 60_000L
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
        // A message created long ago does not get a fresh lifetime on delivery, now or on redelivery.
        val stale = request("tx-2", created = clock.wall - 20 * 60_000L, lifetime = 5 * 60_000L)
        assertEquals(AuthZenRejection.ALREADY_EXPIRED, (receive(stale) as AuthZenResult.Rejected).reason)
        assertTrue(receive(stale) is AuthZenResult.Duplicate)
    }

    /** Review round 1: a five-minute request delivered after 290 s reached SENDING 10 s after the server's expiry. */
    @Test
    fun aDelayedRequestGetsOnlyItsRemainingLifetime() {
        val record = inserted(receive(request(created = clock.wall - 290_000L)))
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        clock.advance(9_000L)
        assertEquals(AuthZenState.PRESENTED, state(record.handle))
        repository.apply(AuthZenAction.Approve(record.handle, 32, "attempt"), clock.now())
        repository.apply(AuthZenAction.AuthResult(record.handle, "attempt", record.digest, 1, true), clock.now())
        clock.advance(11_000L)
        assertTrue(repository.apply(AuthZenAction.BeginSend(record.handle, "op", true, true, 1), clock.now()) is AuthZenResult.Ignored)
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
    }

    @Test
    fun theRemainingLifetimeHoldsOnTheMonotonicClockAfterAWallClockRollback() {
        val record = inserted(receive(request(created = clock.wall - 200_000L)))
        clock.wall -= 3600_000L
        clock.elapsed += 99_000L
        assertEquals(AuthZenState.VERIFIED_PENDING, state(record.handle))
        clock.elapsed += 1_000L
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
    }

    @Test
    fun clockSkewNeverExtendsApprovalPastTheExpiry() {
        // Phone clock behind the server: the request looks created in the future; it still gets no more than its lifetime.
        val ahead = inserted(receive(request("tx-ahead", created = clock.wall + 60_000L)))
        clock.elapsed += 5 * 60_000L
        assertEquals(AuthZenState.EXPIRED, state(ahead.handle))
        // The wall clock reaching the server's expiry ends a request at once, without a grace period.
        val record = inserted(receive(request("tx-wall")))
        clock.wall += 5 * 60_000L - 1
        assertEquals(AuthZenState.VERIFIED_PENDING, state(record.handle))
        clock.wall += 1
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
        // A request whose lifetime was used up in delivery is refused when it arrives.
        assertEquals(AuthZenRejection.ALREADY_EXPIRED, (receive(request("tx-late", created = clock.wall - 5 * 60_000L)) as AuthZenResult.Rejected).reason)
    }

    @Test
    fun lifetimeIsCappedLocally() {
        val record = inserted(receive(request(lifetime = AuthZenDecoder.MAX_LIFETIME_MILLIS + 3600_000L)))
        clock.elapsed += AuthZenDecoder.MAX_LIFETIME_MILLIS
        assertEquals(AuthZenState.EXPIRED, state(record.handle))
    }

    @Test
    fun expiryDuringAuthenticationOrBeforeSendBlocksApproval() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, 32, "attempt"), clock.now())
        clock.advance(5 * 60_000L)
        assertTrue(repository.apply(AuthZenAction.AuthResult(record.handle, "attempt", record.digest, 1, true), clock.now()) is AuthZenResult.Ignored)
        assertEquals(AuthZenState.EXPIRED, state(record.handle))

        val second = inserted(receive(request("tx-2")))
        authorize(second)
        clock.advance(5 * 60_000L)
        assertTrue(repository.apply(AuthZenAction.BeginSend(second.handle, "op", true, true, 1), clock.now()) is AuthZenResult.Ignored)
        assertEquals(AuthZenState.EXPIRED, state(second.handle))
    }

    @Test
    fun rebootInvalidatesPendingApprovalsAndMakesSendsUnknown() {
        val pending = inserted()
        val sending = authorize(inserted(receive(request("tx-2"))))
        repository.apply(AuthZenAction.BeginSend(sending.handle, "op", true, true, 1), clock.now())
        clock.boot = "2"
        assertEquals(AuthZenState.EXPIRED, state(pending.handle))
        assertEquals(AuthZenState.OUTCOME_UNKNOWN, state(sending.handle))
    }

    @Test
    fun aSendThatNeverReportedBecomesUnknown() {
        val record = authorize(inserted())
        repository.apply(AuthZenAction.BeginSend(record.handle, "op", true, true, 1), clock.now())
        clock.advance(5 * 60_000L)
        assertEquals(AuthZenState.SENDING, state(record.handle))
        clock.advance(60_000L)
        assertEquals(AuthZenState.OUTCOME_UNKNOWN, state(record.handle))
    }

    @Test
    fun pendingRequestsAreBoundedPerAccountAndDevice() {
        repeat(3) { inserted(receive(request("a-$it"))) }
        assertEquals(AuthZenRejection.TOO_MANY_FOR_ACCOUNT, (receive(request("a-3")) as AuthZenResult.Rejected).reason)
        for (account in listOf("b", "c")) repeat(3) { receive(request("$account-$it"), account = account) }
        assertTrue(receive(request("e-0"), account = "e") is AuthZenResult.Inserted)
        assertEquals(AuthZenRejection.TOO_MANY_FOR_DEVICE, (receive(request("f-0"), account = "f") as AuthZenResult.Rejected).reason)
    }

    @Test
    fun tombstonesAnswerReplaysUntilPurgedAfterWhichTheReplayIsExpired() {
        val original = request()
        val record = inserted(receive(original))
        repository.apply(AuthZenAction.Deny(record.handle), clock.now())
        repository.apply(AuthZenAction.BeginSend(record.handle, "op", true, true, 1), clock.now())
        repository.apply(AuthZenAction.SendFinished(record.handle, "op", SendOutcome.CONFIRMED), clock.now())
        assertEquals(AuthZenState.DENIED_CONFIRMED, state(record.handle))
        assertTrue(receive(original) is AuthZenResult.Duplicate)
        clock.advance(25 * 3600_000L)
        assertNull(repository.find(record.handle, clock.now()))
        assertTrue(receive(original) is AuthZenResult.Rejected)
    }

    @Test
    fun numberMatchingAndOwnerAuthenticationBindings() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        assertEquals(AuthZenIgnored.NUMBER_REQUIRED, (repository.apply(AuthZenAction.Approve(record.handle, null, "x"), clock.now()) as AuthZenResult.Ignored).reason)
        repository.apply(AuthZenAction.Approve(record.handle, 32, "first"), clock.now())
        // Wrong attempt, wrong request bytes, wrong key generation: all ignored.
        for (action in listOf(
            AuthZenAction.AuthResult(record.handle, "other", record.digest, 1, true),
            AuthZenAction.AuthResult(record.handle, "first", "other-digest", 1, true),
            AuthZenAction.AuthResult(record.handle, "first", record.digest, 2, true),
        )) assertEquals(AuthZenIgnored.STALE_ATTEMPT, (repository.apply(action, clock.now()) as AuthZenResult.Ignored).reason)
        // Failure or cancel goes back to the request, and the attempt cannot be reused.
        repository.apply(AuthZenAction.AuthResult(record.handle, "first", record.digest, 1, false), clock.now())
        assertEquals(AuthZenState.PRESENTED, state(record.handle))
        assertTrue(repository.apply(AuthZenAction.AuthResult(record.handle, "first", record.digest, 1, true), clock.now()) is AuthZenResult.Ignored)

        repository.apply(AuthZenAction.Approve(record.handle, 32, "second"), clock.now())
        val authorized = (repository.apply(AuthZenAction.AuthResult(record.handle, "second", record.digest, 1, true), clock.now()) as AuthZenResult.Updated).record
        assertEquals(AuthZenDecision.APPROVE, authorized.decision)
        assertTrue(repository.apply(AuthZenAction.AuthResult(record.handle, "second", record.digest, 1, true), clock.now()) is AuthZenResult.Ignored)
    }

    @Test
    fun aWrongNumberEndsTheApprovalWithoutRetry() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        val mismatch = (repository.apply(AuthZenAction.Approve(record.handle, 57, "x"), clock.now()) as AuthZenResult.Updated).record
        assertEquals(AuthZenState.DECISION_AUTHORIZED, mismatch.state)
        assertEquals(AuthZenDecision.MISMATCH, mismatch.decision)
        assertTrue(repository.apply(AuthZenAction.Approve(record.handle, 32, "y"), clock.now()) is AuthZenResult.Ignored)
    }

    @Test
    fun reopeningDiscardsAnAuthenticationInProgress() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, 32, "attempt"), clock.now())
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        assertTrue(repository.apply(AuthZenAction.AuthResult(record.handle, "attempt", record.digest, 1, true), clock.now()) is AuthZenResult.Ignored)
        assertEquals(AuthZenState.PRESENTED, state(record.handle))
    }

    @Test
    fun sendBoundaryChecksAndOutcomes() {
        val removed = authorize(inserted())
        repository.apply(AuthZenAction.BeginSend(removed.handle, "op", accountPresent = false, optedIn = true, currentGeneration = 1), clock.now())
        assertEquals(AuthZenState.CANCELED, state(removed.handle))

        val rotated = authorize(inserted(receive(request("tx-2"))))
        repository.apply(AuthZenAction.BeginSend(rotated.handle, "op", true, true, currentGeneration = 2), clock.now())
        assertEquals(AuthZenState.CANCELED, state(rotated.handle))

        val record = authorize(inserted(receive(request("tx-3"))))
        repository.apply(AuthZenAction.BeginSend(record.handle, "op", true, true, 1), clock.now())
        assertTrue(repository.apply(AuthZenAction.Cancel("a", 1, record.transactionId, handle()), clock.now()) is AuthZenResult.Ignored)
        assertTrue(repository.apply(AuthZenAction.SendFinished(record.handle, "other", SendOutcome.CONFIRMED), clock.now()) is AuthZenResult.Ignored)
        repository.apply(AuthZenAction.SendFinished(record.handle, "op", SendOutcome.NOT_DELIVERED), clock.now())
        assertEquals(AuthZenState.DECISION_AUTHORIZED, state(record.handle))
        repository.apply(AuthZenAction.BeginSend(record.handle, "op2", true, true, 1), clock.now())
        repository.apply(AuthZenAction.SendFinished(record.handle, "op2", SendOutcome.UNKNOWN), clock.now())
        assertEquals(AuthZenState.OUTCOME_UNKNOWN, state(record.handle))
        // Unknown is final: no second send of a positive answer.
        assertTrue(repository.apply(AuthZenAction.BeginSend(record.handle, "op3", true, true, 1), clock.now()) is AuthZenResult.Ignored)
    }

    @Test
    fun accountRemovalForgetsTheAccountsRecords() {
        val record = inserted()
        val other = inserted(receive(account = "b"))
        repository.apply(AuthZenAction.AccountRemoved("a"), clock.now())
        assertNull(repository.find(record.handle, clock.now()))
        assertNotNull(repository.find(other.handle, clock.now()))
    }

    @Test
    fun recordsSurviveARestart() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        val restarted = AuthZenRepository(storage, TestLock).find(record.handle, clock.now())!!
        assertEquals(AuthZenState.PRESENTED, restarted.state)
        assertEquals(listOf(81, 57, 32), restarted.displayChoices)
        assertArrayEquals(record.request!!.originalBody, restarted.request!!.originalBody)
        assertEquals(32, restarted.request!!.correctNumber)
    }

    @Test
    fun anUnreadableStoreFailsClosed() {
        val record = inserted()
        storage.files.keys.forEach { storage.files[it] = "garbage".toByteArray() }
        assertNull(repository.find(record.handle, clock.now()))
    }

    /** Two processes (two repositories on one store) racing the same callback: exactly one decision. */
    @Test
    fun concurrentCallbacksAuthorizeOnce() {
        val record = inserted()
        repository.apply(AuthZenAction.Present(record.handle), clock.now())
        repository.apply(AuthZenAction.Approve(record.handle, 32, "attempt"), clock.now())
        val second = AuthZenRepository(storage, TestLock)
        val start = CountDownLatch(1)
        val authorized = AtomicInteger()
        (0 until 16).map { index ->
            thread {
                start.await()
                val result = (if (index % 2 == 0) repository else second).apply(AuthZenAction.AuthResult(record.handle, "attempt", record.digest, 1, true), clock.now())
                if (result is AuthZenResult.Updated) authorized.incrementAndGet()
            }
        }.also { start.countDown() }.forEach { it.join() }
        assertEquals(1, authorized.get())
    }
}
