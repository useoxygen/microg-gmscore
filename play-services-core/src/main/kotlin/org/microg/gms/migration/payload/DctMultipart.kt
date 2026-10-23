/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.payload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream

internal class DctFilePart(val contentType: String, val length: Long, metadata: ByteArray,
                           val open: () -> InputStream) {
    val metadata = metadata.copyOf()
    init { require(length >= 0); contentType.toMediaType() }
}

/** GMS requires a per-part Content-Length, which the resolved OkHttp multipart writer omits. */
internal object DctMultipart {
    fun body(parts: List<DctFilePart>, boundary: String): RequestBody {
        require(parts.isNotEmpty() && boundary.matches(Regex("[A-Za-z0-9_-]{1,70}")))
        val snapshot = parts.toList()
        val headers = snapshot.map { part ->
            ("--$boundary\r\nX-OS-Migration-Metadata: base64; ${part.metadata.toByteString().base64()}\r\n" +
                "Content-Type: ${part.contentType}\r\nContent-Length: ${part.length}\r\n\r\n").toByteArray(Charsets.UTF_8)
        }
        val final = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        var length = final.size.toLong()
        snapshot.forEachIndexed { index, part ->
            length = Math.addExact(length, Math.addExact(headers[index].size.toLong() + 2L, part.length))
        }
        return object : RequestBody() {
            override fun contentType() = "multipart/mixed; boundary=$boundary".toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                snapshot.forEachIndexed { index, part ->
                    sink.write(headers[index])
                    part.open().source().buffer().use { input ->
                        sink.write(input, part.length)
                        if (!input.exhausted()) throw IOException("DCT file exceeds its declared length")
                    }
                    sink.writeUtf8("\r\n")
                }
                sink.write(final)
            }
        }
    }
}
