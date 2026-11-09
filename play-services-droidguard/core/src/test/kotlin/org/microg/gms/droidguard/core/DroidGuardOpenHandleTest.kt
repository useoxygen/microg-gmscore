/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.droidguard.core

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.internal.ConnectionCallbacks
import com.google.android.gms.common.api.internal.OnConnectionFailedListener
import com.google.android.gms.droidguard.internal.DroidGuardInitReply
import com.google.android.gms.droidguard.internal.DroidGuardResultsRequest
import com.google.android.gms.droidguard.internal.IDroidGuardHandle
import com.google.android.gms.droidguard.internal.IDroidGuardCallbacks
import com.google.android.gms.droidguard.internal.IDroidGuardService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.droidguard.DroidGuardApiClient
import org.microg.gms.droidguard.GuardCallback
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.microg.gms.droidguard.DroidGuardHandleImpl as ClientHandle
import org.microg.gms.droidguard.core.DroidGuardHandleImpl as ServiceHandle

/**
 * microG's real [DroidGuardApiClient.openHandle] against a service handle reached through a Binder proxy, so each reply
 * crosses a Parcel as it does between processes (CYC-1506). A service answers a present `DroidGuardInitReply(null, null)`
 * when nothing more is needed; the client must keep that one runtime, and run the legacy one-way `init` only when the
 * service sent no reply at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DroidGuardOpenHandleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
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
    private class RecordingServiceHandle(private val reply: () -> DroidGuardInitReply?) : IDroidGuardHandle.Stub() {
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

        override fun snapshot(map: MutableMap<Any?, Any?>?): ByteArray {
            val runtime = current
            snapshots += runtime to HashMap(map.orEmpty())
            return "synthetic-dg-runtime-${runtime?.id}-${runtime?.caller}".toByteArray()
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

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun syntheticFile(): ParcelFileDescriptor {
        val file = File.createTempFile("synthetic-dg", ".apk", context.cacheDir)
        file.writeText("synthetic")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // --- a present reply without supplemental action ---

    @Test
    fun emptyReplyKeepsOneRuntimeWithTheCallerThroughSnapshotAndClose() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val remote = RemoteHandle(service)
        val api = apiClient(remote)
        val request = DroidGuardResultsRequest().apply { bundle.putString(KEY_THIRD_PARTY_CALLER, CALLER) }
        val handle = api.openHandle(FLOW, request) as ClientHandle

        assertTrue(handle.isInitialized)
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        assertEquals(listOf(RecordingServiceHandle.Runtime(1, FLOW, CALLER, legacy = false)), service.runtimes.toList())
        assertArrayEquals("synthetic-dg-runtime-1-$CALLER".toByteArray(),
            android.util.Base64.decode(handle.snapshot(mapOf(KEY_TOKEN to "synthetic-nonce")), android.util.Base64.DEFAULT))
        assertEquals(service.runtimes[0], service.snapshots.single().first)
        assertEquals("synthetic-nonce", service.snapshots.single().second[KEY_TOKEN])
        handle.close()
        awaitTrue("close") { service.closes.get() == 1 }

        // The one-shot client operation opens, snapshots and closes a separate runtime with the same options.
        val next = api.openHandle(FLOW, request) as ClientHandle
        next.snapshot(mapOf(KEY_TOKEN to "synthetic-nonce-2"))
        next.close()
        awaitTrue("second close") { service.closes.get() == 2 }
        assertEquals(2, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        assertEquals(RecordingServiceHandle.Runtime(2, FLOW, CALLER, legacy = false), service.runtimes.last())
        assertEquals(service.runtimes[1], service.snapshots.last().first)
    }

    @Test
    fun microgServiceHandleReplyIsNotFollowedByALegacyInit() {
        // microG's own service handle answers DroidGuardInitReply(null, null); here its runtime fails to start because
        // DroidGuard's settings provider is absent, which does not change the reply.
        val service = ServiceHandle(context, context.packageName, NetworkHandleProxyFactory(context), GuardCallback(context, context.packageName))
        val remote = RemoteHandle(service)
        val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest()) as ClientHandle
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        assertTrue(handle.isOpened)
        assertFalse(handle.isInitialized)
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, service.getInitState())
        assertEquals(1, remote.count(GET_INIT_STATE))
        handle.close()
        awaitTrue("close") { remote.count(CLOSE) == 1 }
    }

    @Test
    fun incompleteRepliesAreIgnoredWithoutALegacyInit() {
        val replies = listOf<() -> DroidGuardInitReply>(
            { DroidGuardInitReply(null, Bundle().apply { putString("h", "synthetic-vm") }) },
            { DroidGuardInitReply(syntheticFile(), null) },
        )
        for (reply in replies) {
            val service = RecordingServiceHandle(reply)
            val remote = RemoteHandle(service)
            val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest().apply {
                bundle.putString(KEY_THIRD_PARTY_CALLER, CALLER)
            }) as ClientHandle
            assertEquals(1, remote.count(INIT_WITH_REQUEST))
            assertEquals(0, remote.count(INIT))
            assertEquals(listOf(RecordingServiceHandle.Runtime(1, FLOW, CALLER, legacy = false)), service.runtimes.toList())
            assertTrue(handle.isInitialized)
            assertEquals(0, service.closes.get())
            handle.close()
            awaitTrue("close") { service.closes.get() == 1 }
        }
    }

    @Test
    fun supplementalReplyThatCannotBeHandledClosesTheServiceHandle() {
        // A file and a Bundle ask the client to run a VM itself; without its key the client cannot, and the handle fails.
        val service = RecordingServiceHandle { DroidGuardInitReply(syntheticFile(), Bundle()) }
        val remote = RemoteHandle(service)
        val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest()) as ClientHandle
        assertFalse(handle.isOpened)
        assertFalse(handle.isInitialized)
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(0, remote.count(INIT))
        // The runtime the service started is not left running.
        assertEquals(1, service.closes.get())
        assertEquals(0, remote.count(SNAPSHOT))
    }

    // --- no reply: services without initWithRequest, or answering null ---

    @Test
    fun serviceWithoutInitWithRequestFallsBackToTheLegacyInit() {
        val service = RecordingServiceHandle { throw AssertionError("not reached") }
        val remote = RemoteHandle(service, unknownCodes = setOf(INIT_WITH_REQUEST))
        val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest()) as ClientHandle
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(1, remote.count(INIT))
        assertEquals(listOf(RecordingServiceHandle.Runtime(1, FLOW, null, legacy = true)), service.runtimes.toList())
        assertTrue(handle.isOpened)

        handle.snapshot(mapOf(KEY_TOKEN to "synthetic-nonce"))
        assertEquals(service.runtimes[0], service.snapshots.single().first)
        handle.close()
        awaitTrue("close") { service.closes.get() == 1 }
    }

    @Test
    fun nullReplyFallsBackToTheLegacyInit() {
        // microG's network-mode handle answers null; its legacy init only records the flow again.
        val service = RecordingServiceHandle { null }
        val remote = RemoteHandle(service)
        val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest()) as ClientHandle
        assertEquals(1, remote.count(INIT_WITH_REQUEST))
        assertEquals(1, remote.count(INIT))
        assertTrue(service.runtimes.last().legacy)
        assertTrue(handle.isOpened)
        handle.close()
        awaitTrue("close") { service.closes.get() == 1 }
    }

    @Test
    fun olderServiceWithoutInitStateKeepsBinderLivenessFallback() {
        val service = RecordingServiceHandle { DroidGuardInitReply(null, null) }
        val remote = RemoteHandle(service, unknownCodes = setOf(GET_INIT_STATE))
        val handle = apiClient(remote).openHandle(FLOW, DroidGuardResultsRequest()) as ClientHandle
        assertTrue(handle.isOpened)
        assertTrue(handle.isInitialized)
        assertEquals(1, remote.count(GET_INIT_STATE))
        handle.close()
        awaitTrue("close") { service.closes.get() == 1 }
    }

    @Test
    fun serviceBeforeInitializationReportsNotInitialized() {
        val service = ServiceHandle(context, context.packageName, NetworkHandleProxyFactory(context), GuardCallback(context, context.packageName))
        val remote = RemoteHandle(service)
        assertEquals(ClientHandle.INIT_STATE_NOT_INITIALIZED, remote.proxy.getInitState())
    }

    private companion object {
        const val CALLER = "com.example.app"
        const val FLOW = "synthetic-lifecycle-test"
        const val KEY_THIRD_PARTY_CALLER = "thirdPartyCallerAppPackageName"
        const val KEY_TOKEN = "token"
        const val GET_INIT_STATE = IBinder.FIRST_CALL_TRANSACTION + 100

        // IDroidGuardHandle transaction codes: FIRST_CALL_TRANSACTION + the AIDL id.
        const val INIT = IBinder.FIRST_CALL_TRANSACTION + 0
        const val SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2
        const val INIT_WITH_REQUEST = IBinder.FIRST_CALL_TRANSACTION + 4
    }
}
