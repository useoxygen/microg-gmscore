/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.internal.ConnectionCallbacks
import com.google.android.gms.common.api.internal.OnConnectionFailedListener
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.DroidGuardApiClient
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.core.NetworkHandleProxyFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.microg.gms.droidguard.DroidGuardHandleImpl as ClientHandle
import org.microg.gms.droidguard.core.DroidGuardHandleImpl as ServiceHandle

/**
 * [DroidGuardRecaptchaBaseGuard] against microG's own DroidGuard client and service handle classes, and against client
 * calls that block before they return a Task. No Google network: the service handle's initialization fails on microG's
 * own "DroidGuard should not be available locally" check, because the DroidGuard settings provider is absent here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseDroidGuardRuntimeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executors = mutableListOf<ThreadPoolExecutor>()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun tearDown() {
        gates.forEach { it.countDown() }
        scope.cancel()
        executors.forEach { it.shutdownNow() }
    }

    private fun workers(threads: Int = WORKER_THREADS, queue: Int = WORKER_QUEUE) =
        ThreadPoolExecutor(threads, threads, 1, TimeUnit.SECONDS, ArrayBlockingQueue(queue), ThreadPoolExecutor.AbortPolicy())
            .also { executors += it }

    private fun gate() = CountDownLatch(1).also { gates += it }

    /**
     * The service handle reached through a Binder proxy, as the client reaches it across processes: every call is
     * marshalled through Parcels, and [unknownCodes] are refused the way a service without those transactions refuses.
     */
    private class RemoteHandle(private val target: IDroidGuardHandle.Stub, private val unknownCodes: Set<Int> = emptySet()) : Binder() {
        val codes: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val proxy: IDroidGuardHandle = IDroidGuardHandle.Stub.asInterface(this)

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            if (code in unknownCodes) return false
            return target.transact(code, data, reply, flags)
        }

        fun count(code: Int) = synchronized(codes) { codes.count { it == code } }
    }

    private fun apiClient() = DroidGuardApiClient(
        context,
        object : ConnectionCallbacks {
            override fun onConnected(connectionHint: Bundle?) = Unit
            override fun onConnectionSuspended(cause: Int) = Unit
        },
        object : OnConnectionFailedListener {
            override fun onConnectionFailed(result: ConnectionResult) = Unit
        },
    )

    /**
     * A DroidGuard client whose init does what [DroidGuardApiClient.openHandle] does once connected: initWithRequest on
     * the service handle, then the client handle around it.
     */
    private fun clientFor(remote: RemoteHandle) = object : DroidGuardClient {
        override fun init(flow: String, request: DroidGuardResultsRequest?): Task<DroidGuardHandle> {
            val finalRequest = request ?: DroidGuardResultsRequest()
            remote.proxy.initWithRequest(flow, finalRequest)
            return Tasks.forResult(ClientHandle(apiClient(), finalRequest, remote.proxy))
        }

        override fun getResults(flow: String, data: Map<String, String>?, request: DroidGuardResultsRequest?): Task<String> =
            throw AssertionError("not used")
    }

    private fun awaitCount(remote: RemoteHandle, code: Int, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (remote.count(code) < expected) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("transaction $code seen ${remote.count(code)} times")
            Thread.sleep(5)
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun assertNoFurtherCallback(callbacks: RecordingCallbacks) {
        assertNull(callbacks.replies.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(1, callbacks.count.get())
    }

    // --- initialization failure reported by the service ---

    @Test
    fun serviceHandleInitFailureFailsInitClosesTheHandleAndSendsNoResult() {
        val service = ServiceHandle(context, context.packageName, NetworkHandleProxyFactory(context), GuardCallback(context, context.packageName))
        val remote = RemoteHandle(service)
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ clientFor(remote) }, { true }, workers()), scope)

        val callbacks = RecordingCallbacks()
        session.init(callbacks)
        val reply = callbacks.nextInit()
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, reply.status.statusCode)
        assertNull(reply.result)
        assertFalse(session.hasInitializedHandle)

        // initWithRequest returned normally and the Binder is alive, yet the state says not initialized.
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(1, remote.count(GET_INIT_STATE))
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        // The handle is closed once on the service, and no snapshot was taken from it.
        awaitCount(remote, CLOSE, 1)
        Thread.sleep(100)
        assertEquals(1, remote.count(CLOSE))
        assertEquals(0, remote.count(SNAPSHOT))
        assertNoFurtherCallback(callbacks)
    }

    @Test
    fun initializedServiceHandleIsKept() {
        val remote = RemoteHandle(StateHandle(ClientHandle.INIT_STATE_INITIALIZED))
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ clientFor(remote) }, { true }, workers()), scope)
        val callbacks = RecordingCallbacks()
        session.init(callbacks)
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.nextInit().status.statusCode)
        assertTrue(session.hasInitializedHandle)
        assertEquals(0, remote.count(CLOSE))
        session.close()
        awaitCount(remote, CLOSE, 1)
    }

    @Test
    fun serviceWithoutTheInitStateTransactionKeepsTheBinderLivenessCheck() {
        // A service built before getInitState: the transaction is unknown, the reply stays empty and reads as unknown.
        val remote = RemoteHandle(StateHandle(ClientHandle.INIT_STATE_NOT_INITIALIZED), unknownCodes = setOf(GET_INIT_STATE))
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ clientFor(remote) }, { true }, workers()), scope)
        val callbacks = RecordingCallbacks()
        session.init(callbacks)
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.nextInit().status.statusCode)
        session.close()
    }

    /** A service handle whose initialization outcome is fixed; it serves no snapshots. */
    private class StateHandle(private val state: Int) : IDroidGuardHandle.Stub() {
        override fun init(flow: String?) = Unit
        override fun snapshot(map: MutableMap<Any?, Any?>?): ByteArray = throw AssertionError("not used")
        override fun close() = Unit
        override fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply? = null
        override fun getInitState(): Int = state
    }

    // --- client calls that block before returning a Task ---

    private class BlockingClient(
        private val gate: CountDownLatch,
        private val onInit: () -> Task<DroidGuardHandle> = { Tasks.forResult(CountingHandle()) },
        private val onResults: () -> Task<String> = { Tasks.forResult(syntheticGuardResult("late")) },
    ) : DroidGuardClient {
        val interrupts = AtomicInteger()
        val calls = AtomicInteger()
        val handles: MutableList<CountingHandle> = Collections.synchronizedList(ArrayList())
        @Volatile var blocked = false
        @Volatile var returned = false

        private fun <T> block(result: () -> T): T {
            calls.incrementAndGet()
            blocked = true
            while (true) {
                try {
                    gate.await()
                    break
                } catch (e: InterruptedException) {
                    interrupts.incrementAndGet()
                }
            }
            return result().also { returned = true }
        }

        override fun init(flow: String, request: DroidGuardResultsRequest?): Task<DroidGuardHandle> = block {
            onInit().also { task -> (task.result as? CountingHandle)?.let { handles += it } }
        }

        override fun getResults(flow: String, data: Map<String, String>?, request: DroidGuardResultsRequest?): Task<String> =
            block(onResults)
    }

    private class CountingHandle : DroidGuardHandle {
        val closeCount = AtomicInteger()
        override fun snapshot(data: Map<String, String>): String = syntheticGuardResult("snapshot")
        override fun isOpened(): Boolean = true
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    @Test
    fun initBlockedBeforeItsTaskTimesOutPromptlyAndItsLateHandleIsClosedOnce() {
        val gate = gate()
        val client = BlockingClient(gate)
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ client }, { true }, workers()), scope, timeoutMillis = 200)

        val callbacks = RecordingCallbacks()
        val started = System.nanoTime()
        session.init(callbacks)
        val reply = callbacks.next(2_000) as Reply.Init
        assertTrue("answered after ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 2_000_000_000L)
        assertEquals(CommonStatusCodes.TIMEOUT, reply.status.statusCode)
        assertNull(reply.result)
        // The worker is still inside init: nothing had returned when the request was answered.
        assertTrue(client.blocked)
        assertFalse(client.returned)

        gate.countDown()
        awaitTrue("late handle") { client.handles.size == 1 && client.handles[0].closeCount.get() == 1 }
        Thread.sleep(100)
        assertEquals(1, client.handles.single().closeCount.get())
        assertFalse(session.hasInitializedHandle)
        assertNoFurtherCallback(callbacks)
    }

    @Test
    fun resultsBlockedBeforeItsTaskTimesOutPromptlyAndNeverSucceedsLater() {
        val gate = gate()
        val client = BlockingClient(gate)
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ client }, { true }, workers()), scope, timeoutMillis = 200)

        val callbacks = RecordingCallbacks()
        session.execute(callbacks, "synthetic-nonce", "synthetic-id")
        val reply = callbacks.next(2_000) as Reply.Execute
        assertEquals(CommonStatusCodes.TIMEOUT, reply.status.statusCode)
        assertNull(reply.result)
        assertFalse(client.returned)

        gate.countDown()
        awaitTrue("late Task") { client.returned }
        assertNoFurtherCallback(callbacks)
    }

    @Test
    fun closeWhileInitIsBlockedAnswersAtOnceAndClosesTheLateHandleOnce() {
        val gate = gate()
        val client = BlockingClient(gate)
        val session = RecaptchaBaseSession("com.example.app", DroidGuardRecaptchaBaseGuard({ client }, { true }, workers()), scope)

        val initCallbacks = RecordingCallbacks()
        val executeCallbacks = RecordingCallbacks()
        session.init(initCallbacks)
        awaitTrue("init blocked") { client.blocked }
        session.execute(executeCallbacks, "synthetic-nonce", "synthetic-id")
        session.close()

        val init = initCallbacks.next(1_000) as Reply.Init
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, init.status.statusCode)
        assertNull(init.result)
        val execute = executeCallbacks.next(1_000) as Reply.Execute
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, execute.status.statusCode)
        assertNull(execute.result)
        assertFalse(client.returned)

        gate.countDown()
        awaitTrue("late handle") { client.handles.size == 1 && client.handles[0].closeCount.get() == 1 }
        Thread.sleep(100)
        assertEquals(1, client.handles.single().closeCount.get())
        assertFalse(session.hasInitializedHandle)
        assertNoFurtherCallback(initCallbacks)
        assertNoFurtherCallback(executeCallbacks)
    }

    @Test
    fun blockedWorkersBoundTheWorkAndWithdrawnCallsNeverRun() {
        val gate = gate()
        val client = BlockingClient(gate)
        // One worker and one queue slot: the first init holds the worker, the second waits, the third is refused.
        val executor = workers(threads = 1, queue = 1)
        val guard = DroidGuardRecaptchaBaseGuard({ client }, { true }, executor)
        val session = RecaptchaBaseSession("com.example.app", guard, scope, timeoutMillis = 300)

        val first = RecordingCallbacks()
        session.init(first)
        awaitTrue("first init blocked") { client.blocked }
        val second = RecordingCallbacks()
        session.execute(second, "synthetic-nonce", "synthetic-id")
        awaitTrue("second queued") { executor.queue.size == 1 }
        val third = RecordingCallbacks()
        val started = System.nanoTime()
        session.execute(third, "synthetic-nonce", "synthetic-id")
        val refused = third.next(1_000) as Reply.Execute
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, refused.status.statusCode)
        assertTrue(System.nanoTime() - started < 250_000_000L)

        assertEquals(CommonStatusCodes.TIMEOUT, (first.next(2_000) as Reply.Init).status.statusCode)
        assertEquals(CommonStatusCodes.TIMEOUT, (second.next(2_000) as Reply.Execute).status.statusCode)
        // The timed-out queued call was withdrawn rather than left holding the queue slot.
        awaitTrue("queue withdrawn") { executor.queue.isEmpty() }
        assertEquals(1, executor.poolSize)

        gate.countDown()
        awaitTrue("late handle") { client.handles.size == 1 && client.handles[0].closeCount.get() == 1 }
        Thread.sleep(100)
        // Only the first init reached DroidGuard; the withdrawn request never ran.
        assertEquals(1, client.calls.get())
        listOf(first, second, third).forEach { assertNoFurtherCallback(it) }
    }

    @Test
    fun sharedWorkersAreBounded() {
        val shared = DroidGuardRecaptchaBaseGuard.SHARED_WORKERS as ThreadPoolExecutor
        assertEquals(WORKER_THREADS, shared.maximumPoolSize)
        assertEquals(WORKER_QUEUE, shared.queue.remainingCapacity() + shared.queue.size)
    }

    private companion object {
        // IDroidGuardHandle transaction codes: FIRST_CALL_TRANSACTION + the AIDL id.
        const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val INIT_WITH_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 4
        const val GET_INIT_STATE = IBinder.FIRST_CALL_TRANSACTION + 100
    }
}
