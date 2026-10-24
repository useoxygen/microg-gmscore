/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.zip.Deflater

/** This implementation's conservative credBlob capability, not a claim about Google's maximum. */
class PasskeyBlobCreateRequest(credBlob: ByteArray?, val largeBlobSupport: Boolean) : AutoCloseable {
    private val credential = credBlob?.copyOf()
    val credBlobRequested = credBlob != null
    val credBlobStored = credBlob != null && credBlob.size <= CRED_BLOB_CAPACITY
    fun apply(secret: ByteArray): ByteArray {
        if (secret.size > PasskeyBlobReadRequest.MAX_SECRET_BYTES) throw ProtocolException()
        return if (credBlobStored) Proto.decode(secret).replace(3, credential).encode() else secret.copyOf()
    }
    override fun close() { credential?.fill(0) }
    override fun toString() = "PasskeyBlobCreateRequest[redacted]"
    companion object { const val CRED_BLOB_CAPACITY = 32 }
}

/** RP-scoped WebAuthn write payload. Persistence belongs to the account's durable mutation coordinator. */
class PasskeyBlobWriteRequest(bytes: ByteArray) : AutoCloseable {
    private val bytes = bytes.copyOf()
    init { if (bytes.size > PasskeyBlobReadRequest.MAX_LARGE_BLOB_BYTES) throw ProtocolException() }
    fun apply(secret: ByteArray): ByteArray? {
        if (secret.size > PasskeyBlobReadRequest.MAX_SECRET_BYTES) throw ProtocolException()
        PasskeyBlobReadRequest(false, true).read(secret).use { old ->
            if (old.largeBlob?.let { MessageDigest.isEqual(it, bytes) } == true) return null
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val compressed = ByteArray(4096)
        return try {
            deflater.setInput(bytes); deflater.finish()
            val size = deflater.deflate(compressed)
            if (!deflater.finished()) throw ProtocolException()
            val encoded = compressed.copyOf(size)
            try { Proto.decode(secret).replace(4, encoded).replaceInteger(5, bytes.size.toLong()).encode()
                .also { if (it.size > PasskeyBlobReadRequest.MAX_SECRET_BYTES) throw ProtocolException() } }
            finally { encoded.fill(0) }
        } finally { compressed.fill(0); deflater.end() }
    }
    override fun close() { bytes.fill(0) }
    override fun toString() = "PasskeyBlobWriteRequest[redacted]"
}
