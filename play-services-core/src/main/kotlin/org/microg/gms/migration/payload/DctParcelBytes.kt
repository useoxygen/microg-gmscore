/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.internal.safeparcel.SafeParcelReader
import java.io.DataInputStream
import java.io.IOException

/** ParcelByteArray's field1 is an FD containing a big-endian length followed by bytes. Consumes its own FD. */
internal object DctParcelBytes {
    fun readObject(parcel: Parcel, maxBytes: Int = 16 * 1024 * 1024): ByteArray {
        require(maxBytes >= 0)
        var descriptor: ParcelFileDescriptor? = null
        try {
            val end = SafeParcelReader.readObjectHeader(parcel)
            while (parcel.dataPosition() < end) {
                val start = parcel.dataPosition()
                require(end - start >= 4)
                val header = SafeParcelReader.readHeader(parcel)
                if (SafeParcelReader.getFieldId(header) == 1) {
                    require(descriptor == null) { "Duplicate ParcelByteArray descriptor" }
                    descriptor = SafeParcelReader.readParcelable(parcel, header, ParcelFileDescriptor.CREATOR)
                } else SafeParcelReader.skip(parcel, header)
                require(parcel.dataPosition() in (start + 4)..end)
            }
            require(parcel.dataPosition() == end)
            val owned = requireNotNull(descriptor) { "Missing ParcelByteArray descriptor" }
            descriptor = null
            return DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(owned)).use { input ->
                val length = input.readInt()
                if (length !in 0..maxBytes) throw IOException("Unsupported ParcelByteArray length")
                ByteArray(length).also { input.readFully(it) }
            }
        } finally { descriptor?.close() }
    }
}
