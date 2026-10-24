/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import android.os.ParcelFileDescriptor
import org.microg.gms.migration.nearby.DctRecord
import org.microg.gms.migration.nearby.DctSchemas
import java.io.*

/** Same ParcelByteArray wire vocabulary as B's DCT adapter. GMS efks.d/g uses
 * payload11 for the full body and payload3 only as a32768-byte compatibility
 * prefix. Ignoring11 silently truncates large BYTES. All FD reads are bounded. */
internal object ClassicParcelBytes {
    const val MAX_BYTES=1047552
    fun read(input: InputStream): ByteArray = DataInputStream(input).use {
        val size=it.readInt(); require(size in 0..MAX_BYTES)
        ByteArray(size).also(it::readFully)
    }
    fun body(payload: DctRecord): ByteArray {
        val fd=payload.obj(11)?.fields?.get(1) as? ParcelFileDescriptor
        return if(fd!=null)read(DeadlineFdStreams.input(ParcelFileDescriptor.dup(fd.fileDescriptor)))
            else requireNotNull(payload.bytes(3))
    }
    fun fields(cache: File, body: ByteArray): Map<Int,Any> {
        require(body.size<=MAX_BYTES)
        if(body.size<=32768)return mapOf(3 to body)
        val file=File.createTempFile("classic-teleporter-",".tmp",cache)
        var descriptor: ParcelFileDescriptor?=null
        try {
            DataOutputStream(FileOutputStream(file)).use { it.writeInt(body.size); it.write(body) }
            descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
            check(file.delete())
            return mapOf(3 to body.copyOf(32768),11 to DctRecord(DctSchemas.parcelBytes,mapOf(1 to descriptor))).also { descriptor=null }
        } finally { descriptor?.close(); file.delete() }
    }
}
