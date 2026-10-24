/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.android

import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SmartDevice's direct-transfer pipe. This is nested inside Switch's USB protocol,
 * and must never be mistaken for Nearby OfflineFrames or the bulk-data stream.
 * Callers own a deadline and close the channel to interrupt blocked reads. */
internal class D2dPipeChannel(input: InputStream, output: OutputStream) : Closeable {
    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    private var crypto: D2DConnectionContext? = null
    private var negotiated = false
    @Volatile private var closed = false

    /** The target is UKEY2 initiator, although the source sends the capability byte first.
     * Encryption-only: a peer offering only cleartext is rejected, never downgraded. */
    fun negotiateTarget() {
        check(!negotiated && !closed)
        val offer = readFrame()
        if (offer.size != 1 || (offer[0].toInt() and UKEY2) == 0) {
            throw IOException("Source does not offer SmartDevice UKEY2")
        }
        writeFrame(byteArrayOf(UKEY2.toByte()))
        val handshake = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)
        writeFrame(handshake.nextHandshakeMessage)
        handshake.parseHandshakeMessage(readFrame())
        writeFrame(handshake.nextHandshakeMessage)
        // GMS direct-transfer uses UKEY2 for encryption. User verification belongs to
        // the enclosing physical/verified bootstrap channel, not this four-byte value.
        handshake.getVerificationString(4)
        handshake.verifyHandshake()
        val connection = handshake.toConnectionContext()
        if (!readFrame().contentEquals(HANDSHAKE_FINISHED)) throw IOException("Invalid UKEY2 completion marker")
        crypto = connection
        negotiated = true
    }

    fun receive(): ByteArray {
        check(negotiated && !closed)
        return requireNotNull(crypto).decodeMessageFromPeer(readFrame()).also {
            require(it.size <= MAX_MESSAGE) { "SmartDevice plaintext exceeds limit" }
        }
    }

    fun send(message: ByteArray) {
        check(negotiated && !closed)
        require(message.size <= MAX_MESSAGE)
        writeFrame(requireNotNull(crypto).encodeMessageToPeer(message))
    }

    private fun readFrame(): ByteArray {
        check(!closed)
        val size = reader.readInt() // DataInput's signed big-endian int, not varint.
        if (size !in 1..MAX_FRAME) throw IOException("Invalid SmartDevice frame length")
        return ByteArray(size).also(reader::readFully)
    }

    @Synchronized private fun writeFrame(bytes: ByteArray) {
        check(!closed)
        require(bytes.size in 1..MAX_FRAME)
        writer.writeInt(bytes.size)
        writer.write(bytes)
        writer.flush()
    }

    override fun close() {
        closed = true
        crypto = null
        try { reader.close() } finally { writer.close() }
    }

    companion object {
        const val MAX_MESSAGE = 1024 * 1024
        const val MAX_FRAME = MAX_MESSAGE + 65536
        private const val UKEY2 = 8
        val HANDSHAKE_FINISHED = byteArrayOf(1, 1, 2, 3, 5, 8)
    }
}
