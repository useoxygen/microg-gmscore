/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.microg.gms.common.GmsService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SwitchSupportBinderTest {
    @Test fun managedAccountSetupVersionTwoIsAdvertisedOnlyOnTarget76() {
        val features = switchSupportFeatures(GmsService.SMARTDEVICE_D2D_TARGET_DEVICE)
        assertEquals(2L, features.single { it.name == "managed_account_setup" }.version)
        assertFalse(switchSupportFeatures(GmsService.SMARTDEVICE_POST_SETUP).any { it.name == "managed_account_setup" })
        assertTrue(switchSupportFeatures(GmsService.SMARTDEVICE_TARGET_DIRECT_TRANSFER).isEmpty())
    }

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

    @Test fun connectionHintHasOnlyTheRealRetainedCredentialsOr10590WhenAbsent() {
        org.microg.gms.migration.session.DctSessions.retained()?.close()
        val controller = org.microg.gms.migration.session.DctSessions.create(org.robolectric.RuntimeEnvironment.getApplication())
        fun query(expectedStatus: Int) {
            var received = false
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface(PAIR_CALLBACK); assertEquals(1, code)
                    assertEquals(1, data.readInt()); assertEquals(expectedStatus, Status.CREATOR.createFromParcel(data).statusCode)
                    assertEquals(1, data.readInt())
                    val hint = org.microg.gms.migration.nearby.DctRecord.read(data, org.microg.gms.migration.nearby.DctSchemas.hint)
                    if (expectedStatus == 0) {
                        assertEquals(controller.session.sessionId, hint.text(9)); assertEquals(controller.session.password, hint.text(10))
                    } else assertTrue(hint.fields.isEmpty())
                    data.requireEnd(); received = true; return true
                }
            }
            call(EmptySetupStateBinder(Binder.getCallingUid(), false), POST_DESCRIPTOR, 1) {
                writeStrongBinder(callback); writeInt(0)
            }.recycle()
            assertTrue(received)
        }
        try { query(0) } finally { controller.close() }
        query(10590)
    }

    @Test fun targetQuickstartUnsupportedAccountTransferUsesSynchronousErrorCallback() {
        var received = false
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(OS_CALLBACK); assertEquals(5, code); assertEquals(0, flags)
                assertEquals(13, data.readInt()); assertEquals(0, data.readInt()); data.requireEnd()
                reply!!.writeNoException(); received = true; return true
            }
        }
        val binder = TargetQuickStartBinder(org.robolectric.RuntimeEnvironment.getApplication())
        try { call(binder, QUICKSTART_DESCRIPTOR, 18) { writeStrongBinder(callback) }.recycle(); assertTrue(received) }
        finally { binder.close() }
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
