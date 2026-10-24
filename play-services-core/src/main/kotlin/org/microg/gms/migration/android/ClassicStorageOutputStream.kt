/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import java.io.FilterOutputStream
import java.io.OutputStream

/** Disk admission is current free storage, separate from per-payload wire bounds.
 * Completed files may be consumed/deleted by the client while the session stays up. */
internal class ClassicStorageOutputStream(output: OutputStream,
    private val availableBytes: () -> Long) : FilterOutputStream(output) {
    override fun write(value: Int) {
        require(availableBytes()>=1) { "Insufficient storage for Nearby file" }
        out.write(value)
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset>=0 && length>=0 && offset<=bytes.size-length)
        require(length.toLong()<=availableBytes()) { "Insufficient storage for Nearby file" }
        out.write(bytes,offset,length)
    }
}
