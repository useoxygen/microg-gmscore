/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.recaptcha.base

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.internal.IGmsServiceBroker
import com.google.android.gms.recaptchabase.ExecuteResult
import com.google.android.gms.recaptchabase.InitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.common.GmsService
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val SERVICE_DESCRIPTOR = "com.google.android.gms.recaptchabase.internal.IRecaptchaBaseService"
private const val CALLBACKS_DESCRIPTOR = "com.google.android.gms.recaptchabase.internal.IRecaptchaBaseCallbacks"
private const val SAFE_PARCEL_OBJECT = 20293
private const val LONG_HEADER = 0xFFFF0000.toInt()

/**
 * Binder and SafeParcel compatibility, checked against hand-built parcels in the layout a Play services client sends and
 * expects back, without going through the AIDL-generated proxy or the SafeParcel processor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RecaptchaBaseBinderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = FakeGuard()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A callback binder that is not an AIDL stub: it parses what the service sends by hand. */
    private class RawCallbacks : Binder() {
        class Call(val code: Int, val flags: Int, val status: Status, val result: Map<Int, Any?>?)

        val calls = LinkedBlockingQueue<Call>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..2) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACKS_DESCRIPTOR)
            val status = if (data.readInt() != 0) Status.CREATOR.createFromParcel(data) else throw AssertionError("null status")
            val result = if (data.readInt() != 0) readSafeParcel(data, code) else null
            calls += Call(code, flags, status, result)
            return true
        }

        fun next(): Call = calls.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no callback")
    }

    private fun writeSafeParcel(parcel: Parcel, fields: Parcel.() -> Unit) {
        parcel.writeInt(LONG_HEADER or SAFE_PARCEL_OBJECT)
        val sizePos = parcel.dataPosition()
        parcel.writeInt(0)
        val start = parcel.dataPosition()
        parcel.fields()
        val end = parcel.dataPosition()
        parcel.setDataPosition(sizePos)
        parcel.writeInt(end - start)
        parcel.setDataPosition(end)
    }

    private fun Parcel.writeStringField(id: Int, value: String) {
        writeInt(LONG_HEADER or id)
        val sizePos = dataPosition()
        writeInt(0)
        val start = dataPosition()
        writeString(value)
        val end = dataPosition()
        setDataPosition(sizePos)
        writeInt(end - start)
        setDataPosition(end)
    }

    private fun transact(binder: IBinder, code: Int, callbacks: IBinder, request: Parcel.() -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        data.writeInterfaceToken(SERVICE_DESCRIPTOR)
        data.writeStrongBinder(callbacks)
        data.request()
        assertTrue(binder.transact(code, data, reply, 0))
        reply.setDataPosition(0)
        data.recycle()
        return reply
    }

    private fun executeRequest(nonce: String, executeId: String): Parcel.() -> Unit = {
        writeInt(1)
        writeSafeParcel(this) {
            writeStringField(1, nonce)
            writeStringField(2, executeId)
        }
    }

    private val initRequest: Parcel.() -> Unit = {
        writeInt(1)
        writeSafeParcel(this) {}
    }

    @Test
    fun initThenExecuteOverRawBinderTransactions() {
        val service = RecaptchaBaseServiceImpl(RecaptchaBaseSession("com.example.app", guard, scope), Process.myUid())
        val callbacks = RawCallbacks()

        // Transaction 1: init(callbacks, InitRequest) is synchronous and returns no exception.
        transact(service, 1, callbacks, initRequest).apply { readException(); recycle() }
        val init = callbacks.next()
        assertEquals(1, init.code)
        assertEquals(IBinder.FLAG_ONEWAY, init.flags and IBinder.FLAG_ONEWAY)
        assertEquals(CommonStatusCodes.SUCCESS, init.status.statusCode)
        assertEquals(emptyMap<Int, Any?>(), init.result)

        // Transaction 2: execute(callbacks, ExecuteRequest{1: nonce, 2: executeId}).
        transact(service, 2, callbacks, executeRequest("synthetic-nonce", "synthetic-id")).apply { readException(); recycle() }
        val execute = callbacks.next()
        assertEquals(2, execute.code)
        assertEquals(IBinder.FLAG_ONEWAY, execute.flags and IBinder.FLAG_ONEWAY)
        assertEquals(CommonStatusCodes.SUCCESS, execute.status.statusCode)
        val result = execute.result!!
        assertEquals(setOf(1, 2), result.keys)
        val payload = result[1] as String
        val bytes = result[2] as ByteArray

        // Field 1 is base64 of the envelope; field 2 holds the same envelope with the byte-form variable.
        val outer = parseProto(Base64.getDecoder().decode(payload))
        assertEquals("synthetic-id", outer.single(2).string)
        assertEquals("synthetic-id", parseProto(bytes).single(2).string)
        assertEquals(listOf(mapOf("token" to "synthetic-nonce")), guard.handles.single().inputs)
    }

    @Test
    fun nullRequestAndMissingFieldsFailExplicitly() {
        val service = RecaptchaBaseServiceImpl(RecaptchaBaseSession("com.example.app", guard, scope), Process.myUid())
        val callbacks = RawCallbacks()
        transact(service, 2, callbacks) { writeInt(0) }.apply { readException(); recycle() }
        transact(service, 2, callbacks) { writeInt(1); writeSafeParcel(this) { writeStringField(2, "synthetic-id") } }
            .apply { readException(); recycle() }
        repeat(2) {
            val call = callbacks.next()
            assertEquals(2, call.code)
            assertEquals(CommonStatusCodes.DEVELOPER_ERROR, call.status.statusCode)
            assertEquals(null, call.result)
        }
        assertTrue(guard.results.isEmpty())
    }

    @Test
    fun anotherUidIsRefusedBeforeTheRequestIsUsed() {
        var callingUid = 10_001
        val service = RecaptchaBaseServiceImpl(RecaptchaBaseSession("com.example.app", guard, scope), 10_001) { callingUid }
        val callbacks = RawCallbacks()
        callingUid = 10_002
        for (code in 1..2) {
            val reply = transact(service, code, callbacks, if (code == 1) initRequest else executeRequest("synthetic-nonce", "synthetic-id"))
            try {
                reply.readException()
                fail("transaction $code from another UID was accepted")
            } catch (expected: SecurityException) {
            } finally {
                reply.recycle()
            }
        }
        assertTrue(guard.opens.isEmpty() && guard.results.isEmpty())
        assertTrue(callbacks.calls.isEmpty())

        callingUid = 10_001
        transact(service, 2, callbacks, executeRequest("synthetic-nonce", "synthetic-id")).apply { readException(); recycle() }
        assertEquals(CommonStatusCodes.SUCCESS, callbacks.next().status.statusCode)
        assertEquals(listOf("com.example.app"), guard.results.map { it.first })
    }

    @Test
    fun resultParcelsHaveSafeParcelHeadersAndFieldNumbers() {
        fun roundTrip(write: (Parcel) -> Unit, code: Int): Map<Int, Any?> {
            val parcel = Parcel.obtain()
            write(parcel)
            parcel.setDataPosition(0)
            return readSafeParcel(parcel, code).also { parcel.recycle() }
        }
        assertEquals(emptyMap<Int, Any?>(), roundTrip({ InitResult().writeToParcel(it, 0) }, 1))
        val fields = roundTrip({ ExecuteResult("cGF5bG9hZA==", byteArrayOf(1, 2, 3)).writeToParcel(it, 0) }, 2)
        assertEquals("cGF5bG9hZA==", fields[1])
        assertArrayEquals(byteArrayOf(1, 2, 3), fields[2] as ByteArray)

        // A 16.1.0-style result without field 2 still reads.
        val legacy = Parcel.obtain()
        writeSafeParcel(legacy) { writeStringField(1, "cGF5bG9hZA==") }
        legacy.setDataPosition(0)
        val parsed = ExecuteResult.CREATOR.createFromParcel(legacy)
        assertEquals("cGF5bG9hZA==", parsed.payload)
        assertEquals(null, parsed.payloadBytes)
        legacy.recycle()
    }

    @Test
    fun serviceIdRoutesToTheStartAction() {
        assertEquals(380, GmsService.RECAPTCHABASE.SERVICE_ID)
        assertEquals("com.google.android.gms.recaptchabase.service.START", GmsService.RECAPTCHABASE.ACTION)
        assertEquals(GmsService.RECAPTCHABASE, GmsService.byAction("com.google.android.gms.recaptchabase.service.START"))
        assertEquals(listOf("INIT" to 1L, "EXECUTE" to 1L), RECAPTCHA_BASE_FEATURES.map { it.name to it.version })
    }

    @Test
    fun brokerBindsTheVerifiedCallerAndFailsWhenDroidGuardIsUnavailable() {
        val controller = Robolectric.buildService(RecaptchaBaseService::class.java).create()
        val service = controller.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), "com.example.app")

        // A request naming a package the caller does not own is refused.
        try {
            service.handleServiceRequest(RecordingGmsCallbacks(), GetServiceRequest(380).apply { packageName = "com.example.other" }, GmsService.RECAPTCHABASE)
            fail("accepted a package that does not belong to the caller")
        } catch (expected: SecurityException) {
        }

        val gms = RecordingGmsCallbacks()
        service.handleServiceRequest(gms, GetServiceRequest(380).apply { packageName = "com.example.app" }, GmsService.RECAPTCHABASE)
        val connection = gms.calls.poll(5, TimeUnit.SECONDS)!!
        assertEquals(CommonStatusCodes.SUCCESS, connection.first)
        assertEquals(listOf("INIT" to 1L, "EXECUTE" to 1L), connection.third!!.features.map { it.name to it.version })
        assertEquals(SERVICE_DESCRIPTOR, connection.second!!.interfaceDescriptor)

        // The DroidGuard runtime is not enabled here: execute fails explicitly, with no payload.
        val callbacks = RawCallbacks()
        transact(connection.second!!, 2, callbacks, executeRequest("synthetic-nonce", "synthetic-id")).apply { readException(); recycle() }
        var call: RawCallbacks.Call? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (call == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            call = callbacks.calls.poll(20, TimeUnit.MILLISECONDS)
        }
        assertNotNull(call)
        assertEquals(2, call!!.code)
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, call.status.statusCode)
        assertEquals(null, call.result)
        controller.destroy()
    }

    /**
     * A remote client's getService and transactions arrive on a Binder pool thread, not the main thread. The broker must
     * not call main-thread-only Lifecycle APIs there (LifecycleRegistry.addObserver throws off the main thread).
     */
    @Test
    fun brokerServesAGetServiceCallArrivingOnABinderThread() {
        val controller = Robolectric.buildService(RecaptchaBaseService::class.java).create()
        val service = controller.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), "com.example.app")
        val broker = IGmsServiceBroker.Stub.asInterface(service.onBind(Intent(GmsService.RECAPTCHABASE.ACTION)))
        val gms = RecordingGmsCallbacks()
        val callbacks = RawCallbacks()
        val failure = AtomicReference<Throwable>()

        val binderThread = Thread({
            try {
                assertTrue(Looper.getMainLooper().thread !== Thread.currentThread())
                broker.getService(gms, GetServiceRequest(380).apply { packageName = "com.example.app" })
                val connection = gms.calls.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no connection")
                assertEquals(CommonStatusCodes.SUCCESS, connection.first)
                transact(connection.second!!, 2, callbacks, executeRequest("synthetic-nonce", "synthetic-id")).apply {
                    readException(); recycle()
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "binder-pool-test")
        binderThread.start()
        binderThread.join(10_000)
        failure.get()?.let { throw AssertionError("broker failed on a binder thread", it) }

        // The DroidGuard runtime is not enabled here: execute fails explicitly, with no payload.
        var call: RawCallbacks.Call? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (call == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            call = callbacks.calls.poll(20, TimeUnit.MILLISECONDS)
        }
        assertNotNull(call)
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, call!!.status.statusCode)
        assertEquals(null, call.result)
        controller.destroy()
    }

    /** Destroying the service closes every session it handed out; their later requests fail without DroidGuard work. */
    @Test
    fun serviceDestroyClosesItsSessions() {
        val controller = Robolectric.buildService(RecaptchaBaseService::class.java).create()
        val service = controller.get()
        shadowOf(service.packageManager).setPackagesForUid(Binder.getCallingUid(), "com.example.app")
        val gms = RecordingGmsCallbacks()
        service.handleServiceRequest(gms, GetServiceRequest(380).apply { packageName = "com.example.app" }, GmsService.RECAPTCHABASE)
        val binder = gms.calls.poll(5, TimeUnit.SECONDS)!!.second!!
        controller.destroy()

        val callbacks = RawCallbacks()
        transact(binder, 1, callbacks, initRequest).apply { readException(); recycle() }
        val call = callbacks.next()
        assertEquals(1, call.code)
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, call.status.statusCode)
        assertEquals(null, call.result)
    }

    private class RecordingGmsCallbacks : IGmsCallbacks.Stub() {
        val calls = LinkedBlockingQueue<Triple<Int, IBinder?, ConnectionInfo?>>()
        override fun onPostInitComplete(statusCode: Int, binder: IBinder?, params: Bundle?) {
            calls += Triple(statusCode, binder, null)
        }

        override fun onAccountValidationComplete(statusCode: Int, params: Bundle?) = Unit

        override fun onPostInitCompleteWithConnectionInfo(statusCode: Int, binder: IBinder?, info: ConnectionInfo?) {
            calls += Triple(statusCode, binder, info)
        }
    }
}

