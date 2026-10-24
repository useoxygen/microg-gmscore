/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.nearby

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class NearbyDctBinderTest {
    @Test fun acceptWithoutTransportReturnsRawOutOfOrderStatusAndNoFalseSuccess() {
        var result = -1
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.nearby.internal.connection.IResultListener")
                assertEquals(2, code); assertEquals(IBinder.FLAG_ONEWAY, flags)
                result = data.readInt(); assertEquals(0, data.dataAvail()); return true
            }
        }
        val service = NearbyDctBinder(RuntimeEnvironment.getApplication(), Binder.getCallingUid())
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeInt(1)
            DctRecord(DctSchemas.accept, mapOf(1 to callback, 5 to Binder(), 6 to 3,
                9 to DctRecord(DctSchemas.device, mapOf(1 to "abc234")))).writeToParcel(data, 0)
            assertTrue(service.transact(2006, data, reply, 0)); reply.readException()
            assertEquals(8009, result)
        } finally { data.recycle(); reply.recycle(); service.close() }
    }
    @Test fun serviceBinderCannotBePassedToAnotherUid() {
        val service = NearbyDctBinder(RuntimeEnvironment.getApplication(), Binder.getCallingUid() + 1)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeInt(1)
            DctRecord(DctSchemas.result).writeToParcel(data, 0)
            assertThrows(Exception::class.java) { service.transact(2002, data, reply, 0); reply.readException() }
        } finally { data.recycle(); reply.recycle(); service.close() }
    }
    @Test fun cancellationWithoutConnectedPayloadReturnsNotConnected() {
        var result = -1
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.nearby.internal.connection.IResultListener")
                result = data.readInt(); return true
            }
        }
        val service = NearbyDctBinder(RuntimeEnvironment.getApplication(), Binder.getCallingUid())
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeInt(1)
            DctRecord(DctSchemas.cancel, mapOf(1 to callback, 2 to 99L)).writeToParcel(data, 0)
            service.transact(2012, data, reply, 0); reply.readException(); assertEquals(8005, result)
        } finally { data.recycle(); reply.recycle(); service.close() }
    }
}
