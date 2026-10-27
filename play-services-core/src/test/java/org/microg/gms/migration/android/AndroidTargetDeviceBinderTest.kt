/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.DctRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AndroidTargetDeviceBinderTest {
    private fun call(binder: IBinder, code: Int, descriptor: String = TARGET_DESCRIPTOR, write: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor); data.write()
            assertTrue(binder.transact(code, data, reply, 0)); reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
    @Test fun managedAccountQueryReturnsTheRealEmptyPersistedSourceState() {
        var calls = 0
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(AndroidTargetDeviceBinder.TARGET_CALLBACK)
                assertEquals(7, code); assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt())
                val status = Status.CREATOR.createFromParcel(data)
                assertEquals(0, status.statusCode); assertNull(status.resolution)
                assertEquals(1, data.readInt()) // SUCCESS/null crashes Switch's ejn consumer.
                val end = SafeParcelReader.readObjectHeader(data)
                var mode: Int? = null
                while (data.dataPosition() < end) {
                    val header = SafeParcelReader.readHeader(data)
                    // Switch jvz fields1/3 are nullable strings, omitted by Google's writer.
                    assertEquals(2, header and 0xffff)
                    mode = SafeParcelReader.readInt(data, header)
                }
                assertEquals(end, data.dataPosition()); assertEquals(0, mode)
                data.requireEnd(); calls++; return true
            }
        }
        AndroidTargetDeviceBinder(Binder.getCallingUid()).use { target ->
            // Switch uses one-way target tx16 and prefixes SafeParcel ApiMetadata with magic.
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(TARGET_DESCRIPTOR); data.writeStrongBinder(callback)
                data.writeInt(1); data.writeInt(-204102970)
                val start = SafeParcelWriter.writeObjectHeader(data)
                SafeParcelWriter.write(data, 2, false)
                SafeParcelWriter.finishObjectHeader(data, start)
                assertTrue(target.transact(16, data, null, IBinder.FLAG_ONEWAY))
            } finally { data.recycle() }
        }
        assertEquals(1, calls)
    }

    @Test fun malformedManagedAccountQueryCannotEmitASuccessCallback() {
        var called = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                called = true; return true
            }
        }
        AndroidTargetDeviceBinder(Binder.getCallingUid()).use { target ->
            assertThrows(Exception::class.java) { call(target, 16) { writeStrongBinder(callback); writeInt(0); writeInt(77) } }
        }
        assertFalse(called)
    }
    @Test fun startServiceOnSourceFailsThroughTheConfirmedCallbackWithoutStartingAnything() {
        var called = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(PAIR_CALLBACK); assertEquals(8, code)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt()); assertEquals(10590, Status.CREATOR.createFromParcel(data).statusCode)
                assertEquals(1, data.readInt())
                val result = DctRecord.read(data, mapOf(1 to DctRecord.Kind.IntValue))
                assertEquals(0, result.int(1)); data.requireEnd(); called = true; return true
            }
        }
        call(EmptySetupStateBinder(Binder.getCallingUid(), false), 9, POST_DESCRIPTOR) {
            writeStrongBinder(callback); writeString("com.example.fixture"); writeString("synthetic.action")
            writeNullable(Bundle().apply { putString("synthetic", "fixture") }); writeInt(0)
        }
        assertTrue(called)
    }
    @Test fun targetPairCallsReturnExactNoSecretCallbacksInsteadOfUnimplementedTransaction() {
        listOf(13 to 1, 14 to 2, 15 to 4, 17 to 5, 22 to 7).forEach { (operation, event) ->
            var called = false
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface(PAIR_CALLBACK)
                    assertEquals(event, code); assertEquals(IBinder.FLAG_ONEWAY, flags)
                    assertEquals(1, data.readInt()); assertEquals(10590, Status.CREATOR.createFromParcel(data).statusCode)
                    if (operation != 22) {
                        assertEquals(1, data.readInt())
                        assertTrue(DctRecord.read(data, if (operation == 13) AndroidSetupSchemas.hint else
                            if (operation == 17) AndroidSetupSchemas.auth else AndroidSetupSchemas.handshake).fields.isEmpty())
                    }
                    data.requireEnd(); called = true; return true
                }
            }
            AndroidTargetDeviceBinder(Binder.getCallingUid()).use { target ->
                call(target, operation) {
                    writeStrongBinder(callback)
                    if (operation == 15 || operation == 17) writeInt(0)
                    writeInt(0)
                }
            }
            assertTrue(called)
        }
    }
    @Test fun directTargetAbortUsesItsOwnCallbackDescriptor() {
        var called = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(AndroidTargetDeviceBinder.DIRECT_CALLBACK)
                assertEquals(1, code); assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt()); assertEquals(10565, Status.CREATOR.createFromParcel(data).statusCode)
                data.requireEnd(); called = true; return true
            }
        }
        AndroidTargetDeviceBinder(Binder.getCallingUid(), direct = true).use { target ->
            call(target, 2, AndroidTargetDeviceBinder.DIRECT_TARGET_DESCRIPTOR) {
                writeStrongBinder(callback); writeInt(0)
            }
        }
        assertTrue(called)
    }

    @Test fun malformedRequestCannotClearOrAuthenticateState() {
        val state = AndroidPostSetupState(AndroidPostSetupState.VerifiedBootstrap("12345", ByteArray(32) { 7 }))
        AndroidTargetDeviceBinder(Binder.getCallingUid(), state).use { target ->
            assertThrows(Exception::class.java) { call(target, 23) { writeStrongBinder(Binder()); writeInt(0); writeInt(77) } }
            assertEquals("12345", state.hint()?.text(2))
        }
        assertNull(state.hint())
    }
    @Test fun anotherUidCannotUseTargetState() {
        AndroidTargetDeviceBinder(Binder.getCallingUid() + 1).use { target ->
            assertThrows(Exception::class.java) { call(target, 13) { writeStrongBinder(Binder()); writeInt(0) } }
        }
    }
    @Test fun cleanDoesNotClaimAnyTransferAndLeavesNoHint() {
        val state = AndroidPostSetupState(AndroidPostSetupState.VerifiedBootstrap("12345", ByteArray(32) { 7 }))
        var called = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(PAIR_CALLBACK); assertEquals(9, code)
                assertEquals(1, data.readInt()); assertEquals(0, Status.CREATOR.createFromParcel(data).statusCode)
                data.requireEnd(); called = true; return true
            }
        }
        AndroidTargetDeviceBinder(Binder.getCallingUid(), state).use { target ->
            call(target, 23) { writeStrongBinder(callback); writeInt(0) }
            assertNull(state.hint()); assertTrue(called)
        }
    }
}