/**
 * Reads a SafeParcel object by hand. For InitResult (callback code 1) no field is expected; for ExecuteResult (code 2)
 * field 1 is a String and field 2 a byte array.
 */
private fun readSafeParcel(parcel: Parcel, callbackCode: Int): Map<Int, Any?> {
    val header = parcel.readInt()
    assertEquals(SAFE_PARCEL_OBJECT, header and 0xFFFF)
    val size = if (header and LONG_HEADER == LONG_HEADER) parcel.readInt() else header ushr 16
    val end = parcel.dataPosition() + size
    val fields = LinkedHashMap<Int, Any?>()
    while (parcel.dataPosition() < end) {
        val fieldHeader = parcel.readInt()
        val id = fieldHeader and 0xFFFF
        val fieldSize = if (fieldHeader and LONG_HEADER == LONG_HEADER) parcel.readInt() else fieldHeader ushr 16
        val start = parcel.dataPosition()
        fields[id] = when {
            callbackCode == 2 && id == 1 -> parcel.readString()
            callbackCode == 2 && id == 2 -> parcel.createByteArray()
            else -> throw AssertionError("unexpected field $id")
        }
        parcel.setDataPosition(start + fieldSize)
    }
    assertEquals(end, parcel.dataPosition())
    return fields
}
