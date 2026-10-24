/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.migration.*
import org.microg.gms.migration.nearby.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ClassicBinderFailureTest {
    @Test fun unconnectedClientUsesExactEndpointAndPayloadFailureCodes() {
        ClassicNearbyBinder(RuntimeEnvironment.getApplication(),Binder.getCallingUid()).use { target ->
            fun result(code: Int, schema: Map<Int,DctRecord.Kind>, fields: Map<Int,Any>, expected: Int) {
                var observed: Int?=null
                val callback=object: Binder() { override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.nearby.internal.connection.IResultListener"); assertEquals(2,code); observed=data.readInt(); data.requireEnd(); return true
                } }
                val data=Parcel.obtain(); val reply=Parcel.obtain()
                try { data.writeInterfaceToken(NEARBY_DESCRIPTOR); data.writeNullable(DctRecord(schema,mapOf(1 to callback)+fields)); assertTrue(target.transact(code,data,reply,0)); reply.readException() } finally { data.recycle(); reply.recycle() }
                assertEquals(expected,observed)
            }
            result(2005,ClassicSchemas.request,mapOf(5 to "MISS",7 to Binder()),8011)
            result(2006,ClassicSchemas.accept,mapOf(3 to "MISS",5 to Binder()),8009)
            result(2007,ClassicSchemas.reject,mapOf(2 to "MISS"),8011)
            result(2012,ClassicSchemas.cancel,mapOf(2 to 99L),8014)
            result(2008,ClassicSchemas.send,mapOf(2 to arrayOf("MISS"),3 to DctRecord(ClassicSchemas.payload,mapOf(1 to 1L,2 to 1,3 to byteArrayOf(1)))),8005)
        }
    }
}
