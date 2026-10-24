/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ClassicParcelBytesTest {
    @Test fun teleporterReadsTheWholeBodyInsteadOfTheCompatibilityPrefix() {
        val body=ByteArray(100003) { (it%251).toByte() }
        val file=ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeInt(body.size); write(body) } }.toByteArray()
        assertArrayEquals(body,ClassicParcelBytes.read(ByteArrayInputStream(file)))
        assertThrows(IllegalArgumentException::class.java) { ClassicParcelBytes.read(ByteArrayInputStream(byteArrayOf(-1,-1,-1,-1))) }
        assertThrows(EOFException::class.java) { ClassicParcelBytes.read(ByteArrayInputStream(file.copyOf(32768))) }
    }
}
