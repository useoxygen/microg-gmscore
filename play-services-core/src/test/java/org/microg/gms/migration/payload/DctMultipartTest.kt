/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DctMultipartTest {
    @Test fun fileBytesAndMetadataUseDifferentEncodingsAndCloseTheInput() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf(0, 1, 2, -1)) {
            override fun close() { closed = true; super.close() }
        }
        val multipart = DctMultipart.body(listOf(DctFilePart("application/octet-stream", 4, byteArrayOf(8, 1)) { stream }), "synthetic-boundary")
        val result = Buffer().also { multipart.writeTo(it) }.readByteArray()
        val prefix = "--synthetic-boundary\r\nX-OS-Migration-Metadata: base64; CAE=\r\nContent-Type: application/octet-stream\r\nContent-Length: 4\r\n\r\n".toByteArray()
        assertArrayEquals(prefix + byteArrayOf(0, 1, 2, -1) + "\r\n--synthetic-boundary--\r\n".toByteArray(), result)
        assertTrue(closed)
    }
    @Test fun shorterAndLongerStreamsFailInsteadOfReportingACompleteFile() {
        listOf(byteArrayOf(1), byteArrayOf(1, 2, 3)).forEach { bytes ->
            val body = DctMultipart.body(listOf(DctFilePart("application/octet-stream", 2, byteArrayOf()) { bytes.inputStream() }), "synthetic")
            assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
        }
    }
}
