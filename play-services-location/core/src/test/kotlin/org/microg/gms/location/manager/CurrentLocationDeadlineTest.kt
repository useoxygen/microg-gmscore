/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.location.manager

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CurrentLocationDeadlineTest {
    @Test fun noPositionCompletesAtTheRequestDeadline() = runBlocking {
        var registered = 0
        var expired = 0
        withTimeout(2000) {
            awaitCurrentLocationDeadline(10, { expired++ }) { registered++ }
        }
        assertEquals(1, registered)
        assertEquals(1, expired)
    }

    @Test fun callerCancellationDoesNotInventATimeoutResult() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        var expired = 0
        val job = launch {
            awaitCurrentLocationDeadline(60000, { expired++ }) { registered.complete(Unit) }
        }
        registered.await()
        job.cancelAndJoin()
        assertEquals(0, expired)
    }

    @Test fun registrationFailureRemainsAFailure() = runBlocking {
        var expired = 0
        try {
            awaitCurrentLocationDeadline(60000, { expired++ }) { throw IllegalStateException("fixture") }
            fail("registration error was swallowed")
        } catch (_: IllegalStateException) { }
        assertEquals(0, expired)
    }
}
