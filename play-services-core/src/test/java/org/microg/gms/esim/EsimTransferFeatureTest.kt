/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.esim

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.IGmsServiceBroker
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter as Writer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** CYC-1693: SIM Manager's esim client needs esim_transfer v1; once advertised, its calls must get honest answers. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EsimTransferFeatureTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private class Connection(val status: Int, val binder: IBinder?, val info: ConnectionInfo?)

    private fun connect(service: Int): Connection {
        val controller = Robolectric.buildService(EsimTransferService::class.java).create()
        try {
            val broker = IGmsServiceBroker.Stub.asInterface(controller.get().onBind(Intent("com.google.android.gms.esim.service.START")))
            val result = AtomicReference<Connection>()
            broker.getService(object : IGmsCallbacks.Stub() {
                override fun onPostInitComplete(status: Int, binder: IBinder?, params: Bundle?) { result.set(Connection(status, binder, null)) }
                override fun onAccountValidationComplete(status: Int, params: Bundle?) = fail("Unexpected account validation")
                override fun onPostInitCompleteWithConnectionInfo(status: Int, binder: IBinder?, info: ConnectionInfo?) { result.set(Connection(status, binder, info)) }
            }, GetServiceRequest(service).apply { packageName = context.packageName })
            return requireNotNull(result.get()) { "No connection callback" }
        } finally { controller.destroy() }
    }

    @Test fun esimBrokerAdvertisesTheFeatureSimManagerRequires() {
        val connection = connect(333)
        assertEquals(ConnectionResult.SUCCESS, connection.status)
        assertEquals(EsimTransferBinder.DESCRIPTOR, connection.binder!!.interfaceDescriptor)
        val features = requireNotNull(connection.info?.features).associate { it.name to it.version }
        // Google's client compares each required Feature by name and requires available version >= required.
        assertEquals(mapOf("esim_transfer" to 1L), features)
    }

    @Test fun provisioningBrokerStaysUnavailableWithoutFeatures() {
        val connection = connect(508)
        assertEquals(ConnectionResult.API_UNAVAILABLE, connection.status)
        assertNull(connection.binder); assertNull(connection.info)
    }

    private fun callback(name: String, done: CountDownLatch, failure: AtomicReference<Throwable>, check: (Parcel) -> Unit) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                data.enforceInterface(EsimTransferBinder.PREFIX + name)
                assertEquals(1, code); assertEquals(FLAG_ONEWAY, flags)
                check(data); assertEquals(0, data.dataAvail())
            } catch (e: Throwable) { failure.set(e) } finally { done.countDown() }
            return true
        }
    }

    private fun availability(euiccEnabled: Boolean, transferEnabled: Boolean = EsimTransferBinder.TRANSFER_ENABLED): Int {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.telephony.euicc.EuiccManager::class.java)).setIsEnabled(euiccEnabled)
        val required = mutableListOf<Set<String>>()
        val binder = EsimTransferBinder(context, Binder.getCallingUid(), permission = { required += it }, transferEnabled = transferEnabled)
        val done = CountDownLatch(1); val failure = AtomicReference<Throwable>()
        val answer = AtomicReference<Int>()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(EsimTransferBinder.DESCRIPTOR)
            data.writeStrongBinder(callback("IIsEsimTransferAvailableCallbacks", done, failure) {
                assertEquals(1, it.readInt()); assertTrue(Status.CREATOR.createFromParcel(it).isSuccess)
                answer.set(it.readInt())
            })
            data.writeInt(2) // exactly what SIM Manager D.2.1 writes
            assertTrue(binder.transact(12, data, reply, 0)); reply.readException(); assertEquals(0, reply.dataAvail())
            assertTrue(done.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            assertEquals(listOf(setOf("android.permission.WRITE_EMBEDDED_SUBSCRIPTIONS")), required)
            return answer.get()
        } finally { data.recycle(); reply.recycle(); binder.close() }
    }

    /**
     * SimMissingActivity/SUW call isEsimTransferAvailable (tx12, sessionType 2) to cache gmsCoreSupportsXosTransfer,
     * which gates SIM Manager's iPhone-transfer UI. While CYC-718 is blocked the answer is false even with an eUICC.
     */
    @Test fun availabilityQueryFromAddSimAnswersNoTransferWhileTransferIsBlocked() {
        assertEquals(0, availability(euiccEnabled = true))
        assertEquals(0, availability(euiccEnabled = false))
        // The answer still reflects the real eUICC once transfer is turned on.
        assertEquals(1, availability(euiccEnabled = true, transferEnabled = true))
        assertEquals(0, availability(euiccEnabled = false, transferEnabled = true))
    }

    /** EuiccServiceImpl backs up every installed activation code (and removes it on delete) via tx4. */
    @Test fun activationCodeSyncAfterInstallFailsFastAsUnsupportedAndNeedsWrite() {
        for (type in listOf(1, 2)) {
            val required = mutableListOf<Set<String>>()
            val binder = EsimTransferBinder(context, Binder.getCallingUid(), permission = { required += it })
            val done = CountDownLatch(1); val failure = AtomicReference<Throwable>()
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(EsimTransferBinder.DESCRIPTOR)
                data.writeStrongBinder(callback("ISyncProfileCredentialCallbacks", done, failure) {
                    assertEquals(1, it.readInt()); assertEquals(17, Status.CREATOR.createFromParcel(it).statusCode)
                })
                data.writeInt(1)
                EsimWireRecord.build {
                    Writer.write(it, 1, type); Writer.write(it, 2, "8901234567890123456", false)
                    Writer.write(it, 3, if (type == 1) "LPA:1\$smdp.example.invalid\$synthetic" else "", false)
                }.use { it.writeToParcel(data, 0) }
                assertTrue(binder.transact(4, data, reply, 0)); reply.readException()
                assertTrue(done.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
                assertEquals(listOf(setOf("android.permission.WRITE_EMBEDDED_SUBSCRIPTIONS")), required)
            } finally { data.recycle(); reply.recycle(); binder.close() }
        }
    }

    /** SUW post-flow starts a target session (tx10, connector 1); while blocked it fails at once and SIM Manager falls back to download. */
    @Test fun suwTransferStartFailsAtOnceWhileBlockedAndReturnsCancellationSignal() {
        val binder = EsimTransferBinder(context, Binder.getCallingUid(), permission = {})
        val done = CountDownLatch(1); val failure = AtomicReference<Throwable>()
        val listener = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                failure.set(AssertionError("No progress event may be invented")); return true
            }
        }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(EsimTransferBinder.DESCRIPTOR)
            data.writeStrongBinder(callback("IStartTransferSessionCallbacks", done, failure) {
                assertEquals(1, it.readInt()); assertEquals(EsimTransferBinder.TRANSFER_BLOCKED, Status.CREATOR.createFromParcel(it).statusCode)
            })
            data.writeInt(1)
            EsimWireRecord.build { Writer.write(it, 1, 1); Writer.write(it, 2, 0) }.use { it.writeToParcel(data, 0) }
            data.writeStrongBinder(listener)
            assertTrue(binder.transact(10, data, reply, 0)); reply.readException()
            assertEquals(EsimTransferBinder.PREFIX + "IEsimTransferCancellationSignal", reply.readStrongBinder().interfaceDescriptor)
            assertTrue(done.await(5, TimeUnit.SECONDS)); failure.get()?.let { throw it }
        } finally { data.recycle(); reply.recycle(); binder.close() }
    }
}
