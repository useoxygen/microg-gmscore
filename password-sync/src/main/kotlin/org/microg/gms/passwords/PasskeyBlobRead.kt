/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Only existing-credential reads. Native parsing must refuse creation/support/write forms. */
class PasskeyBlobReadRequest(val credBlob: Boolean, val largeBlob: Boolean) {
    /** Call only inside the existing authenticated caller/RP/account/owner secret scope. */
    fun read(secretEncoded: ByteArray): PasskeyBlobReadResult {
        if (secretEncoded.size > MAX_SECRET_BYTES) throw ProtocolException()
        val secret = Proto.decode(secretEncoded)
        val credential = if (credBlob) secret.optional(3) ?: byteArrayOf() else null
        var large: ByteArray? = null
        return try {
            if (largeBlob) large = readLargeBlob(secret)
            PasskeyBlobReadResult(credential, large)
        } finally { credential?.fill(0); large?.fill(0) }
    }

    /** Invalid/absent blob data omits the blob result; it does not claim storage support. */
    private fun readLargeBlob(secret: Proto): ByteArray? {
        var compressed: ByteArray? = null
        var output: ByteArray? = null
        var inflater: Inflater? = null
        try {
            compressed = secret.optional(4) ?: return null
            val expected = secret.integer(5)?.takeIf { it in 0..MAX_LARGE_BLOB_BYTES.toLong() }?.toInt() ?: return null
            // The enclosing secret is bounded at 128KiB. Compressed bytes can exceed
            // the 2KiB output limit because of DEFLATE framing or stored blocks.
            if (compressed.isEmpty()) return null
            val decoder = Inflater(true) // Chromium Gzipper::Inflate uses raw DEFLATE (ZRAW).
            inflater = decoder
            decoder.setInput(compressed)
            // One extra byte detects expansion beyond the declared size without allocating
            // or decoding an unbounded output, including a claimed zero-length blob.
            val buffer = ByteArray(expected + 1)
            output = buffer
            var count = 0
            while (!decoder.finished()) {
                val length = decoder.inflate(buffer, count, buffer.size - count)
                count += length
                if (count > expected) return null
                if (length == 0 && !decoder.finished()) return null
            }
            if (count != expected || decoder.remaining != 0 || decoder.needsDictionary()) return null
            return buffer.copyOf(count)
        } catch (_: ProtocolException) { return null }
        catch (_: DataFormatException) { return null }
        finally { inflater?.end(); compressed?.fill(0); output?.fill(0) }
    }
    override fun toString() = "PasskeyBlobReadRequest[redacted]"
    companion object {
        const val MAX_LARGE_BLOB_BYTES = 2048
        const val MAX_SECRET_BYTES = 128 * 1024
    }
}

/** Caller-owned copies; native code supplies signed credBlob CBOR and unsigned client JSON. */
class PasskeyBlobReadResult internal constructor(credBlob: ByteArray?, largeBlob: ByteArray?) : AutoCloseable {
    val credBlob: ByteArray? = credBlob?.copyOf()
    val largeBlob: ByteArray? = largeBlob?.copyOf()
    override fun close() { credBlob?.fill(0); largeBlob?.fill(0) }
    override fun toString() = "PasskeyBlobReadResult[redacted]"
}
