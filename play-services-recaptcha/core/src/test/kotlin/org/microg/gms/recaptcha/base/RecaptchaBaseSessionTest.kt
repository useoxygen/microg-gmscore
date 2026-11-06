/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = FakeGuard()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun session(caller: String = "com.example.app", timeoutMillis: Long = 5_000, maxInFlight: Int = 8) =
        RecaptchaBaseSession(caller, guard, scope, timeoutMillis, maxInFlight)

    /** The DroidGuard string carried in a successful result's byte form. */
    private fun guardStringIn(reply: Reply.Execute): String {
        val outer = parseProto(reply.result!!.payloadBytes!!)
        val variable = parseProto(outer.single(1).bytes!!)
        return parseProto(parseProto(variable.single(6).bytes!!).single(1).bytes!!).single(11).string
    }

    private fun executeIdIn(reply: Reply.Execute): String =
        parseProto(Base64.getDecoder().decode(reply.result!!.payload)).single(2).string

    private fun awaitInFlight(s: RecaptchaBaseSession, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (s.inFlightCount != expected) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("in flight ${s.inFlightCount}, expected $expected")
            Thread.sleep(5)
        }
    }

    @Test
    fun firstExecuteAfterInitUsesAndClosesTheInitializedHandle() {
        val s = session()
        val callbacks = RecordingCallbacks()
        s.init(callbacks)
        val init = callbacks.nextInit()
        assertEquals(CommonStatusCodes.SUCCESS, init.status.statusCode)
        assertNotNull(init.result)
        assertEquals(listOf("com.example.app"), guard.opens)
        assertTrue(s.hasInitializedHandle)

        s.execute(callbacks, "synthetic-nonce-1", "synthetic-id-1")
        val first = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, first.status.statusCode)
        val handle = guard.handles.single()
        // DroidGuard sees exactly token=nonce; the execute ID is never DroidGuard input.
        assertEquals(listOf(mapOf("token" to "synthetic-nonce-1")), handle.inputs)
        assertEquals(1, handle.closeCount.get())
        assertTrue(guard.results.isEmpty())
        assertEquals(syntheticGuardResult("handle-com.example.app-synthetic-nonce-1"), guardStringIn(first))
        assertEquals("synthetic-id-1", executeIdIn(first))
        assertFalse(s.hasInitializedHandle)

        s.execute(callbacks, "synthetic-nonce-2", "synthetic-id-2")
        val second = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, second.status.statusCode)
        assertEquals(listOf("com.example.app" to mapOf("token" to "synthetic-nonce-2")), guard.results)
        assertEquals(syntheticGuardResult("oneshot-com.example.app-synthetic-nonce-2"), guardStringIn(second))
        assertEquals("synthetic-id-2", executeIdIn(second))
        assertEquals(1, guard.handles.single().inputs.size)
        assertEquals(3, callbacks.count.get())
    }

    @Test
    fun executeWithoutInitUsesOneShotResults() {
        val callbacks = RecordingCallbacks()
        session().execute(callbacks, "synthetic-nonce", null)
        val reply = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, reply.status.statusCode)
        assertTrue(guard.opens.isEmpty())
        assertEquals(1, guard.results.size)
        // A missing execute ID becomes an empty one, as Play services does.
        assertEquals("", executeIdIn(reply))
    }

    @Test
    fun emptyOrMissingNonceIsRefusedWithoutDroidGuard() {
        val callbacks = RecordingCallbacks()
        val s = session()
        s.execute(callbacks, null, "synthetic-id")
        s.execute(callbacks, "", "synthetic-id")
        repeat(2) {
            val reply = callbacks.nextExecute()
            assertEquals(CommonStatusCodes.DEVELOPER_ERROR, reply.status.statusCode)
            assertNull(reply.result)
        }
        assertTrue(guard.results.isEmpty() && guard.opens.isEmpty())
    }

    @Test
    fun initFailureIsInternalErrorWithoutResult() {
        guard.openBehavior = { throw GuardUnavailableException("synthetic") }
        val callbacks = RecordingCallbacks()
        val s = session()
        s.init(callbacks)
        val reply = callbacks.nextInit()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, reply.status.statusCode)
        assertNull(reply.result)
        assertFalse(s.hasInitializedHandle)
    }

    @Test
    fun initTimeoutIsTimeout() {
        guard.openBehavior = FakeGuard.NEVER
        val callbacks = RecordingCallbacks()
        session(timeoutMillis = 100).init(callbacks)
        val reply = callbacks.nextInit()
        assertEquals(CommonStatusCodes.TIMEOUT, reply.status.statusCode)
        assertNull(reply.result)
    }

    @Test
    fun errorMarkerFromInitializedHandleFailsAndClosesIt() {
        guard.openBehavior = { FakeHandle { ERROR_RESULT }.also { guard.handles += it } }
        val callbacks = RecordingCallbacks()
        val s = session()
        s.init(callbacks)
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.nextInit().status.statusCode)
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        val reply = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, reply.status.statusCode)
        assertNull(reply.result)
        assertEquals(1, guard.handles.single().closeCount.get())
    }

    @Test
    fun invalidOneShotResultsFail() {
        val callbacks = RecordingCallbacks()
        val s = session()
        for (bad in listOf(ERROR_RESULT, "", "%%%")) {
            guard.resultsBehavior = { _, _ -> bad }
            s.execute(callbacks, "synthetic-nonce", "synthetic-id")
            val reply = callbacks.nextExecute()
            assertEquals(CommonStatusCodes.INTERNAL_ERROR, reply.status.statusCode)
            assertNull(reply.result)
        }
        guard.resultsBehavior = { _, _ -> throw IllegalStateException("synthetic") }
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, callbacks.nextExecute().status.statusCode)
    }

    @Test
    fun executeTimeoutInterruptsSnapshotAndClosesTheHandle() {
        val interrupted = CountDownLatch(1)
        guard.openBehavior = {
            FakeHandle {
                try {
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    interrupted.countDown()
                    throw e
                }
                syntheticGuardResult("late")
            }.also { guard.handles += it }
        }
        val callbacks = RecordingCallbacks()
        val s = session(timeoutMillis = 200)
        s.init(callbacks)
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.nextInit().status.statusCode)
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        val reply = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.TIMEOUT, reply.status.statusCode)
        assertNull(reply.result)
        interrupted.awaitOrFail()
        assertEquals(1, guard.handles.single().closeCount.get())
    }

    @Test
    fun oneShotTimeoutIsTimeout() {
        guard.resultsBehavior = { _, _ -> awaitCancellation() }
        val callbacks = RecordingCallbacks()
        session(timeoutMillis = 100).execute(callbacks, "synthetic-nonce", "synthetic-id")
        assertEquals(CommonStatusCodes.TIMEOUT, callbacks.nextExecute().status.statusCode)
    }

    @Test
    fun reinitReplacesAndClosesThePreviousHandle() {
        val callbacks = RecordingCallbacks()
        val s = session()
        s.init(callbacks)
        callbacks.nextInit()
        s.init(callbacks)
        callbacks.nextInit()
        assertEquals(2, guard.handles.size)
        assertEquals(1, guard.handles[0].closeCount.get())
        assertEquals(0, guard.handles[1].closeCount.get())
        s.close()
        assertEquals(1, guard.handles[1].closeCount.get())
    }

    @Test
    fun handleReturnedAsTheInitTimeoutFiresIsKeptNotDropped() {
        val handle = FakeHandle { syntheticGuardResult("kept") }
        guard.openBehavior = {
            // Suspends until the timeout has cancelled the operation, then resumes with a live handle: the value a
            // withTimeout return would discard. (A block that never suspends has its timeout ignored by withTimeout.)
            val job = currentCoroutineContext()[Job]!!
            withContext(NonCancellable) { while (!job.isCancelled) delay(5) }
            handle.also { guard.handles += it }
        }
        val callbacks = RecordingCallbacks()
        val s = session(timeoutMillis = 50)
        s.init(callbacks)
        val reply = callbacks.nextInit()
        // The session took the handle, so init reports success and the handle is neither dropped nor closed yet.
        assertEquals(CommonStatusCodes.SUCCESS, reply.status.statusCode)
        assertTrue(s.hasInitializedHandle)
        assertEquals(0, handle.closeCount.get())
        s.close()
        assertEquals(1, handle.closeCount.get())
    }

    @Test
    fun initAndExecuteAfterCloseAreRefusedWithoutDroidGuardWork() {
        val callbacks = RecordingCallbacks()
        val s = session()
        s.close()
        s.close()
        s.init(callbacks)
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        val init = callbacks.nextInit()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, init.status.statusCode)
        assertNull(init.result)
        val execute = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, execute.status.statusCode)
        assertNull(execute.result)
        assertTrue(guard.opens.isEmpty() && guard.results.isEmpty())
        Thread.sleep(100)
        assertEquals(2, callbacks.count.get())
        assertEquals(0, s.inFlightCount)
    }

    @Test
    fun closeAnswersInFlightRequestsOnceWithFailureAndClosesALateHandle() {
        val openEntered = CountDownLatch(1)
        val resultsEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resultsCancelled = CountDownLatch(1)
        guard.openBehavior = {
            // Does not cooperate with cancellation: the handle arrives after close.
            openEntered.countDown()
            release.await()
            FakeHandle { syntheticGuardResult("late") }.also { guard.handles += it }
        }
        guard.resultsBehavior = { _, _ ->
            resultsEntered.countDown()
            try {
                awaitCancellation()
            } finally {
                resultsCancelled.countDown()
            }
        }
        val initCallbacks = RecordingCallbacks()
        val executeCallbacks = RecordingCallbacks()
        val s = session(timeoutMillis = 60_000)
        s.init(initCallbacks)
        s.execute(executeCallbacks, "synthetic-nonce", "synthetic-id")
        openEntered.awaitOrFail()
        resultsEntered.awaitOrFail()

        s.close()
        // Both are answered by close itself, before the guard work ends.
        val init = initCallbacks.nextInit()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, init.status.statusCode)
        assertNull(init.result)
        val execute = executeCallbacks.nextExecute()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, execute.status.statusCode)
        assertNull(execute.result)
        resultsCancelled.awaitOrFail()

        release.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (guard.handles.isEmpty() || guard.handles.single().closeCount.get() == 0) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("late handle not closed")
            Thread.sleep(5)
        }
        awaitInFlight(s, 0)
        assertEquals(1, guard.handles.single().closeCount.get())
        assertFalse(s.hasInitializedHandle)
        // The late init does not report success after its handle was closed.
        assertEquals(1, initCallbacks.count.get())
        assertEquals(1, executeCallbacks.count.get())
    }

    @Test
    fun closingOneSessionLeavesOthersOnTheSameScopeRunning() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        guard.resultsBehavior = { caller, input ->
            if (caller == "com.example.b") {
                entered.countDown()
                while (release.count > 0) delay(5)
            }
            syntheticGuardResult("oneshot-$caller-${input[KEY_TOKEN]}")
        }
        val a = session("com.example.a")
        val b = session("com.example.b")
        val callbacksA = RecordingCallbacks()
        val callbacksB = RecordingCallbacks()
        a.init(callbacksA)
        assertEquals(CommonStatusCodes.SUCCESS, callbacksA.nextInit().status.statusCode)
        b.execute(callbacksB, "synthetic-nonce-b", "synthetic-id-b")
        entered.awaitOrFail()

        a.close()
        assertEquals(1, guard.handles.single().closeCount.get())
        release.countDown()
        val replyB = callbacksB.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, replyB.status.statusCode)
        assertEquals(syntheticGuardResult("oneshot-com.example.b-synthetic-nonce-b"), guardStringIn(replyB))
        b.execute(callbacksB, "synthetic-nonce-b2", "synthetic-id-b2")
        assertEquals(CommonStatusCodes.SUCCESS, callbacksB.nextExecute().status.statusCode)
    }

    @Test
    fun cancelledScopeAnswersPendingRequestsOnce() {
        guard.openBehavior = FakeGuard.NEVER
        guard.resultsBehavior = { _, _ -> awaitCancellation() }
        val callbacks = RecordingCallbacks()
        val s = session(timeoutMillis = 60_000)
        s.init(callbacks)
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        while (guard.opens.isEmpty() || guard.results.isEmpty()) Thread.sleep(5)
        scope.cancel()
        val replies = listOf(callbacks.next(), callbacks.next())
        assertTrue(replies.all {
            when (it) {
                is Reply.Init -> it.status.statusCode == CommonStatusCodes.INTERNAL_ERROR
                is Reply.Execute -> it.status.statusCode == CommonStatusCodes.INTERNAL_ERROR && it.result == null
            }
        })
        // A request after the scope is gone is still answered.
        s.execute(callbacks, "synthetic-nonce", "synthetic-id")
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, callbacks.nextExecute().status.statusCode)
        Thread.sleep(100)
        assertEquals(3, callbacks.count.get())
    }

    @Test
    fun concurrentExecutesShareOneInitializedHandleAndAreBounded() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        guard.resultsBehavior = { caller, input ->
            started.countDown()
            while (release.count > 0) delay(5)
            syntheticGuardResult("oneshot-$caller-${input[KEY_TOKEN]}")
        }
        val callbacks = RecordingCallbacks()
        val s = session(maxInFlight = 5)
        s.init(callbacks)
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.nextInit().status.statusCode)
        awaitInFlight(s, 0)

        // Five fit: one consumes the initialized handle, four block in one-shot results.
        for (i in 1..5) s.execute(callbacks, "synthetic-nonce-$i", "synthetic-id-$i")
        started.awaitOrFail()
        val fast = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, fast.status.statusCode)
        awaitInFlight(s, 4)
        // The connection is now at 4 in flight; two more fill and then exceed the bound.
        s.execute(callbacks, "synthetic-nonce-6", "synthetic-id-6")
        s.execute(callbacks, "synthetic-nonce-7", "synthetic-id-7")
        val refused = callbacks.nextExecute()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, refused.status.statusCode)
        release.countDown()

        val rest = (1..5).map { callbacks.nextExecute() }
        assertTrue(rest.all { it.status.statusCode == CommonStatusCodes.SUCCESS })
        val all = rest + fast
        // Every result is bound to its own request: nonce in the DroidGuard input, ID in the envelope.
        for (reply in all) {
            val id = executeIdIn(reply)
            val n = id.removePrefix("synthetic-id-")
            assertTrue(guardStringIn(reply) in setOf(
                syntheticGuardResult("handle-com.example.app-synthetic-nonce-$n"),
                syntheticGuardResult("oneshot-com.example.app-synthetic-nonce-$n")
            ))
        }
        assertEquals(1, guard.handles.single().inputs.size)
        assertEquals(1, guard.handles.single().closeCount.get())
        assertEquals(5, guard.results.size)
        Thread.sleep(100)
        assertEquals(8, callbacks.count.get())
    }

    @Test
    fun sessionsAreIsolatedByCaller() {
        val a = session("com.example.a")
        val b = session("com.example.b")
        val callbacksA = RecordingCallbacks()
        val callbacksB = RecordingCallbacks()
        a.init(callbacksA)
        callbacksA.nextInit()

        // B never sees A's initialized handle, and DroidGuard is asked on B's behalf.
        b.execute(callbacksB, "synthetic-nonce-b", "synthetic-id-b")
        val replyB = callbacksB.nextExecute()
        assertEquals(syntheticGuardResult("oneshot-com.example.b-synthetic-nonce-b"), guardStringIn(replyB))
        assertTrue(a.hasInitializedHandle)
        assertEquals(0, guard.handles.single().inputs.size)

        a.execute(callbacksA, "synthetic-nonce-a", "synthetic-id-a")
        val replyA = callbacksA.nextExecute()
        assertEquals(syntheticGuardResult("handle-com.example.a-synthetic-nonce-a"), guardStringIn(replyA))
        assertEquals(listOf("com.example.a"), guard.opens)
        assertEquals(listOf("com.example.b"), guard.results.map { it.first })
        assertEquals(2, callbacksA.count.get())
        assertEquals(1, callbacksB.count.get())
    }
}
