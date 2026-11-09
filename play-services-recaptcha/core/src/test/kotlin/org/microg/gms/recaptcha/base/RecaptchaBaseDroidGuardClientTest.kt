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
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.internal.ConnectionCallbacks
import com.google.android.gms.common.api.internal.OnConnectionFailedListener
import com.google.android.gms.droidguard.DroidGuardClient
import com.google.android.gms.droidguard.DroidGuardHandle
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardCallbacks
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import com.google.android.gms.droidguard.internal.IDroidGuardService
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.DroidGuardApiClient
import org.microg.gms.droidguard.DroidGuardResponseFrame
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.microg.gms.droidguard.DroidGuardHandleImpl as ClientHandle

/**
 * microG's real [DroidGuardApiClient.openHandle] against a service handle reached through a Binder proxy, so each reply
 * crosses a Parcel as it does between processes. These checks cover reCAPTCHA session integration and flow-specific
 * response framing. Generic init-reply and lifecycle regressions live in the DroidGuard core module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseDroidGuardClientTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executors = mutableListOf<ThreadPoolExecutor>()

    @After
    fun tearDown() {
        scope.cancel()
        executors.forEach { it.shutdownNow() }
    }

    private fun workers() =
        ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 1, TimeUnit.SECONDS, ArrayBlockingQueue(WORKER_QUEUE), ThreadPoolExecutor.AbortPolicy())
            .also { executors += it }

    /** Forwards Parcels to [target]; [unknownCodes] are refused as by a service built without those transactions. */
    private class RemoteHandle(private val target: IDroidGuardHandle.Stub, private val unknownCodes: Set<Int> = emptySet()) : Binder() {
        private val codes: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val proxy: IDroidGuardHandle = IDroidGuardHandle.Stub.asInterface(this)

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            codes += code
            if (code in unknownCodes) return false
            return target.transact(code, data, reply, flags)
        }

        fun count(code: Int) = synchronized(codes) { codes.count { it == code } }
    }

    /**
     * A service handle that starts one runtime per init call, as microG's service handle does (its legacy `init` is
     * `initWithRequest(flow, null)`), and snapshots whichever runtime is current.
     */
    private class RecordingServiceHandle(
        private val response: (Runtime?) -> ByteArray? = { "synthetic-dg-runtime-${it?.id}-${it?.caller}".toByteArray() },
        private val reply: () -> DroidGuardInitReply?,
    ) : IDroidGuardHandle.Stub() {
        data class Runtime(val id: Int, val flow: String?, val caller: String?, val legacy: Boolean)

        val runtimes: MutableList<Runtime> = Collections.synchronizedList(ArrayList())
        val snapshots: MutableList<Pair<Runtime?, Map<Any?, Any?>>> = Collections.synchronizedList(ArrayList())
        val closes = AtomicInteger()
        @Volatile private var current: Runtime? = null

        private fun start(flow: String?, request: DroidGuardResultsRequest?, legacy: Boolean) {
            val runtime = Runtime(runtimes.size + 1, flow, request?.bundle?.getString(KEY_THIRD_PARTY_CALLER), legacy)
            runtimes += runtime
            current = runtime
        }

        override fun init(flow: String?) = start(flow, null, legacy = true)

        override fun initWithRequest(flow: String?, request: DroidGuardResultsRequest?): DroidGuardInitReply? {
            start(flow, request, legacy = false)
            return reply()
        }

        override fun snapshot(map: MutableMap<Any?, Any?>?): ByteArray? {
            val runtime = current
            snapshots += runtime to HashMap(map.orEmpty())
            return response(runtime)
        }

        override fun close() {
            closes.incrementAndGet()
            current = null
        }

        override fun getInitState(): Int =
            if (current != null) ClientHandle.INIT_STATE_INITIALIZED else ClientHandle.INIT_STATE_NOT_INITIALIZED
    }

    private class HandleService(private val handle: IDroidGuardHandle) : IDroidGuardService.Stub() {
        override fun guard(callbacks: IDroidGuardCallbacks?, flow: String?, map: MutableMap<Any?, Any?>?) = throw AssertionError("not used")
        override fun guardWithRequest(callbacks: IDroidGuardCallbacks?, flow: String?, map: MutableMap<Any?, Any?>?, request: DroidGuardResultsRequest?) =
            throw AssertionError("not used")
        override fun getHandle(): IDroidGuardHandle = handle
        override fun getClientTimeoutMillis(): Int = 60_000
    }

    /** microG's DroidGuard client, already connected to [service]. */
    private inner class ConnectedApiClient(private val service: IDroidGuardService) : DroidGuardApiClient(
        context,
        object : ConnectionCallbacks {
            override fun onConnected(connectionHint: Bundle?) = Unit
            override fun onConnectionSuspended(cause: Int) = Unit
        },
        object : OnConnectionFailedListener {
            override fun onConnectionFailed(result: ConnectionResult) = Unit
        },
    ) {
        override fun getServiceInterface(): IDroidGuardService = service
    }

    private fun apiClient(remote: RemoteHandle) = ConnectedApiClient(HandleService(remote.proxy))

    /**
     * What DroidGuardClientImpl does once connected: it starts a framed flow's trace when called, then schedules
     * openHandle for init, and openHandle, snapshot, close for results.
     */
    private fun clientFor(api: DroidGuardApiClient) = object : DroidGuardClient {
        override fun init(flow: String, request: DroidGuardResultsRequest?): Task<DroidGuardHandle> =
            Tasks.forResult(api.openHandle(flow, request ?: DroidGuardResultsRequest(), DroidGuardResponseFrame.startTrace(flow)))

        override fun getResults(flow: String, data: Map<String, String>?, request: DroidGuardResultsRequest?): Task<String> {
            val handle = api.openHandle(flow, request ?: DroidGuardResultsRequest(), DroidGuardResponseFrame.startTrace(flow))
            return try {
                Tasks.forResult(handle.snapshot(data))
            } finally {
                handle.close()
            }
        }
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    /** One-shot results arrive through a Task listener on the main looper, which this test thread holds. */
    private fun nextIdlingMainLooper(callbacks: RecordingCallbacks): Reply.Execute {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            callbacks.replies.poll(5, TimeUnit.MILLISECONDS)?.let { return it as Reply.Execute }
        }
        throw AssertionError("no callback")
    }

    private fun syntheticFile(): ParcelFileDescriptor {
        val file = File.createTempFile("synthetic-dg", ".apk", context.cacheDir)
        file.writeText("synthetic")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // --- a present reply without supplemental action ---

    @Test
    fun emptyReplyKeepsOneRuntimeWithTheVerifiedCallerThroughSnapshotAndClose() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val remote = RemoteHandle(service)
        val session = RecaptchaBaseSession(CALLER, DroidGuardRecaptchaBaseGuard({ clientFor(apiClient(remote)) }, { true }, workers()), scope)

        val init = RecordingCallbacks()
        session.init(init)
        assertEquals(CommonStatusCodes.SUCCESS, init.nextInit().status.statusCode)
        assertTrue(session.hasInitializedHandle)
        // One modern init, no legacy init: the only runtime carries the caller option.
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        assertEquals(listOf(RecordingServiceHandle.Runtime(1, RECAPTCHA_BASE_FLOW, CALLER, legacy = false)), service.runtimes.toList())

        // The kept handle's snapshot reaches that runtime, then the handle is closed once.
        val execute = RecordingCallbacks()
        session.execute(execute, "synthetic-nonce", "synthetic-id")
        val kept = execute.nextExecute()
        assertEquals(CommonStatusCodes.SUCCESS, kept.status.statusCode)
        // The caller receives the service's bytes framed once by the client, with the init and snapshot trace.
        val keptFrame = decodeFrame(guardResponseOf(kept))
        assertArrayEquals("synthetic-dg-runtime-1-$CALLER".toByteArray(), keptFrame.response)
        assertEquals(COARSE_EVENTS, keptFrame.trace.map { it.type })
        assertEquals(16, keptFrame.clientUuid!!.size)
        awaitTrue("close") { service.closes.get() == 1 }
        val (snapshotRuntime, input) = service.snapshots.single()
        assertEquals(service.runtimes[0], snapshotRuntime)
        assertEquals("synthetic-nonce", input[KEY_TOKEN])

        // A one-shot result opens its own handle the same way.
        val oneShot = RecordingCallbacks()
        session.execute(oneShot, "synthetic-nonce-2", "synthetic-id-2")
        val oneShotReply = nextIdlingMainLooper(oneShot)
        assertEquals(CommonStatusCodes.SUCCESS, oneShotReply.status.statusCode)
        val oneShotFrame = decodeFrame(guardResponseOf(oneShotReply))
        assertArrayEquals("synthetic-dg-runtime-2-$CALLER".toByteArray(), oneShotFrame.response)
        assertEquals(COARSE_EVENTS, oneShotFrame.trace.map { it.type })
        // Both frames carry this client's one UUID.
        assertArrayEquals(keptFrame.clientUuid, oneShotFrame.clientUuid)
        awaitTrue("one-shot close") { service.closes.get() == 2 }
        assertEquals(2, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        assertEquals(RecordingServiceHandle.Runtime(2, RECAPTCHA_BASE_FLOW, CALLER, legacy = false), service.runtimes.last())
        assertEquals(service.runtimes[1], service.snapshots.last().first)
        assertEquals(2, service.runtimes.size)
        session.close()
    }

    // --- the client response frame (CYC-1544) ---

    private fun openFramed(service: RecordingServiceHandle): ClientHandle =
        apiClient(RemoteHandle(service)).openHandle(RECAPTCHA_BASE_FLOW, DroidGuardResultsRequest(), DroidGuardResponseFrame.startTrace(RECAPTCHA_BASE_FLOW)) as ClientHandle

    @Test
    fun retainedHandleFramesEachSnapshotOnceWithTheHeldDelay() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val handle = openFramed(service)
        Thread.sleep(HELD_MILLIS)
        val first = decodeFrame(decodeResult(handle.snapshot(mapOf(KEY_TOKEN to "synthetic-nonce"))))
        Thread.sleep(HELD_MILLIS)
        val second = decodeFrame(decodeResult(handle.snapshot(mapOf(KEY_TOKEN to "synthetic-nonce-2"))))
        handle.close()

        for (frame in listOf(first, second)) {
            // The service's bytes are kept unchanged and are not themselves framed again.
            assertArrayEquals("synthetic-dg-runtime-1-null".toByteArray(), frame.response)
            assertFalse(DroidGuardResponseFrame.isFramed(frame.response))
            // Each snapshot copies the init trace: four events, not an accumulating list.
            assertEquals(COARSE_EVENTS, frame.trace.map { it.type })
            assertEquals(null, frame.trace[0].elapsedMillis)
        }
        // The time the handle was held between init end and snapshot start is measured, and grows for a later snapshot.
        assertTrue("held ${first.trace[2].elapsedMillis}", first.trace[2].elapsedMillis!! >= HELD_MILLIS)
        assertTrue("held ${second.trace[2].elapsedMillis}", second.trace[2].elapsedMillis!! >= 2 * HELD_MILLIS)
        assertEquals(first.trace[1], second.trace[1])
        assertArrayEquals(first.clientUuid, second.clientUuid)
    }

    @Test
    fun oneShotResultIsFramedOnce() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val result = clientFor(apiClient(RemoteHandle(service))).getResults(RECAPTCHA_BASE_FLOW, mapOf(KEY_TOKEN to "synthetic-nonce"), null).result!!
        val frame = decodeFrame(decodeResult(result))
        assertArrayEquals("synthetic-dg-runtime-1-null".toByteArray(), frame.response)
        assertEquals(COARSE_EVENTS, frame.trace.map { it.type })
        RecaptchaBasePayloads.requireUsableGuardResult(result)
        awaitTrue("close") { service.closes.get() == 1 }
    }

    @Test
    fun otherFlowsKeepTheServiceBytes() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val client = clientFor(apiClient(RemoteHandle(service)))
        assertArrayEquals("synthetic-dg-runtime-1-null".toByteArray(), decodeResult(client.getResults("synthetic-other-flow", emptyMap(), null).result!!))
        val handle = client.init("synthetic-other-flow", null).result!!
        assertArrayEquals("synthetic-dg-runtime-2-null".toByteArray(), decodeResult(handle.snapshot(emptyMap())))
        handle.close()
    }

    @Test
    fun alreadyFramedServiceBytesPassThroughUnchanged() {
        val framed = DroidGuardResponseFrame.frame("synthetic-native".toByteArray(), org.microg.gms.droidguard.DroidGuardClientTrace.start(), null)
        val handle = openFramed(RecordingServiceHandle(response = { framed }) { DroidGuardInitReply(null, null) })
        assertArrayEquals(framed, decodeResult(handle.snapshot(emptyMap())))
        handle.close()
    }

    @Test
    fun failedSnapshotsStayUnframedAndAreRejected() {
        val failures = listOf<(Any?) -> ByteArray?>(
            { "ERROR : synthetic runtime failure".toByteArray() },
            { ByteArray(0) },
            { null },
            { throw IllegalStateException("synthetic") },
        )
        for (failure in failures) {
            val handle = openFramed(RecordingServiceHandle(response = failure) { DroidGuardInitReply(null, null) })
            val result = handle.snapshot(emptyMap())
            assertFalse(DroidGuardResponseFrame.isFramed(decodeResult(result)))
            try {
                RecaptchaBasePayloads.requireUsableGuardResult(result)
                fail("accepted a failed snapshot")
            } catch (expected: InvalidGuardResultException) {
            }
            handle.close()
        }
        // A handle whose initialization failed answers with its error marker, unframed.
        val failed = apiClient(RemoteHandle(RecordingServiceHandle { DroidGuardInitReply(syntheticFile(), Bundle()) }))
            .openHandle(RECAPTCHA_BASE_FLOW, DroidGuardResultsRequest(), DroidGuardResponseFrame.startTrace(RECAPTCHA_BASE_FLOW))
        val result = failed.snapshot(emptyMap())
        assertTrue(String(decodeResult(result)).startsWith("ERROR : "))
    }

    private fun decodeResult(result: String): ByteArray {
        // URL-safe, unpadded, unwrapped, as before.
        assertTrue(result, result.matches(Regex("^[A-Za-z0-9_-]*$")))
        return java.util.Base64.getUrlDecoder().decode(result)
    }

    /** The DroidGuard response inside an execute result's byte form: variable 1, signal responses 6, response 1, field 11. */
    private fun guardResponseOf(reply: Reply.Execute): ByteArray {
        val variable = parseProto(reply.result!!.payloadBytes!!).single(1).bytes!!
        val signals = parseProto(variable).single(6).bytes!!
        val response = parseProto(signals).single(1).bytes!!
        return decodeResult(parseProto(response).single(11).string)
    }

    private companion object {
        const val CALLER = "com.example.app"
        const val HELD_MILLIS = 60L
        val COARSE_EVENTS = listOf(1L, 12L, 13L, 14L)

        // IDroidGuardHandle transaction codes: FIRST_CALL_TRANSACTION + the AIDL id.
        const val INIT = IBinder.FIRST_CALL_TRANSACTION + 0
        const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val INIT_WITH_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 4
    }
}
