/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.GuardCallback
import org.microg.gms.droidguard.HandleProxy
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.microg.gms.droidguard.DroidGuardHandleImpl as ClientHandle

/** Exercises the real service handle with synthetic VMs, without a network or Google runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DroidGuardHandleLifetimeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val callback = GuardCallback(context, context.packageName)
    private val executor = Executors.newCachedThreadPool()
    private val factory = RecordingFactory()
    private val service = DroidGuardHandleImpl(context, context.packageName, factory, callback)

    @After
    fun tearDown() {
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        // close blocks before the first init by contract.
        if (factory.calls.isNotEmpty()) service.close()
    }

    private data class Creation(val packageName: String, val flow: String?, val callback: GuardCallback, val request: DroidGuardResultsRequest?)

    private inner class RecordingFactory : NetworkHandleProxyFactory(context) {
        val calls = Collections.synchronizedList(mutableListOf<Creation>())
        private val candidates = ArrayDeque<() -> HandleProxy>()

        fun enqueue(runtime: Runtime) = enqueue { HandleProxy(runtime, "synthetic-${runtime.id}") }
        fun enqueue(create: () -> HandleProxy) { candidates.add(create) }

        override fun createHandle(packageName: String, flow: String?, callback: GuardCallback, request: DroidGuardResultsRequest?): HandleProxy {
            calls += Creation(packageName, flow, callback, request)
            events += "create:$flow"
            return synchronized(candidates) { candidates.removeFirst() }.invoke()
        }
    }

    // These public methods deliberately match the reflection ABI used by HandleProxy and the service handle.
    class Runtime(
        val id: String,
        private val events: MutableList<String>,
        private val initialize: () -> Boolean = { true },
        private val reply: () -> Parcelable? = { null },
        private val snapshot: () -> Unit = {},
        private val closing: () -> Unit = {},
    ) {
        val closes = AtomicInteger()
        val snapshots = AtomicInteger()

        fun init(): Boolean { events += "init:$id"; return initialize() }
        fun rb(): Parcelable? { events += "reply:$id"; return reply() }
        fun ss(map: Map<Any?, Any?>): ByteArray {
            snapshots.incrementAndGet()
            snapshot()
            check(closes.get() == 0) { "snapshot used a closed runtime" }
            return "$id:${map["input"]}".toByteArray()
        }
        fun close() { closes.incrementAndGet(); events += "close:$id"; closing() }
    }

    private fun runtime(id: String) = Runtime(id, events).also { factory.enqueue(it) }
    private fun request(caller: String) = DroidGuardResultsRequest().apply {
        bundle.putString("thirdPartyCaller", caller)
        timeoutMillis = 12_345
        clientVersion = 123
        openHandles = 7
    }
    private fun snapshot() = service.snapshot(mutableMapOf<Any?, Any?>("input" to "synthetic"))
    private fun assertSnapshot(id: String) = assertArrayEquals("$id:synthetic".toByteArray(), snapshot())
    private fun assertEmpty(reply: DroidGuardInitReply) {
        assertNull(reply.pfd)
        assertNull(reply.`object`)
    }
    private fun assertFailed() {
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        assertEquals("ERROR : no fallback for recaptcha-frame", String(snapshot()))
    }

    @Test
    fun firstInitKeepsTheRequestedRuntimeAndClosesItOnlyOnce() {
        val runtime = runtime("first")
        val request = request("com.example.first")
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        assertEmpty(service.initWithRequest("first-flow", request))
        assertEquals(ClientHandle.INIT_STATE_INITIALIZED, service.getInitState())
        assertEquals(Creation(context.packageName, "first-flow", callback, request), factory.calls.single())
        assertEquals(12_345, request.timeoutMillis)
        assertEquals(123, request.clientVersion)
        assertEquals(7, request.openHandles)
        assertSnapshot("first")
        assertEquals(0, runtime.closes.get())
        service.close()
        service.close()
        assertEquals(1, runtime.closes.get())
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        assertTrue(String(snapshot()).startsWith("ERROR :"))
        assertEquals(1, runtime.snapshots.get())
    }

    @Test
    fun modernThenLegacyReplacesTheRuntimeWithTheLatestFlowAndNullRequest() {
        val first = runtime("modern")
        val second = runtime("legacy")
        val request = request("com.example.modern")
        service.initWithRequest("modern-flow", request)
        service.init("legacy-flow")
        assertSame(request, factory.calls[0].request)
        assertEquals(Creation(context.packageName, "legacy-flow", callback, null), factory.calls[1])
        assertEquals(listOf("create:modern-flow", "init:modern", "reply:modern", "close:modern", "create:legacy-flow", "init:legacy", "reply:legacy"), events.toList())
        assertSnapshot("legacy")
        assertEquals(1, first.closes.get())
        assertEquals(0, second.closes.get())
        service.close()
        assertEquals(1, second.closes.get())
    }

    @Test
    fun legacyThenModernReplacesTheRuntimeAndPassesTheExactCallerRequest() {
        val first = runtime("legacy")
        val second = runtime("modern")
        service.init("legacy-flow")
        val request = request("com.example.modern")
        assertEmpty(service.initWithRequest("modern-flow", request))
        assertNull(factory.calls[0].request)
        assertEquals(Creation(context.packageName, "modern-flow", callback, request), factory.calls[1])
        assertSnapshot("modern")
        assertEquals(1, first.closes.get())
        service.close()
        assertEquals(1, second.closes.get())
    }

    @Test
    fun explicitModernInitReplacesTheRequestEvenForTheSameFlow() {
        val first = runtime("first")
        val second = runtime("second")
        val firstRequest = request("com.example.first")
        val secondRequest = request("com.example.second")
        service.initWithRequest("same-flow", firstRequest)
        service.initWithRequest("same-flow", secondRequest)
        assertSame(firstRequest, factory.calls[0].request)
        assertSame(secondRequest, factory.calls[1].request)
        assertEquals("com.example.second", secondRequest.bundle.getString("thirdPartyCaller"))
        assertSnapshot("second")
        assertEquals(1, first.closes.get())
        service.close()
        assertEquals(1, second.closes.get())
    }

    @Test
    fun falseInitClosesTheFailedCandidateAndRetryClearsTheError() {
        val failed = Runtime("failed", events, initialize = { false }).also { factory.enqueue(it) }
        assertEmpty(service.initWithRequest("recaptcha-frame", request("com.example.failed")))
        assertFailed()
        assertEquals(1, failed.closes.get())
        assertEquals(0, failed.snapshots.get())
        val retry = runtime("retry")
        service.initWithRequest("retry-flow", request("com.example.retry"))
        assertEquals(ClientHandle.INIT_STATE_INITIALIZED, service.getInitState())
        assertSnapshot("retry")
        service.close()
        assertEquals(1, retry.closes.get())
        assertEquals(1, failed.closes.get())
    }

    @Test
    fun failedReplacementClosesBothRuntimesAndDoesNotUseTheSupersededOne() {
        val previous = runtime("previous")
        service.initWithRequest("previous-flow", null)
        val failed = Runtime("failed", events, initialize = { throw IllegalStateException("synthetic init failure") }).also { factory.enqueue(it) }
        assertEmpty(service.initWithRequest("recaptcha-frame", null))
        assertFailed()
        assertEquals(1, previous.closes.get())
        assertEquals(1, failed.closes.get())
        assertEquals(0, previous.snapshots.get())
        service.close()
        assertEquals(1, failed.closes.get())
    }

    @Test
    fun factoryFailureRetiresTheOldRuntimeAndAllowsALegacyRetry() {
        val previous = runtime("previous")
        service.init("previous-flow")
        factory.enqueue { throw IllegalStateException("synthetic factory failure") }
        assertEmpty(service.initWithRequest("recaptcha-frame", null))
        assertFailed()
        assertEquals(1, previous.closes.get())
        runtime("retry")
        service.init("retry-flow")
        assertEquals(ClientHandle.INIT_STATE_INITIALIZED, service.getInitState())
        assertSnapshot("retry")
        assertNull(factory.calls.last().request)
    }

    @Test
    fun replyFailureClosesTheCandidateAndAllowsAnotherInit() {
        val failed = Runtime("failed", events, reply = { throw IllegalStateException("synthetic reply failure") }).also { factory.enqueue(it) }
        assertEmpty(service.initWithRequest("recaptcha-frame", null))
        assertFailed()
        assertEquals(1, failed.closes.get())
        runtime("retry")
        service.initWithRequest("retry-flow", null)
        assertEquals(ClientHandle.INIT_STATE_INITIALIZED, service.getInitState())
        assertSnapshot("retry")
    }

    @Test
    fun missingSupplementalApkClosesTheCandidate() {
        val failed = Runtime("missing-apk", events, reply = { Bundle() }).also { factory.enqueue(it) }
        factory.getTheApkFile("synthetic-missing-apk").delete()
        assertEmpty(service.initWithRequest("recaptcha-frame", null))
        assertFailed()
        assertEquals(1, failed.closes.get())
        service.close()
        assertEquals(1, failed.closes.get())
    }

    @Test
    fun supplementalReplyKeepsTheRuntimeAndTransfersTheFileToTheCaller() {
        val data = Bundle().apply { putString("synthetic", "reply") }
        val runtime = Runtime("supplemental", events, reply = { data }).also { factory.enqueue(it) }
        val apk = factory.getTheApkFile("synthetic-supplemental")
        apk.parentFile!!.mkdirs()
        apk.writeText("synthetic apk bytes")
        val reply = service.initWithRequest("supplemental-flow", null)
        assertSame(data, reply.`object`)
        assertEquals(ClientHandle.INIT_STATE_INITIALIZED, service.getInitState())
        assertEquals(0, runtime.closes.get())
        service.close()
        assertEquals(1, runtime.closes.get())
        // The returned descriptor belongs to the caller, even after the service runtime is closed.
        assertEquals("synthetic apk bytes", ParcelFileDescriptor.AutoCloseInputStream(reply.pfd!!).bufferedReader().use { it.readText() })
    }

    @Test
    fun closeExceptionsDoNotPreventReplacementOrReplaceTheInitFailure() {
        val previous = Runtime("previous", events, closing = { throw IllegalStateException("synthetic close failure") }).also { factory.enqueue(it) }
        service.init("previous-flow")
        val failed = Runtime("failed", events, initialize = { false }, closing = { throw IllegalStateException("synthetic cleanup failure") }).also { factory.enqueue(it) }
        assertEmpty(service.initWithRequest("recaptcha-frame", null))
        assertFailed()
        assertEquals(1, previous.closes.get())
        assertEquals(1, failed.closes.get())
        runtime("retry")
        service.initWithRequest("retry-flow", null)
        assertSnapshot("retry")
        service.close()
        service.close()
        assertEquals(1, previous.closes.get())
        assertEquals(1, failed.closes.get())
    }

    private fun await(latch: CountDownLatch) = assertTrue("timed out waiting for synthetic runtime", latch.await(5, TimeUnit.SECONDS))
    private fun assertWaiting(future: Future<*>) {
        try {
            future.get(150, TimeUnit.MILLISECONDS)
            fail("operation completed while another operation still owned the runtime")
        } catch (_: TimeoutException) {
        }
    }

    private fun checkSnapshotOwnership(replace: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = Runtime("held", events, snapshot = { entered.countDown(); await(release) }).also { factory.enqueue(it) }
        service.init("held-flow")
        val snapshot = executor.submit<ByteArray> { snapshot() }
        try {
            await(entered)
            if (replace) runtime("replacement")
            val started = CountDownLatch(1)
            val operation = executor.submit {
                started.countDown()
                if (replace) service.init("replacement-flow") else service.close()
            }
            await(started)
            assertWaiting(operation)
            assertEquals(0, runtime.closes.get())
            release.countDown()
            assertArrayEquals("held:synthetic".toByteArray(), snapshot.get(5, TimeUnit.SECONDS))
            operation.get(5, TimeUnit.SECONDS)
            assertEquals(1, runtime.closes.get())
            if (replace) assertSnapshot("replacement")
        } finally {
            release.countDown()
        }
    }

    @Test fun replacementWaitsForAnInFlightSnapshot() = checkSnapshotOwnership(replace = true)
    @Test fun closeWaitsForAnInFlightSnapshot() = checkSnapshotOwnership(replace = false)

    private fun checkInitOwnership(replace: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = Runtime("initializing", events, reply = { entered.countDown(); await(release); null }).also { factory.enqueue(it) }
        val init = executor.submit<DroidGuardInitReply> { service.initWithRequest("initializing-flow", null) }
        try {
            await(entered)
            assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
            if (replace) runtime("replacement")
            val started = CountDownLatch(1)
            val operation = executor.submit {
                started.countDown()
                if (replace) service.init("replacement-flow") else service.close()
            }
            await(started)
            assertWaiting(operation)
            assertEquals(0, first.closes.get())
            release.countDown()
            assertEmpty(init.get(5, TimeUnit.SECONDS))
            operation.get(5, TimeUnit.SECONDS)
            assertEquals(1, first.closes.get())
            if (replace) assertSnapshot("replacement")
            else assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        } finally {
            release.countDown()
        }
    }

    @Test fun concurrentInitsDoNotPublishOrLeakTheSupersededCandidate() = checkInitOwnership(replace = true)
    @Test fun closeWaitsUntilInitHasFinishedPreparingItsReply() = checkInitOwnership(replace = false)
}
