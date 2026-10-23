/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SwitchSupportBinderTest {
    private fun call(binder: IBinder, descriptor: String, code: Int, write: Parcel.() -> Unit): Parcel {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            assertTrue(binder.transact(code, data, reply, 0))
            reply.readException()
            return reply
        } catch (t: Throwable) { reply.recycle(); throw t }
        finally { data.recycle() }
    }

    @Test fun optionalSetupQueriesCompleteWithAnEmptyBundle() {
        listOf(true, false).forEach { target ->
            var received = false
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface(PAIR_CALLBACK)
                    assertEquals(6, code)
                    assertEquals(IBinder.FLAG_ONEWAY, flags)
                    assertEquals(1, data.readInt())
                    assertEquals(0, Status.CREATOR.createFromParcel(data).statusCode)
                    assertEquals(1, data.readInt())
                    assertTrue(Bundle.CREATOR.createFromParcel(data).isEmpty)
                    data.requireEnd()
                    received = true
                    return true
                }
            }
            call(EmptySetupStateBinder(Binder.getCallingUid(), target), if (target) TARGET_DESCRIPTOR else POST_DESCRIPTOR,
                if (target) 21 else 7) {
                    writeStrongBinder(callback)
                    writeInt(1)
                    writeInt(-204102970) // ApiMetadata's non-SafeParcel prefix, observed on the phone
                    val start = SafeParcelWriter.writeObjectHeader(this)
                    SafeParcelWriter.write(this, 2, false)
                    SafeParcelWriter.finishObjectHeader(this, start)
                }.recycle()
            assertTrue(received)
        }
    }

    @Test fun wifiExportReturnsTheListEncodingExpectedByTheCaller() {
        val reply = call(ExportStateBinder(Binder.getCallingUid(), true), WIFI_EXPORT_DESCRIPTOR, 1) {}
        try { assertEquals(emptyList<Any>(), reply.readArrayList(javaClass.classLoader)); reply.requireEnd() }
        finally { reply.recycle() }
    }

    @Test fun helperReportsBothArtifactQueriesAsAbsent() {
        listOf(2, 8).forEach { operation ->
            var received = false
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.backup.migrate.helper.IBooleanCallbacks")
                    assertEquals(1, code)
                    assertEquals(0, data.readInt())
                    data.requireEnd()
                    received = true
                    return true
                }
            }
            call(EmptyMigrateHelper(Binder.getCallingUid()), "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper",
                operation) { writeString("synthetic-artifact"); writeStrongBinder(callback) }.recycle()
            assertTrue(received)
        }
    }

    @Test fun anotherUidCannotUseASessionBinder() {
        assertThrows(Exception::class.java) {
            call(EmptySetupStateBinder(Binder.getCallingUid() + 1, true), TARGET_DESCRIPTOR, 21) {
                writeStrongBinder(Binder()); writeInt(0)
            }
        }
    }

    @Test fun trailingFieldsCannotBeMistakenForAValidQuery() {
        var received = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { received = true; return true }
        }
        assertThrows(Exception::class.java) {
            call(EmptySetupStateBinder(Binder.getCallingUid(), true), TARGET_DESCRIPTOR, 21) {
                writeStrongBinder(callback); writeInt(0); writeInt(123)
            }
        }
        assertFalse(received)
    }
}
