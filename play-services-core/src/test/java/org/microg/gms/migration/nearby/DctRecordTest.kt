/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.nearby

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctRecordTest {
    @Test fun nestedRequestKeepsPayloadIdAndRequestIdSeparateAndSkipsUnknownFields() {
        val record = DctRecord(DctSchemas.payload + (99 to DctRecord.Kind.Text), mapOf(
            1 to 17L, 2 to 0, 17 to 1, 3 to byteArrayOf(42), 99 to "future",
            15 to DctRecord(DctSchemas.request, mapOf(1 to 923L, 2 to android.net.Uri.parse("/communications"),
                3 to "application/vnd.os-migration.control+proto", 4 to listOf(DctRecord(DctSchemas.header,
                    mapOf(1 to "X-Synthetic", 2 to "value")))))))
        val parcel = Parcel.obtain()
        try {
            record.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            DctRecord.read(parcel, DctSchemas.payload).use {
                assertEquals(17L, it.long(1)); assertEquals(923L, it.obj(15)!!.long(1))
                assertEquals("X-Synthetic", it.obj(15)!!.list(4).single().text(1))
                assertFalse(it.fields.containsKey(99)); assertArrayEquals(byteArrayOf(42), it.bytes(3))
                assertEquals(parcel.dataSize(), parcel.dataPosition())
            }
        } finally { parcel.recycle() }
    }
    @Test fun binderResultListenerIsNotLostByUnknownFields() {
        val binder = Binder(); val p = Parcel.obtain()
        try {
            DctRecord(DctSchemas.result, mapOf(1 to binder)).writeToParcel(p, 0); p.setDataPosition(0)
            assertSame(binder, DctRecord.read(p, DctSchemas.result).binder(1))
        } finally { p.recycle() }
    }
    @Test fun nestedObjectCannotReadIntoTheFollowingField() {
        val p = Parcel.obtain()
        try {
            DctRecord(DctSchemas.payload, mapOf(15 to DctRecord(DctSchemas.request, mapOf(1 to 923L)), 1 to 17L)).writeToParcel(p, 0)
            p.setDataPosition(20); p.writeInt(0x7fffffff) // nested object length exceeds enclosing field
            p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { DctRecord.read(p, DctSchemas.payload) }
        } finally { p.recycle() }
    }
    @Test fun malformedByteLengthIsRejectedBeforeAllocation() {
        val p = Parcel.obtain()
        try {
            DctRecord(DctSchemas.payload, mapOf(3 to byteArrayOf(1))).writeToParcel(p, 0)
            p.setDataPosition(16); p.writeInt(Int.MAX_VALUE); p.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { DctRecord.read(p, DctSchemas.payload) }
        } finally { p.recycle() }
    }
}
