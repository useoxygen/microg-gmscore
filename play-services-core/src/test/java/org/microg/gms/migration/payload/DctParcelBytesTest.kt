/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DctParcelBytesTest {
    private fun decode(length: Int, bytes: ByteArray, maximum: Int = 1024): ByteArray {
        val file = Files.createTempFile("dct-synthetic-", ".bin").toFile()
        val parcel = Parcel.obtain()
        try {
            DataOutputStream(file.outputStream()).use { it.writeInt(length); it.write(bytes) }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val start = SafeParcelWriter.writeObjectHeader(parcel)
                SafeParcelWriter.write(parcel, 1, fd, 1, false) // transfer sender ownership, as GMS does
                SafeParcelWriter.finishObjectHeader(parcel, start)
                parcel.setDataPosition(0)
                return DctParcelBytes.readObject(parcel, maximum)
            }
        } finally { parcel.recycle(); file.delete() }
    }
    @Test fun fileBackedBytesUseNetworkByteOrderRatherThanParcelByteArrayEncoding() {
        val bytes = byteArrayOf(10, 2, 10, 0)
        assertArrayEquals(bytes, decode(4, bytes))
        assertArrayEquals(byteArrayOf(), decode(0, byteArrayOf()))
    }
    @Test fun negativeExcessiveAndTruncatedLengthsFailBeforeDelivery() {
        assertThrows(IOException::class.java) { decode(-1, byteArrayOf()) }
        assertThrows(IOException::class.java) { decode(1025, byteArrayOf()) }
        assertThrows(IOException::class.java) { decode(4, byteArrayOf(1)) }
    }
}
