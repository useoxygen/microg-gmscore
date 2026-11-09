/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Intent
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.IGmsServiceBroker
import com.google.android.gms.recaptchabase.ExecuteRequest
import com.google.android.gms.recaptchabase.InitRequest
import com.google.android.gms.recaptchabase.internal.IRecaptchaBaseService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.GmsService
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val GMS_CALLBACKS_DESCRIPTOR = "com.google.android.gms.common.internal.IGmsCallbacks"

/** IGmsCallbacks.onPostInitCompleteWithConnectionInfo: the third method, FIRST_CALL_TRANSACTION + 2. */
private const val TRANSACTION_POST_INIT_WITH_CONNECTION_INFO = IBinder.FIRST_CALL_TRANSACTION + 2

/**
 * Session lifetime through the real broker: each client's session lives as long as the client process that asked for
 * it, and is retired once when that process dies or the broker could not deliver it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseServiceLifetimeTest {
    private val guard = FakeGuard()
    private lateinit var controller: ServiceController<RecaptchaBaseService>
    private lateinit var service: RecaptchaBaseService
    private lateinit var broker: IGmsServiceBroker
    private var destroyed = false

    @Before
    fun setUp() {
        controller = Robolectric.buildService(RecaptchaBaseService::class.java).create()
        service = controller.get()
        service.guardFactory = { guard }
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), "com.example.app")
        broker = IGmsServiceBroker.Stub.asInterface(service.onBind(Intent(GmsService.RECAPTCHABASE.ACTION)))
    }

    @After
    fun tearDown() {
        if (!destroyed) controller.destroy()
    }

    private fun destroyService() {
        destroyed = true
        controller.destroy()
    }

    /**
     * The broker callback binder of a client in another process, as the service sees it: it arrives wrapped in an
     * IGmsCallbacks proxy, and the kernel's death notification is modelled by [die]. Like a BinderProxy, a dead binder
     * refuses links and transactions, and each linked recipient hears of the death once. It is not a local [Binder], so
     * nothing short-circuits the service's link to it.
     */
    private class ClientProcess : IBinder {
        private val recipients = ArrayList<IBinder.DeathRecipient>()
        private var dead = false

        /** Makes delivery to this live client fail, as when its broker callback throws back across Binder. */
        @Volatile
        var failDelivery = false
        val connections = LinkedBlockingQueue<IBinder>()

        val linkedRecipients: Int get() = synchronized(this) { recipients.size }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = synchronized(this) {
            if (dead) throw DeadObjectException()
            recipients += recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = synchronized(this) {
            when {
                recipients.remove(recipient) -> true
                dead -> false
                else -> throw NoSuchElementException("not linked")
            }
        }

        override fun isBinderAlive(): Boolean = synchronized(this) { !dead }
        override fun pingBinder(): Boolean = isBinderAlive
        override fun getInterfaceDescriptor(): String = GMS_CALLBACKS_DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun dump(fd: FileDescriptor, args: Array<out String>?) = Unit
        override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = Unit

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!isBinderAlive || failDelivery) throw DeadObjectException()
            if (code != TRANSACTION_POST_INIT_WITH_CONNECTION_INFO) return false
            data.setDataPosition(0)
            data.enforceInterface(GMS_CALLBACKS_DESCRIPTOR)
            assertEquals(CommonStatusCodes.SUCCESS, data.readInt())
            connections += data.readStrongBinder()
            reply?.writeNoException()
            reply?.setDataPosition(0)
            return true
        }

        /**
         * Kills the process: later links and transactions fail, and each recipient linked at that moment is told
         * [deliveries] times (more than once only to model a racing duplicate), each on its own binder thread.
         */
        fun die(deliveries: Int = 1): List<Thread> {
            val notified = synchronized(this) {
                dead = true
                recipients.toList().also { recipients.clear() }
            }
            return (1..deliveries).map {
                Thread({ notified.forEach { it.binderDied() } }, "binder-death").apply { start() }
            }
        }

        fun dieAndWait(deliveries: Int = 1) = die(deliveries).forEach { it.join(5_000) }
    }

    /** One client: connects through the broker over its own callback binder, as the SDK does with getService. */
    private inner class Client {
        val process = ClientProcess()
        val callbacks = RecordingCallbacks()
        lateinit var connection: IRecaptchaBaseService

        fun connect(): Client {
            broker.getService(IGmsCallbacks.Stub.asInterface(process), GetServiceRequest(380).apply { packageName = "com.example.app" })
            connection = IRecaptchaBaseService.Stub.asInterface(process.connections.poll(5, TimeUnit.SECONDS)!!)
            return this
        }

        fun init() = connection.init(callbacks, InitRequest())
        fun execute(nonce: String) = connection.execute(callbacks, ExecuteRequest(nonce, "synthetic-id"))
    }

    /** Waits for [callbacks]' next reply, running the service's main-thread work meanwhile. */
    private fun nextReply(callbacks: RecordingCallbacks): Reply {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            callbacks.replies.poll(10, TimeUnit.MILLISECONDS)?.let { return it }
        }
        throw AssertionError("no reply")
    }

    private fun initialized(): Pair<Client, FakeHandle> {
        val client = Client().connect()
        client.init()
        assertEquals(CommonStatusCodes.SUCCESS, (nextReply(client.callbacks) as Reply.Init).status.statusCode)
        return client to guard.handles.last()
    }

    private fun awaitClosed(handle: FakeHandle) {
        val deadline = System.currentTimeMillis() + 5_000
        while (handle.closeCount.get() == 0) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("handle not closed")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Test
    fun clientDeathRetiresOnlyThatClientsSession() {
        val (a, handleA) = initialized()
        val (b, handleB) = initialized()
        assertEquals(2, service.openSessionCount)
        assertEquals(1, a.process.linkedRecipients)

        a.process.dieAndWait()

        assertEquals(1, handleA.closeCount.get())
        assertEquals(0, handleB.closeCount.get())
        assertEquals(1, service.openSessionCount)
        // A's session refuses work without DroidGuard; a request reaching it later is answered with a failure.
        a.execute("synthetic-nonce-a")
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, (nextReply(a.callbacks) as Reply.Execute).status.statusCode)
        assertTrue(guard.results.isEmpty())
        assertTrue(handleA.inputs.isEmpty())

        // B still owns its initialized handle: its execute snapshots it.
        b.execute("synthetic-nonce-b")
        val execute = nextReply(b.callbacks) as Reply.Execute
        assertEquals(CommonStatusCodes.SUCCESS, execute.status.statusCode)
        assertEquals(listOf(mapOf("token" to "synthetic-nonce-b")), handleB.inputs)
        assertEquals(1, b.process.linkedRecipients)
    }

    @Test
    fun brokerDeliveryFailureRetiresTheNewSession() {
        val (live, liveHandle) = initialized()
        val client = Client()
        client.process.failDelivery = true
        try {
            broker.getService(IGmsCallbacks.Stub.asInterface(client.process), GetServiceRequest(380).apply { packageName = "com.example.app" })
            fail("delivery failure was not reported to the broker caller")
        } catch (expected: DeadObjectException) {
        }
        // The undelivered session is gone, and the still-live client binder no longer holds a recipient for it.
        assertEquals(1, service.openSessionCount)
        assertEquals(0, client.process.linkedRecipients)
        assertEquals(0, liveHandle.closeCount.get())
        assertEquals(1, live.process.linkedRecipients)
    }

    @Test
    fun deathWhileInitRunsClosesTheLateHandleAndAnswersOnce() {
        val (other, otherHandle) = initialized()
        val pendingOpen = AtomicReference<Continuation<Unit>>()
        val opening = CountDownLatch(1)
        guard.openBehavior = {
            // Ignores cancellation: the handle is returned after its client has died.
            suspendCoroutine<Unit> { pendingOpen.set(it); opening.countDown() }
            FakeHandle { syntheticGuardResult("late") }.also { guard.handles += it }
        }
        val client = Client().connect()
        client.init()
        opening.awaitOrFail()

        client.process.dieAndWait()
        val init = nextReply(client.callbacks) as Reply.Init
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, init.status.statusCode)
        assertNull(init.result)
        assertEquals(1, service.openSessionCount)

        pendingOpen.get().resume(Unit)
        val late = guard.handles.last()
        awaitClosed(late)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, late.closeCount.get())
        // No second answer, and no success, after the close.
        assertEquals(1, client.callbacks.count.get())
        assertEquals(0, otherHandle.closeCount.get())
        other.execute("synthetic-nonce")
        assertEquals(CommonStatusCodes.SUCCESS, (nextReply(other.callbacks) as Reply.Execute).status.statusCode)
    }

    @Test
    fun repeatedDeadClientsDoNotGrowTheRegistry() {
        val (_, liveHandle) = initialized()
        val handles = (1..40).map {
            val (client, handle) = initialized()
            client.process.dieAndWait()
            handle
        }
        // A client that is already dead when the broker links to it is retired as well.
        val dead = ClientProcess().apply { dieAndWait() }
        try {
            broker.getService(IGmsCallbacks.Stub.asInterface(dead), GetServiceRequest(380).apply { packageName = "com.example.app" })
            fail("delivery to a dead client succeeded")
        } catch (expected: DeadObjectException) {
        }

        assertEquals(1, service.openSessionCount)
        handles.forEach { assertEquals(1, it.closeCount.get()) }
        assertEquals(0, liveHandle.closeCount.get())
    }

    @Test
    fun destroyClosesSurvivingSessionsAndUnlinksTheirClients() {
        val (a, handleA) = initialized()
        val (b, handleB) = initialized()
        a.process.dieAndWait()
        // A was retired when it died, not left for destroy.
        assertEquals(1, handleA.closeCount.get())
        assertEquals(1, service.openSessionCount)

        destroyService()

        assertEquals(1, handleA.closeCount.get())
        assertEquals(1, handleB.closeCount.get())
        assertEquals(0, service.openSessionCount)
        assertEquals(0, b.process.linkedRecipients)
        // A death after destroy finds nothing to close.
        b.process.dieAndWait()
        assertEquals(1, handleB.closeCount.get())
        b.execute("synthetic-nonce")
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, (nextReply(b.callbacks) as Reply.Execute).status.statusCode)
    }

    @Test
    fun racingDeathNotificationsAndDestroyCloseEachSessionOnce() {
        val clients = (1..20).map { initialized() }
        val threads = clients.take(15).flatMap { (client, _) -> client.process.die(deliveries = 3) }
        destroyService()
        threads.forEach { it.join(5_000) }

        clients.forEach { (client, handle) ->
            assertEquals(1, handle.closeCount.get())
            assertEquals(0, client.process.linkedRecipients)
            assertEquals(1, client.callbacks.count.get())
        }
        assertEquals(0, service.openSessionCount)
    }
}
